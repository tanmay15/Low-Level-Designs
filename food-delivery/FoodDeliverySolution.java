// =============================================================================
// LLD: FOOD DELIVERY SYSTEM — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Customers can browse restaurants and their menus
//   2. Customers can place an order with multiple items from one restaurant
//   3. Order goes through states: PLACED → PREPARING → OUT_FOR_DELIVERY → DELIVERED
//   4. An available delivery partner is assigned to the order when it is ready
//   5. Customer can cancel an order only before it reaches PREPARING state
//   6. Delivery fee is configurable and can vary by strategy
//   7. Subscribers are notified on every order status change (Observer)
//
// Non-Functional:
//   - Order state machine enforces valid transitions — invalid moves are rejected
//   - Delivery partner availability is managed like a resource (similar to ParkingSpot)
//   - Delivery fee strategy is swappable without changing OrderService (Strategy)
//   - Adding a new notification type = add a new OrderStatusObserver (Observer)
//
// Out of scope: Payment gateway, restaurant ratings, real-time GPS tracking, surge pricing
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum OrderStatus { PLACED, PREPARING, OUT_FOR_DELIVERY, DELIVERED, CANCELLED }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:   MenuItem, Restaurant, Customer, DeliveryPartner, OrderItem, Order
// Interface:  DeliveryFeeStrategy  (Strategy)
// Interface:  OrderStatusObserver  (Observer)
// Service:    OrderService
//
// Relationships:
//   Restaurant  HAS-A (Composition)   Map<id, MenuItem>
//   Order       HAS-A (Aggregation)   Customer, Restaurant
//   Order       HAS-A (Composition)   List<OrderItem>
//   Order       HAS-A (Aggregation)   DeliveryPartner (assigned later)
//   OrderService USES                 DeliveryFeeStrategy
//   OrderService OWNS                 List<OrderStatusObserver>
//
// State Machine on Order (most complex in all our problems):
//   PLACED → PREPARING → OUT_FOR_DELIVERY → DELIVERED
//   PLACED → CANCELLED
//   Any other transition → rejected
// =============================================================================


// ── MenuItem ──────────────────────────────────────────────────────────────────

class MenuItem {
    public String id;
    public String name;
    public double price;
    public String category;

    public MenuItem(String id, String name, double price, String category) {
        this.id       = id;
        this.name     = name;
        this.price    = price;
        this.category = category;
    }
}


// ── Restaurant ────────────────────────────────────────────────────────────────

class Restaurant {
    public String id;
    public String name;
    public String location;
    private Map<String, MenuItem> menu; // menuItemId → MenuItem

    public Restaurant(String id, String name, String location) {
        this.id       = id;
        this.name     = name;
        this.location = location;
        this.menu     = new HashMap<>();
    }

    public void addMenuItem(MenuItem item) {
        menu.put(item.id, item);
    }

    public MenuItem getMenuItem(String itemId) {
        return menu.get(itemId);
    }

    public Map<String, MenuItem> getMenu() { return menu; }
}


// ── Customer ──────────────────────────────────────────────────────────────────

class Customer {
    public String id;
    public String name;
    public String phone;
    public String address;

    public Customer(String id, String name, String phone, String address) {
        this.id      = id;
        this.name    = name;
        this.phone   = phone;
        this.address = address;
    }
}


// ── DeliveryPartner ───────────────────────────────────────────────────────────
// Availability is managed like a resource — same principle as ParkingSpot.
// markBusy() / markAvailable() are the only ways to change availability.

class DeliveryPartner {
    public String id;
    public String name;
    private boolean available;

    public DeliveryPartner(String id, String name) {
        this.id        = id;
        this.name      = name;
        this.available = true;
    }

    public boolean isAvailable() { return available; }
    public void markBusy()      { this.available = false; }
    public void markAvailable() { this.available = true; }
}


// ── OrderItem ─────────────────────────────────────────────────────────────────
// Line item within an order. quantity × price = subtotal.
// Similar to ShowSeat (which holds seat + price per show).

class OrderItem {
    public MenuItem menuItem;
    public int      quantity;
    public double   subtotal;

    public OrderItem(MenuItem menuItem, int quantity) {
        this.menuItem = menuItem;
        this.quantity = quantity;
        this.subtotal = menuItem.price * quantity;
    }
}


// ── Order ─────────────────────────────────────────────────────────────────────
// State machine: PLACED → PREPARING → OUT_FOR_DELIVERY → DELIVERED
//                PLACED → CANCELLED
// updateStatus() validates the transition before applying it.

class Order {
    public String         id;
    public Customer       customer;
    public Restaurant     restaurant;
    public List<OrderItem> items;
    public OrderStatus    status;
    public DeliveryPartner deliveryPartner; // assigned later, starts null
    public double         itemsTotal;
    public double         deliveryFee;
    public double         totalAmount;
    public Date           placedAt;

    public Order(String id, Customer customer, Restaurant restaurant,
                 List<OrderItem> items, double deliveryFee) {
        this.id              = id;
        this.customer        = customer;
        this.restaurant      = restaurant;
        this.items           = items;
        this.status          = OrderStatus.PLACED;
        this.deliveryPartner = null;
        this.deliveryFee     = deliveryFee;
        this.placedAt        = new Date();

        double total = 0;
        for (OrderItem item : items) total += item.subtotal;
        this.itemsTotal  = total;
        this.totalAmount = total + deliveryFee;
    }

    // Validates and applies a state transition
    public void updateStatus(OrderStatus newStatus) {
        if (!isValidTransition(this.status, newStatus)) {
            throw new RuntimeException("Invalid transition: " + this.status + " → " + newStatus);
        }
        this.status = newStatus;
    }

    public void assignPartner(DeliveryPartner partner) {
        this.deliveryPartner = partner;
    }

    private boolean isValidTransition(OrderStatus from, OrderStatus to) {
        switch (from) {
            case PLACED:            return to == OrderStatus.PREPARING || to == OrderStatus.CANCELLED;
            case PREPARING:         return to == OrderStatus.OUT_FOR_DELIVERY;
            case OUT_FOR_DELIVERY:  return to == OrderStatus.DELIVERED;
            default:                return false; // DELIVERED and CANCELLED are terminal
        }
    }
}


// ── DeliveryFeeStrategy (Strategy Pattern) ───────────────────────────────────

interface DeliveryFeeStrategy {
    double calculate(Order order);
}

// Always charges a flat fee
class FlatFeeStrategy implements DeliveryFeeStrategy {
    private double fee;
    public FlatFeeStrategy(double fee) { this.fee = fee; }

    @Override
    public double calculate(Order order) { return fee; }
}

// Free delivery above a threshold; otherwise charges a fee
class FreeAboveThresholdStrategy implements DeliveryFeeStrategy {
    private double threshold;
    private double fee;
    public FreeAboveThresholdStrategy(double threshold, double fee) {
        this.threshold = threshold;
        this.fee       = fee;
    }

    @Override
    public double calculate(Order order) {
        return order.itemsTotal >= threshold ? 0 : fee;
    }
}


// ── OrderStatusObserver (Observer Pattern) ────────────────────────────────────
// Notified every time an order's status changes.
// Adding new notification type = add new class, nothing else changes.

interface OrderStatusObserver {
    void onStatusChange(Order order, OrderStatus newStatus);
}

class CustomerNotifier implements OrderStatusObserver {
    @Override
    public void onStatusChange(Order order, OrderStatus newStatus) {
        System.out.println("  [SMS → " + order.customer.phone + "] Order " +
                order.id + " is now: " + newStatus);
    }
}

class RestaurantNotifier implements OrderStatusObserver {
    @Override
    public void onStatusChange(Order order, OrderStatus newStatus) {
        if (newStatus == OrderStatus.PLACED) {
            System.out.println("  [NOTIFY → " + order.restaurant.name + "] New order received: " + order.id);
        }
    }
}


// ── OrderService ──────────────────────────────────────────────────────────────
// Orchestrates: place order, update state, assign partner, cancel, notify observers.

class OrderService {
    private Map<String, Restaurant>      restaurants;
    private Map<String, Customer>        customers;
    private Map<String, DeliveryPartner> partners;
    private Map<String, Order>           orders;
    private List<OrderStatusObserver>    observers;
    private int                          orderCounter;
    private DeliveryFeeStrategy          feeStrategy;

    public OrderService() {
        this.restaurants  = new HashMap<>();
        this.customers    = new HashMap<>();
        this.partners     = new HashMap<>();
        this.orders       = new HashMap<>();
        this.observers    = new ArrayList<>();
        this.orderCounter = 0;
        this.feeStrategy  = new FlatFeeStrategy(40); // ₹40 flat default
    }

    public void setFeeStrategy(DeliveryFeeStrategy strategy) {
        this.feeStrategy = strategy;
    }

    public void addObserver(OrderStatusObserver observer) {
        observers.add(observer);
    }

    public void registerRestaurant(Restaurant r) { restaurants.put(r.id, r); }
    public void registerCustomer(Customer c)     { customers.put(c.id, c); }
    public void registerPartner(DeliveryPartner p) { partners.put(p.id, p); }

    // Place: resolves menu items, calculates totals, creates Order
    public Order placeOrder(String customerId, String restaurantId,
                            Map<String, Integer> itemQuantities) { // itemId → qty
        Customer   customer   = customers.get(customerId);
        Restaurant restaurant = restaurants.get(restaurantId);
        if (customer == null)   throw new RuntimeException("Customer not found: " + customerId);
        if (restaurant == null) throw new RuntimeException("Restaurant not found: " + restaurantId);

        List<OrderItem> items = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : itemQuantities.entrySet()) {
            MenuItem item = restaurant.getMenuItem(entry.getKey());
            if (item == null) throw new RuntimeException("Menu item not found: " + entry.getKey());
            items.add(new OrderItem(item, entry.getValue()));
        }
        if (items.isEmpty()) throw new RuntimeException("Order must have at least one item");

        // Calculate delivery fee based on items total (needs a temporary order shell)
        // We pass 0 first, then recalculate using the strategy
        Order order = new Order("ORD-" + (++orderCounter), customer, restaurant, items, 0);
        double fee  = feeStrategy.calculate(order);
        order.deliveryFee  = fee;
        order.totalAmount  = order.itemsTotal + fee;

        orders.put(order.id, order);

        System.out.println("[ORDER PLACED] " + order.id + " | " + customer.name +
                " from " + restaurant.name);
        for (OrderItem oi : items) {
            System.out.println("  " + oi.menuItem.name + " × " + oi.quantity +
                    " = ₹" + (int) oi.subtotal);
        }
        System.out.println("  Delivery Fee: ₹" + (int) fee +
                " | Total: ₹" + (int) order.totalAmount);

        notifyObservers(order, OrderStatus.PLACED);
        return order;
    }

    // Update order status — validates transition, then notifies observers
    public void updateOrderStatus(String orderId, OrderStatus newStatus) {
        Order order = getOrder(orderId);
        order.updateStatus(newStatus); // throws if invalid transition
        System.out.println("[STATUS] " + orderId + " → " + newStatus);
        notifyObservers(order, newStatus);
    }

    // Assign first available delivery partner to the order
    public void assignDeliveryPartner(String orderId) {
        Order order = getOrder(orderId);
        if (order.status != OrderStatus.PREPARING) {
            throw new RuntimeException("Can only assign partner when order is PREPARING");
        }

        DeliveryPartner partner = null;
        for (DeliveryPartner p : partners.values()) {
            if (p.isAvailable()) { partner = p; break; }
        }
        if (partner == null) throw new RuntimeException("No delivery partner available");

        partner.markBusy();
        order.assignPartner(partner);
        System.out.println("[PARTNER ASSIGNED] " + partner.name + " → Order " + orderId);
    }

    // Mark partner as available again after delivery
    public void completeDelivery(String orderId) {
        Order order = getOrder(orderId);
        updateOrderStatus(orderId, OrderStatus.DELIVERED);
        if (order.deliveryPartner != null) {
            order.deliveryPartner.markAvailable();
            System.out.println("[PARTNER FREE] " + order.deliveryPartner.name + " is available again");
        }
    }

    // Cancel: only allowed from PLACED state
    public void cancelOrder(String orderId) {
        Order order = getOrder(orderId);
        if (order.status != OrderStatus.PLACED) {
            throw new RuntimeException("Cannot cancel order in status: " + order.status +
                    " (only PLACED orders can be cancelled)");
        }
        order.updateStatus(OrderStatus.CANCELLED);
        System.out.println("[CANCELLED] Order " + orderId);
        notifyObservers(order, OrderStatus.CANCELLED);
    }

    public void printOrder(String orderId) {
        Order order = getOrder(orderId);
        System.out.println("\n── Order " + order.id + " ──");
        System.out.println("  Customer:  " + order.customer.name);
        System.out.println("  Restaurant:" + order.restaurant.name);
        System.out.println("  Status:    " + order.status);
        System.out.println("  Partner:   " + (order.deliveryPartner != null ? order.deliveryPartner.name : "not assigned"));
        System.out.println("  Total:     ₹" + (int) order.totalAmount);
        System.out.println();
    }

    private Order getOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) throw new RuntimeException("Order not found: " + orderId);
        return order;
    }

    private void notifyObservers(Order order, OrderStatus status) {
        for (OrderStatusObserver observer : observers) {
            observer.onStatusChange(order, status);
        }
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: FoodDeliverySolution.java
// =============================================================================

public class FoodDeliverySolution {
    public static void main(String[] args) {
        System.out.println("=== Food Delivery System Demo ===\n");

        OrderService service = new OrderService();

        // Observers
        service.addObserver(new CustomerNotifier());
        service.addObserver(new RestaurantNotifier());

        // Setup
        Restaurant r1 = new Restaurant("R1", "Biryani House", "Andheri");
        r1.addMenuItem(new MenuItem("M1", "Chicken Biryani", 280, "Main"));
        r1.addMenuItem(new MenuItem("M2", "Raita",           60,  "Side"));
        r1.addMenuItem(new MenuItem("M3", "Gulab Jamun",     80,  "Dessert"));

        Restaurant r2 = new Restaurant("R2", "Pizza Palace", "Bandra");
        r2.addMenuItem(new MenuItem("P1", "Margherita Pizza", 350, "Main"));
        r2.addMenuItem(new MenuItem("P2", "Garlic Bread",     120, "Side"));

        Customer alice = new Customer("C1", "Alice", "+91-9000000001", "Malad West");
        Customer bob   = new Customer("C2", "Bob",   "+91-9000000002", "Goregaon");

        DeliveryPartner dp1 = new DeliveryPartner("DP1", "Ravi");
        DeliveryPartner dp2 = new DeliveryPartner("DP2", "Suresh");

        service.registerRestaurant(r1);
        service.registerRestaurant(r2);
        service.registerCustomer(alice);
        service.registerCustomer(bob);
        service.registerPartner(dp1);
        service.registerPartner(dp2);
        System.out.println();

        // ── Full happy path: PLACED → PREPARING → assigned → DELIVERED ────────
        System.out.println("── Alice places order from Biryani House ──");
        Map<String, Integer> aliceItems = new LinkedHashMap<>();
        aliceItems.put("M1", 2); // 2 Chicken Biryani
        aliceItems.put("M2", 1); // 1 Raita
        Order aliceOrder = service.placeOrder("C1", "R1", aliceItems);
        System.out.println();

        service.updateOrderStatus(aliceOrder.id, OrderStatus.PREPARING);
        service.assignDeliveryPartner(aliceOrder.id);
        service.updateOrderStatus(aliceOrder.id, OrderStatus.OUT_FOR_DELIVERY);
        service.completeDelivery(aliceOrder.id);
        service.printOrder(aliceOrder.id);

        // ── Free delivery above threshold ─────────────────────────────────────
        System.out.println("── Switching to free-above-₹500 strategy ──");
        service.setFeeStrategy(new FreeAboveThresholdStrategy(500, 40));
        Map<String, Integer> bigOrder = new LinkedHashMap<>();
        bigOrder.put("P1", 2); // 2 × ₹350 = ₹700 → free delivery
        Order bobOrder = service.placeOrder("C2", "R2", bigOrder);
        System.out.println();

        // ── Cancellation: PLACED can be cancelled ─────────────────────────────
        System.out.println("── Bob cancels his order ──");
        service.cancelOrder(bobOrder.id);
        System.out.println();

        // ── Invalid transition after cancel ───────────────────────────────────
        System.out.println("── Try to move cancelled order to PREPARING ──");
        try {
            service.updateOrderStatus(bobOrder.id, OrderStatus.PREPARING);
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
        System.out.println();

        // ── Cancellation too late (already PREPARING) ─────────────────────────
        System.out.println("── Try to cancel after PREPARING ──");
        Map<String, Integer> aliceItems2 = new LinkedHashMap<>();
        aliceItems2.put("M3", 3);
        Order lateCancel = service.placeOrder("C1", "R1", aliceItems2);
        service.updateOrderStatus(lateCancel.id, OrderStatus.PREPARING);
        System.out.println();
        try {
            service.cancelOrder(lateCancel.id);
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
        System.out.println();

        // ── No available partner ───────────────────────────────────────────────
        System.out.println("── No available partner scenario ──");
        // Both dp1 and dp2 busy — dp1 was freed after Alice's delivery, but dp2 is still free
        // Manually mark both busy
        dp1.markBusy();
        dp2.markBusy();
        Map<String, Integer> testOrder = new LinkedHashMap<>();
        testOrder.put("P2", 1);
        Order noPartnerOrder = service.placeOrder("C2", "R2", testOrder);
        service.updateOrderStatus(noPartnerOrder.id, OrderStatus.PREPARING);
        System.out.println();
        try {
            service.assignDeliveryPartner(noPartnerOrder.id);
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
    }
}
