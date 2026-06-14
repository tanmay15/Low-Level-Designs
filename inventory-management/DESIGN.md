# LLD: Inventory Management System

> Implementation: `InventoryManagementSolution.java`
> Reference: https://www.hellointerview.com/learn/low-level-design/problem-breakdowns/inventory-management

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Track stock for products across multiple warehouses |
| 2 | Add stock to a warehouse (receiving shipments) |
| 3 | Remove stock from a warehouse (fulfilling orders) |
| 4 | Transfer stock between warehouses — atomically |
| 5 | Check availability: which warehouses can fulfil a product+quantity |
| 6 | Low-stock alerts — per product per warehouse threshold |
| 7 | Reject operations that would result in negative inventory |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | **Thread-safe**: multiple operations can happen simultaneously (synchronized) |
| 2 | Alert mechanism is pluggable — `AlertListener` interface (Observer pattern) |

### Out of Scope
Product catalogue management, order processing, payment, persistence, shipping logistics.

---

## Step 2 — Why Product is NOT an Entity

Same reasoning as Amazon Locker and Package:

> "Products exist externally. Orders and payments are handled upstream."

Our system only needs a product ID string to track stock. We don't care about product name, SKU, category, price — that belongs to a product catalogue service. So `productId: String` is just a key in our maps.

```java
// ❌ Over-modelled:
mgr.addStock("WH-A", new Product("iPhone-15", "Apple", 999.99), 100);

// ✅ Correct:
mgr.addStock("WH-A", "iPhone-15", 100);
```

---

## Step 3 — Entities

| Class | Responsibility |
|-------|---------------|
| `Warehouse` | Physical storage location. Owns its own stock map and alert configs. All mutations are synchronized. |
| `AlertConfig` | Data holder: threshold + listener for one product at one warehouse. |
| `AlertListener` | Interface (Observer). Called when stock drops to/below threshold. |
| `ConsoleAlertListener` | Concrete listener — prints to console. |
| `InventoryManager` | Orchestrator. Routes single-warehouse calls. Handles multi-warehouse operations (transfer, availability check). |

---

## Step 4 — Thread-Safety: synchronized on Warehouse

Each `Warehouse` operation is `synchronized` on the warehouse object itself:

```java
public synchronized void addStock(String productId, int quantity) { ... }
public synchronized void removeStock(String productId, int quantity) { ... }
public synchronized int getStock(String productId) { ... }
```

**Key insight:** The lock is per-warehouse, not global.

- WH-A can receive a shipment while WH-B fulfils an order **in parallel**.
- Only two operations on the **same warehouse** are serialized.

This is better than a single global lock on `InventoryManager`, which would serialize all operations across all warehouses unnecessarily.

---

## Step 5 — Transfer: Deadlock Prevention

Transfer must atomically remove from source and add to destination.

**The deadlock scenario:**

```
Thread 1: transfer(WH-A → WH-B)  — acquires lock on WH-A, waits for WH-B
Thread 2: transfer(WH-B → WH-A)  — acquires lock on WH-B, waits for WH-A
→ Circular wait → deadlock
```

**The fix: always lock in the same order (alphabetical by warehouse ID)**

```java
Warehouse first  = fromId.compareTo(toId) < 0 ? from : to;
Warehouse second = fromId.compareTo(toId) < 0 ? to   : from;

synchronized (first) {
    synchronized (second) {
        from.removeStock(productId, quantity);
        to.addStock(productId, quantity);
    }
}
```

Now both Thread 1 and Thread 2 try to acquire WH-A first. One succeeds, the other waits. No circular dependency → no deadlock.

**Java's reentrant synchronized**: holding the outer lock on `first` and then calling `removeStock()` (which is also `synchronized`) on the same object works correctly — Java allows the same thread to re-acquire its own lock.

---

## Step 6 — Per-Product Per-Warehouse Alert Thresholds

The alert threshold is **not global**. It is configured separately for each product at each warehouse:

```java
mgr.setAlertConfig("WH-A", "iPhone-15", 10, listener); // WH-A alerts at ≤10 units
mgr.setAlertConfig("WH-B", "iPhone-15",  5, listener); // WH-B alerts at ≤5 units
```

Same product, different thresholds at different locations. This is modelled as:
```
Warehouse.alertConfigs: Map<productId, AlertConfig>
```

Alert is checked after every `addStock` and `removeStock`:
```java
private void checkAlert(String productId) {
    AlertConfig config = alertConfigs.get(productId);
    if (config == null) return;
    int current = stock.getOrDefault(productId, 0);
    if (current <= config.threshold) {
        config.listener.onLowStock(id, productId, current, config.threshold);
    }
}
```

---

## Step 7 — Class Attributes & Methods

### `Warehouse`

| Member | Type | Description |
|--------|------|-------------|
| `id`, `location` | String | identifiers |
| `stock` (private) | Map\<productId, Integer\> | current quantities |
| `alertConfigs` (private) | Map\<productId, AlertConfig\> | per-product thresholds |
| `addStock(productId, qty)` | synchronized void | add qty, check alert |
| `removeStock(productId, qty)` | synchronized void | remove qty (reject if insufficient), check alert |
| `getStock(productId)` | synchronized int | read-safe stock query |
| `canFulfil(productId, qty)` | synchronized boolean | stock ≥ qty |
| `setAlertConfig(productId, threshold, listener)` | void | configure alert |

### `InventoryManager`

| Method | Description |
|--------|-------------|
| `addStock(warehouseId, productId, qty)` | delegate to warehouse |
| `removeStock(warehouseId, productId, qty)` | delegate to warehouse |
| `getStock(warehouseId, productId)` | delegate to warehouse |
| `transfer(fromId, toId, productId, qty)` | atomic, deadlock-safe multi-warehouse lock |
| `checkAvailability(productId, qty)` | returns list of warehouse IDs that can fulfil |
| `setAlertConfig(warehouseId, productId, threshold, listener)` | configure per-warehouse alert |

---

## Step 8 — Design Patterns

### Observer — `AlertListener`

The alert system is pluggable. The caller provides an `AlertListener` implementation when configuring the threshold. `Warehouse` doesn't know or care what happens after `onLowStock()` is called.

```java
interface AlertListener {
    void onLowStock(String warehouseId, String productId, int currentStock, int threshold);
}
// Swap implementations without touching Warehouse:
//   new ConsoleAlertListener()  → print to console
//   new EmailAlertListener()    → send email
//   new WebhookAlertListener()  → call HTTP endpoint
```

---

## Step 9 — How This Differs From Other Problems

| Aspect | Inventory Management | Other Problems |
|--------|---------------------|----------------|
| Thread-safety required | ✅ explicit (synchronized) | ❌ not required |
| Deadlock risk in multi-resource operation | ✅ (transfer) | ❌ |
| Per-entity per-product threshold | ✅ | ❌ |
| No transaction entity | ✅ (pure in/out) | ❌ (ParkingTicket, BorrowRecord) |
| No state machine | ✅ (just a counter) | ❌ most problems have states |
| Product NOT an entity | ✅ (just a String key) | ❌ most problems model the core item |
| Multiple warehouses coordinated by manager | ✅ | Similar to ElevatorController + Elevators |

---

## Step 10 — Extensibility

| Extension | How |
|-----------|-----|
| Email/webhook alert | New `EmailAlertListener` implements `AlertListener` — zero other changes |
| Prevent overselling | Add `reserveStock(productId, qty)` → temporary hold before confirming removal |
| Stock in transit | Add `InTransitRecord` entity tracking transfers in flight |
| Product expiry | Add `expiryDate` on stock batches; `removeExpiredStock()` sweep |
| Indexed availability | `Map<productId, Map<warehouseId, Integer>>` for O(1) cross-warehouse queries instead of O(warehouses) scan |

---

## Quick Recall — 3 Main Takeaways

1. **Thread-safety = synchronized per warehouse, not global.** `synchronized` on `this` inside each Warehouse means parallel operations on different warehouses are allowed. Only same-warehouse operations are serialized.

2. **Transfer deadlock fix = alphabetical lock ordering.** Always lock the warehouse with the lexicographically smaller ID first. Both conflicting threads try for the same lock first → no circular wait → no deadlock.

3. **Alert is per product per warehouse.** `Map<productId, AlertConfig>` inside each Warehouse. Threshold for iPhone at WH-A is independent of threshold for iPhone at WH-B.
