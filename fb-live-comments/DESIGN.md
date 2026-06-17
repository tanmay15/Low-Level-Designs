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
| 4 | All active viewers receive every new comment (fan-out) |
| 5 | Host ends the stream — all viewers notified |
| 6 | Query last N comments from a stream |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Fan-out is O(viewers) per comment |
| 2 | Viewer must join before posting a comment |

### Out of Scope
Real-time WebSocket delivery, comment pinning, likes on comments, banning users, stream replay, moderation, analytics

---

## Step 2 — The Core: Viewer IS the Observer

The simplest model: a **Viewer** joins a stream and gets notified of comments. No separate listener abstraction needed.

```
postComment(authorId, streamId, content):
  create LiveComment
  append to comments[streamId]
  for each viewer in viewers[streamId]:
    viewer.receiveComment(comment)   ← fan-out
```

`Viewer.receiveComment()` simulates a WebSocket push to that viewer's screen. In production, this would be an async network push.

---

## Step 3 — Stream State Machine

```
SCHEDULED ──[startStream()]──► LIVE ──[endStream()]──► ENDED
```

- Only `LIVE` streams accept `joinStream()` and `postComment()`
- `endStream()` calls `viewer.onStreamEnd()` for all remaining viewers, then sets status = ENDED

---

## Step 4 — Entities

| Class | Role |
|-------|------|
| `LiveStream` | Stream entity — hostId, title, status, timing |
| `LiveComment` | Immutable comment — authorId, content, timestamp. Append-only. |
| `Viewer` | The observer. Has `receiveComment()` and `onStreamEnd()`. One per viewer per stream. |
| `LiveCommentService` | Orchestrator — manages stream lifecycle, viewer joins/leaves, fan-out |

### Enum

| Enum | Values |
|------|--------|
| `StreamStatus` | SCHEDULED, LIVE, ENDED |

---

## Step 5 — Data Structures

```
streams:     Map<streamId, LiveStream>
comments:    Map<streamId, List<LiveComment>>    ← append-only comment log
viewers:     Map<streamId, List<Viewer>>          ← active viewers (fan-out targets)
viewerIndex: Map<userId+streamId, Viewer>         ← O(1) lookup for join/leave/comment-auth
```

`viewers` drives the fan-out loop in `postComment()`.
`viewerIndex` (key = `userId:streamId`) enables:
- Preventing duplicate joins (check before creating Viewer)
- Fast lookup in `leaveStream()` to remove the right Viewer
- Validating that commenter is actually in the stream

---

## Step 6 — Class Attributes & Methods

### `Viewer`

| Member | Type | Description |
|--------|------|-------------|
| `userId` | String | who this viewer is |
| `streamId` | String | which stream they're watching |
| `joinedAt` | long | epoch ms |
| `receiveComment(comment)` | void | fan-out target — prints/pushes the comment |
| `onStreamEnd()` | void | notified when host ends the stream |

### `LiveCommentService`

| Method | Description |
|--------|-------------|
| `startStream(hostId, title)` | Create SCHEDULED → LIVE stream, init maps |
| `endStream(streamId)` | LIVE → ENDED, call `onStreamEnd()` on all viewers |
| `joinStream(userId, streamId)` | Create Viewer, add to viewers list and viewerIndex |
| `leaveStream(userId, streamId)` | Remove Viewer from list and index |
| `postComment(authorId, streamId, content)` | Validate, create LiveComment, fan-out to all viewers |
| `getRecentComments(streamId, limit)` | Return last N from comments list |
| `getViewerCount(streamId)` | Return `viewers[streamId].size()` |

---

## Step 7 — Fan-Out After a Viewer Leaves

Demo shows this clearly:

```
Charlie joins → [U2, U3, U4] all receive comments
Charlie leaves → [U2, U4] receive next comment
```

Because `leaveStream()` removes the Viewer from `viewers[streamId]`, subsequent `postComment()` fan-out loops don't include Charlie. No special handling needed.

---

## Step 8 — Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Viewer IS the observer (no separate CommentListener) | Yes | Simpler — viewer and notification target are the same thing |
| `viewerIndex: Map<userId+streamId, Viewer>` | Yes | O(1) dedup on join + O(1) remove on leave |
| Viewer must join before commenting | Yes | Matches real-world behaviour — you must watch to comment |
| Duplicate join guard | Yes | `viewerIndex.containsKey(key)` — returns existing Viewer if already joined |
| `comments` stored separately from viewers | Yes | `getRecentComments()` works even after viewers leave or stream ends |

---

## Step 9 — Extensibility

| Extension | How |
|-----------|-----|
| Moderation | In `postComment()`, scan content for banned words before fan-out |
| Analytics | Count `comments[streamId].size()` on demand, or maintain a counter |
| Rate limiting | Track `lastCommentTime` per viewer in `Viewer`; reject if too recent |
| Comment likes | Add `likedBy: Set<userId>` on `LiveComment` |
| Real-time delivery | `receiveComment()` pushes over a stored WebSocket connection |

---

## Quick Recall — 3 Main Takeaways

1. **Viewer IS the observer**: `receiveComment()` on `Viewer` is the fan-out target. `postComment()` loops over `viewers[streamId]` and calls it on each. No separate listener layer.

2. **`viewerIndex: Map<userId:streamId, Viewer>`** serves three purposes: dedup joins, fast removal on leave, and validating commenter is actually in the stream.

3. **Leave removes from fan-out immediately**: `leaveStream()` removes Viewer from `viewers[streamId]`. Next `postComment()` loop naturally excludes them — no flags, no filtering.
