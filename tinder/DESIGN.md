# LLD: Tinder (Dating App)

> Implementation: `TinderSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Register user profiles (name, age, gender, bio, photos) |
| 2 | Swipe LEFT (pass) or RIGHT (like) on another user |
| 3 | If both users swipe RIGHT on each other → create a Match |
| 4 | View all matches for a user |
| 5 | Get potential profiles — users not yet swiped on |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Match detection is O(1) — lookup existing swipe in the swipe graph map |
| 2 | No duplicate matches — guard check before creating |

### Out of Scope
Messaging between matched users (see WhatsApp LLD), geolocation filtering, age/preference filters, premium Boost/Super-Like features, photo storage, undo swipe

---

## Step 2 — The Core Insight: Bidirectional Match Detection

When U1 swipes RIGHT on U2, check if U2 already swiped RIGHT on U1:

```
swipes: Map<swiperId, Map<targetId, SwipeDirection>>

U1 swipes RIGHT on U2:
  swipes["U1"]["U2"] = RIGHT
  check swipes["U2"]["U1"] == RIGHT?
    → YES → MATCH
    → NO  → no match yet, wait for U2 to swipe
```

This is O(1) — two map lookups. No scanning, no lists to iterate.

---

## Step 3 — Entities

| Class | Role |
|-------|------|
| `UserProfile` | Account with name, age, gender, bio, photos |
| `Swipe` | Immutable record of one swipe — audit log |
| `Match` | Created when both users right-swipe each other. Immutable once created. |
| `TinderService` | Owns all state: user registry, swipe graph, matches |

### Enums

| Enum | Values |
|------|--------|
| `SwipeDirection` | LEFT, RIGHT |
| `Gender` | MALE, FEMALE, NON\_BINARY |

---

## Step 4 — Data Structures

```
users:       Map<userId, UserProfile>                     // O(1) user lookup
swipes:      Map<swiperId, Map<targetId, SwipeDirection>> // the swipe graph
matches:     Map<matchId, Match>                          // all matches
userMatches: Map<userId, List<matchId>>                   // O(1) match lookup per user
```

### Why nested Map for swipes?

Alternative — store `Swipe` objects in a list:
```java
List<Swipe> allSwipes  // to find if U2 swiped U1: O(n) scan
```

Nested map:
```java
swipes.get("U2").get("U1")  // O(1) — direct lookup
```

Nested map is the right choice whenever you need "given two IDs, what's the relationship between them?"

---

## Step 5 — swipe() Method Logic

```java
public Match swipe(String swiperId, String targetId, SwipeDirection direction) {
    swipes.get(swiperId).put(targetId, direction);  // record this swipe

    if (direction == SwipeDirection.RIGHT) {
        SwipeDirection theirSwipe = swipes.get(targetId).get(swiperId);
        if (theirSwipe == SwipeDirection.RIGHT) {
            return createMatch(swiperId, targetId);  // mutual → MATCH
        }
    }
    return null; // no match yet
}
```

Note: only check for match on RIGHT swipe. A LEFT swipe can never create a match.

---

## Step 6 — Class Attributes & Methods

### `UserProfile`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | U1, U2, ... |
| `name`, `age`, `gender`, `bio` | various | profile data |
| `photos` | List\<String\> | photo URLs |

### `Match`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | MATCH-N |
| `user1Id`, `user2Id` | String | the two matched users |
| `matchedAt` | long | epoch ms |

### `TinderService`

| Method | Description |
|--------|-------------|
| `addUser(user)` | register profile, init swipe map and match list |
| `swipe(swiperId, targetId, direction)` | record swipe, check for match, return Match or null |
| `getMatches(userId)` | return all Match objects for this user |
| `getPotentialProfiles(userId, limit)` | users not yet swiped on by this user |

---

## Step 7 — Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| `Map<swiperId, Map<targetId, SwipeDirection>>` | Yes | O(1) match detection — no list scan |
| `userMatches: Map<userId, List<matchId>>` | Yes | O(1) "get all matches for user" — no scan of all matches |
| Match dedup guard in `createMatch()` | Yes | Prevents duplicate Match objects if `swipe()` called twice |
| `Swipe` entity kept separate from map | Yes | Audit log — you can query "when did U1 swipe U2?" |
| `getPotentialProfiles` excludes already-swiped | Yes | Don't show profiles already decided on |

---

## Step 8 — How This Compares to Other Problems

| Aspect | Tinder | BookMyShow |
|--------|--------|------------|
| Core entity | UserProfile | ShowSeat |
| Key operation | Mutual swipe detection | Seat locking (concurrency) |
| State machine | No explicit state on User | ShowSeat: AVAILABLE→LOCKED→BOOKED |
| Key data structure | Nested Map (swipe graph) | Map<seatId, ShowSeat> |
| Pattern | None (simple service) | Concurrency (synchronized) |

---

## Step 9 — Extensibility

| Extension | How |
|-----------|-----|
| Messaging after match | On `createMatch()`: create a `Chat` in `WhatsAppService` |
| Geolocation filtering | Add `latitude`, `longitude` on `UserProfile`; filter in `getPotentialProfiles` by distance |
| Super Like | Add `SUPER_LIKE` to `SwipeDirection`; creates a notification for the target |
| Preference filters | Add `preferredGender`, `ageRange` on `UserProfile`; filter in `getPotentialProfiles` |
| Undo last swipe | Store last swipe per user; `undoSwipe()` removes from swipes map |

---

## Quick Recall — 3 Main Takeaways

1. **Nested Map for swipe graph**: `Map<swiperId, Map<targetId, SwipeDirection>>` gives O(1) match detection. When U1 right-swipes U2, check `swipes.get(U2).get(U1) == RIGHT`.

2. **Match only on RIGHT swipe**: LEFT swipe returns null immediately. Only RIGHT triggers the mutual check.

3. **`userMatches: Map<userId, List<matchId>>`**: Both matched users' lists are updated in `createMatch()`. `getMatches(userId)` is then O(matches) not O(all-matches).
