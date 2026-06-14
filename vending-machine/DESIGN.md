# LLD: Vending Machine

> **Code file:** `VendingMachineSolution.java` — keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
| # | Requirement |
|---|---|
| 1 | Customer inserts coins (PENNY=1¢, NICKEL=5¢, DIME=10¢, QUARTER=25¢) |
| 2 | Customer selects an item by slot code (A1, A2, B1, …) |
| 3 | Machine dispenses item and returns exact change if overpaid |
| 4 | Customer can refund inserted coins at any time before selection |
| 5 | Machine rejects invalid operations based on current state |
| 6 | Admin can refill item quantity and add new items to slots |

### Non-Functional
- All money stored as `int` (cents) — no floating point arithmetic
- VendingMachine is a Singleton — one machine, one state
- Every operation validates state before executing

### Out of Scope
Multiple coin return denominations, network connectivity, receipts, payment card

---

## Step 2 — Entities

| Entity | Type | Role |
|---|---|---|
| `MachineState` | Enum | IDLE / COIN_INSERTED / DISPENSING |
| `Coin` | Enum | PENNY(1) / NICKEL(5) / DIME(10) / QUARTER(25) — value in cents |
| `Item` | Class | code, name, price (cents) — pure metadata |
| `Slot` | Class | Item + quantity — owns availability state |
| `VendingMachine` | Class | Singleton + State Machine — is the service |

---

## Step 3 — Class Design

### The State Machine (the entire design)

```
                 insertCoin()
    IDLE ─────────────────────────► COIN_INSERTED
                                        │    │    │
                        insertCoin()  ──┘    │    │
                                             │    │
                                 selectItem()│    │ refund()
                                (valid case) │    │
                                             ▼    ▼
                                         DISPENSING  IDLE
                                             │
                                    dispense()│ (internal, auto)
                                             ▼
                                           IDLE
```

### What each state allows

| Operation | IDLE | COIN_INSERTED | DISPENSING |
|---|---|---|---|
| `insertCoin()` | ✓ → COIN_INSERTED | ✓ stays | ✗ error |
| `selectItem()` | ✗ "insert coins first" | ✓ → DISPENSING → IDLE | ✗ error |
| `refund()` | ✗ "no money inserted" | ✓ → IDLE | ✗ error |

### Why no service class?

In BookMyShow, `BookingService` orchestrates across `Show`, `User`, `ShowSeat`, `Booking` — multiple entities. A service exists when coordination crosses entity boundaries.

Here, everything is the same machine's own state and inventory. `VendingMachine` IS the single orchestrator. No external coordination needed → no service class.

### Relationships

```
VendingMachine (Singleton, State Machine)
  └── HAS-A Map<slotCode, Slot>    (Composition)

Slot
  └── HAS-A Item                   (Composition)
```

### Attributes and Methods

**`Coin`**
- `public final int value` — the only field. Used directly for arithmetic: `insertedAmount += coin.value`

**`Item`**
- `code`, `name`, `price` (cents) — read-only data, no status

**`Slot`**
- `Item item`, `int quantity`
- `isAvailable()` → `quantity > 0`

**`VendingMachine`**
- `private MachineState state` — current state
- `private int insertedAmount` — running total in cents
- `private Map<String, Slot> slots`
- `insertCoin(Coin)` → validate state, add to balance, transition if IDLE
- `selectItem(code)` → validate state + availability + balance → dispense or soft-fail
- `refund()` → validate state, return money, → IDLE
- `private dispense(slot)` → decrement stock, calculate change, → IDLE
- `addSlot()`, `refillSlot()` — admin operations

---

## Step 4 — Design Patterns

### 1. Singleton — VendingMachine

Same as ParkingLot. One machine = one instance.

### 2. State Machine — MachineState

The defining pattern of this problem. Three states, strict valid-transition rules.

**Important: Two levels of failure in `selectItem()`**

Unlike other state machines where failure = throw exception, vending machine has **soft failures** that keep the machine in `COIN_INSERTED`:

```java
// SOFT FAIL → stay in COIN_INSERTED (money retained, customer can choose again)
if (!slot.isAvailable())         → print out-of-stock, return
if (insertedAmount < slot.price) → print insufficient funds, return

// HARD SUCCESS → COIN_INSERTED → DISPENSING → IDLE
// (all checks passed)
```

This distinction is unique — in Food Delivery, invalid state transition throws an exception. Here, insufficient funds and out-of-stock are valid states that don't terminate the session.

---

## Step 5 — Similarities and Differences

### Similarities to other problems

| Similarity | Vending Machine | Other problem |
|---|---|---|
| Singleton | VendingMachine.getInstance() | ParkingLot.getInstance() |
| State machine | Machine state drives operations | BookMyShow ShowSeat, Food Delivery Order |
| Inventory tracking | Slot.quantity decremented on dispense | Library's BookCopy availability |
| Admin vs customer ops | addSlot/refillSlot vs insertCoin/select | Library's addBook vs borrowBook |

### What's unique to Vending Machine

| Aspect | Vending Machine | All other problems |
|---|---|---|
| **State machine location** | The ENTIRE MACHINE is the state machine | A sub-entity (ShowSeat, BookCopy, Order) has state |
| **Soft failures** | Out-of-stock and insufficient funds keep session alive | Most failures throw exceptions |
| **Money tracking** | Running balance, change calculation | Not present in other problems |
| **int for money** | Always cents, never double | Not relevant elsewhere |
| **No service class** | Machine IS the service | Most problems have a separate service |

---

## Step 6 — The Three Takeaways

### 1. Every method validates state FIRST

```java
void selectItem(String code) {
    if (state != MachineState.COIN_INSERTED) { ... throw ... }
    // only then: check stock, check balance, dispense
}

void insertCoin(Coin coin) {
    if (state == MachineState.DISPENSING) { ... throw ... }
    // only then: add to balance
}
```

This is stricter than other problems. BookMyShow only validates in specific methods. Here, every single public method has a state check as its first action.

### 2. int for money, always

```java
// WRONG — floating point
double insertedAmount = 0.25 + 0.10 + 0.05; // = 0.39999999999...

// RIGHT — integer cents
int insertedAmount = 25 + 10 + 5; // = 40, always exact

enum Coin { PENNY(1), NICKEL(5), DIME(10), QUARTER(25) }
```

### 3. Refund is mandatory

The flow without refund is broken: customer inserts 60¢, item is out of stock, machine keeps money forever. Always implement `refund()`. It's the only graceful exit from `COIN_INSERTED` when selection fails.

---

## Step 7 — Extensibility

| Change | What to do |
|---|---|
| New coin denomination (50¢) | Add `HALF_DOLLAR(50)` to `Coin` enum |
| New payment method (card) | Add `insertCard(Card card)` method; new transition from IDLE |
| Discount / promo pricing | Add Strategy for price calculation; `getPrice(item, context)` |
| Maintenance mode | Add `MAINTENANCE` state; `enterMaintenance()` and `exitMaintenance()` |
| Multiple item selection | Accumulate a cart; single `checkout()` call at the end |
| GoF State Pattern | Replace enum + switch with `VendingMachineState` interface; each state is a class. More extensible but more complex |

### The GoF State Pattern alternative (for advanced interviews)

Instead of `MachineState` enum with if/else in methods:
```
VendingMachineState (interface)
  insertCoin(machine, coin)
  selectItem(machine, code)
  refund(machine)
  ├── IdleState implements VendingMachineState
  ├── CoinInsertedState implements VendingMachineState
  └── DispensingState implements VendingMachineState

VendingMachine.currentState = new IdleState()
```

Each state class handles its own behavior. Adding a new state = new class, nothing else changes. More extensible but harder to write quickly in an interview.

**For interview: enum approach is faster and clearer. Mention GoF State Pattern as the extensible alternative.**
