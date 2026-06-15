# LLD: ATM Machine

> Implementation: `ATMMachineSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Insert card → enter PIN → (check balance / withdraw / deposit) → eject card |
| 2 | PIN validation: lock card after 3 wrong attempts |
| 3 | Withdraw: reject if account balance insufficient OR ATM cash is below minimum reserve |
| 4 | Deposit: add cash to account and increase ATM cash |
| 5 | Every transaction (including failed ones) is recorded as an audit log entry |
| 6 | Eject card resets the ATM to IDLE from any state |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | ATM is a Singleton — one machine, one session at a time |
| 2 | Bank is a separate service — external authority for all account and PIN data |

### Out of Scope
Network calls to real bank, card skimming prevention, receipt printing hardware, card blocking across multiple ATMs

---

## Step 2 — How This Differs From Vending Machine

Both use a state machine, but ATM has distinct extra concerns:

| Aspect | Vending Machine | ATM Machine |
|--------|----------------|-------------|
| Actors | One (buyer) | Two (cardholder + Bank) |
| Authentication | None | PIN + 3-attempt lockout |
| Account data | Internal | Owned by Bank service — ATM never stores it |
| Transaction audit | None | Every operation logged as Transaction |
| Money tracking | Coins in machine | ATM cash reserve + Bank account balance |

---

## Step 3 — Entities

| Class | Role |
|-------|------|
| `Card` | Physical card — holds `cardNumber`, `accountId`, `locked` flag |
| `Account` | Bank-owned account — private `balance` and `pin`, tracks `wrongAttempts` |
| `Transaction` | Audit record for every ATM operation (SUCCESS or FAILED) |
| `Bank` | External authority — owns accounts and cards, validates PIN |
| `ATM` | Singleton state machine — orchestrates the user session |

### Enums

| Enum | Values |
|------|--------|
| `ATMState` | IDLE, CARD\_INSERTED, PIN\_VERIFIED |
| `TransactionType` | WITHDRAW, DEPOSIT, BALANCE\_CHECK |
| `TransactionStatus` | SUCCESS, FAILED |

---

## Step 4 — ATM State Machine

```
IDLE ──[insertCard()]──► CARD_INSERTED ──[enterPIN() ✓]──► PIN_VERIFIED
  ▲                            │                                  │
  │                    [enterPIN() ✗ ×3]                  [withdraw()]
  │                     card.locked=true                  [deposit()]
  │                     auto-ejectCard()              [checkBalance()]
  │                            │                                  │
  └────────────────────[ejectCard()]──────────────────────────────┘
```

**Key transition rules (from code):**
- `insertCard()` only works from `IDLE`. Rejects if `card.locked == true`.
- `enterPIN()` only works from `CARD_INSERTED`. On 3rd wrong attempt: locks card + calls `ejectCard()`.
- `withdraw()`, `deposit()`, `checkBalance()` guarded by `requirePinVerified()` — only work from `PIN_VERIFIED`.
- `ejectCard()` works from any state, resets to `IDLE` and clears `currentCard`.

---

## Step 5 — Class Attributes & Methods

### `Card`

| Member | Type | Description |
|--------|------|-------------|
| `cardNumber` | String | identifier |
| `accountId` | String | links to Bank Account |
| `pin` (private) | String | card-specific PIN — never exposed |
| `wrongAttempts` | int | per-card counter, reset to 0 on correct PIN |
| `locked` | boolean | set true after 3 wrong PINs — only this card locked |
| `checkPin(entered)` | boolean | compare with stored PIN |

### `Account` (Bank-owned, purely financial)

| Member | Type | Description |
|--------|------|-------------|
| `balance` (private) | int | in paise — no floating point |
| `getBalance()` | int | read balance |
| `debit(amount)` | boolean | returns false if insufficient |
| `credit(amount)` | void | adds to balance |

### `Transaction` (Audit entity)

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | TXN-N auto-generated |
| `accountId` | String | which account |
| `type` | TransactionType | WITHDRAW / DEPOSIT / BALANCE\_CHECK |
| `amount` | int | in paise |
| `status` | TransactionStatus | SUCCESS or FAILED |
| `note` | String | failure reason (e.g. "ATM cash low") |

### `Bank`

| Method | Description |
|--------|-------------|
| `addAccount(account)` | register account |
| `addCard(card)` | register card |
| `validatePin(card, enteredPin)` | check PIN, increment wrongAttempts, lock card on 3rd failure |
| `getBalance(accountId)` | read balance |
| `debit(accountId, amount)` | remove from account (returns false if insufficient) |
| `credit(accountId, amount)` | add to account |

### `ATM` (Singleton)

| Member / Method | Description |
|-----------------|-------------|
| `state` (private) | current ATMState |
| `bank` | reference to Bank service |
| `currentCard` | card in session (null when IDLE) |
| `cashAvailable` | ATM's own cash in paise |
| `MIN_CASH_RESERVE` | ₹10,000 — withdraw rejected if going below this |
| `transactionLog` | List\<Transaction\> — audit trail |
| `insertCard(cardNumber)` | IDLE → CARD\_INSERTED |
| `enterPIN(pin)` | CARD\_INSERTED → PIN\_VERIFIED (or lock + eject) |
| `checkBalance()` | read balance from Bank |
| `withdraw(amountRupees)` | validate ATM cash + Bank debit |
| `deposit(amountRupees)` | Bank credit + increase ATM cash |
| `ejectCard()` | reset to IDLE from any state |
| `printTransactionLog()` | display all Transaction records |

---

## Step 6 — Design Patterns

### Singleton — `ATM`

```java
ATM.getInstance(bank, 10000000); // one instance, one session at a time
ATM.reset(); // used between demo scenarios
```

### Separation of Concerns — `Bank` as external service

ATM never stores account data directly. All financial operations are delegated:
```java
// ATM delegates to Bank:
if (!bank.debit(currentCard.accountId, amount)) { /* reject */ }
bank.credit(currentCard.accountId, amount);
bank.validatePin(currentCard, pin);
```

### Transaction as audit entity

Same role as `ParkingTicket` (Parking Lot) and `BorrowRecord` (Library Management):
- Created for every operation, including failed ones
- Immutable once logged
- Carries `status` and `note` for failed transactions

---

## Step 7 — Key Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Balance stored in paise (int) | Yes | Never use float for money — floating point precision errors |
| PIN on Card (private), not Account | Yes | One account can have multiple cards, each with its own PIN. Locking card A must not affect card B. |
| `wrongAttempts` on Card | Yes | Attempts are per-card, not per-account — consistent with PIN being on Card |
| `locked` on Card | Yes | Locking is physical-card-level — card is replaced, account continues |
| Account has NO PIN logic | Yes | Account is purely financial: balance, debit, credit. Clean separation. |
| `MIN_CASH_RESERVE` on ATM | Yes | ATM must always keep enough cash for smaller withdrawals |
| Failed transactions still logged | Yes | Audit trail must be complete — failures are as important as successes |
| `ejectCard()` works from any state | Yes | Safety — user must always be able to get their card back |

---

## Step 8 — Extensibility

| Extension | How |
|-----------|-----|
| Multiple ATMs | Remove Singleton, maintain a `Map<atmId, ATM>` in a network coordinator |
| Card blocking across all ATMs | Bank sets `card.blocked` flag; all ATMs check via `bank.isCardBlocked()` |
| Retry with delay after wrong PIN | Add cooldown timer to Account before allowing next attempt |
| Receipt printing | Observer — ATM notifies `ReceiptPrinter` on successful transaction |
| Daily withdrawal limit | Track `dailyWithdrawn` on Account, reset at midnight |

---

## Quick Recall — 3 Main Takeaways

1. **Two actors**: Cardholder at the ATM, Bank as the external authority. ATM delegates all financial operations to Bank — it never stores account data itself.

2. **PIN and `wrongAttempts` on Card, not Account**: One account can have multiple cards, each with its own PIN. Locking card A after 3 wrong PINs must NOT affect card B. Account owns only financial data (balance, debit, credit) — no PIN logic at all.

3. **Money always in paise (int), never float**: `500000` paise = ₹5000. Integer arithmetic eliminates floating-point precision issues for all money operations.
