// =============================================================================
// LLD: FB LIVE COMMENTS (Facebook Live / YouTube Live style)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Host starts a live stream
//   2. Viewers join and leave the stream
//   3. Viewers post comments in real time
//   4. All active viewers receive every new comment (fan-out)
//   5. Multiple consumers receive comments: UI display, moderation, analytics
//   6. Host can end the stream — all listeners notified
//   7. Query recent N comments from a stream
//
// Non-Functional:
//   - Observer pattern: adding a new comment consumer requires zero changes
//     to the ingestion logic
//   - Fan-out is O(listeners) per comment — acceptable for in-memory LLD
//
// Out of scope: real-time WebSocket delivery, comment pinning, likes on comments,
//   banning users, replay after stream ends, comment pagination
//
// KEY INSIGHT — Observer fan-out:
//   postComment() → for each listener → listener.onComment(comment)
//   New consumer (e.g. ML toxicity checker) = implement CommentListener + subscribe().
//   Zero changes to LiveCommentService.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum StreamStatus { SCHEDULED, LIVE, ENDED }


// =============================================================================
// ENTITIES
// =============================================================================

// ── LiveStream ────────────────────────────────────────────────────────────────
class LiveStream {
    public String       id;
    public String       hostId;
    public String       title;
    public StreamStatus status;
    public long         startedAt;
    public long         endedAt;

    public LiveStream(String id, String hostId, String title) {
        this.id      = id;
        this.hostId  = hostId;
        this.title   = title;
        this.status  = StreamStatus.SCHEDULED;
    }

    @Override
    public String toString() {
        return String.format("Stream[%s | \"%s\" | host=%s | %s]",
                id, title, hostId, status);
    }
}

// ── LiveComment ───────────────────────────────────────────────────────────────
// Immutable once posted — append-only log.
class LiveComment {
    public String id;
    public String streamId;
    public String authorId;
    public String content;
    public long   timestamp;

    public LiveComment(String id, String streamId, String authorId, String content) {
        this.id        = id;
        this.streamId  = streamId;
        this.authorId  = authorId;
        this.content   = content;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("[%s] %s: \"%s\"", id, authorId, content);
    }
}


// =============================================================================
// OBSERVER PATTERN — CommentListener
// =============================================================================
// Any team can subscribe to live comments without modifying LiveCommentService.

interface CommentListener {
    void onComment(LiveComment comment);
    void onStreamEnd(String streamId);
}

// ── ViewerDisplay ─────────────────────────────────────────────────────────────
// What the viewer sees on screen. Each viewer has their own display instance.
class ViewerDisplay implements CommentListener {
    private String viewerId;

    public ViewerDisplay(String viewerId) { this.viewerId = viewerId; }

    @Override
    public void onComment(LiveComment comment) {
        System.out.println("    [SCREEN:" + viewerId + "] " + comment);
    }

    @Override
    public void onStreamEnd(String streamId) {
        System.out.println("    [SCREEN:" + viewerId + "] Stream ended.");
    }
}

// ── ModerationConsumer ────────────────────────────────────────────────────────
// Flags comments with banned words. Shared across all viewers (one instance).
class ModerationConsumer implements CommentListener {
    private static final List<String> BANNED = Arrays.asList("spam", "hate", "abuse");

    @Override
    public void onComment(LiveComment comment) {
        for (String word : BANNED) {
            if (comment.content.toLowerCase().contains(word)) {
                System.out.println("    [MODERATION] ⚠ Flagged " + comment.id
                        + ": contains \"" + word + "\"");
                return;
            }
        }
    }

    @Override
    public void onStreamEnd(String streamId) { /* cleanup moderation state if needed */ }
}

// ── AnalyticsConsumer ─────────────────────────────────────────────────────────
// Counts comments per stream for engagement metrics.
class AnalyticsConsumer implements CommentListener {
    private Map<String, Integer> commentCount = new HashMap<>();

    @Override
    public void onComment(LiveComment comment) {
        commentCount.merge(comment.streamId, 1, Integer::sum);
    }

    @Override
    public void onStreamEnd(String streamId) {
        System.out.println("    [ANALYTICS] Stream " + streamId
                + " ended with " + commentCount.getOrDefault(streamId, 0) + " comments");
    }

    public int getCommentCount(String streamId) {
        return commentCount.getOrDefault(streamId, 0);
    }
}


// =============================================================================
// LIVE COMMENT SERVICE
// =============================================================================
// Central service managing stream lifecycle, viewer joins, and comment fan-out.
//
// Data structures:
//   streams:   Map<streamId, LiveStream>
//   comments:  Map<streamId, List<LiveComment>>   — append-only comment log
//   viewers:   Map<streamId, Set<userId>>          — current active viewers
//   listeners: Map<streamId, List<CommentListener>>— Observer subscriptions

class LiveCommentService {
    private Map<String, LiveStream>              streams   = new HashMap<>();
    private Map<String, List<LiveComment>>       comments  = new HashMap<>();
    private Map<String, Set<String>>             viewers   = new HashMap<>();
    private Map<String, List<CommentListener>>   listeners = new HashMap<>();

    private int streamCounter  = 0;
    private int commentCounter = 0;

    // ── Stream lifecycle ──────────────────────────────────────────────────────

    public LiveStream startStream(String hostId, String title) {
        String     streamId = "STR-" + (++streamCounter);
        LiveStream stream   = new LiveStream(streamId, hostId, title);
        stream.status    = StreamStatus.LIVE;
        stream.startedAt = System.currentTimeMillis();

        streams.put(streamId, stream);
        comments.put(streamId, new ArrayList<>());
        viewers.put(streamId, new HashSet<>());
        listeners.put(streamId, new ArrayList<>());

        System.out.println("[STREAM] " + hostId + " started: \"" + title
                + "\" [" + streamId + "]");
        return stream;
    }

    public void endStream(String streamId) {
        LiveStream stream = streams.get(streamId);
        if (stream == null || stream.status != StreamStatus.LIVE) return;

        stream.status  = StreamStatus.ENDED;
        stream.endedAt = System.currentTimeMillis();

        // Notify all listeners stream has ended
        for (CommentListener listener : listeners.getOrDefault(streamId, new ArrayList<>())) {
            listener.onStreamEnd(streamId);
        }
        System.out.println("[STREAM] " + streamId + " ENDED | total comments: "
                + comments.get(streamId).size());
    }

    // ── Viewer management ─────────────────────────────────────────────────────

    // Each viewer registers their own display listener on join.
    // Shared listeners (moderation, analytics) can be added separately via subscribe().
    public void joinStream(String userId, String streamId, CommentListener display) {
        LiveStream stream = streams.get(streamId);
        if (stream == null || stream.status != StreamStatus.LIVE)
            throw new RuntimeException("Stream not live");

        viewers.get(streamId).add(userId);
        listeners.get(streamId).add(display);

        System.out.println("[VIEWER] " + userId + " joined " + streamId
                + " | total viewers: " + viewers.get(streamId).size());
    }

    public void leaveStream(String userId, String streamId) {
        viewers.getOrDefault(streamId, new HashSet<>()).remove(userId);
        System.out.println("[VIEWER] " + userId + " left " + streamId
                + " | total viewers: " + viewers.get(streamId).size());
    }

    // Subscribe a shared listener (moderation, analytics) — not tied to a specific viewer
    public void subscribe(String streamId, CommentListener listener) {
        listeners.getOrDefault(streamId, new ArrayList<>()).add(listener);
    }

    // ── Comment posting ───────────────────────────────────────────────────────
    // Fan-out: one comment → all listeners notified synchronously.

    public LiveComment postComment(String authorId, String streamId, String content) {
        LiveStream stream = streams.get(streamId);
        if (stream == null || stream.status != StreamStatus.LIVE)
            throw new RuntimeException("Cannot comment on a stream that is not live");
        if (!viewers.get(streamId).contains(authorId))
            throw new RuntimeException("Must join stream before commenting: " + authorId);

        String      commentId = "CMT-" + (++commentCounter);
        LiveComment comment   = new LiveComment(commentId, streamId, authorId, content);
        comments.get(streamId).add(comment);

        System.out.println("[COMMENT] " + authorId + ": \"" + content + "\"");

        // Fan-out to all subscribed listeners
        for (CommentListener listener : listeners.get(streamId)) {
            listener.onComment(comment);
        }

        return comment;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<LiveComment> getRecentComments(String streamId, int limit) {
        List<LiveComment> all  = comments.getOrDefault(streamId, new ArrayList<>());
        int               from = Math.max(0, all.size() - limit);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    public int getViewerCount(String streamId) {
        return viewers.getOrDefault(streamId, new HashSet<>()).size();
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class FBLiveCommentsSolution {
    public static void main(String[] args) {
        System.out.println("=== FB Live Comments Demo ===\n");

        LiveCommentService service = new LiveCommentService();

        // Shared consumers — subscribe once, receive all comments on the stream
        ModerationConsumer moderation = new ModerationConsumer();
        AnalyticsConsumer  analytics  = new AnalyticsConsumer();

        // ── Host starts a stream ───────────────────────────────────────────────
        LiveStream stream   = service.startStream("HOST-1", "Cooking with Alice");
        String     streamId = stream.id;

        // Subscribe shared listeners to the stream
        service.subscribe(streamId, moderation);
        service.subscribe(streamId, analytics);
        System.out.println();

        // ── Viewers join with their own display ───────────────────────────────
        System.out.println("── Viewers Joining ──");
        service.joinStream("U2", streamId, new ViewerDisplay("U2")); // Bob
        service.joinStream("U3", streamId, new ViewerDisplay("U3")); // Charlie
        service.joinStream("U4", streamId, new ViewerDisplay("U4")); // Diana
        System.out.println();

        // ── Comments flow — fan-out to all listeners ──────────────────────────
        System.out.println("── Live Comments ──");
        service.postComment("U2", streamId, "This looks delicious!");
        System.out.println();

        service.postComment("U3", streamId, "What's the recipe?");
        System.out.println();

        service.postComment("U4", streamId, "This is spam!"); // triggers moderation
        System.out.println();

        service.postComment("U2", streamId, "I hate this show!"); // triggers moderation
        System.out.println();

        // ── Viewer leaves ─────────────────────────────────────────────────────
        System.out.println("── Viewer Leaves ──");
        service.leaveStream("U3", streamId);
        System.out.println();

        // ── New consumer added at runtime (no code change in service) ─────────
        System.out.println("── Adding new consumer at runtime ──");
        service.subscribe(streamId, comment ->
                System.out.println("    [ML-TOXICITY] Analysing: \"" + comment.content + "\""));

        service.postComment("U4", streamId, "Now ML checks this too!");
        System.out.println();

        // ── Recent comments query ─────────────────────────────────────────────
        System.out.println("── Recent 3 Comments ──");
        service.getRecentComments(streamId, 3).forEach(System.out::println);
        System.out.println();

        // ── Stream ends — all listeners notified ──────────────────────────────
        System.out.println("── Stream Ends ──");
        service.endStream(streamId);
        System.out.println();

        // ── Analytics summary ─────────────────────────────────────────────────
        System.out.println("── Analytics ──");
        System.out.println("Total comments on " + streamId + ": "
                + analytics.getCommentCount(streamId));
    }
}
