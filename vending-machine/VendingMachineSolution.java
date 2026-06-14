// =============================================================================
// LLD: VENDING MACHINE — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Customer inserts coins (PENNY=1¢, NICKEL=5¢, DIME=10¢, QUARTER=25¢)
//   2. Customer selects an item by slot code (A1, A2, B1, …)
//   3. Machine dispenses item and returns exact change if overpaid
//   4. Customer can refund inserted coins at any time before selection
//   5. Machine rejects invalid operations based on current state
//   6. Admin can refill item quantity and add new items to slots
//
// Non-Functional:
//   - All money stored as int (cents) — no floating point arithmetic
//   - VendingMachine is a Singleton — one machine, one state
//   - Every operation validates state before executing (State Machine)
//   - No separate service class — the machine IS the service
//
// Out of scope: Multiple coin return denominations, network connectivity, receipts
// =============================================================================
//
// KEY INSIGHT: In all other problems, STATE lives on a sub-entity
//   (ShowSeat, BookCopy, Order, BookCopy). Here, the ENTIRE MACHINE
//   is the state machine. Every method's first job is to check machine state.
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

// Three states of the machine. DISPENSING is transient — immediately returns to IDLE.
enum MachineState { IDLE, COIN_INSERTED, DISPENSING }

// Coin denominations in cents. value field used for arithmetic.
enum Coin {
    PENNY(1), NICKEL(5), DIME(10), QUARTER(25);

    public final int value; // in CENTS — avoids all floating-point issues

    Coin(int value) { this.value = value; }
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:   Item, Slot
// Singleton:  VendingMachine
//
// WHY NO SERVICE CLASS?
//   In BookMyShow, BookingService orchestrates across multiple entities (User, Show,
//   ShowSeat, Booking). A service exists when coordination crosses entity boundaries.
//   Here, VendingMachine itself IS the single orchestrator — insert, select, dispense,
//   refund all operate on the same machine's state and inventory. No separate service needed.
//
// Relationships:
//   VendingMachine   HAS-A (Composition)   Map<code, Slot>
//   Slot             HAS-A (Composition)   Item
//   VendingMachine   IS-A                  Singleton
//   VendingMachine   IS-A                  State Machine  (IDLE ↔ COIN_INSERTED ↔ DISPENSING)
// =============================================================================


// ── Item ──────────────────────────────────────────────────────────────────────
// Pure data — no status. Status (quantity) lives in Slot.

class Item {
    public String code;  // slot code: A1, B2, etc.
    public String name;
    public int    price; // in CENTS — same unit as Coin.value

    public Item(String code, String name, int price) {
        this.code  = code;
        this.name  = name;
        this.price = price;
    }
}


// ── Slot ──────────────────────────────────────────────────────────────────────
// One slot in the machine. Holds an item type and how many remain.
// Quantity is not in Item because the same item could theoretically be in
// multiple slots — keeping it in Slot is the right separation.

class Slot {
    public Item item;
    public int  quantity;

    public Slot(Item item, int quantity) {
        this.item     = item;
        this.quantity = quantity;
    }

    public boolean isAvailable() { return quantity > 0; }
}


// ── VendingMachine (Singleton + State Machine) ────────────────────────────────
// THE MACHINE IS THE STATE MACHINE.
// Every public method checks the current state before doing anything.
//
// State transitions:
//   IDLE         → insertCoin()  → COIN_INSERTED
//   COIN_INSERTED → insertCoin() → COIN_INSERTED  (add to balance)
//   COIN_INSERTED → selectItem() → DISPENSING → IDLE  (if valid)
//   COIN_INSERTED → refund()    → IDLE
//   DISPENSING    → (automatic) → IDLE
//
// IMPORTANT: insertedAmount is in CENTS (int), never double.

class VendingMachine {
    private static VendingMachine instance;

    private MachineState        state;
    private int                 insertedAmount; // in cents
    private Map<String, Slot>   slots;          // slotCode → Slot

    private VendingMachine() {
        this.state          = MachineState.IDLE;
        this.insertedAmount = 0;
        this.slots          = new HashMap<>();
    }

    public static VendingMachine getInstance() {
        if (instance == null) instance = new VendingMachine();
        return instance;
    }

    // ── Admin operations ──────────────────────────────────────────────────────

    public void addSlot(String code, Item item, int quantity) {
        slots.put(code, new Slot(item, quantity));
        System.out.println("[ADMIN] Added slot " + code + ": " + item.name +
                " × " + quantity + " @ " + item.price + "¢");
    }

    public void refillSlot(String code, int quantity) {
        Slot slot = slots.get(code);
        if (slot == null) throw new RuntimeException("Invalid slot: " + code);
        slot.quantity += quantity;
        System.out.println("[ADMIN] Refilled slot " + code + " (" + slot.item.name +
                "): +" + quantity + " → total " + slot.quantity);
    }

    // ── Customer operations ───────────────────────────────────────────────────

    // Valid in IDLE and COIN_INSERTED states. Rejected in DISPENSING.
    public void insertCoin(Coin coin) {
        if (state == MachineState.DISPENSING) {
            throw new RuntimeException("Cannot insert coin — machine is dispensing");
        }
        insertedAmount += coin.value;
        if (state == MachineState.IDLE) {
            state = MachineState.COIN_INSERTED; // first coin triggers state change
        }
        System.out.println("[COIN] Inserted " + coin + " (" + coin.value + "¢)" +
                " | Balance: " + insertedAmount + "¢");
    }

    // Valid ONLY in COIN_INSERTED state.
    // Two soft failures (stay in COIN_INSERTED): insufficient funds, out of stock.
    // One success path: → DISPENSING → IDLE.
    public void selectItem(String code) {
        if (state != MachineState.COIN_INSERTED) {
            throw new RuntimeException(
                    state == MachineState.IDLE
                    ? "Please insert coins before selecting an item"
                    : "Machine is busy — please wait");
        }

        Slot slot = slots.get(code);
        if (slot == null) {
            throw new RuntimeException("Invalid slot code: " + code + " (money retained)");
        }

        if (!slot.isAvailable()) {
            System.out.println("[OUT OF STOCK] " + slot.item.name +
                    " is out of stock. Please select another item or press refund.");
            return; // stay in COIN_INSERTED — money retained so customer can choose again
        }

        if (insertedAmount < slot.item.price) {
            int shortfall = slot.item.price - insertedAmount;
            System.out.println("[INSUFFICIENT FUNDS] Need " + shortfall + "¢ more for " +
                    slot.item.name + ". Current balance: " + insertedAmount + "¢");
            return; // stay in COIN_INSERTED — money retained
        }

        // All checks passed — dispense
        state = MachineState.DISPENSING;
        dispense(slot);
    }

    // Refund: only valid in COIN_INSERTED. Returns all inserted money. → IDLE.
    public int refund() {
        if (state != MachineState.COIN_INSERTED) {
            if (state == MachineState.IDLE) {
                System.out.println("[REFUND] No money inserted");
            } else {
                System.out.println("[REFUND] Cannot refund while dispensing");
            }
            return 0;
        }
        int returned = insertedAmount;
        insertedAmount = 0;
        state = MachineState.IDLE;
        System.out.println("[REFUND] Returned " + returned + "¢ | Machine ready");
        return returned;
    }

    // Internal — called only from selectItem() when in DISPENSING state.
    // Transitions back to IDLE when done.
    private void dispense(Slot slot) {
        slot.quantity--;

        int change = insertedAmount - slot.item.price;
        insertedAmount = 0;
        state = MachineState.IDLE; // back to IDLE after dispense

        System.out.println("[DISPENSED] " + slot.item.name + " (" + slot.item.price + "¢)");
        if (change > 0) {
            System.out.println("[CHANGE]    " + change + "¢ returned");
        }
        System.out.println("[READY]     Machine ready | Remaining stock: " +
                slot.item.name + " × " + slot.quantity);
    }

    public void printInventory() {
        System.out.println("\n── Inventory ──");
        System.out.printf("  %-6s %-20s %8s %8s%n", "Slot", "Item", "Price", "Stock");
        System.out.println("  " + "-".repeat(46));
        for (Map.Entry<String, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            System.out.printf("  %-6s %-20s %6d¢ %8d%n",
                    entry.getKey(), slot.item.name, slot.item.price, slot.quantity);
        }
        System.out.println();
    }

    public MachineState getState()          { return state; }
    public int          getInsertedAmount() { return insertedAmount; }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: VendingMachineSolution.java
// =============================================================================

public class VendingMachineSolution {
    public static void main(String[] args) {
        System.out.println("=== Vending Machine Demo ===\n");

        VendingMachine machine = VendingMachine.getInstance();

        // Setup inventory
        machine.addSlot("A1", new Item("A1", "Coke",       65), 2);
        machine.addSlot("A2", new Item("A2", "Diet Coke",  65), 1);
        machine.addSlot("B1", new Item("B1", "Chips",      50), 3);
        machine.addSlot("B2", new Item("B2", "Chocolate",  75), 0); // out of stock
        machine.addSlot("C1", new Item("C1", "Water",      35), 5);
        machine.printInventory();

        // ── Happy path: exact change ──────────────────────────────────────────
        System.out.println("── Exact change for Chips (50¢) ──");
        machine.insertCoin(Coin.QUARTER); // 25¢
        machine.insertCoin(Coin.QUARTER); // 50¢
        machine.selectItem("B1");         // dispense Chips, no change
        System.out.println();

        // ── Happy path: overpay → change returned ─────────────────────────────
        System.out.println("── Overpay for Water (35¢), insert 50¢ ──");
        machine.insertCoin(Coin.QUARTER); // 25¢
        machine.insertCoin(Coin.DIME);    // 35¢
        machine.insertCoin(Coin.DIME);    // 45¢
        machine.insertCoin(Coin.NICKEL);  // 50¢
        machine.selectItem("C1");         // dispense Water, 15¢ change
        System.out.println();

        // ── Insufficient funds — machine keeps money ──────────────────────────
        System.out.println("── Insufficient funds for Coke (65¢) ──");
        machine.insertCoin(Coin.QUARTER); // 25¢
        machine.insertCoin(Coin.DIME);    // 35¢
        machine.selectItem("A1");         // need 30¢ more — machine stays COIN_INSERTED
        machine.insertCoin(Coin.QUARTER); // 60¢
        machine.insertCoin(Coin.NICKEL);  // 65¢
        machine.selectItem("A1");         // now enough — dispense Coke
        System.out.println();

        // ── Out of stock ──────────────────────────────────────────────────────
        System.out.println("── Out of stock item (Chocolate B2) ──");
        machine.insertCoin(Coin.QUARTER);
        machine.insertCoin(Coin.QUARTER);
        machine.insertCoin(Coin.QUARTER); // 75¢
        machine.selectItem("B2");          // out of stock — money retained
        // Customer selects something else with retained balance
        machine.selectItem("A1");          // 65¢ → dispense Coke, 10¢ change
        System.out.println();

        // ── Refund ────────────────────────────────────────────────────────────
        System.out.println("── Customer inserts money then wants refund ──");
        machine.insertCoin(Coin.QUARTER);
        machine.insertCoin(Coin.QUARTER);
        machine.insertCoin(Coin.DIME);    // 60¢ inserted
        machine.refund();                 // all 60¢ returned → IDLE
        System.out.println();

        // ── State violation: select without inserting money ───────────────────
        System.out.println("── Select without inserting coins (IDLE state) ──");
        try {
            machine.selectItem("B1");
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
        System.out.println();

        // ── State violation: insert coin while DISPENSING ─────────────────────
        // (Hard to demo in single thread; shown via direct state check)
        System.out.println("── Refund when no money inserted (IDLE state) ──");
        machine.refund(); // prints "No money inserted"
        System.out.println();

        // ── Admin: refill ─────────────────────────────────────────────────────
        System.out.println("── Admin refills out-of-stock Chocolate ──");
        machine.refillSlot("B2", 5);
        machine.printInventory();

        // ── Machine state is a Singleton ──────────────────────────────────────
        System.out.println("── Singleton check ──");
        VendingMachine m2 = VendingMachine.getInstance();
        System.out.println("Same instance: " + (machine == m2)); // true
    }
}
