// =============================================================================
// LLD: ONLINE AUCTION SYSTEM
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Seller creates an auction for an item with a starting price and duration
//   2. Bidders place bids — each bid must exceed the current highest
//   3. Auction closes when endTime passes (time-driven state transition)
//   4. Winner = highest bidder at the time of closing
//   5. Seller can close auction manually before endTime
//   6. Bidder cannot bid on their own auction
//
// Non-Functional:
//   - State machine: SCHEDULED → OPEN → CLOSED / CANCELLED
//   - Bids are immutable once placed (audit trail)
//
// Out of scope: payment processing, bid retraction, reserve price,
//   buy-now price, real-time auction streaming, proxy bidding
//
// UNIQUE ASPECT vs other problems:
//   In most problems, state transitions are triggered by USER ACTIONS.
//   In Online Auction, OPEN → CLOSED is triggered by TIME (endTime passes).
//   This makes the demo different — we simulate time passing to close auctions.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum AuctionStatus { SCHEDULED, OPEN, CLOSED, CANCELLED }


// =============================================================================
// ENTITIES
// =============================================================================

// ── Item ──────────────────────────────────────────────────────────────────────
class Item {
    public String id;
    public String name;
    public String description;

    public Item(String id, String name, String description) {
        this.id          = id;
        this.name        = name;
        this.description = description;
    }
}

// ── Bidder ────────────────────────────────────────────────────────────────────
class Bidder {
    public String id;
    public String name;

    public Bidder(String id, String name) {
        this.id   = id;
        this.name = name;
    }
}

// ── Bid ───────────────────────────────────────────────────────────────────────
// Immutable once placed. Each bid is a record — forms the auction's audit trail.

class Bid {
    public String id;
    public String bidderId;
    public String auctionId;
    public int    amount;      // in paise / cents
    public long   timestamp;

    public Bid(String id, String bidderId, String auctionId, int amount, long timestamp) {
        this.id        = id;
        this.bidderId  = bidderId;
        this.auctionId = auctionId;
        this.amount    = amount;
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return String.format("Bid[%s | bidder=%s | ₹%d]", id, bidderId, amount / 100);
    }
}

// ── Auction ───────────────────────────────────────────────────────────────────
// The core entity. State machine: SCHEDULED → OPEN → CLOSED / CANCELLED.
// Holds all bids and tracks the current highest.

class Auction {
    public String        id;
    public String        sellerId;
    public Item          item;
    public int           startingPrice;   // in paise
    public int           currentHighest;  // in paise
    public String        highestBidderId;
    public Bid           winningBid;      // set on close
    public AuctionStatus status;
    public long          startTime;
    public long          endTime;         // auction closes when now > endTime
    public List<Bid>     bids;

    public Auction(String id, String sellerId, Item item,
                   int startingPrice, long startTime, long durationMs) {
        this.id             = id;
        this.sellerId       = sellerId;
        this.item           = item;
        this.startingPrice  = startingPrice;
        this.currentHighest = startingPrice;
        this.status         = AuctionStatus.SCHEDULED;
        this.startTime      = startTime;
        this.endTime        = startTime + durationMs;
        this.bids           = new ArrayList<>();
    }

    public boolean isExpired(long nowMs) {
        return nowMs >= endTime;
    }
}


// =============================================================================
// AUCTION SERVICE
// =============================================================================

class AuctionService {
    private Map<String, Auction> auctions;
    private Map<String, Bidder>  bidders;
    private int                  auctionCounter;
    private int                  bidCounter;

    public AuctionService() {
        this.auctions       = new HashMap<>();
        this.bidders        = new HashMap<>();
        this.auctionCounter = 0;
        this.bidCounter     = 0;
    }

    public void registerBidder(Bidder bidder) {
        bidders.put(bidder.id, bidder);
        System.out.println("[REGISTER] Bidder: " + bidder.name);
    }

    // ── createAuction ─────────────────────────────────────────────────────────
    public Auction createAuction(String sellerId, Item item,
                                 int startingPriceRs, long startMs, long durationMs) {
        String  id      = "AUC-" + (++auctionCounter);
        Auction auction = new Auction(id, sellerId, item,
                startingPriceRs * 100, startMs, durationMs);
        auctions.put(id, auction);
        System.out.println("[CREATED] " + id + " | item=" + item.name
                + " | start=₹" + startingPriceRs
                + " | closes=" + new Date(auction.endTime));
        return auction;
    }

    // ── openAuction ───────────────────────────────────────────────────────────
    // In production: triggered by a scheduler when startTime is reached.
    // In demo: called manually.
    public void openAuction(String auctionId, long nowMs) {
        Auction auction = get(auctionId);
        if (auction.status != AuctionStatus.SCHEDULED)
            throw new RuntimeException("Auction " + auctionId + " is not in SCHEDULED state");
        auction.status = AuctionStatus.OPEN;
        System.out.println("[OPEN] " + auctionId + " is now accepting bids");
    }

    // ── placeBid ──────────────────────────────────────────────────────────────
    // Validates: auction OPEN, not expired, bidder != seller, amount > currentHighest
    public Bid placeBid(String auctionId, String bidderId, int amountRs, long nowMs) {
        Auction auction = get(auctionId);

        if (auction.status != AuctionStatus.OPEN)
            throw new RuntimeException("Auction is not open for bidding");
        if (auction.isExpired(nowMs)) {
            autoClose(auction, nowMs);
            throw new RuntimeException("Auction has expired");
        }
        if (auction.sellerId.equals(bidderId))
            throw new RuntimeException("Seller cannot bid on their own auction");

        int amount = amountRs * 100;
        if (amount <= auction.currentHighest)
            throw new RuntimeException("Bid ₹" + amountRs
                    + " must exceed current highest ₹" + auction.currentHighest / 100);

        String bid_id = "BID-" + (++bidCounter);
        Bid    bid    = new Bid(bid_id, bidderId, auctionId, amount, nowMs);

        auction.bids.add(bid);
        auction.currentHighest  = amount;
        auction.highestBidderId = bidderId;

        Bidder bidder = bidders.get(bidderId);
        String name   = bidder != null ? bidder.name : bidderId;
        System.out.println("[BID] " + bid + " by " + name
                + " | new highest: ₹" + amountRs);
        return bid;
    }

    // ── closeAuction ──────────────────────────────────────────────────────────
    // Manually closed by seller, or auto-closed when endTime passes.
    public Bid closeAuction(String auctionId, long nowMs) {
        Auction auction = get(auctionId);
        if (auction.status == AuctionStatus.CLOSED)
            throw new RuntimeException("Auction already closed");
        if (auction.status == AuctionStatus.CANCELLED)
            throw new RuntimeException("Auction was cancelled");

        return autoClose(auction, nowMs);
    }

    // ── checkAndCloseExpired ──────────────────────────────────────────────────
    // Simulates the cron sweep that closes expired auctions.
    // In production: runs periodically in background.
    public void checkAndCloseExpired(long nowMs) {
        System.out.println("[SWEEP] Checking for expired auctions at " + new Date(nowMs));
        for (Auction a : auctions.values()) {
            if (a.status == AuctionStatus.OPEN && a.isExpired(nowMs)) {
                autoClose(a, nowMs);
            }
        }
    }

    // ── cancelAuction ─────────────────────────────────────────────────────────
    // Only allowed if no bids have been placed yet.
    public void cancelAuction(String auctionId) {
        Auction auction = get(auctionId);
        if (!auction.bids.isEmpty())
            throw new RuntimeException("Cannot cancel — bids already placed");
        if (auction.status == AuctionStatus.CLOSED)
            throw new RuntimeException("Cannot cancel a closed auction");
        auction.status = AuctionStatus.CANCELLED;
        System.out.println("[CANCEL] " + auctionId + " cancelled");
    }

    // ── Query ─────────────────────────────────────────────────────────────────
    public void printAuctionStatus(String auctionId) {
        Auction a = get(auctionId);
        System.out.printf("── Auction %s [%s] ──%n", a.id, a.status);
        System.out.printf("  Item: %s | Starting: ₹%d | Current Highest: ₹%d%n",
                a.item.name, a.startingPrice / 100, a.currentHighest / 100);
        System.out.printf("  Bids placed: %d%n", a.bids.size());
        if (a.winningBid != null) {
            Bidder winner = bidders.get(a.highestBidderId);
            System.out.println("  WINNER: " + (winner != null ? winner.name : a.highestBidderId)
                    + " with " + a.winningBid);
        } else if (a.bids.isEmpty()) {
            System.out.println("  No bids placed");
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Bid autoClose(Auction auction, long nowMs) {
        auction.status = AuctionStatus.CLOSED;

        if (auction.bids.isEmpty()) {
            System.out.println("[CLOSED] " + auction.id + " | No bids — item unsold");
            return null;
        }

        auction.winningBid = auction.bids.get(auction.bids.size() - 1); // last bid = highest
        Bidder winner      = bidders.get(auction.highestBidderId);
        System.out.println("[CLOSED] " + auction.id + " | WINNER: "
                + (winner != null ? winner.name : auction.highestBidderId)
                + " | Winning bid: " + auction.winningBid);
        return auction.winningBid;
    }

    private Auction get(String id) {
        Auction a = auctions.get(id);
        if (a == null) throw new RuntimeException("Auction not found: " + id);
        return a;
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class OnlineAuctionSolution {
    public static void main(String[] args) {
        System.out.println("=== Online Auction Demo ===\n");

        AuctionService service = new AuctionService();

        // ── Setup bidders ─────────────────────────────────────────────────────
        service.registerBidder(new Bidder("SELLER-1", "Rahul (Seller)"));
        service.registerBidder(new Bidder("BIDDER-A", "Alice"));
        service.registerBidder(new Bidder("BIDDER-B", "Bob"));
        service.registerBidder(new Bidder("BIDDER-C", "Charlie"));
        System.out.println();

        long now = System.currentTimeMillis();

        // ── Scenario 1: Normal auction lifecycle ──────────────────────────────
        System.out.println("════ Scenario 1: Normal auction — highest bidder wins ════");

        Item vintage = new Item("ITEM-1", "Vintage Guitar", "1960s Fender Stratocaster");
        Auction a1 = service.createAuction("SELLER-1", vintage, 50000, now, 60000); // ₹50k start, 60s duration

        service.openAuction(a1.id, now);

        service.placeBid(a1.id, "BIDDER-A", 55000, now + 10000); // Alice: ₹55k
        service.placeBid(a1.id, "BIDDER-B", 62000, now + 20000); // Bob: ₹62k
        service.placeBid(a1.id, "BIDDER-A", 70000, now + 30000); // Alice outbids Bob
        service.placeBid(a1.id, "BIDDER-C", 75000, now + 40000); // Charlie: ₹75k

        // Simulate time passing — auction expires, close it
        service.closeAuction(a1.id, now + 70000);

        System.out.println();
        service.printAuctionStatus(a1.id);
        System.out.println();

        // ── Scenario 2: Bid too low ───────────────────────────────────────────
        System.out.println("════ Scenario 2: Bid must exceed current highest ════");
        Item painting = new Item("ITEM-2", "Abstract Painting", "Modern art, 2020");
        Auction a2 = service.createAuction("SELLER-1", painting, 10000, now, 3600000);
        service.openAuction(a2.id, now);
        service.placeBid(a2.id, "BIDDER-A", 12000, now + 1000);

        try {
            service.placeBid(a2.id, "BIDDER-B", 11000, now + 2000); // lower than Alice's
        } catch (RuntimeException e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }
        System.out.println();

        // ── Scenario 3: Seller tries to bid on own auction ───────────────────
        System.out.println("════ Scenario 3: Seller bid rejected ════");
        try {
            service.placeBid(a2.id, "SELLER-1", 15000, now + 3000);
        } catch (RuntimeException e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }
        System.out.println();

        // ── Scenario 4: No bids — item unsold ────────────────────────────────
        System.out.println("════ Scenario 4: Auction closes with no bids ════");
        Item sculpture = new Item("ITEM-3", "Marble Sculpture", "Heavy and awkward");
        Auction a3 = service.createAuction("SELLER-1", sculpture, 500000, now, 1000); // 1ms duration
        service.openAuction(a3.id, now);
        // Nobody bids
        service.checkAndCloseExpired(now + 2000); // sweep closes it
        service.printAuctionStatus(a3.id);
        System.out.println();

        // ── Scenario 5: Cancel auction before any bids ───────────────────────
        System.out.println("════ Scenario 5: Cancel auction (no bids yet) ════");
        Item watch = new Item("ITEM-4", "Luxury Watch", "Swiss made");
        Auction a4 = service.createAuction("SELLER-1", watch, 20000, now, 3600000);
        service.cancelAuction(a4.id);
        service.printAuctionStatus(a4.id);
    }
}
