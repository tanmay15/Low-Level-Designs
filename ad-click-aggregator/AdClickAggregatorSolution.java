// =============================================================================
// LLD: AD CLICK AGGREGATOR
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Record ad events (CLICK, VIEW, CONVERSION) from users
//   2. Each ad belongs to a Campaign
//   3. Deduplicate: count only FIRST click per user per ad (unique clicks)
//   4. Multiple consumers receive events: Analytics, Billing, etc. (Observer)
//   5. Query aggregated stats:
//        - getStats(adId)                            → clicks, views, unique users
//        - getTopAds(campaignId, n)                  → top N ads by clicks
//        - getClicksByWindow(adId, startMs, endMs)   → time-windowed click count
//
// Non-Functional:
//   - Low-latency ingestion (in-memory, no I/O on critical path)
//   - Consumer list is extensible without changing ingestion logic (Observer)
//   - Deduplication strategy is swappable (Strategy)
//
// Out of scope: persistence, distributed queues, real-time streaming, auth
//
// KEY ARCHITECTURAL INSIGHT — TWO PATHS:
//   Write path: EventIngestionService.recordEvent() — validates, deduplicates, publishes
//   Read path:  AnalyticsService.getStats()         — queries aggregated counters
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum EventType { CLICK, VIEW, CONVERSION }


// =============================================================================
// STEP 3 — ENTITIES
// =============================================================================

// ── Campaign ──────────────────────────────────────────────────────────────────
class Campaign {
    public String id;
    public String name;
    public String advertiserId;
    public double budget;

    public Campaign(String id, String name, String advertiserId, double budget) {
        this.id           = id;
        this.name         = name;
        this.advertiserId = advertiserId;
        this.budget       = budget;
    }
}

// ── Ad ────────────────────────────────────────────────────────────────────────
// An Ad belongs to exactly one Campaign.
class Ad {
    public String id;
    public String url;
    public String campaignId;

    public Ad(String id, String url, String campaignId) {
        this.id         = id;
        this.url        = url;
        this.campaignId = campaignId;
    }
}

// ── User ──────────────────────────────────────────────────────────────────────
class User {
    public String id;
    public String name;

    public User(String id, String name) {
        this.id   = id;
        this.name = name;
    }
}

// ── ClickEvent ────────────────────────────────────────────────────────────────
// Immutable record of one interaction. Timestamp is critical for time-windowed queries.
class ClickEvent {
    public String    id;
    public EventType type;
    public String    userId;
    public String    adId;
    public long      timestampMs;   // epoch millis — needed for windowed aggregation

    public ClickEvent(String id, EventType type, String userId, String adId, long timestampMs) {
        this.id          = id;
        this.type        = type;
        this.userId      = userId;
        this.adId        = adId;
        this.timestampMs = timestampMs;
    }
}

// ── AdStats ───────────────────────────────────────────────────────────────────
// Live aggregated counters per ad. Updated on every accepted event.
// Separating this from raw events gives O(1) stat reads.
class AdStats {
    public String adId;
    public int    totalClicks;
    public int    totalViews;
    public int    totalConversions;
    public int    uniqueUserClicks;   // deduplicated click count

    public AdStats(String adId) {
        this.adId = adId;
    }

    @Override
    public String toString() {
        return String.format("AdStats[%s] clicks=%d uniqueClicks=%d views=%d conversions=%d",
                adId, totalClicks, uniqueUserClicks, totalViews, totalConversions);
    }
}


// =============================================================================
// OBSERVER PATTERN — EventConsumer
// =============================================================================
// Multiple teams consume events without EventIngestionService knowing about them.
// Add a new consumer without touching ingestion logic.

interface EventConsumer {
    void onEvent(ClickEvent event);
}

// Analytics consumer — updates AdStats in real-time
class AnalyticsConsumer implements EventConsumer {
    // adId → AdStats (live counters)
    private Map<String, AdStats> statsMap = new HashMap<>();

    // adId → Set<userId> — for deduplication of clicks
    private Map<String, Set<String>> uniqueUsersPerAd = new HashMap<>();

    @Override
    public void onEvent(ClickEvent event) {
        AdStats stats = statsMap.computeIfAbsent(event.adId, AdStats::new);
        Set<String> seenUsers = uniqueUsersPerAd.computeIfAbsent(event.adId, k -> new HashSet<>());

        switch (event.type) {
            case CLICK:
                stats.totalClicks++;
                if (seenUsers.add(event.userId)) {  // add() returns false if already present
                    stats.uniqueUserClicks++;         // only count first click per user
                }
                break;
            case VIEW:
                stats.totalViews++;
                break;
            case CONVERSION:
                stats.totalConversions++;
                break;
        }
    }

    public AdStats getStats(String adId) {
        return statsMap.getOrDefault(adId, new AdStats(adId));
    }

    // Top N ads by click count for a given campaign
    // campaignAds: caller provides which adIds belong to the campaign
    public List<String> getTopAds(List<String> campaignAdIds, int n) {
        List<String> sorted = new ArrayList<>(campaignAdIds);
        sorted.sort((a, b) -> {
            int ca = statsMap.getOrDefault(a, new AdStats(a)).totalClicks;
            int cb = statsMap.getOrDefault(b, new AdStats(b)).totalClicks;
            return cb - ca; // descending
        });
        return sorted.subList(0, Math.min(n, sorted.size()));
    }
}

// Billing consumer — charges advertiser per click
class BillingConsumer implements EventConsumer {
    private Map<String, Double> billingLedger = new HashMap<>(); // adId → cost
    private double costPerClick = 0.05; // $0.05 per click

    @Override
    public void onEvent(ClickEvent event) {
        if (event.type == EventType.CLICK) {
            billingLedger.merge(event.adId, costPerClick, Double::sum);
            System.out.println("  [BILLING] Ad " + event.adId +
                    " charged $" + costPerClick +
                    " | total: $" + String.format("%.2f", billingLedger.get(event.adId)));
        }
    }

    public double getTotalCost(String adId) {
        return billingLedger.getOrDefault(adId, 0.0);
    }
}


// =============================================================================
// SERVICES
// =============================================================================

// ── AdRegistry ────────────────────────────────────────────────────────────────
// Manages known campaigns, ads, users.
// Uses Maps (not Lists) so lookups are O(1) by ID.

class AdRegistry {
    private Map<String, Campaign> campaigns = new HashMap<>();
    private Map<String, Ad>       ads       = new HashMap<>();
    private Map<String, User>     users     = new HashMap<>();

    // campaignId → list of adIds (for campaign-level queries)
    private Map<String, List<String>> adsByCampaign = new HashMap<>();

    public void addCampaign(Campaign c) {
        campaigns.put(c.id, c);
        adsByCampaign.putIfAbsent(c.id, new ArrayList<>());
        System.out.println("[REGISTRY] Campaign added: " + c.name);
    }

    public void addAd(Ad ad) {
        if (!campaigns.containsKey(ad.campaignId))
            throw new RuntimeException("Campaign " + ad.campaignId + " not found");
        ads.put(ad.id, ad);
        adsByCampaign.get(ad.campaignId).add(ad.id);
        System.out.println("[REGISTRY] Ad added: " + ad.id + " → campaign " + ad.campaignId);
    }

    public void addUser(User user) {
        users.put(user.id, user);
    }

    public Ad       getAd(String adId)           { return ads.get(adId); }
    public User     getUser(String userId)        { return users.get(userId); }
    public Campaign getCampaign(String cId)       { return campaigns.get(cId); }
    public List<String> getAdIds(String campaignId) {
        return adsByCampaign.getOrDefault(campaignId, new ArrayList<>());
    }
}


// ── EventIngestionService — WRITE PATH ────────────────────────────────────────
// Validates input, creates ClickEvent, stores raw events, notifies all consumers.
// This is the hot path — kept as lightweight as possible.

class EventIngestionService {
    private AdRegistry              registry;
    private List<EventConsumer>     consumers;
    private Map<String, List<ClickEvent>> rawEventsByAd; // adId → raw event log
    private int                     eventCounter;

    public EventIngestionService(AdRegistry registry) {
        this.registry       = registry;
        this.consumers      = new ArrayList<>();
        this.rawEventsByAd  = new HashMap<>();
        this.eventCounter   = 0;
    }

    public void subscribe(EventConsumer consumer) {
        consumers.add(consumer);
    }

    // recordEvent — the main ingestion method
    public void recordEvent(String userId, String adId, EventType type) {
        recordEvent(userId, adId, type, System.currentTimeMillis());
    }

    // Overload with explicit timestamp — useful for testing / replaying events
    public void recordEvent(String userId, String adId, EventType type, long timestampMs) {
        // Validate
        if (registry.getAd(adId) == null)
            throw new RuntimeException("Ad " + adId + " not found");
        if (registry.getUser(userId) == null)
            throw new RuntimeException("User " + userId + " not found");

        // Build event
        String     eventId = "EVT-" + (++eventCounter);
        ClickEvent event   = new ClickEvent(eventId, type, userId, adId, timestampMs);

        // Store raw event — needed for time-windowed queries
        rawEventsByAd.computeIfAbsent(adId, k -> new ArrayList<>()).add(event);

        // Notify all consumers (Observer fan-out)
        for (EventConsumer consumer : consumers) {
            consumer.onEvent(event);
        }

        System.out.println("[EVENT] " + type + " | user=" + userId + " | ad=" + adId);
    }

    // Time-windowed query on raw events — O(n) scan, acceptable at interview scale
    public int getClicksByWindow(String adId, long startMs, long endMs) {
        List<ClickEvent> events = rawEventsByAd.getOrDefault(adId, new ArrayList<>());
        int count = 0;
        for (ClickEvent e : events) {
            if (e.type == EventType.CLICK
                    && e.timestampMs >= startMs
                    && e.timestampMs <= endMs) {
                count++;
            }
        }
        return count;
    }
}


// =============================================================================
// STEP 4 — DEMO
// =============================================================================

public class AdClickAggregatorSolution {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Ad Click Aggregator Demo ===\n");

        // ── Setup ─────────────────────────────────────────────────────────────
        AdRegistry registry = new AdRegistry();

        Campaign c1 = new Campaign("C1", "Summer Sale", "AdvertiserA", 10000);
        Campaign c2 = new Campaign("C2", "Winter Promo", "AdvertiserB", 5000);
        registry.addCampaign(c1);
        registry.addCampaign(c2);

        Ad ad1 = new Ad("AD1", "https://example.com/summer", "C1");
        Ad ad2 = new Ad("AD2", "https://example.com/shoes", "C1");
        Ad ad3 = new Ad("AD3", "https://example.com/winter", "C2");
        registry.addAd(ad1);
        registry.addAd(ad2);
        registry.addAd(ad3);

        registry.addUser(new User("U1", "Alice"));
        registry.addUser(new User("U2", "Bob"));
        registry.addUser(new User("U3", "Charlie"));

        // ── Wire up consumers (Observer) ──────────────────────────────────────
        AnalyticsConsumer analytics = new AnalyticsConsumer();
        BillingConsumer   billing   = new BillingConsumer();

        EventIngestionService ingestion = new EventIngestionService(registry);
        ingestion.subscribe(analytics); // analytics team
        ingestion.subscribe(billing);   // billing team

        System.out.println();

        // ── Record events ─────────────────────────────────────────────────────
        System.out.println("── Recording Events ──");

        long t0 = System.currentTimeMillis();

        ingestion.recordEvent("U1", "AD1", EventType.VIEW,  t0);
        ingestion.recordEvent("U1", "AD1", EventType.CLICK, t0 + 100);
        ingestion.recordEvent("U1", "AD1", EventType.CLICK, t0 + 200); // duplicate — U1 clicked AD1 twice
        ingestion.recordEvent("U2", "AD1", EventType.CLICK, t0 + 300); // new user → unique click
        ingestion.recordEvent("U3", "AD1", EventType.CLICK, t0 + 400);
        ingestion.recordEvent("U2", "AD2", EventType.CLICK, t0 + 500);
        ingestion.recordEvent("U3", "AD2", EventType.VIEW,  t0 + 600);
        ingestion.recordEvent("U1", "AD3", EventType.CLICK, t0 + 700);
        ingestion.recordEvent("U2", "AD3", EventType.CONVERSION, t0 + 800);

        System.out.println();

        // ── Query aggregated stats ────────────────────────────────────────────
        System.out.println("── Ad Stats (Analytics) ──");
        System.out.println(analytics.getStats("AD1")); // 3 total clicks, 2 unique (U1 deduplicated)
        System.out.println(analytics.getStats("AD2"));
        System.out.println(analytics.getStats("AD3"));

        System.out.println();

        // ── Top ads by campaign ───────────────────────────────────────────────
        System.out.println("── Top Ads for Campaign C1 ──");
        List<String> topAds = analytics.getTopAds(registry.getAdIds("C1"), 2);
        System.out.println("Top 2 ads: " + topAds);

        System.out.println();

        // ── Time-windowed query ───────────────────────────────────────────────
        System.out.println("── Time-Windowed Query ──");
        // Clicks for AD1 in the first 300ms window
        int windowClicks = ingestion.getClicksByWindow("AD1", t0, t0 + 300);
        System.out.println("AD1 clicks in first 300ms: " + windowClicks); // should be 2 (t+100, t+200)

        System.out.println();

        // ── Observer: add a new consumer without changing ingestion ───────────
        System.out.println("── Adding new consumer (FraudDetection) at runtime ──");
        ingestion.subscribe(event -> {
            // Lambda as anonymous consumer — fraud flag if same user clicks 10+ times
            System.out.println("  [FRAUD-CHECK] Received event: " + event.type +
                    " from user=" + event.userId);
        });

        ingestion.recordEvent("U1", "AD2", EventType.CLICK, t0 + 900);
        System.out.println("  (FraudDetection consumer received the event above ↑)");

        System.out.println();

        // ── Billing summary ───────────────────────────────────────────────────
        System.out.println("── Billing Summary ──");
        System.out.printf("AD1 total billing: $%.2f%n", billing.getTotalCost("AD1"));
        System.out.printf("AD2 total billing: $%.2f%n", billing.getTotalCost("AD2"));
        System.out.printf("AD3 total billing: $%.2f%n", billing.getTotalCost("AD3"));
    }
}
