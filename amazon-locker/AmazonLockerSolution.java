// =============================================================================
// LLD: AMAZON LOCKER  (aligned with hellointerview.com breakdown)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS (from article)
// -----------------------------------------------------------------------------
// Functional:
//   1. Carrier deposits a package by specifying SIZE → system assigns matching
//      compartment, opens it, returns access token code
//   2. One access token per package; generated on deposit
//   3. Customer retrieves package by entering access token code
//      → validates code, checks expiry, opens compartment
//   4. Access tokens expire after 7 days
//      → expired code rejected with clear error ("token has expired")
//      → package stays physically in compartment until staff removes it
//   5. Staff can open all expired compartments → physically retrieve packages
//   6. Invalid codes rejected with specific error messages
//
// Non-Functional:
//   - O(1) token code lookup: Map<String, AccessToken>
//   - Each class has one clear responsibility (Information Expert principle)
//
// Out of scope:
//   - Multiple locker stations (single locker only)
//   - How token reaches the customer (SMS/email — someone else's problem)
//   - Lockout after wrong code attempts
//   - Size fallback (MEDIUM package CANNOT use LARGE compartment — exact match)
//   - Package entity (locker only cares about size, not package metadata)
//   - Payment
//
// KEY DESIGN NOTE vs our previous version:
//   - Package is NOT an entity. Size is just an input parameter to depositPackage().
//   - Expiry does NOT free the compartment. Package is still physically inside.
//     Staff must call openExpiredCompartments() first, then clearExpiredDeposit().
//   - Two-step lifecycle only: deposit → pickup (no ASSIGNED intermediate step)
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum Size { SMALL, MEDIUM, LARGE }


// =============================================================================
// STEP 3 — ENTITIES
// =============================================================================

// ── Compartment ───────────────────────────────────────────────────────────────
// A physical locker slot. Tracks its own physical state (occupied or not).
// Physical state lives on the entity because it describes the entity's condition —
// not a system-managed relationship.

class Compartment {
    public String  id;
    public Size    size;
    private boolean occupied;

    public Compartment(String id, Size size) {
        this.id       = id;
        this.size     = size;
        this.occupied = false;
    }

    public boolean isOccupied()  { return occupied; }
    public void    markOccupied(){ occupied = true; }
    public void    markFree()    { occupied = false; }

    // Simulates sending the "open" signal to the physical locker hardware.
    // Hardware auto-closes after ~30 seconds (out of scope here).
    public void open() {
        System.out.println("  [HARDWARE] Compartment " + id + " (" + size + ") unlocked");
    }
}


// ── AccessToken ───────────────────────────────────────────────────────────────
// A bearer token representing the RIGHT to open a specific compartment.
// Owns the expiration logic (Information Expert: it has the data, so it checks it).
//
// WHY this is its own class and not just a String field on Compartment:
//   An AccessToken is not just a code. It has an expiration timestamp and
//   points to a specific compartment. That's a concept worth modelling.

class AccessToken {
    private String      code;
    private long        expirationMs;   // epoch millis
    private Compartment compartment;

    public AccessToken(String code, long expirationMs, Compartment compartment) {
        this.code          = code;
        this.expirationMs  = expirationMs;
        this.compartment   = compartment;
    }

    public boolean    isExpired()      { return System.currentTimeMillis() > expirationMs; }
    public Compartment getCompartment(){ return compartment; }
    public String     getCode()        { return code; }
}


// =============================================================================
// STEP 4 — LOCKER (Orchestrator)
// =============================================================================
// The Locker is the system's public API. It orchestrates:
//   - Finding available compartments
//   - Generating and mapping access tokens
//   - Validating codes on pickup
//   - Exposing expired compartments to staff
//
// Relationships:
//   Locker HAS-A (Composition) List<Compartment>
//   Locker USES               Map<code, AccessToken>  for O(1) lookup

class Locker {
    private List<Compartment>          compartments;
    private Map<String, AccessToken>   accessTokenMapping;
    private Random                     random;

    // 7 days in milliseconds — per article requirement
    private static final long EXPIRY_MS = 7L * 24 * 60 * 60 * 1000;

    public Locker(List<Compartment> compartments) {
        this.compartments       = compartments;
        this.accessTokenMapping = new HashMap<>();
        this.random             = new Random();
    }

    // ── depositPackage ────────────────────────────────────────────────────────
    // Carrier calls this when they arrive at the locker with a package.
    // Returns the access token code (system sends this to the customer separately).
    //
    // Flow: find compartment → open it → mark occupied → generate token → return code

    public String depositPackage(Size size) {
        Compartment compartment = getAvailableCompartment(size);
        if (compartment == null)
            throw new RuntimeException("No available compartment of size " + size);

        compartment.open();
        compartment.markOccupied();
        AccessToken token = generateAccessToken(compartment, EXPIRY_MS);
        accessTokenMapping.put(token.getCode(), token);

        System.out.println("  [DEPOSIT] Code: " + token.getCode() +
                " | Compartment: " + compartment.id + " (" + size + ")");
        return token.getCode();
    }

    // Overload: for testing/demo — allows custom expiry duration
    public String depositPackage(Size size, long customExpiryMs) {
        Compartment compartment = getAvailableCompartment(size);
        if (compartment == null)
            throw new RuntimeException("No available compartment of size " + size);

        compartment.open();
        compartment.markOccupied();
        AccessToken token = generateAccessToken(compartment, customExpiryMs);
        accessTokenMapping.put(token.getCode(), token);

        System.out.println("  [DEPOSIT] Code: " + token.getCode() +
                " | Compartment: " + compartment.id + " (" + size + ") [custom expiry]");
        return token.getCode();
    }

    // ── pickup ────────────────────────────────────────────────────────────────
    // Customer enters their code at the kiosk.
    // Returns void — the physical compartment door opening IS the feedback.
    //
    // Error cases:
    //   - null/empty code  → "Invalid access token code"
    //   - code not in map  → "Invalid access token code"  (same msg, no info leak)
    //   - code expired     → "Access token has expired"   (different — actionable for user)

    public void pickup(String tokenCode) {
        if (tokenCode == null || tokenCode.isEmpty())
            throw new RuntimeException("Invalid access token code");

        AccessToken token = accessTokenMapping.get(tokenCode);
        if (token == null)
            throw new RuntimeException("Invalid access token code");

        if (token.isExpired())
            throw new RuntimeException("Access token has expired");

        // Valid — open the door and clean up state
        token.getCompartment().open();
        clearDeposit(token);
        System.out.println("  [PICKUP] Package retrieved successfully");
    }

    // ── openExpiredCompartments ───────────────────────────────────────────────
    // STAFF OPERATION: opens all compartments whose tokens have expired.
    // Staff physically removes packages and then calls clearExpiredDeposit() per compartment.
    //
    // IMPORTANT: This does NOT free the compartments.
    //   Physical presence of a package is independent of whether the token is valid.
    //   Staff must physically remove the package before the compartment is freed.

    public void openExpiredCompartments() {
        System.out.println("  [STAFF] Opening all expired compartments...");
        int count = 0;
        for (AccessToken token : accessTokenMapping.values()) {
            if (token.isExpired()) {
                token.getCompartment().open();
                count++;
            }
        }
        if (count == 0) System.out.println("  [STAFF] No expired compartments found.");
    }

    // ── clearExpiredDeposit ───────────────────────────────────────────────────
    // Called by staff AFTER physically removing a package from an expired compartment.
    // This is what actually frees the compartment for future deposits.

    public void clearExpiredDeposit(String tokenCode) {
        AccessToken token = accessTokenMapping.get(tokenCode);
        if (token == null) throw new RuntimeException("Token not found: " + tokenCode);
        if (!token.isExpired()) throw new RuntimeException("Token is not expired: " + tokenCode);

        token.getCompartment().markFree();
        accessTokenMapping.remove(tokenCode);
        System.out.println("  [STAFF] Compartment " + token.getCompartment().id +
                " freed after package removal");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    // Exact size match — MEDIUM package cannot go into LARGE compartment.
    // Size fallback is an extensibility discussion, not the default behaviour.
    private Compartment getAvailableCompartment(Size size) {
        for (Compartment c : compartments) {
            if (c.size == size && !c.isOccupied()) return c;
        }
        return null;
    }

    private AccessToken generateAccessToken(Compartment compartment, long expiryDurationMs) {
        String code;
        do {
            code = String.format("%06d", random.nextInt(1000000));
        } while (accessTokenMapping.containsKey(code)); // ensure uniqueness

        long expiration = System.currentTimeMillis() + expiryDurationMs;
        return new AccessToken(code, expiration, compartment);
    }

    // Frees compartment + removes token — called only after successful customer pickup
    private void clearDeposit(AccessToken token) {
        token.getCompartment().markFree();
        accessTokenMapping.remove(token.getCode());
    }

    public void printStatus() {
        System.out.println("  ── Locker Status ──");
        for (Compartment c : compartments) {
            System.out.printf("  %-6s | %-6s | %s%n",
                    c.id, c.size, c.isOccupied() ? "OCCUPIED" : "AVAILABLE");
        }
    }
}


// =============================================================================
// STEP 5 — DEMO
// =============================================================================

public class AmazonLockerSolution {
    public static void main(String[] args) {
        System.out.println("=== Amazon Locker Demo ===\n");

        // ── Build the locker with compartments ────────────────────────────────
        List<Compartment> compartments = new ArrayList<>();
        compartments.add(new Compartment("S1", Size.SMALL));
        compartments.add(new Compartment("S2", Size.SMALL));
        compartments.add(new Compartment("M1", Size.MEDIUM));
        compartments.add(new Compartment("M2", Size.MEDIUM));
        compartments.add(new Compartment("L1", Size.LARGE));

        Locker locker = new Locker(compartments);

        System.out.println("Initial status:");
        locker.printStatus();
        System.out.println();

        // ── Scenario 1: Normal deposit and pickup ─────────────────────────────
        System.out.println("════ Scenario 1: Normal deposit and pickup ════");
        String code1 = locker.depositPackage(Size.MEDIUM);
        locker.printStatus();
        System.out.println();

        locker.pickup(code1);
        System.out.println("After pickup:");
        locker.printStatus();
        System.out.println();

        // ── Scenario 2: Invalid code ──────────────────────────────────────────
        System.out.println("════ Scenario 2: Invalid and already-used codes ════");
        String code2 = locker.depositPackage(Size.SMALL);

        try {
            locker.pickup("999999"); // random wrong code
        } catch (RuntimeException e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }

        locker.pickup(code2); // valid pickup — removes token from map

        try {
            locker.pickup(code2); // same code again — already removed, looks invalid
        } catch (RuntimeException e) {
            System.out.println("  [ERROR] " + e.getMessage() + " (already used)");
        }
        System.out.println();

        // ── Scenario 3: No compartment of requested size ──────────────────────
        System.out.println("════ Scenario 3: No compartment available (exact size match) ════");

        // Fill both SMALL compartments
        String codeA = locker.depositPackage(Size.SMALL);
        String codeB = locker.depositPackage(Size.SMALL);

        try {
            locker.depositPackage(Size.SMALL); // no SMALL left
        } catch (RuntimeException e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }

        // MEDIUM available but SMALL package cannot use it (exact match)
        System.out.println("  (MEDIUM compartments are free but SMALL package cannot use them)");

        // Free up for next scenario
        locker.pickup(codeA);
        locker.pickup(codeB);
        System.out.println();

        // ── Scenario 4: Expired token ─────────────────────────────────────────
        System.out.println("════ Scenario 4: Expired token + staff operation ════");

        // Deposit with -1ms expiry → already expired immediately
        String expiredCode = locker.depositPackage(Size.LARGE, -1);

        System.out.println();
        System.out.println("Customer tries expired code:");
        try {
            locker.pickup(expiredCode);
        } catch (RuntimeException e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }

        System.out.println();
        System.out.println("Locker status — L1 still OCCUPIED (package physically there):");
        locker.printStatus();

        System.out.println();
        System.out.println("Staff runs openExpiredCompartments():");
        locker.openExpiredCompartments();

        System.out.println("Staff physically removes package, then clears deposit:");
        locker.clearExpiredDeposit(expiredCode);

        System.out.println();
        System.out.println("Locker status — L1 now AVAILABLE:");
        locker.printStatus();

        System.out.println();

        // ── Scenario 5: Multiple deposits at once ─────────────────────────────
        System.out.println("════ Scenario 5: Multiple simultaneous deposits ════");
        String c1 = locker.depositPackage(Size.SMALL);
        String c2 = locker.depositPackage(Size.MEDIUM);
        String c3 = locker.depositPackage(Size.LARGE);

        System.out.println();
        locker.printStatus();

        System.out.println();
        locker.pickup(c1);
        locker.pickup(c2);
        locker.pickup(c3);

        System.out.println();
        System.out.println("Final status (all compartments free):");
        locker.printStatus();
    }
}
