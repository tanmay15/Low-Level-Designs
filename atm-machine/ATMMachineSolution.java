// =============================================================================
// LLD: ATM MACHINE
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Insert card → enter PIN → (check balance | withdraw | deposit) → eject card
//   2. PIN validation: lock card after 3 wrong attempts
//   3. Withdraw: reject if insufficient balance or ATM cash is low
//   4. Deposit: add cash to account
//   5. Every transaction is recorded (audit trail)
//   6. Eject card resets ATM to IDLE at any point
//
// Non-Functional:
//   - ATM is Singleton (one machine)
//   - Bank is a separate service (external authority for accounts + PIN)
//
// Out of scope: network calls to real bank, card skimming prevention, receipt
//   printing hardware, card blocking across multiple ATMs
//
// COMPARED TO VENDING MACHINE:
//   - Vending Machine: one actor, money in int (cents)
//   - ATM: two actors (cardholder + Bank), PIN validation, card lockout,
//     separate Bank service for account data, transaction audit log
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum ATMState        { IDLE, CARD_INSERTED, PIN_VERIFIED }
enum TransactionType { WITHDRAW, DEPOSIT, BALANCE_CHECK }
enum TransactionStatus { SUCCESS, FAILED }


// =============================================================================
// ENTITIES
// =============================================================================

// ── Card ──────────────────────────────────────────────────────────────────────
class Card {
    public String cardNumber;
    public String accountId;
    public boolean locked;  // locked after 3 wrong PIN attempts

    public Card(String cardNumber, String accountId) {
        this.cardNumber = cardNumber;
        this.accountId  = accountId;
        this.locked     = false;
    }
}

// ── Account ───────────────────────────────────────────────────────────────────
// Owned by the Bank, not the ATM. ATM never stores account data directly.
class Account {
    public String id;
    private int   balance;      // in paise/cents (integer, no floats)
    private String pin;
    public int    wrongAttempts;

    public Account(String id, int initialBalance, String pin) {
        this.id           = id;
        this.balance      = initialBalance;
        this.pin          = pin;
        this.wrongAttempts = 0;
    }

    public int    getBalance()              { return balance; }
    public boolean checkPin(String entered) { return pin.equals(entered); }
    public void   credit(int amount)        { balance += amount; }
    public boolean debit(int amount) {
        if (balance < amount) return false;
        balance -= amount;
        return true;
    }
}

// ── Transaction ───────────────────────────────────────────────────────────────
// Audit record — same role as ParkingTicket / BorrowRecord.
class Transaction {
    public String            id;
    public String            accountId;
    public TransactionType   type;
    public int               amount;
    public TransactionStatus status;
    public String            note;
    public long              timestamp;

    public Transaction(String id, String accountId, TransactionType type, int amount) {
        this.id        = id;
        this.accountId = accountId;
        this.type      = type;
        this.amount    = amount;
        this.status    = TransactionStatus.SUCCESS;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("[%s] %s %s ₹%d %s",
                id, accountId, type, amount / 100, status);
    }
}


// =============================================================================
// BANK SERVICE (external authority)
// =============================================================================
// ATM delegates all account/PIN logic to the Bank.
// In real world this is a network call; in LLD it's a local service.

class Bank {
    private Map<String, Account> accounts = new HashMap<>(); // accountId → Account
    private Map<String, Card>    cards    = new HashMap<>(); // cardNumber → Card

    public void addAccount(Account account)  { accounts.put(account.id, account); }
    public void addCard(Card card)           { cards.put(card.cardNumber, card); }

    public Card    getCard(String cardNumber)  { return cards.get(cardNumber); }
    public Account getAccount(String accountId){ return accounts.get(accountId); }

    // Returns true if PIN correct; false + increments wrongAttempts otherwise.
    // Locks card after 3 failures.
    public boolean validatePin(Card card, String enteredPin) {
        Account account = accounts.get(card.accountId);
        if (account == null) return false;

        if (account.checkPin(enteredPin)) {
            account.wrongAttempts = 0;
            return true;
        }

        account.wrongAttempts++;
        System.out.println("  [BANK] Wrong PIN. Attempts: " + account.wrongAttempts + "/3");
        if (account.wrongAttempts >= 3) {
            card.locked = true;
            System.out.println("  [BANK] Card " + card.cardNumber + " LOCKED after 3 wrong attempts");
        }
        return false;
    }

    public int  getBalance(String accountId)              { return accounts.get(accountId).getBalance(); }
    public boolean debit(String accountId, int amount)    { return accounts.get(accountId).debit(amount); }
    public void    credit(String accountId, int amount)   { accounts.get(accountId).credit(amount); }
}


// =============================================================================
// ATM (Singleton — State Machine)
// =============================================================================
// The ATM orchestrates the user session. It never stores account data itself —
// all financial operations are delegated to the Bank.
//
// STATE MACHINE:
//   IDLE → [insertCard] → CARD_INSERTED → [enterPIN] → PIN_VERIFIED
//   PIN_VERIFIED → [withdraw | deposit | checkBalance] → PIN_VERIFIED (stays)
//   Any state → [ejectCard] → IDLE

class ATM {
    private static ATM instance;

    private ATMState state;
    private Bank     bank;
    private Card     currentCard;     // card currently inserted
    private int      cashAvailable;   // how much cash ATM physically has (in paise)
    private List<Transaction> transactionLog;
    private int      txnCounter;

    private static final int MIN_CASH_RESERVE = 10000 * 100; // ₹10,000 minimum

    private ATM(Bank bank, int initialCash) {
        this.bank           = bank;
        this.state          = ATMState.IDLE;
        this.cashAvailable  = initialCash;
        this.transactionLog = new ArrayList<>();
        this.txnCounter     = 0;
    }

    public static ATM getInstance(Bank bank, int initialCash) {
        if (instance == null) instance = new ATM(bank, initialCash);
        return instance;
    }

    public static void reset() { instance = null; }

    // ── insertCard ────────────────────────────────────────────────────────────
    public void insertCard(String cardNumber) {
        if (state != ATMState.IDLE) {
            System.out.println("  [ATM] Please eject current card first");
            return;
        }
        Card card = bank.getCard(cardNumber);
        if (card == null) {
            System.out.println("  [ATM] Card not recognised: " + cardNumber);
            return;
        }
        if (card.locked) {
            System.out.println("  [ATM] Card is locked. Visit your bank branch.");
            return;
        }
        currentCard = card;
        state       = ATMState.CARD_INSERTED;
        System.out.println("  [ATM] Card inserted. Please enter your PIN.");
    }

    // ── enterPIN ──────────────────────────────────────────────────────────────
    public void enterPIN(String pin) {
        if (state != ATMState.CARD_INSERTED) {
            System.out.println("  [ATM] Please insert a card first");
            return;
        }
        if (bank.validatePin(currentCard, pin)) {
            state = ATMState.PIN_VERIFIED;
            System.out.println("  [ATM] PIN verified. Select a transaction.");
        } else if (currentCard.locked) {
            ejectCard(); // auto-eject locked card
        }
    }

    // ── checkBalance ──────────────────────────────────────────────────────────
    public void checkBalance() {
        if (!requirePinVerified()) return;
        int balance = bank.getBalance(currentCard.accountId);
        System.out.printf("  [ATM] Balance: ₹%.2f%n", balance / 100.0);
        log(TransactionType.BALANCE_CHECK, 0);
    }

    // ── withdraw ──────────────────────────────────────────────────────────────
    public void withdraw(int amountRupees) {
        if (!requirePinVerified()) return;
        int amount = amountRupees * 100;

        if (cashAvailable - amount < MIN_CASH_RESERVE) {
            System.out.println("  [ATM] ATM cash insufficient. Try another ATM.");
            logFailed(TransactionType.WITHDRAW, amount, "ATM cash low");
            return;
        }
        if (!bank.debit(currentCard.accountId, amount)) {
            System.out.println("  [ATM] Insufficient account balance");
            logFailed(TransactionType.WITHDRAW, amount, "Insufficient balance");
            return;
        }
        cashAvailable -= amount;
        System.out.printf("  [ATM] Dispensing ₹%d. Please collect cash.%n", amountRupees);
        log(TransactionType.WITHDRAW, amount);
    }

    // ── deposit ───────────────────────────────────────────────────────────────
    public void deposit(int amountRupees) {
        if (!requirePinVerified()) return;
        int amount = amountRupees * 100;
        bank.credit(currentCard.accountId, amount);
        cashAvailable += amount;
        System.out.printf("  [ATM] ₹%d deposited successfully.%n", amountRupees);
        log(TransactionType.DEPOSIT, amount);
    }

    // ── ejectCard ─────────────────────────────────────────────────────────────
    // Resets session — works from any state.
    public void ejectCard() {
        if (state == ATMState.IDLE) {
            System.out.println("  [ATM] No card inserted");
            return;
        }
        System.out.println("  [ATM] Card ejected. Goodbye.");
        currentCard = null;
        state       = ATMState.IDLE;
    }

    public void printTransactionLog() {
        System.out.println("── Transaction Log ──");
        for (Transaction t : transactionLog) System.out.println("  " + t);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean requirePinVerified() {
        if (state == ATMState.PIN_VERIFIED) return true;
        System.out.println("  [ATM] Please insert card and enter PIN first");
        return false;
    }

    private void log(TransactionType type, int amount) {
        Transaction t = new Transaction("TXN-" + (++txnCounter),
                currentCard.accountId, type, amount);
        transactionLog.add(t);
    }

    private void logFailed(TransactionType type, int amount, String note) {
        Transaction t = new Transaction("TXN-" + (++txnCounter),
                currentCard.accountId, type, amount);
        t.status = TransactionStatus.FAILED;
        t.note   = note;
        transactionLog.add(t);
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class ATMMachineSolution {
    public static void main(String[] args) {
        System.out.println("=== ATM Machine Demo ===\n");

        // ── Setup Bank ────────────────────────────────────────────────────────
        Bank bank = new Bank();

        Account acc1 = new Account("ACC-1", 500000, "1234"); // ₹5000
        Account acc2 = new Account("ACC-2", 200000, "9999"); // ₹2000
        bank.addAccount(acc1);
        bank.addAccount(acc2);

        Card card1 = new Card("CARD-1001", "ACC-1");
        Card card2 = new Card("CARD-1002", "ACC-2");
        bank.addCard(card1);
        bank.addCard(card2);

        ATM.reset();
        ATM atm = ATM.getInstance(bank, 10000000); // ₹1,00,000 in ATM

        System.out.println();

        // ── Scenario 1: Normal withdraw ────────────────────────────────────────
        System.out.println("════ Scenario 1: Normal withdraw ════");
        atm.insertCard("CARD-1001");
        atm.enterPIN("1234");
        atm.checkBalance();
        atm.withdraw(1000);
        atm.checkBalance();
        atm.ejectCard();
        System.out.println();

        // ── Scenario 2: Wrong PIN → lock ──────────────────────────────────────
        System.out.println("════ Scenario 2: Wrong PIN 3 times → card locked ════");
        atm.insertCard("CARD-1002");
        atm.enterPIN("0000");
        atm.enterPIN("1111");
        atm.enterPIN("2222"); // 3rd wrong → card locked + auto-eject
        System.out.println();

        // ── Scenario 3: Try using locked card ─────────────────────────────────
        System.out.println("════ Scenario 3: Locked card rejected ════");
        atm.insertCard("CARD-1002");
        System.out.println();

        // ── Scenario 4: Insufficient balance ──────────────────────────────────
        System.out.println("════ Scenario 4: Withdraw more than balance ════");
        atm.insertCard("CARD-1001");
        atm.enterPIN("1234");
        atm.withdraw(10000); // only ₹4000 left (withdrew ₹1000 in scenario 1)
        atm.ejectCard();
        System.out.println();

        // ── Scenario 5: Deposit ───────────────────────────────────────────────
        System.out.println("════ Scenario 5: Deposit ════");
        atm.insertCard("CARD-1001");
        atm.enterPIN("1234");
        atm.deposit(2000);
        atm.checkBalance();
        atm.ejectCard();
        System.out.println();

        // ── Transaction log ───────────────────────────────────────────────────
        System.out.println("════ Transaction Audit Log ════");
        atm.printTransactionLog();
    }
}
