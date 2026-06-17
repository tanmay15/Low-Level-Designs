# LLD: FB Live Comments (Facebook Live / YouTube Live)

> Implementation: `FBLiveCommentsSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Host starts a live stream |
| 2 | Viewers join and leave the stream |
| 3 | Viewers post comments in real time |
| 4 | Every active listener (viewer displays, moderation, analytics) receives each new comment |
| 5 | Multiple consumer types: UI display, moderation, analytics — extensible without code change |
| 6 | Host ends the stream — all listeners notified |
| 7 | Query last N comments from a stream |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Observer pattern — adding a new comment consumer requires zero changes to `LiveCommentService` |
| 2 | Fan-out is synchronous and O(listeners) per comment |

### Out of Scope
Real-time WebSocket delivery, comment pinning, likes on comments, banning users, stream replay, comment pagination

---

## Step 2 — The Core: Observer Fan-Out

This is the most Observer-centric problem in the entire set. Every comment is fanned out to ALL listeners:

```
postComment(authorId, streamId, content):
  create LiveComment
  comments[streamId].add(comment)
  for each listener in listeners[streamId]:
    listener.onComment(comment)     ← fan-out
```

Adding a new consumer (ML toxicity checker, subtitles generator, billing for paid streams) = implement `CommentListener` + call `subscribe(streamId, newListener)`. Zero changes to `LiveCommentService`.

---

## Step 3 — Two Types of Listeners

| Type | Example | One per... | Lifecycle |
|------|---------|------------|-----------|
| Per-viewer | `ViewerDisplay("U2")` | Each viewer | Created on `joinStream()`, removed on `leaveStream()` |
| Shared | `ModerationConsumer`, `AnalyticsConsumer` | Whole stream | Subscribed once via `subscribe()`, lives for stream duration |

Per-viewer listeners are added via `joinStream(userId, streamId, display)`.
Shared listeners are added via `subscribe(streamId, listener)`.

Both end up in the same `listeners[streamId]` list — the fan-out loop doesn't distinguish between them.

---

## Step 4 — Stream State Machine

```
SCHEDULED ──[startStream()]──► LIVE ──[endStream()]──► ENDED
```

Only LIVE streams accept comments and viewer joins. `postComment()` and `joinStream()` both validate `stream.status == LIVE`.

On `endStream()`:
- Stream status set to ENDED
- All listeners notified via `listener.onStreamEnd(streamId)`
- New comments rejected after this

---

## Step 5 — Entities

| Class | Role |
|-------|------|
| `LiveStream` | Stream entity — hostId, title, status, startedAt/endedAt |
| `LiveComment` | Immutable comment record — authorId, content, timestamp |
| `CommentListener` | Observer interface — `onComment()` + `onStreamEnd()` |
| `ViewerDisplay` | Per-viewer listener — prints comment to viewer's screen |
| `ModerationConsumer` | Shared listener — flags banned words |
| `AnalyticsConsumer` | Shared listener — counts comments per stream |
| `LiveCommentService` | Orchestrator — manages all streams, viewers, and fan-out |

### Enum

| Enum | Values |
|------|--------|
| `StreamStatus` | SCHEDULED, LIVE, ENDED |

---

## Step 6 — Data Structures

```
streams:   Map<streamId, LiveStream>
comments:  Map<streamId, List<LiveComment>>      ← append-only log
viewers:   Map<streamId, Set<userId>>             ← current active viewers
listeners: Map<streamId, List<CommentListener>>   ← Observer subscriptions
```

`comments` stores all comments for later querying (`getRecentComments`).
`viewers` is a Set — prevents the same user from being counted twice.
`listeners` drives the fan-out in `postComment()`.

---

## Step 7 — CommentListener Interface

```java
interface CommentListener {
    void onComment(LiveComment comment);
    void onStreamEnd(String streamId);
}
```

Three implementations in the solution:

| Class | `onComment()` behavior | `onStreamEnd()` behavior |
|-------|----------------------|--------------------------|
| `ViewerDisplay` | Print to viewer's screen | Tell viewer stream ended |
| `ModerationConsumer` | Scan for banned words, flag if found | No-op |
| `AnalyticsConsumer` | Increment comment count for stream | Print total count |

Lambda as consumer (added in demo):
```java
service.subscribe(streamId, comment ->
    System.out.println("[ML-TOXICITY] Analysing: " + comment.content));
```

---

## Step 8 — Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Shared vs per-viewer listeners both in same list | Yes | Fan-out loop doesn't need to distinguish — simplicity |
| `viewers: Set<userId>` | Yes | Prevents double-counting the same viewer |
| `comments` stored even after stream ends | Yes | `getRecentComments()` works for replay/recap |
| Author must be a viewer to comment | Yes | Real platforms require you to be watching to comment |
| `onStreamEnd()` in interface | Yes | Allows each listener to do its own cleanup (print totals, close connections) |

---

## Step 9 — How This Compares to Other Observer Problems

| | FB Live Comments | Notification Service | Ad Click Aggregator |
|-|-----------------|---------------------|---------------------|
| Observer trigger | New comment posted | User action (e.g. order placed) | Ad click/view event |
| Fan-out target | All stream viewers | User's subscribed channels | Analytics + Billing teams |
| Listeners per event | Many (all viewers) | Few (user's channels) | Few (2-3 consumers) |
| Listener lifecycle | Per-viewer (join/leave) | Per-user subscription | Static (system-level) |

---

## Step 10 — Extensibility

| Extension | How |
|-----------|-----|
| Comment likes | Add `likeCount` on `LiveComment`; `likeComment(commentId, userId)` updates it |
| Pinned comment | Add `pinnedCommentId` on `LiveStream`; `pinComment(streamId, commentId, hostId)` |
| User ban | Add `bannedUsers: Set<userId>` on stream; check in `postComment()` |
| Real-time delivery | Replace synchronous fan-out with WebSocket push per viewer |
| Rate limiting | Track `lastCommentTime` per viewer; reject if too frequent |

---

## Quick Recall — 3 Main Takeaways

1. **Pure Observer fan-out**: `postComment()` loops over `listeners[streamId]` and calls `onComment()` on each. New consumer = implement `CommentListener` + `subscribe()`. Zero changes to service.

2. **Two listener types, same list**: Per-viewer displays (registered on `joinStream`) and shared listeners (moderation, analytics — registered via `subscribe`) all live in the same `listeners` list. The fan-out loop doesn't care which type.

3. **Viewer must join before commenting**: `viewers: Set<userId>` tracks active viewers. `postComment()` checks `viewers.contains(authorId)` — enforces the real-world rule that you must be watching to comment.
