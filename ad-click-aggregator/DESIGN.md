# LLD: Ad Click Aggregator

> Implementation: `AdClickAggregatorSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Record ad events: CLICK, VIEW, CONVERSION — from a user on an ad |
| 2 | Each ad belongs to exactly one Campaign |
| 3 | **Deduplication** — count only the FIRST click per user per ad (unique clicks) |
| 4 | **Multiple consumers** receive events without changing ingestion logic (Observer) |
| 5 | Query: `getStats(adId)` → total clicks, views, conversions, unique users |
| 6 | Query: `getTopAds(campaignId, n)` → top N ads by click count |
| 7 | Query: `getClicksByWindow(adId, startMs, endMs)` → time-windowed click count |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Low-latency ingestion — no heavy computation on the write path |
| 2 | Consumer list is extensible at runtime without touching ingestion logic |
| 3 | Stats queries are O(1) — pre-aggregated counters, not re-scanned on every read |

### Out of Scope
Persistence, distributed message queues, real-time streaming (Kafka/Flink), authentication, ad fraud detection (mentioned but not implemented beyond a demo hook)

---

## Step 2 — The Key Architectural Insight: Two Paths

Most candidates only build the write path. "Aggregator" means you need both:

```
WRITE PATH:
  recordEvent(userId, adId, type)
    → validate entities (Ad exists? User exists?)
    → build ClickEvent with timestamp
    → store raw event (for time-windowed queries)
    → fan-out to all consumers (Observer)

READ PATH:
  getStats(adId)        → O(1) — reads pre-aggregated AdStats counter
  getTopAds(cId, n)     → O(k log k) — sort known ads for campaign
  getClicksByWindow(...)→ O(n) — scan raw events in time range
```

The `AnalyticsConsumer` maintains **live counters** (`AdStats`) updated on every event so reads are O(1), not O(n).

---

## Step 3 — Entities & Class Design

### Entities

| Class | Role |
|-------|------|
| `Campaign` | A marketing campaign run by an advertiser |
| `Ad` | One advertisement, belongs to one Campaign |
| `User` | Person who interacts with ads |
| `ClickEvent` | Immutable record of one interaction (type + userId + adId + timestamp) |
| `AdStats` | Live aggregated counters per ad — updated by AnalyticsConsumer |

### Services

| Class | Role |
|-------|------|
| `AdRegistry` | Manages known ads, campaigns, users (`Map<id, entity>` for O(1) lookups) |
| `EventIngestionService` | Write path — validates, creates event, stores raw, notifies consumers |
| `AnalyticsConsumer` | Observer — maintains AdStats + unique-user deduplication |
| `BillingConsumer` | Observer — charges advertiser per click |

### Enums

| Enum | Values |
|------|--------|
| `EventType` | CLICK, VIEW, CONVERSION |

---

## Step 4 — Design Patterns

### 1. Observer — `EventConsumer`

Multiple consumers receive every event. Adding a new consumer (fraud detection, reporting) requires zero changes to `EventIngestionService`.

```java
interface EventConsumer {
    void onEvent(ClickEvent event);
}
// Subscribe:
ingestion.subscribe(analytics);
ingestion.subscribe(billing);
ingestion.subscribe(event -> { /* lambda consumer */ });
```

**Fan-out in ingestion:**
```java
for (EventConsumer consumer : consumers) {
    consumer.onEvent(event);
}
```

### 2. Deduplication — `Set<userId>` per ad

Unique click = only the FIRST click from a user on a specific ad counts.

```java
Map<String, Set<String>> uniqueUsersPerAd; // adId → set of userIds who clicked
// In AnalyticsConsumer.onEvent():
if (seenUsers.add(event.userId)) {   // Set.add() returns false if already present
    stats.uniqueUserClicks++;        // only incremented on first click
}
// U1 clicks AD1 twice → totalClicks=2, uniqueUserClicks=1
```

---

## Step 5 — Class Attributes & Methods

### `ClickEvent`

| Field | Type | Purpose |
|-------|------|---------|
| `id` | String | unique event ID |
| `type` | EventType | CLICK / VIEW / CONVERSION |
| `userId` | String | who triggered it |
| `adId` | String | which ad |
| `timestampMs` | long | epoch millis — critical for time-windowed queries |

### `AdStats`

| Field | Type | Purpose |
|-------|------|---------|
| `totalClicks` | int | raw click count |
| `uniqueUserClicks` | int | deduplicated click count |
| `totalViews` | int | raw view count |
| `totalConversions` | int | raw conversion count |

### `EventIngestionService`

| Method | Description |
|--------|-------------|
| `subscribe(consumer)` | Register a new event consumer |
| `recordEvent(userId, adId, type)` | Validate → create event → store → fan-out |
| `recordEvent(userId, adId, type, ts)` | Overload with explicit timestamp (for tests/replay) |
| `getClicksByWindow(adId, start, end)` | O(n) scan of raw events in time range |

### `AnalyticsConsumer`

| Method | Description |
|--------|-------------|
| `onEvent(event)` | Update AdStats, handle deduplication |
| `getStats(adId)` | O(1) return of pre-aggregated AdStats |
| `getTopAds(adIds, n)` | Sort ads by click count, return top N |

---

## Step 6 — Key Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| `Map<id, Entity>` in AdRegistry (not List) | Maps | O(1) lookup by ID — using `List.get(id)` is wrong, it takes index |
| Separate `AnalyticsConsumer` from ingestion | Yes | Single Responsibility — ingestion doesn't know how to aggregate |
| `AdStats` as live counter (not re-scan) | Yes | O(1) reads; recalculating from raw events on every query = O(n) |
| Store raw events alongside counters | Yes | Needed for time-windowed queries which can't be pre-aggregated |
| `Set<userId>` for dedup (not checking raw events) | Yes | O(1) dedup check; scanning all events per click = expensive |
| Timestamp on `ClickEvent` | Required | Without it, time-windowed queries are impossible |
| `Campaign` as first-class entity | Yes | Explicitly mentioned in requirements; Ad → Campaign is a key grouping for queries |

---

## Step 7 — How This Differs From Other Problems

| Aspect | Ad Click Aggregator | Other Problems |
|--------|---------------------|----------------|
| Two distinct paths (write + read) | Explicit write + read path | Single service does both |
| Fan-out to multiple independent consumers | Observer with N consumers | Usually one service or one observer |
| Time-windowed query requiring timestamps | Yes (epochMs on event) | No — BookMyShow, Library have dates but don't window-query |
| Deduplication logic | Set per entity | Not needed elsewhere |
| Pre-aggregated counters + raw event log | Both maintained simultaneously | Usually just one data structure |

---

## Step 8 — What Your Pseudocode Missed (Summary)

| Gap | Fix |
|----|-----|
| `map.put(adId, ev)` replaces the list | `map.computeIfAbsent(adId, k → new ArrayList<>()).add(ev)` |
| `List<Advertisements>` + `get(adId)` | Use `Map<String, Ad>` — List.get() takes index, not ID |
| No timestamp on Event | Add `long timestampMs` — required for windowed queries |
| Campaign in requirements but not coded | `Campaign` class, `Ad.campaignId` field, `adsByCampaign` map |
| No aggregation queries | `getStats()`, `getTopAds()`, `getClicksByWindow()` — the entire read path |
| "Unique per user" mentioned but not implemented | `Map<adId, Set<userId>>` in AnalyticsConsumer |
| "Multiple consumers" mentioned but not implemented | Observer pattern — `EventConsumer` interface, `subscribe()` method |

---

## Step 9 — Extensibility

| Extension | How |
|-----------|-----|
| Fraud detection | Add `FraudConsumer` → `ingestion.subscribe(fraud)` — zero other changes |
| Real-time dashboard | Add `DashboardConsumer` — same Observer subscription |
| Per-campaign stats | Add `Map<campaignId, AdStats>` in AnalyticsConsumer, aggregate on fan-out |
| Hourly bucketing | Store events in `Map<adId, Map<hourBucket, List<ClickEvent>>>` for O(1) windowed reads |
| Dedup strategy | Extract to `DeduplicationStrategy` interface — e.g. `UniquePerSession`, `UniquePerDay` |

---

## Quick Recall — 3 Main Takeaways

1. **Write path vs Read path**: Record events fast (write path), pre-aggregate counters for fast reads (read path). Don't re-scan raw events on every stats query.

2. **Observer for consumers**: `EventConsumer` interface + `subscribe()` — analytics team, billing team, fraud detection all get events without any coupling to ingestion code.

3. **Deduplication = Set**: `Map<adId, Set<userId>>` — `Set.add()` returns false if already present → O(1) unique-click detection. This is what "unique clicks" means in ad tech.
