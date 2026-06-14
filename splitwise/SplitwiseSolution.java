// =============================================================================
// LLD: SPLITWISE — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Users can add an expense paid by one user, split among multiple users
//   2. Three split types: EQUAL (auto-divide), PERCENTAGE (user gives %), EXACT (user gives amount)
//   3. Track who owes whom and how much (per-pair net balances)
//   4. Users can settle debts by paying each other
//   5. View overall balance summary or a specific user's balance
//
// Non-Functional:
//   - All split types share userId + amount fields → Abstract Class
//   - calculateAmount() logic differs per type → enforced via abstract method
//   - Balances are net (A owes B 100, B owes A 60 → A owes B 40 only)
//
// Out of scope: Groups, currencies, payment gateway, expense categories, history
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENTITIES
// =============================================================================

class User {
    public String id;
    public String name;
    public String email;

    public User(String id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Abstract: ExpenseSplit        (shared userId + amount, enforces calculateAmount)
// Concrete: EqualSplit, PercentageSplit, ExactSplit   IS-A ExpenseSplit
// Entity:   Expense
// Service:  SplitwiseService
//
// Relationships:
//   Expense        HAS-A (Aggregation)   User (paidBy)
//   Expense        HAS-A (Composition)   List<ExpenseSplit>
//   SplitwiseService OWNS                Map<userId, User>
//   SplitwiseService OWNS                List<Expense>
//   SplitwiseService OWNS                owes Map (net balances)
//   EqualSplit / PercentageSplit / ExactSplit  IS-A  ExpenseSplit
//
// Why Abstract Class for ExpenseSplit (not interface)?
//   All split types share TWO fields: userId (who owes) + amount (their share).
//   calculateAmount() must be called uniformly on any split type (polymorphism).
//   Interface cannot hold mutable instance state like `amount`.
//   So: abstract class with shared fields + abstract method = correct choice.
// =============================================================================


// ── ExpenseSplit (Abstract Class) ─────────────────────────────────────────────
// Shared state: userId (who this split belongs to), amount (their calculated share).
// Subclass provides: how the amount is calculated from totalAmount + participant count.

abstract class ExpenseSplit {
    public String userId;
    public double amount; // calculated share — set by calculateAmount()

    public ExpenseSplit(String userId) {
        this.userId = userId;
    }

    // Each split type implements its own calculation logic
    public abstract void calculateAmount(double totalAmount, int numParticipants);
}


// ── EqualSplit ────────────────────────────────────────────────────────────────
// Divides total evenly among all participants.

class EqualSplit extends ExpenseSplit {
    public EqualSplit(String userId) {
        super(userId);
    }

    @Override
    public void calculateAmount(double totalAmount, int numParticipants) {
        this.amount = totalAmount / numParticipants;
    }
}


// ── PercentageSplit ───────────────────────────────────────────────────────────
// Each participant declares their percentage. Must sum to 100 across all splits.

class PercentageSplit extends ExpenseSplit {
    public double percentage;

    public PercentageSplit(String userId, double percentage) {
        super(userId);
        this.percentage = percentage;
    }

    @Override
    public void calculateAmount(double totalAmount, int numParticipants) {
        this.amount = (totalAmount * percentage) / 100.0;
    }
}


// ── ExactSplit ────────────────────────────────────────────────────────────────
// Each participant declares their exact share. Must sum to totalAmount.

class ExactSplit extends ExpenseSplit {
    public ExactSplit(String userId, double exactAmount) {
        super(userId);
        this.amount = exactAmount; // already known — calculateAmount is a no-op
    }

    @Override
    public void calculateAmount(double totalAmount, int numParticipants) {
        // amount is set directly in constructor — nothing to calculate
    }
}


// ── Expense ───────────────────────────────────────────────────────────────────

class Expense {
    public String id;
    public String description;
    public double amount;
    public String paidByUserId;
    public List<ExpenseSplit> splits;
    public Date createdAt;

    public Expense(String id, String description, double amount,
                   String paidByUserId, List<ExpenseSplit> splits) {
        this.id = id;
        this.description = description;
        this.amount = amount;
        this.paidByUserId = paidByUserId;
        this.splits = splits;
        this.createdAt = new Date();
    }
}


// ── SplitwiseService ──────────────────────────────────────────────────────────
// Orchestrates all operations: add expense, settle, track net balances.
//
// Balance structure:
//   owes.get(A).get(B) = amount A owes B  (positive only)
//   Net: if A owes B and B owes A, they are netted down to one direction.
//
// updateOwes(owerId, creditorId, amount):
//   Checks if creditorId already owes owerId → offsets first, then records remainder.

class SplitwiseService {
    private Map<String, User> users;
    private List<Expense> expenses;
    private Map<String, Map<String, Double>> owes; // owes[A][B] = A owes B
    private int expenseCounter;

    public SplitwiseService() {
        this.users = new HashMap<>();
        this.expenses = new ArrayList<>();
        this.owes = new HashMap<>();
        this.expenseCounter = 0;
    }

    public void addUser(User user) {
        users.put(user.id, user);
        owes.put(user.id, new HashMap<>());
    }

    // Add an expense — calculates each split's share, then updates balances
    public void addExpense(String description, double amount,
                           String paidByUserId, List<ExpenseSplit> splits) {
        validateSplits(amount, splits);

        int numParticipants = splits.size();
        for (ExpenseSplit split : splits) {
            split.calculateAmount(amount, numParticipants);
        }

        Expense expense = new Expense("EXP-" + (++expenseCounter), description, amount,
                paidByUserId, splits);
        expenses.add(expense);

        // For every split that is NOT the payer, that person owes the payer
        for (ExpenseSplit split : splits) {
            if (!split.userId.equals(paidByUserId)) {
                updateOwes(split.userId, paidByUserId, split.amount);
            }
        }

        System.out.println("[EXPENSE] " + description + " | Total: ₹" + (int) amount +
                " | Paid by: " + userName(paidByUserId));
        for (ExpenseSplit split : splits) {
            if (!split.userId.equals(paidByUserId)) {
                System.out.println("  " + userName(split.userId) + " owes " +
                        userName(paidByUserId) + " ₹" + String.format("%.2f", split.amount));
            }
        }
    }

    // Settle: fromUser pays toUser, reducing fromUser's debt to toUser
    public void settle(String fromUserId, String toUserId, double amount) {
        double currentOwed = getOwed(fromUserId, toUserId);
        if (currentOwed < 0.01) {
            System.out.println("[SETTLE] " + userName(fromUserId) +
                    " does not owe " + userName(toUserId) + " anything");
            return;
        }
        double settleAmount = Math.min(amount, currentOwed);
        updateOwes(fromUserId, toUserId, -settleAmount);
        System.out.println("[SETTLE] " + userName(fromUserId) + " paid " +
                userName(toUserId) + " ₹" + (int) settleAmount);
    }

    public void printBalances() {
        System.out.println("\n── Balance Summary ──");
        boolean anyDebt = false;
        for (String owerId : owes.keySet()) {
            for (Map.Entry<String, Double> entry : owes.get(owerId).entrySet()) {
                if (entry.getValue() > 0.01) {
                    System.out.println("  " + userName(owerId) + " owes " +
                            userName(entry.getKey()) + " ₹" + String.format("%.2f", entry.getValue()));
                    anyDebt = true;
                }
            }
        }
        if (!anyDebt) System.out.println("  All settled up!");
        System.out.println();
    }

    public void printUserBalance(String userId) {
        System.out.println("\n── Balance for " + userName(userId) + " ──");
        boolean anyDebt = false;
        // What this user owes others
        for (Map.Entry<String, Double> entry : owes.get(userId).entrySet()) {
            if (entry.getValue() > 0.01) {
                System.out.println("  Owes " + userName(entry.getKey()) +
                        ": ₹" + String.format("%.2f", entry.getValue()));
                anyDebt = true;
            }
        }
        // What others owe this user
        for (String otherId : owes.keySet()) {
            if (!otherId.equals(userId)) {
                double amount = getOwed(otherId, userId);
                if (amount > 0.01) {
                    System.out.println("  " + userName(otherId) +
                            " owes them: ₹" + String.format("%.2f", amount));
                    anyDebt = true;
                }
            }
        }
        if (!anyDebt) System.out.println("  All settled up!");
        System.out.println();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    // Net balance update: if creditor already owes ower, offset first
    private void updateOwes(String owerId, String creditorId, double delta) {
        double currentOwed = getOwed(owerId, creditorId);   // owerId → creditorId
        double reverseOwed = getOwed(creditorId, owerId);   // creditorId → owerId

        double netDelta = delta;

        if (reverseOwed > 0.01 && delta > 0) {
            // creditorId already owes owerId — net them out
            if (delta <= reverseOwed) {
                setOwed(creditorId, owerId, reverseOwed - delta);
                return;
            } else {
                setOwed(creditorId, owerId, 0);
                netDelta = delta - reverseOwed;
            }
        }

        setOwed(owerId, creditorId, Math.max(0, currentOwed + netDelta));
    }

    private double getOwed(String owerId, String creditorId) {
        return owes.getOrDefault(owerId, new HashMap<>()).getOrDefault(creditorId, 0.0);
    }

    private void setOwed(String owerId, String creditorId, double amount) {
        owes.putIfAbsent(owerId, new HashMap<>());
        owes.get(owerId).put(creditorId, amount);
    }

    private String userName(String userId) {
        User user = users.get(userId);
        return user != null ? user.name : userId;
    }

    private void validateSplits(double totalAmount, List<ExpenseSplit> splits) {
        // Check percentage splits sum to 100
        double percentageSum = 0;
        double exactSum = 0;
        boolean hasPercentage = false;
        boolean hasExact = false;

        for (ExpenseSplit split : splits) {
            if (split instanceof PercentageSplit) {
                percentageSum += ((PercentageSplit) split).percentage;
                hasPercentage = true;
            } else if (split instanceof ExactSplit) {
                exactSum += split.amount;
                hasExact = true;
            }
        }
        if (hasPercentage && Math.abs(percentageSum - 100.0) > 0.01) {
            throw new RuntimeException("Percentage splits must sum to 100. Got: " + percentageSum);
        }
        if (hasExact && Math.abs(exactSum - totalAmount) > 0.01) {
            throw new RuntimeException("Exact splits must sum to total amount " + totalAmount + ". Got: " + exactSum);
        }
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: SplitwiseSolution.java
// =============================================================================

public class SplitwiseSolution {
    public static void main(String[] args) {
        System.out.println("=== Splitwise Demo ===\n");

        SplitwiseService service = new SplitwiseService();

        User alice   = new User("U1", "Alice", "alice@email.com");
        User bob     = new User("U2", "Bob",   "bob@email.com");
        User charlie = new User("U3", "Charlie", "charlie@email.com");

        service.addUser(alice);
        service.addUser(bob);
        service.addUser(charlie);

        // ── Equal Split ───────────────────────────────────────────────────────
        // Alice pays ₹300 for dinner, split equally among all three
        // Each owes ₹100. Bob and Charlie owe Alice ₹100 each.
        System.out.println("── Equal Split ──");
        service.addExpense("Dinner", 300, "U1",
                new ArrayList<>(Arrays.asList(
                        new EqualSplit("U1"),
                        new EqualSplit("U2"),
                        new EqualSplit("U3")
                )));

        service.printBalances();

        // ── Percentage Split ──────────────────────────────────────────────────
        // Bob pays ₹200 for movie tickets, split 50/30/20 (Alice/Bob/Charlie)
        // Alice owes Bob ₹100, Charlie owes Bob ₹40. Bob's share ₹60 is his own.
        System.out.println("── Percentage Split ──");
        service.addExpense("Movie Tickets", 200, "U2",
                new ArrayList<>(Arrays.asList(
                        new PercentageSplit("U1", 50),
                        new PercentageSplit("U2", 30),
                        new PercentageSplit("U3", 20)
                )));

        service.printBalances();

        // ── Exact Split ───────────────────────────────────────────────────────
        // Charlie pays ₹150 for snacks: Alice ₹80, Bob ₹70
        System.out.println("── Exact Split ──");
        service.addExpense("Snacks", 150, "U3",
                new ArrayList<>(Arrays.asList(
                        new ExactSplit("U1", 80),
                        new ExactSplit("U2", 70)
                )));

        service.printBalances();

        // ── Individual balance view ───────────────────────────────────────────
        service.printUserBalance("U1"); // Alice
        service.printUserBalance("U2"); // Bob

        // ── Settlement ────────────────────────────────────────────────────────
        System.out.println("── Bob settles with Alice ──");
        service.settle("U2", "U1", 100); // Bob pays Alice ₹100
        service.printBalances();

        // Settle remaining
        System.out.println("── Charlie settles fully ──");
        service.settle("U3", "U1", 100); // Charlie pays Alice ₹100
        service.settle("U3", "U2", 40);  // Charlie pays Bob ₹40
        service.printBalances();

        // ── Validation error: percentages don't add to 100 ───────────────────
        System.out.println("── Validation: bad percentage split ──");
        try {
            service.addExpense("Bad split", 100, "U1",
                    new ArrayList<>(Arrays.asList(
                            new PercentageSplit("U1", 60),
                            new PercentageSplit("U2", 20) // 80 total, not 100
                    )));
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }

        // ── Validation error: exact amounts don't sum to total ────────────────
        System.out.println("\n── Validation: bad exact split ──");
        try {
            service.addExpense("Bad exact", 100, "U1",
                    new ArrayList<>(Arrays.asList(
                            new ExactSplit("U2", 40),
                            new ExactSplit("U3", 40) // 80 total, not 100
                    )));
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
    }
}
