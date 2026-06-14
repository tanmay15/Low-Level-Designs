// =============================================================================
// LLD: INVENTORY MANAGEMENT SYSTEM
// Design doc: DESIGN.md
// Reference: hellointerview.com/learn/low-level-design/problem-breakdowns/inventory-management
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Track stock for products across multiple warehouses
//   2. Add stock to a warehouse (receiving shipments)
//   3. Remove stock from a warehouse (fulfilling orders)
//   4. Transfer stock between warehouses — atomically
//   5. Check availability: which warehouses can fulfil a given product+quantity
//   6. Low-stock alerts — per product per warehouse threshold
//   7. Reject operations that would result in negative inventory
//
// Non-Functional:
//   - Thread-safe: multiple operations can happen simultaneously across warehouses
//   - Alert mechanism is pluggable (AlertListener interface — Observer pattern)
//
// Out of scope:''
//   - Product catalogue management (products exist externally — tracked by String ID)
//   - Order processing, payment, shipping
//   - Persistence
//
// UNIQUE NUANCES vs other problems:
//   1. Thread-safety — ONLY problem in this set with explicit concurrency requirement
//      → addStock/removeStock are synchronized on the Warehouse object
//   2. Transfer atomicity — multi-resource lock. Deadlock prevented by always
//      acquiring locks in consistent alphabetical order by warehouse ID
//   3. Alert threshold is PER PRODUCT PER WAREHOUSE, not global
//   4. No transaction entity (no Ticket, no BorrowRecord) — pure in/out
//   5. No Product entity — locker only tracks productId (String). Product
//      metadata lives in a separate system (same reasoning as Amazon Locker)
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — OBSERVER PATTERN: AlertListener
// =============================================================================
// Pluggable callback triggered when stock drops to or below a threshold.
// "What happens after that — email, webhook, logging — is someone else's problem."
// The system just calls onLowStock(). The listener decides what to do with it.

interface AlertListener {
    void onLowStock(String warehouseId, String productId, int currentStock, int threshold);
}

// Concrete implementation: logs to console
class ConsoleAlertListener implements AlertListener {
    @Override
    public void onLowStock(String warehouseId, String productId, int currentStock, int threshold) {
        System.out.println("  ⚠ [LOW STOCK ALERT] Warehouse=" + warehouseId +
                " | Product=" + productId +
                " | Stock=" + currentStock +
                " | Threshold=" + threshold);
    }
}


// =============================================================================
// STEP 3 — AlertConfig
// =============================================================================
// Simple data holder: stores threshold + listener for one product at one warehouse.
// Lives inside Warehouse, keyed by productId.

class AlertConfig {
    public int           threshold;
    public AlertListener listener;

    public AlertConfig(int threshold, AlertListener listener) {
        this.threshold = threshold;
        this.listener  = listener;
    }
}


// =============================================================================
// STEP 4 — Warehouse
// =============================================================================
// Physical storage location. Owns its own inventory and alert configurations.
//
// THREAD-SAFETY:
//   All mutating methods (addStock, removeStock) are synchronized on `this`.
//   This means only one operation can modify a warehouse's stock at a time.
//   Multiple warehouses can operate in parallel — no global lock.
//
// KEY DATA STRUCTURE:
//   Map<productId, Integer> stock  → direct O(1) lookup per product
//   Map<productId, AlertConfig>    → per-product alert settings

class Warehouse {
    public String id;
    public String location;

    private Map<String, Integer>     stock;         // productId → quantity
    private Map<String, AlertConfig> alertConfigs;  // productId → alert config

    public Warehouse(String id, String location) {
        this.id           = id;
        this.location     = location;
        this.stock        = new HashMap<>();
        this.alertConfigs = new HashMap<>();
    }

    // ── Add stock ─────────────────────────────────────────────────────────────
    // synchronized: only one thread can add stock at a time per warehouse

    public synchronized void addStock(String productId, int quantity) {
        if (quantity <= 0) throw new RuntimeException("Quantity must be positive");
        stock.merge(productId, quantity, Integer::sum);
        System.out.println("  [" + id + "] +" + quantity + " " + productId +
                " → stock now: " + stock.get(productId));
        checkAlert(productId);
    }

    // ── Remove stock ──────────────────────────────────────────────────────────
    // Rejects if removal would take stock below zero — invariant enforced here.

    public synchronized void removeStock(String productId, int quantity) {
        if (quantity <= 0) throw new RuntimeException("Quantity must be positive");
        int current = stock.getOrDefault(productId, 0);
        if (current < quantity)
            throw new RuntimeException("[" + id + "] Insufficient stock for " + productId
                    + " | Need: " + quantity + " | Have: " + current);
        stock.put(productId, current - quantity);
        System.out.println("  [" + id + "] -" + quantity + " " + productId +
                " → stock now: " + stock.get(productId));
        checkAlert(productId);
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    public synchronized int getStock(String productId) {
        return stock.getOrDefault(productId, 0);
    }

    public synchronized boolean canFulfil(String productId, int quantity) {
        return stock.getOrDefault(productId, 0) >= quantity;
    }

    // ── Alert config ──────────────────────────────────────────────────────────
    // Per product per warehouse threshold. Setting a config for product P at
    // warehouse WH-A doesn't affect threshold for P at WH-B.

    public void setAlertConfig(String productId, int threshold, AlertListener listener) {
        alertConfigs.put(productId, new AlertConfig(threshold, listener));
        System.out.println("  [ALERT CONFIG] " + id + " | " + productId
                + " threshold=" + threshold);
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public synchronized void printStatus() {
        System.out.println("  Warehouse " + id + " (" + location + "):");
        if (stock.isEmpty()) {
            System.out.println("    (no stock)");
            return;
        }
        for (Map.Entry<String, Integer> e : stock.entrySet()) {
            AlertConfig cfg = alertConfigs.get(e.getKey());
            String alertInfo = cfg != null ? " [alert≤" + cfg.threshold + "]" : "";
            System.out.printf("    %-20s : %4d units%s%n", e.getKey(), e.getValue(), alertInfo);
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────
    // checkAlert is always called from within a synchronized context.

    private void checkAlert(String productId) {
        AlertConfig config = alertConfigs.get(productId);
        if (config == null) return;
        int current = stock.getOrDefault(productId, 0);
        if (current <= config.threshold) {
            config.listener.onLowStock(id, productId, current, config.threshold);
        }
    }
}


// =============================================================================
// STEP 5 — InventoryManager (Orchestrator)
// =============================================================================
// Entry point for all inventory operations. Manages all warehouses.
// Delegates single-warehouse operations to Warehouse.
// Handles multi-warehouse operations (transfer, availability check) itself.
//
// TRANSFER DEADLOCK PREVENTION:
//   If two transfers run concurrently:
//     T1: WH-A → WH-B  (wants lock A, then lock B)
//     T2: WH-B → WH-A  (wants lock B, then lock A)
//   → Circular wait → deadlock
//
//   Fix: always acquire locks in the same order (alphabetical by warehouse ID).
//   Both T1 and T2 lock WH-A first, then WH-B → no circular wait → no deadlock.

class InventoryManager {
    private Map<String, Warehouse> warehouses;

    public InventoryManager() {
        this.warehouses = new HashMap<>();
    }

    public void addWarehouse(Warehouse warehouse) {
        warehouses.put(warehouse.id, warehouse);
        System.out.println("[SETUP] Warehouse: " + warehouse.id + " at " + warehouse.location);
    }

    // ── Single-warehouse operations ───────────────────────────────────────────

    public void addStock(String warehouseId, String productId, int quantity) {
        get(warehouseId).addStock(productId, quantity);
    }

    public void removeStock(String warehouseId, String productId, int quantity) {
        get(warehouseId).removeStock(productId, quantity);
    }

    public int getStock(String warehouseId, String productId) {
        return get(warehouseId).getStock(productId);
    }

    public void setAlertConfig(String warehouseId, String productId,
                               int threshold, AlertListener listener) {
        get(warehouseId).setAlertConfig(productId, threshold, listener);
    }

    // ── Transfer: atomic, deadlock-safe ──────────────────────────────────────
    // Both warehouses are locked simultaneously in consistent order.
    // Java's synchronized is reentrant — holding the outer lock, then calling
    // a synchronized method on the same object works correctly.

    public void transfer(String fromId, String toId, String productId, int quantity) {
        if (fromId.equals(toId))
            throw new RuntimeException("Source and destination cannot be the same warehouse");

        Warehouse from = get(fromId);
        Warehouse to   = get(toId);

        // Consistent lock ordering: alphabetically smaller ID is locked first
        Warehouse first  = fromId.compareTo(toId) < 0 ? from : to;
        Warehouse second = fromId.compareTo(toId) < 0 ? to   : from;

        synchronized (first) {
            synchronized (second) {
                // Validate before touching either warehouse
                if (from.getStock(productId) < quantity)
                    throw new RuntimeException("Insufficient stock to transfer: "
                            + from.getStock(productId) + " < " + quantity);

                from.removeStock(productId, quantity);
                to.addStock(productId, quantity);
            }
        }
        System.out.println("  [TRANSFER] " + quantity + "x " + productId
                + " | " + fromId + " → " + toId);
    }

    // ── Availability check ────────────────────────────────────────────────────
    // Returns list of warehouse IDs that have enough stock to fulfil the request.
    // Useful for routing fulfillment decisions to the right warehouse.

    public List<String> checkAvailability(String productId, int quantity) {
        List<String> available = new ArrayList<>();
        for (Warehouse w : warehouses.values()) {
            if (w.canFulfil(productId, quantity)) available.add(w.id);
        }
        System.out.println("  [AVAILABILITY] " + productId + " qty=" + quantity
                + " → can fulfil: " + available);
        return available;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public void printStatus() {
        System.out.println("── Inventory Status ──");
        for (Warehouse w : warehouses.values()) w.printStatus();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Warehouse get(String warehouseId) {
        Warehouse w = warehouses.get(warehouseId);
        if (w == null) throw new RuntimeException("Warehouse not found: " + warehouseId);
        return w;
    }
}


// =============================================================================
// STEP 6 — DEMO
// =============================================================================

public class InventoryManagementSolution {
    public static void main(String[] args) {
        System.out.println("=== Inventory Management Demo ===\n");

        // ── Setup ─────────────────────────────────────────────────────────────
        InventoryManager mgr = new InventoryManager();

        Warehouse whA = new Warehouse("WH-A", "Mumbai");
        Warehouse whB = new Warehouse("WH-B", "Delhi");
        Warehouse whC = new Warehouse("WH-C", "Bangalore");

        mgr.addWarehouse(whA);
        mgr.addWarehouse(whB);
        mgr.addWarehouse(whC);

        System.out.println();

        // ── Configure low-stock alerts ────────────────────────────────────────
        System.out.println("── Configuring Alerts ──");
        AlertListener alertListener = new ConsoleAlertListener();

        // WH-A alerts for iPhone when stock ≤ 10
        mgr.setAlertConfig("WH-A", "iPhone-15", 10, alertListener);
        // WH-B alerts for iPhone when stock ≤ 5 (different threshold, same product)
        mgr.setAlertConfig("WH-B", "iPhone-15", 5, alertListener);
        // WH-A alerts for MacBook when stock ≤ 3
        mgr.setAlertConfig("WH-A", "MacBook-Pro", 3, alertListener);

        System.out.println();

        // ── Scenario 1: Add stock (receiving shipments) ───────────────────────
        System.out.println("════ Scenario 1: Receiving shipments ════");
        mgr.addStock("WH-A", "iPhone-15",  100);
        mgr.addStock("WH-A", "MacBook-Pro", 20);
        mgr.addStock("WH-B", "iPhone-15",   50);
        mgr.addStock("WH-B", "MacBook-Pro", 10);
        mgr.addStock("WH-C", "iPhone-15",   30);

        System.out.println();
        mgr.printStatus();
        System.out.println();

        // ── Scenario 2: Remove stock (fulfilling orders) ──────────────────────
        System.out.println("════ Scenario 2: Fulfilling orders ════");
        mgr.removeStock("WH-A", "iPhone-15",  85); // drops to 15 — above alert threshold of 10
        mgr.removeStock("WH-A", "iPhone-15",   6); // drops to 9  — TRIGGERS ALERT (≤10)
        mgr.removeStock("WH-B", "iPhone-15",  44); // drops to 6  — above alert threshold of 5
        mgr.removeStock("WH-B", "iPhone-15",   2); // drops to 4  — TRIGGERS ALERT (≤5)

        System.out.println();

        // ── Scenario 3: Reject insufficient stock ─────────────────────────────
        System.out.println("════ Scenario 3: Insufficient stock rejection ════");
        try {
            mgr.removeStock("WH-C", "iPhone-15", 50); // only 30 in WH-C
        } catch (RuntimeException e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }
        System.out.println();

        // ── Scenario 4: Availability check ────────────────────────────────────
        System.out.println("════ Scenario 4: Availability check ════");
        mgr.checkAvailability("iPhone-15", 8);   // WH-A(9)=no, WH-B(4)=no, WH-C(30)=yes
        mgr.checkAvailability("iPhone-15", 5);   // WH-A(9)=yes, WH-B(4)=no, WH-C(30)=yes
        mgr.checkAvailability("MacBook-Pro", 25); // none — not enough anywhere

        System.out.println();

        // ── Scenario 5: Transfer (atomic, deadlock-safe) ──────────────────────
        System.out.println("════ Scenario 5: Transfer stock between warehouses ════");
        System.out.println("Before transfer:");
        System.out.printf("  WH-A MacBook-Pro: %d | WH-C MacBook-Pro: %d%n",
                mgr.getStock("WH-A", "MacBook-Pro"),
                mgr.getStock("WH-C", "MacBook-Pro"));

        mgr.transfer("WH-A", "WH-C", "MacBook-Pro", 5);

        System.out.println("After transfer:");
        System.out.printf("  WH-A MacBook-Pro: %d | WH-C MacBook-Pro: %d%n",
                mgr.getStock("WH-A", "MacBook-Pro"),
                mgr.getStock("WH-C", "MacBook-Pro"));

        System.out.println();

        // ── Scenario 6: Transfer fails — insufficient stock ───────────────────
        System.out.println("════ Scenario 6: Transfer rejected — insufficient stock ════");
        try {
            mgr.transfer("WH-B", "WH-C", "iPhone-15", 100); // WH-B only has 4
        } catch (RuntimeException e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }

        System.out.println();

        // ── Final status ──────────────────────────────────────────────────────
        System.out.println("════ Final Inventory Status ════");
        mgr.printStatus();

        System.out.println();

        // ── Thread-safety note ────────────────────────────────────────────────
        System.out.println("── Thread-Safety Note ──");
        System.out.println("  addStock/removeStock are synchronized on the Warehouse object.");
        System.out.println("  Transfer locks both warehouses in alphabetical ID order");
        System.out.println("  to prevent deadlock when concurrent transfers happen.");
        System.out.println("  Multiple warehouses can operate in parallel (no global lock).");
    }
}
