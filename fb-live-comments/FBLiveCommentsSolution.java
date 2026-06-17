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
//   5. Host ends the stream — all viewers notified
//   6. Query recent N comments from a stream
//
// Non-Functional:
//   - Fan-out is O(viewers) per comment
//   - Viewer must join before posting a comment
//
// Out of scope: real-time WebSocket delivery, comment pinning, likes on comments,
//   banning users, stream replay, moderation, analytics
//
// KEY INSIGHT:
//   Viewer IS the observer. postComment() fans out to all viewers via
//   viewer.receiveComment(). No separate listener abstraction needed.
//   In production: receiveComment() would push over a WebSocket connection.
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

// ── Viewer ────────────────────────────────────────────────────────────────────
// A viewer is the observer. receiveComment() simulates a WebSocket push to
// their screen. In production this would be an async push over the network.
class Viewer {
    public String userId;
    public String streamId;
    public long   joinedAt;

    public Viewer(String userId, String streamId) {
        this.userId    = userId;
        this.streamId  = streamId;
        this.joinedAt  = System.currentTimeMillis();
    }

    // Called on every new comment — fan-out target
    public void receiveComment(LiveComment comment) {
        System.out.println("  [SCREEN:" + userId + "] " + comment);
    }

    public void onStreamEnd() {
        System.out.println("  [SCREEN:" + userId + "] Stream has ended.");
    }
}


// =============================================================================
// LIVE COMMENT SERVICE
// =============================================================================

class LiveCommentService {
    private Map<String, LiveStream>       streams        = new HashMap<>();
    private Map<String, List<LiveComment>> comments      = new HashMap<>(); // streamId → comment log
    private Map<String, List<Viewer>>     viewers        = new HashMap<>(); // streamId → active viewers
    private Map<String, Viewer>           viewerIndex    = new HashMap<>(); // userId+streamId → Viewer

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
        viewers.put(streamId, new ArrayList<>());

        System.out.println("[STREAM] " + hostId + " started: \"" + title
                + "\" [" + streamId + "]");
        return stream;
    }

    public void endStream(String streamId) {
        LiveStream stream = streams.get(streamId);
        if (stream == null || stream.status != StreamStatus.LIVE) return;

        stream.status  = StreamStatus.ENDED;
        stream.endedAt = System.currentTimeMillis();

        // Notify all viewers the stream has ended
        for (Viewer viewer : viewers.getOrDefault(streamId, new ArrayList<>())) {
            viewer.onStreamEnd();
        }
        System.out.println("[STREAM] " + streamId + " ENDED | total comments: "
                + comments.get(streamId).size());
    }

    // ── Viewer management ─────────────────────────────────────────────────────

    public Viewer joinStream(String userId, String streamId) {
        LiveStream stream = streams.get(streamId);
        if (stream == null || stream.status != StreamStatus.LIVE)
            throw new RuntimeException("Stream not live");

        String key = userId + ":" + streamId;
        if (viewerIndex.containsKey(key)) return viewerIndex.get(key); // already joined

        Viewer viewer = new Viewer(userId, streamId);
        viewers.get(streamId).add(viewer);
        viewerIndex.put(key, viewer);

        System.out.println("[VIEWER] " + userId + " joined " + streamId
                + " | viewers: " + viewers.get(streamId).size());
        return viewer;
    }

    public void leaveStream(String userId, String streamId) {
        String key    = userId + ":" + streamId;
        Viewer viewer = viewerIndex.remove(key);
        if (viewer != null) {
            viewers.get(streamId).remove(viewer);
            System.out.println("[VIEWER] " + userId + " left " + streamId
                    + " | viewers: " + viewers.get(streamId).size());
        }
    }

    // ── Comment posting — fan-out to all viewers ──────────────────────────────

    public LiveComment postComment(String authorId, String streamId, String content) {
        LiveStream stream = streams.get(streamId);
        if (stream == null || stream.status != StreamStatus.LIVE)
            throw new RuntimeException("Cannot comment on a stream that is not live");

        String key = authorId + ":" + streamId;
        if (!viewerIndex.containsKey(key))
            throw new RuntimeException("Must join stream before commenting: " + authorId);

        String      commentId = "CMT-" + (++commentCounter);
        LiveComment comment   = new LiveComment(commentId, streamId, authorId, content);
        comments.get(streamId).add(comment);

        System.out.println("[COMMENT] " + authorId + ": \"" + content + "\"");

        // Fan-out to all active viewers
        for (Viewer viewer : viewers.get(streamId)) {
            viewer.receiveComment(comment);
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
        return viewers.getOrDefault(streamId, new ArrayList<>()).size();
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class FBLiveCommentsSolution {
    public static void main(String[] args) {
        System.out.println("=== FB Live Comments Demo ===\n");

        LiveCommentService service = new LiveCommentService();

        // ── Host starts a stream ───────────────────────────────────────────────
        LiveStream stream   = service.startStream("HOST-1", "Cooking with Alice");
        String     streamId = stream.id;
        System.out.println();

        // ── Viewers join ───────────────────────────────────────────────────────
        System.out.println("── Viewers Joining ──");
        service.joinStream("U2", streamId); // Bob joins
        service.joinStream("U3", streamId); // Charlie joins
        service.joinStream("U4", streamId); // Diana joins
        System.out.println();

        // ── Comments — fan-out to all viewers ─────────────────────────────────
        System.out.println("── Live Comments (all viewers receive each comment) ──");
        service.postComment("U2", streamId, "This looks delicious!");
        System.out.println();

        service.postComment("U3", streamId, "What's the recipe?");
        System.out.println();

        // ── Viewer leaves mid-stream ───────────────────────────────────────────
        System.out.println("── Charlie Leaves ──");
        service.leaveStream("U3", streamId);
        System.out.println();

        // ── Comment after Charlie left — only U2 and U4 receive it ────────────
        System.out.println("── Comment after Charlie left ──");
        service.postComment("U4", streamId, "Missing Charlie already!");
        System.out.println();

        // ── Recent comments ────────────────────────────────────────────────────
        System.out.println("── Recent 2 Comments ──");
        service.getRecentComments(streamId, 2).forEach(System.out::println);
        System.out.println();

        // ── Stream ends — all remaining viewers notified ───────────────────────
        System.out.println("── Stream Ends ──");
        service.endStream(streamId);
    }
}
