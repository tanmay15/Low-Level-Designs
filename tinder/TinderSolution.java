// =============================================================================
// LLD: TINDER (Dating App)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Register user profiles (name, age, gender, bio)
//   2. Swipe LEFT (pass) or RIGHT (like) on another user
//   3. If both users swipe RIGHT on each other → it's a Match
//   4. View all matches for a user
//   5. Get potential profiles (users not yet swiped on)
//
// Non-Functional:
//   - Match detection is O(1): check existing swipe map on the target
//   - No duplicate matches: check before creating
//
// Out of scope: messaging between matched users (→ WhatsApp LLD),
//   geolocation filtering, age/preference filters, premium features,
//   profile photo storage, undo swipe
//
// KEY INSIGHT — bidirectional match detection:
//   When U1 swipes RIGHT on U2:
//     check swipes[U2][U1] == RIGHT → if yes → MATCH
//   Data structure: Map<swiperId, Map<targetId, SwipeDirection>>
//   This gives O(1) match detection without scanning any list.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum SwipeDirection { LEFT, RIGHT }
enum Gender         { MALE, FEMALE, NON_BINARY }


// =============================================================================
// ENTITIES
// =============================================================================

// ── UserProfile ───────────────────────────────────────────────────────────────
class UserProfile {
    public String       id;
    public String       name;
    public int          age;
    public Gender       gender;
    public String       bio;
    public List<String> photos; // photo URLs

    public UserProfile(String id, String name, int age, Gender gender, String bio) {
        this.id     = id;
        this.name   = name;
        this.age    = age;
        this.gender = gender;
        this.bio    = bio;
        this.photos = new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("User[%s | %s | age=%d | %s | \"%s\"]",
                id, name, age, gender, bio);
    }
}

// ── Swipe ─────────────────────────────────────────────────────────────────────
// Immutable record. The swipes map gives O(1) lookup; this entity serves as
// an audit log if needed.
class Swipe {
    public String         swiperId;
    public String         targetId;
    public SwipeDirection direction;
    public long           timestamp;

    public Swipe(String swiperId, String targetId, SwipeDirection direction) {
        this.swiperId  = swiperId;
        this.targetId  = targetId;
        this.direction = direction;
        this.timestamp = System.currentTimeMillis();
    }
}

// ── Match ─────────────────────────────────────────────────────────────────────
// Created exactly once when both users right-swipe each other.
class Match {
    public String id;
    public String user1Id;
    public String user2Id;
    public long   matchedAt;

    public Match(String id, String user1Id, String user2Id) {
        this.id        = id;
        this.user1Id   = user1Id;
        this.user2Id   = user2Id;
        this.matchedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Match[%s ❤ %s]", user1Id, user2Id);
    }
}


// =============================================================================
// TINDER SERVICE
// =============================================================================
// Owns all state: users, swipe graph, matches.
//
// Core data structures:
//   swipes:     Map<swiperId, Map<targetId, SwipeDirection>> — the swipe graph
//   matches:    Map<matchId, Match>
//   userMatches:Map<userId, List<matchId>>  — for fast match retrieval per user

class TinderService {
    private Map<String, UserProfile>              users       = new HashMap<>();
    private Map<String, Map<String, SwipeDirection>> swipes   = new HashMap<>();
    private Map<String, Match>                    matches     = new HashMap<>();
    private Map<String, List<String>>             userMatches = new HashMap<>();
    private int matchCounter = 0;

    // ── Registration ──────────────────────────────────────────────────────────

    public void addUser(UserProfile user) {
        users.put(user.id, user);
        swipes.put(user.id, new HashMap<>());
        userMatches.put(user.id, new ArrayList<>());
        System.out.println("[TINDER] Registered: " + user);
    }

    // ── Swipe ─────────────────────────────────────────────────────────────────
    // Returns a Match object if it's a mutual right-swipe, null otherwise.

    public Match swipe(String swiperId, String targetId, SwipeDirection direction) {
        if (!users.containsKey(swiperId) || !users.containsKey(targetId))
            throw new RuntimeException("User not found");
        if (swiperId.equals(targetId))
            throw new RuntimeException("Cannot swipe on yourself");

        // Record the swipe (overwrite if already swiped — e.g. reconsider)
        swipes.get(swiperId).put(targetId, direction);

        String swiperName = users.get(swiperId).name;
        String targetName = users.get(targetId).name;
        System.out.println("[SWIPE] " + swiperName + " ─" + direction + "→ " + targetName);

        // Match check: only on RIGHT swipe
        if (direction == SwipeDirection.RIGHT) {
            SwipeDirection theirSwipe = swipes.get(targetId).get(swiperId);
            if (theirSwipe == SwipeDirection.RIGHT) {
                return createMatch(swiperId, targetId);
            }
        }

        return null; // no match yet
    }

    private Match createMatch(String user1Id, String user2Id) {
        // Guard: avoid duplicate match if somehow called twice
        for (String matchId : userMatches.get(user1Id)) {
            Match m = matches.get(matchId);
            if ((m.user1Id.equals(user1Id) && m.user2Id.equals(user2Id)) ||
                (m.user1Id.equals(user2Id) && m.user2Id.equals(user1Id))) {
                return m; // already matched
            }
        }

        String matchId = "MATCH-" + (++matchCounter);
        Match match = new Match(matchId, user1Id, user2Id);
        matches.put(matchId, match);
        userMatches.get(user1Id).add(matchId);
        userMatches.get(user2Id).add(matchId);

        System.out.println("  ❤ IT'S A MATCH! "
                + users.get(user1Id).name + " & " + users.get(user2Id).name);
        return match;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<Match> getMatches(String userId) {
        List<Match> result = new ArrayList<>();
        for (String matchId : userMatches.getOrDefault(userId, new ArrayList<>())) {
            result.add(matches.get(matchId));
        }
        return result;
    }

    // Users this person hasn't swiped on yet — simple feed candidates
    public List<UserProfile> getPotentialProfiles(String userId, int limit) {
        Map<String, SwipeDirection> alreadySwiped = swipes.getOrDefault(userId, new HashMap<>());
        List<UserProfile> candidates = new ArrayList<>();
        for (UserProfile user : users.values()) {
            if (!user.id.equals(userId) && !alreadySwiped.containsKey(user.id)) {
                candidates.add(user);
                if (candidates.size() >= limit) break;
            }
        }
        return candidates;
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class TinderSolution {
    public static void main(String[] args) {
        System.out.println("=== Tinder Demo ===\n");

        TinderService tinder = new TinderService();

        tinder.addUser(new UserProfile("U1", "Alice",   25, Gender.FEMALE, "Love hiking"));
        tinder.addUser(new UserProfile("U2", "Bob",     27, Gender.MALE,   "Coffee addict"));
        tinder.addUser(new UserProfile("U3", "Charlie", 24, Gender.MALE,   "Movie buff"));
        tinder.addUser(new UserProfile("U4", "Diana",   26, Gender.FEMALE, "Foodie"));
        System.out.println();

        // Scenario 1: mutual RIGHT swipe → match
        System.out.println("── Scenario 1: Mutual Right Swipe ──");
        tinder.swipe("U1", "U2", SwipeDirection.RIGHT); // Alice → Bob: RIGHT
        tinder.swipe("U2", "U1", SwipeDirection.RIGHT); // Bob → Alice: RIGHT → MATCH!
        System.out.println();

        // Scenario 2: one-sided → no match
        System.out.println("── Scenario 2: One-Sided Right Swipe ──");
        tinder.swipe("U3", "U4", SwipeDirection.RIGHT); // Charlie → Diana: RIGHT
        tinder.swipe("U4", "U3", SwipeDirection.LEFT);  // Diana → Charlie: LEFT → no match
        System.out.println();

        // Scenario 3: target liked swiper first, then swiper likes back → match
        System.out.println("── Scenario 3: Target liked first, swiper responds ──");
        tinder.swipe("U1", "U4", SwipeDirection.RIGHT); // Alice → Diana: RIGHT (no match yet — Diana hasn't swiped)
        tinder.swipe("U4", "U1", SwipeDirection.RIGHT); // Diana → Alice: RIGHT → MATCH!
        System.out.println();

        // Scenario 4: left swipe — no match
        System.out.println("── Scenario 4: Both left swipe ──");
        tinder.swipe("U2", "U3", SwipeDirection.LEFT);
        tinder.swipe("U3", "U2", SwipeDirection.LEFT);
        System.out.println();

        // Query matches
        System.out.println("── Alice's Matches ──");
        tinder.getMatches("U1").forEach(System.out::println);

        System.out.println("\n── Charlie's Potential Profiles ──");
        tinder.getPotentialProfiles("U3", 5).forEach(System.out::println);
    }
}
