// =============================================================================
// LLD: METRICS MONITORING PLATFORM (Datadog / Prometheus style)
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Ingest metric data points (name, value, timestamp) from services
//   2. Define alert rules: metric + aggregation + time window + threshold
//   3. Evaluate alerts on every ingestion → fire notification on breach
//   4. Alert auto-resolves when metric recovers
//   5. Query metrics over a time range with aggregation (AVG, MAX, MIN)
//
// Out of scope: time-series DB, Prometheus agents, Kafka pipeline,
//   PromQL, dashboard UI, cardinality, downsampling
//
// KEY DESIGN DECISIONS:
// 1. Storage: Map<metricName, List<MetricPoint>> — one list per metric name
// 2. Alert state machine (OK → FIRING → OK) — prevents duplicate spam
//    Fire notification ONLY on OK→FIRING transition, not every data point
// 3. Observer: NotificationChannel — Email/Slack receive alert without changing AlertService
// 4. Strategy: AggregationType — AVG/MAX/MIN per rule, swappable
// =============================================================================

import java.util.*;
import java.util.stream.Collectors;


// =============================================================================
// ENUMS
// =============================================================================

enum Comparator      { GT, GTE, LT, LTE }
enum AggregationType { AVG, MAX, MIN, COUNT }
enum AlertState      { OK, FIRING }


// =============================================================================
// METRIC POINT — one data point emitted by a service
// =============================================================================

class MetricPoint {
    public String metricName;
    public double value;
    public long   timestamp;  // epoch millis

    public MetricPoint(String metricName, double value, long timestamp) {
        this.metricName = metricName;
        this.value      = value;
        this.timestamp  = timestamp;
    }

    @Override
    public String toString() {
        return metricName + "=" + value + " @" + timestamp;
    }
}


// =============================================================================
// ALERT RULE — defines a monitoring condition
// =============================================================================

class AlertRule {
    public String              id;
    public String              name;
    public String              metricName;
    public AggregationType     aggregation;
    public long                windowMillis;     // e.g. 60_000 = 1 minute
    public Comparator          comparator;
    public double              threshold;
    public AlertState          state;             // state machine: OK or FIRING
    public List<NotificationChannel> channels;

    public AlertRule(String id, String name, String metricName,
                     AggregationType aggregation, long windowMillis,
                     Comparator comparator, double threshold,
                     List<NotificationChannel> channels) {
        this.id           = id;
        this.name         = name;
        this.metricName   = metricName;
        this.aggregation  = aggregation;
        this.windowMillis = windowMillis;
        this.comparator   = comparator;
        this.threshold    = threshold;
        this.channels     = channels;
        this.state        = AlertState.OK;
    }
}


// =============================================================================
// ALERT — immutable event fired when a rule transitions OK → FIRING
// =============================================================================

class Alert {
    public String ruleId;
    public String metricName;
    public double triggeredValue;
    public long   firedAt;

    public Alert(String ruleId, String metricName, double triggeredValue, long firedAt) {
        this.ruleId         = ruleId;
        this.metricName     = metricName;
        this.triggeredValue = triggeredValue;
        this.firedAt        = firedAt;
    }

    @Override
    public String toString() {
        return String.format("Alert[rule=%s | metric=%s | value=%.1f]",
                ruleId, metricName, triggeredValue);
    }
}


// =============================================================================
// OBSERVER PATTERN — NotificationChannel
// =============================================================================
// AlertService fans out to all channels when a rule fires.

interface NotificationChannel {
    void send(Alert alert, AlertRule rule);
}

class EmailChannel implements NotificationChannel {
    private String email;
    public EmailChannel(String email) { this.email = email; }

    @Override
    public void send(Alert alert, AlertRule rule) {
        System.out.printf("  [EMAIL → %s] ALERT: \"%s\" | value=%.1f > threshold %.1f%n",
                email, rule.name, alert.triggeredValue, rule.threshold);
    }
}

class SlackChannel implements NotificationChannel {
    private String channel;
    public SlackChannel(String channel) { this.channel = channel; }

    @Override
    public void send(Alert alert, AlertRule rule) {
        System.out.printf("  [SLACK → %s] :fire: \"%s\" breached! value=%.1f%n",
                channel, rule.name, alert.triggeredValue);
    }
}


// =============================================================================
// METRICS SERVICE
// =============================================================================
// Single service that handles ingestion, storage, alert evaluation, and querying.

class MetricsService {
    // metricName → time-series list of data points
    private Map<String, List<MetricPoint>> storage  = new HashMap<>();
    private List<AlertRule>                rules    = new ArrayList<>();
    private List<Alert>                    alerts   = new ArrayList<>();

    // ── Ingestion ─────────────────────────────────────────────────────────────

    public void ingest(String metricName, double value) {
        ingest(new MetricPoint(metricName, value, System.currentTimeMillis()));
    }

    public void ingest(MetricPoint point) {
        storage.computeIfAbsent(point.metricName, k -> new ArrayList<>()).add(point);
        evaluateAlerts(point.metricName, point.timestamp);
    }

    // ── Alert rule management ─────────────────────────────────────────────────

    public void addRule(AlertRule rule) {
        rules.add(rule);
        System.out.println("[MONITOR] Rule added: " + rule.name);
    }

    // ── Alert evaluation ──────────────────────────────────────────────────────
    // Called after every ingestion. Checks all rules for the updated metric.

    private void evaluateAlerts(String metricName, long now) {
        for (AlertRule rule : rules) {
            if (!rule.metricName.equals(metricName)) continue;

            // Get all points within the rule's time window
            List<MetricPoint> series   = storage.get(metricName);
            long              from     = now - rule.windowMillis;
            List<MetricPoint> window   = getInWindow(series, from, now);

            if (window.isEmpty()) continue;

            double aggValue = aggregate(window, rule.aggregation);
            boolean breached = compare(aggValue, rule.comparator, rule.threshold);

            if (breached && rule.state == AlertState.OK) {
                // ── OK → FIRING ───────────────────────────────────────────
                rule.state = AlertState.FIRING;
                Alert alert = new Alert(rule.id, metricName, aggValue, now);
                alerts.add(alert);
                System.out.println("[MONITOR] FIRING: " + alert);
                for (NotificationChannel ch : rule.channels) ch.send(alert, rule);

            } else if (!breached && rule.state == AlertState.FIRING) {
                // ── FIRING → OK (resolved) ────────────────────────────────
                rule.state = AlertState.OK;
                System.out.printf("[MONITOR] RESOLVED: \"%s\" value=%.1f%n", rule.name, aggValue);
            }
            // Already FIRING + still breached → no duplicate notification
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public double query(String metricName, long from, long to, AggregationType type) {
        List<MetricPoint> series = storage.getOrDefault(metricName, new ArrayList<>());
        List<MetricPoint> window = getInWindow(series, from, to);
        double result = aggregate(window, type);
        System.out.printf("[QUERY] %s(%s) [last %.0fs] = %.2f%n",
                type, metricName, (to - from) / 1000.0, result);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<MetricPoint> getInWindow(List<MetricPoint> series, long from, long to) {
        return series.stream()
                .filter(p -> p.timestamp >= from && p.timestamp <= to)
                .collect(Collectors.toList());
    }

    // STRATEGY: different aggregation types — swappable per rule
    private double aggregate(List<MetricPoint> points, AggregationType type) {
        if (points.isEmpty()) return 0;
        switch (type) {
            case AVG:   return points.stream().mapToDouble(p -> p.value).average().orElse(0);
            case MAX:   return points.stream().mapToDouble(p -> p.value).max().orElse(0);
            case MIN:   return points.stream().mapToDouble(p -> p.value).min().orElse(0);
            case COUNT: return points.size();
            default:    return 0;
        }
    }

    private boolean compare(double value, Comparator comp, double threshold) {
        switch (comp) {
            case GT:  return value >  threshold;
            case GTE: return value >= threshold;
            case LT:  return value <  threshold;
            case LTE: return value <= threshold;
            default:  return false;
        }
    }

    public List<Alert> getAlerts() { return alerts; }
}


// =============================================================================
// DEMO
// =============================================================================

public class MetricsMonitoringSolution {
    public static void main(String[] args) {
        System.out.println("=== Metrics Monitoring Demo ===\n");

        MetricsService monitor = new MetricsService();

        // ── Define alert rules ────────────────────────────────────────────────
        System.out.println("── Alert Rules ──");
        monitor.addRule(new AlertRule(
                "R1", "High CPU",
                "cpu.usage",
                AggregationType.AVG,
                5000L,              // 5 second window
                Comparator.GT,
                85.0,
                Arrays.asList(new EmailChannel("oncall@company.com"), new SlackChannel("#alerts"))
        ));

        monitor.addRule(new AlertRule(
                "R2", "High API Latency",
                "api.latency",
                AggregationType.MAX,
                5000L,
                Comparator.GT,
                500.0,
                Arrays.asList(new SlackChannel("#incidents"))
        ));
        System.out.println();

        // ── Scenario 1: Normal metrics — no alert ─────────────────────────────
        System.out.println("── Scenario 1: Normal CPU (no alert expected) ──");
        monitor.ingest("cpu.usage", 70.0);
        monitor.ingest("cpu.usage", 72.0);
        monitor.ingest("cpu.usage", 68.0);
        System.out.println();

        // ── Scenario 2: CPU spikes → alert fires ──────────────────────────────
        System.out.println("── Scenario 2: CPU Spike → Alert Fires ──");
        monitor.ingest("cpu.usage", 90.0);
        monitor.ingest("cpu.usage", 92.0);
        System.out.println();

        // ── Scenario 3: Still high → no duplicate alert ───────────────────────
        System.out.println("── Scenario 3: Still High → No Duplicate Notification ──");
        monitor.ingest("cpu.usage", 95.0);
        System.out.println();

        // ── Scenario 4: Latency breach ────────────────────────────────────────
        System.out.println("── Scenario 4: API Latency Breach ──");
        monitor.ingest("api.latency", 200.0);
        monitor.ingest("api.latency", 350.0);
        monitor.ingest("api.latency", 550.0);   // MAX in window = 550 > 500 → fires
        System.out.println();

        // ── Scenario 5: Dashboard query ───────────────────────────────────────
        System.out.println("── Scenario 5: Dashboard Queries ──");
        long now = System.currentTimeMillis();
        monitor.query("cpu.usage",   now - 30000, now, AggregationType.AVG);
        monitor.query("cpu.usage",   now - 30000, now, AggregationType.MAX);
        monitor.query("api.latency", now - 30000, now, AggregationType.MAX);
        System.out.println();

        // ── Summary ───────────────────────────────────────────────────────────
        System.out.println("── Alerts Fired: " + monitor.getAlerts().size() + " ──");
        monitor.getAlerts().forEach(a -> System.out.println("  " + a));
    }
}
