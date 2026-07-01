// =============================================================================
// LLD: STOCK BROKER APPLICATION (Meesho SDE3 Machine Coding — Aug 2025)
// =============================================================================
// REQUIREMENTS:
//   1. Register users with balance
//   2. Add stocks to the exchange
//   3. User can add balance to their account
//   4. User can place BUY / SELL orders (price + quantity)
//   5. Order matching: FIFO Price-Time priority
//      - BUY: higher price = higher priority; same price → earlier time first
//      - SELL: lower price = higher priority; same price → earlier time first
//      - Trade executes when buyPrice >= sellPrice → at the SELL order's price
//   6. User can view holdings (current stock positions)
//   7. User can view past successful trades
//
// OUT OF SCOPE: market orders, stop-loss, margin trading, brokerage fee
//
// KEY DESIGN DECISIONS:
//
// 1. PriorityQueue per stock (OrderBook):
//    - buyOrders: max-heap by price, tie-break by earliest timestamp (FIFO)
//    - sellOrders: min-heap by price, tie-break by earliest timestamp (FIFO)
//
// 2. CONCURRENCY — synchronized(orderBook) per stock:
//    Two users placing orders on the SAME stock simultaneously could:
//    - Both see the same matching sell order and both execute trades against it
//    - Both deduct balance for the same execution → double-spend
//    Fix: lock on the stock's OrderBook object for the entire place + match cycle.
//    Different stocks have independent locks → parallel trading on different stocks.
//
// 3. AtomicInteger for order/trade IDs — counters shared across all synchronized
//    blocks of different stocks, so they need their own thread-safe increment.
// =============================================================================

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


// =============================================================================
// ENUMS
// =============================================================================

enum OrderType   { BUY, SELL }
enum OrderStatus { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED }


// =============================================================================
// ENTITIES
// =============================================================================

// ── Stock ─────────────────────────────────────────────────────────────────────
class Stock {
    public String id;
    public String name;
    public String ticker;

    public Stock(String id, String name, String ticker) {
        this.id     = id;
        this.name   = name;
        this.ticker = ticker;
    }

    @Override
    public String toString() { return ticker + "(" + id + ")"; }
}

// ── Order ─────────────────────────────────────────────────────────────────────
class Order {
    public String      id;
    public String      userId;
    public String      stockId;
    public OrderType   type;
    public double      price;
    public int         quantity;       // remaining (decrements on partial fill)
    public int         originalQty;
    public long        timestamp;
    public OrderStatus status;

    public Order(String id, String userId, String stockId,
                 OrderType type, double price, int quantity, long timestamp) {
        this.id          = id;
        this.userId      = userId;
        this.stockId     = stockId;
        this.type        = type;
        this.price       = price;
        this.quantity    = quantity;
        this.originalQty = quantity;
        this.timestamp   = timestamp;
        this.status      = OrderStatus.OPEN;
    }

    @Override
    public String toString() {
        return String.format("Order[%s | %s | %s | %.2f × %d | %s]",
                id, type, stockId, price, quantity, status);
    }
}

// ── Trade ─────────────────────────────────────────────────────────────────────
// Immutable record of a matched execution.
class Trade {
    public String id;
    public String buyOrderId;
    public String sellOrderId;
    public String stockId;
    public double price;      // always the SELL order's price
    public int    quantity;
    public long   executedAt;

    public Trade(String id, String buyOrderId, String sellOrderId,
                 String stockId, double price, int quantity, long executedAt) {
        this.id          = id;
        this.buyOrderId  = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.stockId     = stockId;
        this.price       = price;
        this.quantity    = quantity;
        this.executedAt  = executedAt;
    }

    @Override
    public String toString() {
        return String.format("Trade[%s | stock=%s | price=%.2f | qty=%d]",
                id, stockId, price, quantity);
    }
}

// ── Holding ───────────────────────────────────────────────────────────────────
// A user's current position in one stock.
class Holding {
    public String stockId;
    public int    quantity;
    public double avgBuyPrice;

    public Holding(String stockId) {
        this.stockId     = stockId;
        this.quantity    = 0;
        this.avgBuyPrice = 0;
    }

    // Update average price when more shares are bought
    public void buy(int qty, double price) {
        avgBuyPrice = (avgBuyPrice * quantity + price * qty) / (quantity + qty);
        quantity   += qty;
    }

    public void sell(int qty) {
        quantity -= qty;
    }

    @Override
    public String toString() {
        return String.format("Holding[%s | qty=%d | avgBuy=%.2f]",
                stockId, quantity, avgBuyPrice);
    }
}

// ── User ──────────────────────────────────────────────────────────────────────
class BrokerUser {
    public String                  id;
    public String                  name;
    public double                  balance;
    public Map<String, Holding>    holdings;   // stockId → Holding
    public List<Order>             orders;     // all orders placed by user
    public List<Trade>             trades;     // all executed trades for user

    public BrokerUser(String id, String name, double initialBalance) {
        this.id       = id;
        this.name     = name;
        this.balance  = initialBalance;
        this.holdings = new HashMap<>();
        this.orders   = new ArrayList<>();
        this.trades   = new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("User[%s | %s | balance=%.2f]", id, name, balance);
    }
}

// ── OrderBook ─────────────────────────────────────────────────────────────────
// Per-stock data structure. This object is the LOCK for concurrency.
class OrderBook {
    public String stockId;

    // BUY: higher price first; tie → earlier timestamp first (FIFO)
    public PriorityQueue<Order> buyOrders = new PriorityQueue<>((a, b) -> {
        if (Double.compare(b.price, a.price) != 0) return Double.compare(b.price, a.price);
        return Long.compare(a.timestamp, b.timestamp);
    });

    // SELL: lower price first; tie → earlier timestamp first (FIFO)
    public PriorityQueue<Order> sellOrders = new PriorityQueue<>((a, b) -> {
        if (Double.compare(a.price, b.price) != 0) return Double.compare(a.price, b.price);
        return Long.compare(a.timestamp, b.timestamp);
    });

    public List<Trade> trades = new ArrayList<>();

    public OrderBook(String stockId) { this.stockId = stockId; }
}


// =============================================================================
// STOCK BROKER SERVICE
// =============================================================================

class StockBrokerService {
    private Map<String, BrokerUser> users      = new HashMap<>();
    private Map<String, Stock>      stocks     = new HashMap<>();
    private Map<String, OrderBook>  orderBooks = new HashMap<>();   // stockId → OrderBook

    private int           userCounter  = 0;
    private int           stockCounter = 0;
    private AtomicInteger orderCounter = new AtomicInteger(0);
    private AtomicInteger tradeCounter = new AtomicInteger(0);

    // ── Setup ─────────────────────────────────────────────────────────────────

    public BrokerUser registerUser(String name, double initialBalance) {
        String id = "U" + (++userCounter);
        BrokerUser user = new BrokerUser(id, name, initialBalance);
        users.put(id, user);
        System.out.println("[BROKER] Registered: " + user);
        return user;
    }

    public Stock addStock(String name, String ticker) {
        String id = "S" + (++stockCounter);
        Stock stock = new Stock(id, name, ticker);
        stocks.put(id, stock);
        orderBooks.put(id, new OrderBook(id));
        System.out.println("[BROKER] Stock added: " + ticker + " (" + id + ")");
        return stock;
    }

    public void addBalance(String userId, double amount) {
        BrokerUser user = users.get(userId);
        if (user == null) { System.out.println("[BROKER] User not found"); return; }
        user.balance += amount;
        System.out.printf("[BROKER] Balance added: %s now has %.2f%n", user.name, user.balance);
    }

    // ── Place Order ───────────────────────────────────────────────────────────
    // CRITICAL: add order to book + match — must be atomic per stock.
    // Synchronized on OrderBook so two users trading same stock don't race.

    public Order placeOrder(String userId, String stockId,
                            OrderType type, double price, int quantity) {
        BrokerUser user       = users.get(userId);
        OrderBook  orderBook  = orderBooks.get(stockId);

        if (user == null)      { System.out.println("[ORDER] User not found");  return null; }
        if (orderBook == null) { System.out.println("[ORDER] Stock not found"); return null; }

        // Pre-validation (outside lock — cheap checks)
        if (type == OrderType.BUY && user.balance < price * quantity) {
            System.out.printf("[ORDER] Insufficient balance: need %.2f have %.2f%n",
                    price * quantity, user.balance);
            return null;
        }
        if (type == OrderType.SELL) {
            Holding h = user.holdings.get(stockId);
            if (h == null || h.quantity < quantity) {
                System.out.println("[ORDER] Insufficient holdings to sell");
                return null;
            }
        }

        String orderId = "ORD-" + orderCounter.incrementAndGet();
        Order  order   = new Order(orderId, userId, stockId, type, price, quantity,
                                   System.currentTimeMillis());

        // CRITICAL SECTION — lock on this stock's order book
        synchronized (orderBook) {
            if (type == OrderType.BUY)  orderBook.buyOrders.add(order);
            else                         orderBook.sellOrders.add(order);

            user.orders.add(order);

            // Attempt to match orders
            matchOrders(orderBook);
        }

        System.out.println("[ORDER] Placed: " + order);
        return order;
    }

    // ── Order Matching — FIFO Price-Time ──────────────────────────────────────
    // Called inside synchronized(orderBook) — already holds the lock.
    // Matches top buy and top sell as long as buyPrice >= sellPrice.

    private void matchOrders(OrderBook book) {
        while (!book.buyOrders.isEmpty() && !book.sellOrders.isEmpty()) {
            Order buy  = book.buyOrders.peek();
            Order sell = book.sellOrders.peek();

            if (buy.price < sell.price) break;  // no match possible

            // Trade quantity = minimum of both remaining quantities
            int    tradeQty   = Math.min(buy.quantity, sell.quantity);
            double tradePrice = sell.price;   // trade at the SELL order's price

            // Settle buyer
            BrokerUser buyer = users.get(buy.userId);
            buyer.balance -= tradePrice * tradeQty;
            buyer.holdings.computeIfAbsent(book.stockId, k -> new Holding(k))
                          .buy(tradeQty, tradePrice);

            // Settle seller
            BrokerUser seller = users.get(sell.userId);
            seller.balance += tradePrice * tradeQty;
            Holding sellerHolding = seller.holdings.get(book.stockId);
            if (sellerHolding != null) sellerHolding.sell(tradeQty);

            // Update order quantities
            buy.quantity  -= tradeQty;
            sell.quantity -= tradeQty;

            // Create trade record
            String tradeId = "TRD-" + tradeCounter.incrementAndGet();
            Trade  trade   = new Trade(tradeId, buy.id, sell.id,
                                       book.stockId, tradePrice, tradeQty,
                                       System.currentTimeMillis());
            book.trades.add(trade);
            buyer.trades.add(trade);
            seller.trades.add(trade);

            System.out.println("  [MATCH] " + trade
                    + " | buyer=" + buyer.name + " seller=" + seller.name);

            // Remove fully filled orders
            if (buy.quantity == 0) {
                book.buyOrders.poll();
                buy.status = OrderStatus.FILLED;
            } else {
                buy.status = OrderStatus.PARTIALLY_FILLED;
            }
            if (sell.quantity == 0) {
                book.sellOrders.poll();
                sell.status = OrderStatus.FILLED;
            } else {
                sell.status = OrderStatus.PARTIALLY_FILLED;
            }
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public void printHoldings(String userId) {
        BrokerUser user = users.get(userId);
        if (user == null) return;
        System.out.println("[HOLDINGS] " + user.name + " | balance=₹" + user.balance);
        user.holdings.forEach((sId, h) -> {
            if (h.quantity > 0) System.out.println("  " + h);
        });
    }

    public void printTrades(String userId) {
        BrokerUser user = users.get(userId);
        if (user == null) return;
        System.out.println("[TRADES] " + user.name + ":");
        user.trades.forEach(t -> System.out.println("  " + t));
    }

    public void printOrderBook(String stockId) {
        OrderBook book = orderBooks.get(stockId);
        if (book == null) return;
        System.out.println("[ORDER BOOK] " + stockId);
        System.out.println("  BUY orders:  " + book.buyOrders.size() + " pending");
        System.out.println("  SELL orders: " + book.sellOrders.size() + " pending");
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class StockBrokerSolution {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Stock Broker Demo ===\n");

        StockBrokerService broker = new StockBrokerService();

        // ── Setup ──────────────────────────────────────────────────────────────
        Stock tcs    = broker.addStock("Tata Consultancy", "TCS");
        Stock infy   = broker.addStock("Infosys", "INFY");

        BrokerUser alice = broker.registerUser("Alice", 100000.0);
        BrokerUser bob   = broker.registerUser("Bob",   150000.0);
        BrokerUser carol = broker.registerUser("Carol",  50000.0);

        // Give Alice some TCS stock (simulate she already holds it)
        alice.holdings.put(tcs.id, new Holding(tcs.id));
        alice.holdings.get(tcs.id).buy(100, 3500.0);  // 100 shares at avg 3500
        System.out.println();

        // ── Scenario 1: Basic match ────────────────────────────────────────────
        System.out.println("── Scenario 1: Simple Buy-Sell Match ──");
        // Alice sells 10 TCS @ ₹3600
        broker.placeOrder(alice.id, tcs.id, OrderType.SELL, 3600.0, 10);
        // Bob buys 10 TCS @ ₹3650 — price >= sell price → MATCH at 3600
        broker.placeOrder(bob.id,   tcs.id, OrderType.BUY,  3650.0, 10);
        System.out.println();
        broker.printHoldings(alice.id);
        broker.printHoldings(bob.id);
        System.out.println();

        // ── Scenario 2: Partial fill ───────────────────────────────────────────
        System.out.println("── Scenario 2: Partial Fill ──");
        // Alice sells 20 TCS @ ₹3700, Carol only buys 5 → partial fill
        broker.placeOrder(alice.id, tcs.id, OrderType.SELL, 3700.0, 20);
        broker.placeOrder(carol.id, tcs.id, OrderType.BUY,  3750.0, 5);
        broker.printOrderBook(tcs.id);   // 15 sell orders still open
        System.out.println();

        // ── Scenario 3: Price-Time priority (FIFO) ────────────────────────────
        System.out.println("── Scenario 3: FIFO — Two sellers same price, earliest gets matched ──");
        // Two sell orders at same price — Alice placed first (FIFO priority)
        broker.placeOrder(alice.id, tcs.id, OrderType.SELL, 3800.0, 5);
        Thread.sleep(10); // ensure timestamp difference
        broker.placeOrder(carol.id, tcs.id, OrderType.BUY,  3750.0, 5); // give Carol shares first
        carol.holdings.computeIfAbsent(tcs.id, k -> new Holding(k)).buy(5, 3800);

        broker.placeOrder(carol.id, tcs.id, OrderType.SELL, 3800.0, 5); // Carol also sells @ same price
        Thread.sleep(10);
        broker.placeOrder(bob.id,   tcs.id, OrderType.BUY,  3850.0, 5); // Bob buys → should match Alice first
        System.out.println();

        // ── Scenario 4: No match (spread) ─────────────────────────────────────
        System.out.println("── Scenario 4: No Match (buy price < sell price) ──");
        broker.placeOrder(bob.id,   infy.id, OrderType.BUY,  1400.0, 50); // Bob wants INFY @ 1400
        // No seller yet → order stays in book
        broker.printOrderBook(infy.id);
        System.out.println();

        // ── Scenario 5: CONCURRENCY ────────────────────────────────────────────
        // Alice and Carol both place matching orders simultaneously on TCS.
        // Only one should successfully match with the pending sell order.
        System.out.println("── Scenario 5: Concurrent Order Placement ──");
        broker.placeOrder(alice.id, tcs.id, OrderType.SELL, 3600.0, 5); // new sell
        Thread t1 = new Thread(() ->
                broker.placeOrder(bob.id,   tcs.id, OrderType.BUY, 3650.0, 5), "Thread-Bob");
        Thread t2 = new Thread(() ->
                broker.placeOrder(carol.id, tcs.id, OrderType.BUY, 3650.0, 5), "Thread-Carol");
        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.println();

        // ── Final state ───────────────────────────────────────────────────────
        System.out.println("── Final Portfolios ──");
        broker.printHoldings(alice.id);
        broker.printHoldings(bob.id);
        broker.printTrades(alice.id);
    }
}
