# LLD: Amazon Locker

> Implementation: `AmazonLockerSolution.java`
> Reference: https://www.hellointerview.com/learn/low-level-design/problem-breakdowns/amazon-locker

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Carrier deposits a package by specifying SIZE → system assigns matching compartment, opens it, returns access code |
| 2 | One access token per package; generated on deposit |
| 3 | Customer retrieves package by entering access token code |
| 4 | Access tokens expire after **7 days** |
| 5 | Expired code → rejected with "Access token has expired" |
| 6 | **Package stays physically in compartment** until staff removes it (even after expiry) |
| 7 | Staff can open all expired compartments → `openExpiredCompartments()` |
| 8 | Staff clears compartment AFTER physically removing package → `clearExpiredDeposit()` |
| 9 | Wrong/already-used code → rejected with "Invalid access token code" |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | O(1) token lookup: `Map<String, AccessToken>` |
| 2 | Each class owns the data it acts on (Information Expert) |

### Out of Scope

| Item | Reason |
|------|--------|
| Multiple locker stations | Not in requirements |
| Package as an entity | Locker only cares about size — package metadata belongs to fulfillment system |
| Size fallback (SMALL → MEDIUM if SMALL full) | Exact match only; fallback is an extensibility discussion |
| How token reaches customer (SMS/email) | Someone else's responsibility |
| Lockout after wrong code attempts | Complexity not needed |
| Payment | Out of scope |

---

## Step 2 — Why Package is NOT an Entity

A common mistake is creating a `Package` class. Ask: "What does the locker system actually need from a package?"

**Only one thing: its size.**

Everything else — order ID, customer name, shipping details — lives in Amazon's fulfillment system. Our locker doesn't need it. So `size` is just an input parameter:

```java
// ❌ Over-modelled:
locker.deposit(new Package("PKG-1", "ORD-101", PackageSize.MEDIUM, "Running shoes"));

// ✅ Correct:
locker.depositPackage(Size.MEDIUM);
```

This is the Information Expert principle: don't model entities that don't have behaviour or state relevant to your system.

---

## Step 3 — Entities

### Final Entity Set

| Entity | Responsibility |
|--------|---------------|
| `Compartment` | Physical locker slot. Owns its own occupied state. |
| `AccessToken` | Bearer token: code + expiration + compartment reference. Owns expiry logic. |
| `Locker` | Orchestrator. Finds compartments, maps tokens, validates pickup, exposes staff operations. |

### Why AccessToken is its own class (not just a String)

An access token is not just a code — it has:
- An expiration timestamp (7-day TTL)
- A reference to the specific compartment it unlocks

That's a concept worth modelling. If it were just a String, expiry logic and compartment lookup would leak into `Locker`. Giving it its own class keeps each class's responsibility focused.

---

## Step 4 — State on Compartment (not in Locker)

`Compartment` tracks `occupied: boolean` itself.

**Why not `Set<Compartment> occupiedCompartments` in `Locker`?**

Occupied/free is a physical condition intrinsic to the compartment — it either contains a package or it doesn't. Physical state belongs on the entity.

Relational state (e.g. "assigned to this user") would belong in the orchestrator.

---

## Step 5 — The Critical Expiry Insight

| Situation | Token | Compartment |
|-----------|-------|------------|
| Package deposited | Valid | OCCUPIED |
| Customer picks up (valid code) | Removed from map | FREE |
| 7 days pass, not picked up | Expired (stays in map) | Still OCCUPIED |
| Customer tries expired code | Rejected | Still OCCUPIED |
| Staff calls `openExpiredCompartments()` | Still in map | Door opens physically |
| Staff calls `clearExpiredDeposit()` | Removed from map | FREE |

**Why does the expired token stay in the map?**

If you delete it immediately on expiry, a customer who tries their code gets "Invalid code" — they don't know if they mistyped or if their code expired. Keeping the expired token lets you give a more specific and actionable error message: "Access token has expired."

**Why does `openExpiredCompartments()` NOT free the compartment?**

The package is physically still inside the compartment. Marking it free without removing the package would let you assign it to a new deposit while the old package is still there. Only `clearExpiredDeposit()` (called after staff physically removes the package) marks the compartment free.

---

## Step 6 — Class Attributes & Methods

### `Compartment`

| Member | Type | Description |
|--------|------|-------------|
| `id`, `size` | String, Size | identifier and size |
| `occupied` (private) | boolean | physical state |
| `isOccupied()` | boolean | read state |
| `markOccupied()` | void | set occupied = true |
| `markFree()` | void | set occupied = false |
| `open()` | void | send signal to hardware (simulated) |

### `AccessToken`

| Member | Type | Description |
|--------|------|-------------|
| `code` (private) | String | 6-digit code |
| `expirationMs` (private) | long | epoch millis |
| `compartment` (private) | Compartment | which compartment this unlocks |
| `isExpired()` | boolean | `now > expirationMs` |
| `getCompartment()` | Compartment | reference to the compartment |
| `getCode()` | String | the code string |

### `Locker`

| Method | Description |
|--------|-------------|
| `depositPackage(size)` | Find compartment → open → mark occupied → generate token → return code |
| `pickup(tokenCode)` | Validate code, check expiry, open compartment, call `clearDeposit()` |
| `openExpiredCompartments()` | Staff: open all compartments with expired tokens (don't free them) |
| `clearExpiredDeposit(tokenCode)` | Staff: free compartment + remove token after manual package removal |
| `getAvailableCompartment(size)` | Linear scan for exact size match |
| `generateAccessToken(compartment, expiryMs)` | 6-digit unique code, 7-day expiry |
| `clearDeposit(token)` | Free compartment + remove token (called on successful pickup only) |

---

## Step 7 — Error Handling

| Scenario | Error message | Why |
|----------|--------------|-----|
| Wrong code / never existed | "Invalid access token code" | Same message as already-used → no info leak |
| Already used code | "Invalid access token code" | Token removed from map on pickup → looks invalid |
| Expired code | "Access token has expired" | Different, specific — tells user to contact support |
| No compartment of requested size | "No available compartment of size X" | Clear actionable error for carrier |

---

## Step 8 — Extensibility

| Extension | How |
|-----------|-----|
| **Size fallback** (MEDIUM package → LARGE if MEDIUM full) | Change `getAvailableCompartment()` to iterate sizes from requested upward |
| **Compartment maintenance mode** | Add `CompartmentStatus` enum (AVAILABLE, OCCUPIED, OUT\_OF\_SERVICE), replace `occupied` boolean |
| **Two-phase deposit** (verify package is physically present before generating token) | Add `reserveCompartment(size) → reservationId` and `confirmDeposit(reservationId) → code`. Adds RESERVED state. |
| **Multiple locker stations** | Add `LockerStation` class, `Map<stationId, Locker>` in a `LockerNetwork` |
| **Indexed O(1) availability** | Replace linear scan with `Map<Size, Queue<Compartment>>` — dequeue on deposit, enqueue on pickup |

---

## Quick Recall — 3 Main Takeaways

1. **Package is not an entity.** Size is just an input. Locker doesn't care about order IDs or customer names.

2. **Expiry ≠ free compartment.** Token becomes invalid but package is physically still inside. `openExpiredCompartments()` lets staff see which ones to clear. `clearExpiredDeposit()` is what actually frees the compartment.

3. **Same error message for wrong code and already-used code.** Once a package is picked up, the token is deleted from the map — it's indistinguishable from a wrong code. Only expired codes get a different, specific message.
