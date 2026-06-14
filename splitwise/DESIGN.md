# LLD: Splitwise

> **Code file:** `SplitwiseSolution.java` — keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
| # | Requirement |
|---|---|
| 1 | Users can add an expense paid by one user, split among multiple users |
| 2 | Three split types: EQUAL (auto-divide), PERCENTAGE (user gives %), EXACT (user gives amount) |
| 3 | Track who owes whom and how much (per-pair net balances) |
| 4 | Users can settle debts by paying each other |
| 5 | View overall balance summary or a specific user's balance |

### Non-Functional
- Balances are **net** — if A owes B ₹100 and B owes A ₹60, result is A owes B ₹40 only
- Adding a new split type requires no change to `SplitwiseService`
- All split types share userId and amount fields → Abstract Class

### Out of Scope
Groups, currencies, payment gateway, expense categories, expense history/edit

---

## Step 2 — Entities

| Entity | Type | Role |
|---|---|---|
| `User` | Class | Person in the system: id, name, email |
| `ExpenseSplit` | **Abstract Class** | Shared fields: userId + amount. Enforces `calculateAmount()` |
| `EqualSplit` | Class | Extends ExpenseSplit → divides total evenly |
| `PercentageSplit` | Class | Extends ExpenseSplit → holds percentage, computes share |
| `ExactSplit` | Class | Extends ExpenseSplit → amount set directly in constructor |
| `Expense` | Class | One expense event: description, amount, paidBy, list of splits |
| `SplitwiseService` | Service | Orchestrates add expense, settle, balance tracking |

---

## Step 3 — Class Design

### Relationships

```
SplitwiseService
  ├── OWNS Map<String, User>           (users registry)
  ├── OWNS List<Expense>               (all expense records)
  └── OWNS owes Map                    (net per-pair balances)

Expense
  ├── HAS-A paidByUserId (reference to User)
  └── HAS-A List<ExpenseSplit>
           ├── EqualSplit      IS-A ExpenseSplit (abstract)
           ├── PercentageSplit IS-A ExpenseSplit (abstract)
           └── ExactSplit      IS-A ExpenseSplit (abstract)
```

### Attributes and Methods

**`ExpenseSplit` (abstract)**
- `public String userId` — who this share belongs to
- `public double amount` — calculated share (set by `calculateAmount()`)
- `abstract calculateAmount(totalAmount, numParticipants)` — subclass fills in

**`EqualSplit`**
- `calculateAmount`: `amount = totalAmount / numParticipants`

**`PercentageSplit`**
- Extra field: `public double percentage`
- `calculateAmount`: `amount = totalAmount * percentage / 100`

**`ExactSplit`**
- Amount set in constructor — `calculateAmount` is a no-op

**`Expense`**
- `id`, `description`, `amount`, `paidByUserId`, `List<ExpenseSplit>`, `createdAt`

**`SplitwiseService`**
- `addUser(User)` — register user, initialize their owes entry
- `addExpense(description, amount, paidByUserId, splits)` — validates + calculates + updates balances
- `settle(fromUserId, toUserId, amount)` — reduce fromUser's debt to toUser
- `printBalances()` — show all non-zero debts
- `printUserBalance(userId)` — show one user's debts in and out
- `updateOwes(owerId, creditorId, delta)` — net balance update (offsets reverse debts first)

---

## Step 4 — Design Patterns

### 1. Abstract Class + Polymorphism — `ExpenseSplit`

This is the second use of abstract class in our problems (alongside Logger's `LogAppender`).

```
ExpenseSplit (abstract)
  userId    ← shared field (which user this split belongs to)
  amount    ← shared field (their calculated share)
  calculateAmount() ← abstract (each type calculates differently)

EqualSplit      → amount = total / count
PercentageSplit → amount = total * pct / 100  + extra field: percentage
ExactSplit      → amount set directly in constructor
```

**Why abstract class and not interface?**
- `userId` and `amount` are **mutable instance fields** shared by all split types
- Interfaces cannot hold mutable instance state
- All three types are genuinely IS-A `ExpenseSplit` — they share identity, not just a contract

**Why not just one class with an enum?**
- `PercentageSplit` has an extra field (`percentage`) that `EqualSplit` doesn't need
- `ExactSplit`'s `calculateAmount` is a no-op — different lifecycle
- Separate subclasses make each type's intent clear and independently testable

### 2. Service Class — `SplitwiseService`
Same decision as BookMyShow's `BookingService`. The split logic, balance updates, and settlement are orchestration concerns — not the responsibility of `Expense` or `User` alone.

---

## Step 5 — Balance Tracking

### The owes Map
```
owes[A][B] = 50  →  A owes B ₹50
owes[B][A] = 0   →  B does not owe A (netted out)
```

### Net update logic (updateOwes)
When adding ₹X to owes[A][B]:
1. Check if owes[B][A] > 0 (reverse debt exists)
2. If yes: offset — subtract from reverse first, only add remainder to forward
3. This prevents A owes B ₹100 AND B owes A ₹60 coexisting — they become A owes B ₹40

### Settlement
`settle(A, B, amount)` → reduces owes[A][B] by amount (A is paying B)

---

## Step 6 — Extensibility

| Change | What to do |
|---|---|
| Add new split type (SHARE-based) | Extend `ExpenseSplit`, implement `calculateAmount()` |
| Add groups | Add `Group` class with `List<User>`, `SplitwiseService` operates on group's users |
| Add expense categories | Add `category` field to `Expense` |
| Add simplify debts feature | Compute net balance per user, greedy settle from max creditor to max debtor |
| Multiple currencies | Add `currency` to `Expense`, convert to base currency before balance update |

---

## Key Interview Points

- **Why abstract class for splits?** Because `userId` and `amount` are state shared by all three types. Interface cannot hold mutable instance fields. Also the IS-A relationship is genuine — `EqualSplit` IS an `ExpenseSplit`.
- **Why not just one `Expense` class with split type enum?** Because `PercentageSplit` needs a `percentage` field, `ExactSplit` has a different `calculateAmount` lifecycle. Subclasses cleanly capture these differences.
- **Net balances:** Never let A→B and B→A both be non-zero. Always offset on update. This mirrors how Splitwise's "Simplify Debts" works.
- **Validation:** Percentage splits must sum to 100. Exact splits must sum to total. Check before any balance update.
- **Service class:** `SplitwiseService` owns the owes map because balance state spans multiple users and expenses — no single entity is the right owner.
