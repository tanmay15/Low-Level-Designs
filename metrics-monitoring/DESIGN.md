# LLD: Metrics Monitoring Platform (Datadog / Prometheus style)

> Reference: [Hello Interview — Metrics Monitoring](https://www.hellointerview.com/learn/system-design/problem-breakdowns/metrics-monitoring)

## Step 1 — Requirements

### Functional
1. Ingest metric data points (name, value, timestamp, labels) from services
2. Query metrics with label filters and time range
3. Aggregate query results: AVG, MAX, MIN, SUM, COUNT, P99
4. Define alert rules: metric + aggregation + time window + threshold + comparator
5. Evaluate alerts on every ingested point — fire notification on threshold breach
6. Auto-resolve alert when metric recovers below threshold

### Non-Functional
- Metrics stored as time series: `Map<metricKey, List<MetricPoint>>`
- Alert state machine prevents duplicate notifications (OK → FIRING → OK)
- `NotificationChannel` is pluggable (Observer pattern)
- `AggregationType` is pluggable (Strategy pattern)

### Out of Scope (HLD concerns)
- Time-series database (Prometheus TSDB, InfluxDB, Cassandra)
- Metric collection agents / scraping
- Real-time streaming pipeline (Kafka, Flink)
- PromQL / custom query language
- Dashboard UI rendering
- Cardinality explosion, downsampling, data retention policies
- Multi-region replication

---

## Step 2 — Entities

| Entity              | Role                                                                 |
|---------------------|----------------------------------------------------------------------|
| `MetricPoint`       | One data point: name, value, timestamp, labels                       |
| `RegisteredService` | Metadata of a monitored service/host                                 |
| `AlertRule`         | Condition definition: metric + aggregation + window + threshold; owns its state |
| `Alert`             | Immutable event record created when rule transitions OK → FIRING     |

---

## Step 3 — Class Design

### Metric Key Format — Prometheus style
```
"cpu.usage{host=server1,env=prod}"
```
- Labels are sorted alphabetically → consistent key for the same label set
- Every unique `metricName + label combination` = its own time series
- This collapses two fields (metricName + labels Map) into one lookup key

### `MetricStore` — Core Data Structure
```
Map<String, List<MetricPoint>> series
  key:   "cpu.usage{host=server1,env=prod}"
  value: [MetricPoint@t1, MetricPoint@t2, ...]  ← append-only, oldest first
```

**Key operations:**
| Method                               | Notes                                           |
|--------------------------------------|-------------------------------------------------|
| `buildKey(name, labels)`             | Sorts labels → deterministic key               |
| `add(MetricPoint)`                   | Appends to the right time series               |
| `getInWindow(key, from, to)`         | Filters by timestamp range                     |
| `findKeys(name, labelFilter)`        | Returns all keys matching metric + label subset|

### `AlertRule` — State Machine
```
        threshold breached
OK  ──────────────────────→  FIRING
        ↑                        │
        │  threshold recovered   │
        └────────────────────────┘
```
- Notification fires **only on OK → FIRING transition**
- While FIRING and still breached → no duplicate notification
- Recovers automatically when next evaluation finds threshold not breached

### `AlertRule` Attributes
| Attribute       | Type              | Notes                                        |
|-----------------|-------------------|----------------------------------------------|
| `metricName`    | String            | Which metric to watch                         |
| `labelFilter`   | `Map<K,V>`        | null = match all labels for this metric       |
| `aggregation`   | `AggregationType` | How to collapse window points to one number  |
| `windowMillis`  | long              | Evaluation window (e.g. 5 min = 300_000ms)   |
| `comparator`    | `Comparator`      | GT / GTE / LT / LTE / EQ                    |
| `threshold`     | double            | The breach value                             |
| `state`         | `AlertState`      | OK or FIRING — state machine                 |
| `channels`      | `List<NotificationChannel>` | Where to send on breach           |

### `AlertService.evaluate()` — Execution Flow
```
1. Find all metricKeys matching rule.metricName + rule.labelFilter
2. Get all MetricPoints in window [now - windowMillis, now]
3. Aggregate points → single double (AVG, MAX, P99...)
4. Compare against threshold using comparator
5a. If breached AND state==OK  → state=FIRING, create Alert, fan-out to channels
5b. If not breached AND state==FIRING → state=OK (resolved, no notification)
5c. Otherwise → no change
```

### Design Patterns

| Pattern         | Where                           | Why                                               |
|-----------------|---------------------------------|---------------------------------------------------|
| **Observer**    | `NotificationChannel`           | Email/Slack/PagerDuty all react to same Alert     |
| **Strategy**    | `AggregationType` + `Aggregator`| Different aggregation per rule without code change|
| **State Machine**| `AlertRule.state` (OK/FIRING)  | Prevents duplicate alert spam                     |

### Services

| Service                   | Responsibility                                          |
|---------------------------|---------------------------------------------------------|
| `MetricIngestionService`  | Entry point — store point + trigger alert evaluation    |
| `MetricQueryService`      | Dashboard queries — filter + aggregate over time range  |
| `AlertService`            | Rule management + state machine + fan-out               |
| `MetricStore`             | Core data structure — time series storage + key lookup  |

---

## Step 4 — How It Differs from Other Problems

| Feature           | Metrics Monitoring                   | Ad Click Aggregator                   |
|-------------------|--------------------------------------|---------------------------------------|
| Core data          | Time-series (timestamp is key)      | Event log per ad                      |
| Consumer pattern   | AlertService evaluates on write     | EventConsumer fan-out on write        |
| Aggregation        | Windowed (last N ms)                | Cumulative counters                   |
| State machine      | AlertRule (OK/FIRING) per rule      | Not needed                            |
| Query              | Time range + label filter           | By campaign / time bucket             |

---

## Step 5 — Extensibility
- **Downsampling**: Roll up old minute-level data into hourly/daily averages to save storage
- **Anomaly detection**: Replace static threshold in `AlertRule` with ML-based baseline comparator
- **Dashboard panels**: Add `Dashboard` entity with `List<Panel>`, each panel = a query config
- **Multi-condition alerts**: AND/OR multiple `AlertRule` conditions before firing
- **Metric agent**: `RegisteredService` has a collection interval; agent pushes on schedule

---

## Quick Recall
1. Metric key = `"metricName{sorted_labels}"` — one time series per unique label combination
2. `MetricStore` = `Map<key, List<MetricPoint>>` — append-only, filtered by time range for queries
3. Alert state machine: **notification fires only on OK→FIRING** — not on every incoming point
4. Observer (NotificationChannel) + Strategy (AggregationType) are both used here
5. P99 = sort values, take index at `ceil(0.99 × count) - 1`
