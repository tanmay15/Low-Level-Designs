# HLD Design: Facebook (News Feed)

> **Pattern:** Real-time Updates + Scaling Reads + Handling Large Blobs
> **Difficulty:** Hard
> **Hello Interview Tag:** FB News Feed

---

## Step 1 — Requirements

### Functional Requirements (Core — "above the line")

1. **Users** can create an account, add/remove friends, and follow pages.
2. **Post Creation** — Users can create posts (text, images, videos) visible to their friends/followers.
3. **News Feed** — Users can view a ranked, paginated feed of posts from friends and followed pages.
4. **Reactions & Comments** — Users can like/react to posts and add/view comments.
5. **Notifications** — Users receive real-time notifications (new likes, comments, friend requests).

### Non-Functional Requirements

| Requirement | Target |
|---|---|
| Scale | 3B users, 1B DAU, ~500M posts/day |
| Feed read latency | < 200ms (p99) |
| Post write latency | < 500ms |
| Availability | 99.99% (favor availability over consistency) |
| Feed freshness | Eventually consistent — stale by a few seconds is acceptable |
| Media upload | Images up to 10MB, Videos up to 4GB |
| Durability | No post/media loss |

> **Key observation:** Read-to-write ratio is extremely skewed (~1000:1). Feed reads dominate.
> A user with 10M followers creates 10M fan-out events per post — this is the core challenge.

### Below the Line (Out of Scope)

- Ad serving and targeting
- Marketplace, Groups, Events
- ML-based feed ranking (treat ranker as a black box)
- Video encoding pipeline (treat as a managed service)
- Stories, Reels (similar pattern to posts — omitted for focus)

---

## Step 2 — Core Entities

| Entity | Type | Reason |
|---|---|---|
| `User` | Class | Owns profile data, friend list, follower count |
| `Post` | Class | Content unit — text, media references, metadata |
| `Media` | Class | Binary blob reference (S3 key + CDN URL) |
| `FeedItem` | Class | Denormalized post snapshot stored per user in feed cache |
| `Friendship` | Relation | Bidirectional edge in social graph |
| `Reaction` | Class | Like/Love/Haha/Sad/Angry per user per post |
| `Comment` | Class | Threaded text attached to a post |
| `Notification` | Class | Event payload delivered in real-time to a user |
| `PostType` | Enum | TEXT, IMAGE, VIDEO, LINK |
| `ReactionType` | Enum | LIKE, LOVE, HAHA, SAD, ANGRY, WOW |

---

## Step 3 — API Design

### User & Social Graph

```
POST   /users                          → Create account
POST   /friendships/{userId}           → Send friend request
DELETE /friendships/{userId}           → Unfriend
POST   /follows/{pageId}               → Follow a page
```

### Post Creation

```
POST   /posts
Body:  { type, text?, mediaIds[]?, visibility }
→ 201 { postId, createdAt }

POST   /media/upload-url
Body:  { fileName, fileType, fileSize }
→ 200 { uploadUrl (pre-signed S3), mediaId }
```

> **Why pre-signed S3 URL?** Client uploads media directly to S3 — bypasses our servers
> entirely for the binary payload. Our server only stores the resulting S3 key.

### News Feed

```
GET    /feed?cursor={cursor}&limit=20
→ 200 { items: FeedItem[], nextCursor }
```

### Reactions & Comments

```
POST   /posts/{postId}/reactions       Body: { type: ReactionType }
DELETE /posts/{postId}/reactions       (undo reaction)
POST   /posts/{postId}/comments        Body: { text, parentCommentId? }
GET    /posts/{postId}/comments?cursor=...
```

### Notifications

```
GET    /notifications?cursor=...       (paginated history)
WS     /ws/notifications               (real-time push via WebSocket)
```

---

## Step 4 — High-Level Design

### Component Overview (ASCII Architecture Diagram)

```
                          ┌─────────────────────────────────────┐
                          │              CLIENTS                 │
                          │     (Web / iOS / Android)            │
                          └──────────────┬──────────────────────┘
                                         │ HTTPS / WebSocket
                          ┌──────────────▼──────────────────────┐
                          │           API GATEWAY                │
                          │   (Auth, Rate Limiting, Routing)     │
                          └───┬────────┬──────────┬─────────────┘
                              │        │          │
             ┌────────────────▼──┐  ┌──▼───────┐ ┌▼──────────────────┐
             │   Post Service    │  │  Feed    │ │  Notification      │
             │ (create/read post)│  │ Service  │ │  Service           │
             └───────┬───────────┘  └──┬───────┘ └───────┬───────────┘
                     │                 │                  │
        ┌────────────▼──┐   ┌──────────▼──────────┐  ┌───▼────────────┐
        │  Posts DB     │   │   Feed Cache         │  │  Notification  │
        │  (Cassandra)  │   │   (Redis Cluster)    │  │  Store (Redis) │
        └───────────────┘   └─────────────────────┘  └────────────────┘
                     │
        ┌────────────▼──────────────────────┐
        │            Kafka                   │
        │   Topic: post-created              │
        │   Topic: reaction-created          │
        └──────────────┬────────────────────┘
                       │
          ┌────────────▼────────────────────┐
          │      Fan-out Worker Pool         │
          │  (consumes post-created events)  │
          │  writes FeedItem to Redis per    │
          │  follower/friend                 │
          └──────────────┬──────────────────┘
                         │
              ┌──────────▼────────────┐
              │   Social Graph DB     │
              │   (followers list)    │
              │   (MySQL / TAO)       │
              └───────────────────────┘

        ┌───────────────────────────────────┐
        │         Media Pipeline            │
        │  Client → Pre-signed S3 Upload    │
        │  → S3 Event → Transcoder (video)  │
        │  → CDN (CloudFront) origin        │
        └───────────────────────────────────┘

        ┌───────────────────────────────────┐
        │      User / Auth Service          │
        │   PostgreSQL (user profiles)      │
        │   Redis (session tokens / JWT)    │
        └───────────────────────────────────┘
```

### Data Flow: Creating a Post

```
1. Client POSTs to /posts (text) or first gets a pre-signed URL for media.
2. Post Service validates, writes to Cassandra (Posts DB).
3. Post Service publishes PostCreatedEvent to Kafka (topic: post-created).
4. Fan-out Worker consumes the event:
   a. Reads author's friend list + follower list from Social Graph DB.
   b. For each follower, prepends a FeedItem (denormalized post snapshot) to
      their Redis feed list.
5. Feed is now "ready" for all followers — no DB join needed at read time.
```

### Data Flow: Reading the Feed

```
1. Client GETs /feed?cursor=...
2. Feed Service reads user's feed list from Redis (pure in-memory, O(1) per item).
3. Returns paginated FeedItem[] (already denormalized — no additional DB lookups).
4. On cache miss (new user / evicted), fall back to Social Graph + Posts DB
   to reconstruct the feed (hybrid pull).
```

---

## Step 5 — Deep Dives

### Deep Dive 1: Fan-out Strategy (The Core Trade-off)

This is the most important design decision for a social feed.

#### Option A: Fan-out on Write (Push Model) — Used Above

When a user creates a post, immediately write a copy of the feed item into every
follower's feed cache.

```
Post created → Kafka → Fan-out Workers → Write to Redis[follower_1], Redis[follower_2], ...
```

**Pros:**
- Feed reads are O(1) — just read from Redis, no joins.
- Read latency is minimal (< 10ms from cache).

**Cons:**
- **Celebrity problem**: A user with 10M followers triggers 10M Redis writes per post.
- Wasted work for inactive users (their feed is pre-built but never read).
- Fan-out lag: followers see posts seconds/minutes late.

**Mitigation for celebrities:**
> Use a hybrid approach: For users with > N followers (e.g. > 10,000), skip fan-out.
> Instead, when a regular user loads their feed, merge the pre-built cached feed with
> a live "celebrity pull" query. This is exactly what Twitter/X and Facebook do.

---

#### Option B: Fan-out on Read (Pull Model)

When a user requests their feed, query posts from all friends/follows in real-time.

```
Feed request → Query all friends → Fetch recent posts → Merge + Rank → Return
```

**Pros:**
- Zero write amplification — posting is cheap.
- Always fresh — no staleness.

**Cons:**
- Read is expensive: 1 feed load = N queries (N = number of friends).
- For a user with 1000 friends, that's 1000 DB lookups per feed refresh.
- Latency is high and unpredictable.

---

#### ✅ Production Choice: Hybrid (Fan-out on Write for regular users, Pull for celebrities)

```
                   ┌─────────────┐
                   │  Post Event │
                   └──────┬──────┘
                          │
              ┌───────────▼──────────────┐
              │  Is author a celebrity?  │
              │  (followers > 10,000)    │
              └──────┬───────────┬───────┘
                    NO           YES
                     │            │
        ┌────────────▼─┐  ┌───────▼──────────────────────┐
        │ Fan-out to   │  │ Store post in Posts DB only.  │
        │ all follower │  │ Pulled lazily at feed-read    │
        │ Redis feeds  │  │ time and merged with cache.   │
        └──────────────┘  └───────────────────────────────┘
```

---

### Deep Dive 2: Feed Storage Schema (Redis)

Each user's feed is stored as a **Redis Sorted Set**:

```
Key:   feed:{userId}
Score: post_timestamp (unix ms) — enables pagination by time
Value: serialized FeedItem JSON
       {
         postId, authorId, authorName, authorAvatarUrl,
         type, text, mediaUrl, reactionCount, commentCount,
         createdAt
       }
```

> **Why denormalized?** At feed read time we need author name, avatar, text — all in one
> shot. If we stored only `postId` we'd need N round-trips to hydrate each post.
> The tradeoff: if an author changes their avatar, stale avatars appear in feeds
> (acceptable — feeds are eventually consistent).

**Feed list bounded to last 1000 items per user** — `ZREMRANGEBYRANK` trims on each write.

---

### Deep Dive 3: Media Storage

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Media Upload Flow                            │
│                                                                      │
│  1. Client requests a pre-signed S3 PUT URL from our server.        │
│     POST /media/upload-url → { uploadUrl, mediaId }                  │
│                                                                      │
│  2. Client uploads binary directly to S3 (bypasses our servers).    │
│     PUT {uploadUrl}  ← raw bytes go straight to S3                  │
│                                                                      │
│  3. Client confirms upload in the post payload:                      │
│     POST /posts  Body: { text: "...", mediaIds: ["mediaId"] }        │
│                                                                      │
│  4. For videos: S3 event triggers a transcoding job (FFmpeg /        │
│     AWS Elemental MediaConvert) → outputs HLS/DASH adaptive streams. │
│                                                                      │
│  5. CDN (CloudFront) serves all media via edge nodes closest to      │
│     the user. Our origin is S3.                                      │
└──────────────────────────────────────────────────────────────────────┘
```

**Why pre-signed URLs?**
- Our API servers never touch binary blobs → no bandwidth bottleneck.
- S3 handles durability (11 nines), replication, and storage scaling.
- Pre-signed URL expires in 15 minutes → security.

---

### Deep Dive 4: Real-time Notifications

For reactions, comments, and friend requests, we need real-time delivery.

```
┌────────────────────────────────────────────────────────────────┐
│                   Notification Flow                            │
│                                                                │
│  1. Reaction/Comment event → Kafka topic: notification-events  │
│                                                                │
│  2. Notification Service consumes event:                       │
│     a. Writes to Notification Store (Redis + Cassandra for     │
│        history).                                               │
│     b. Checks if target user has an active WebSocket.         │
│        YES → push directly over WS.                           │
│        NO  → store in Redis; deliver on next app open.        │
│                                                                │
│  3. Client connects once at login via WebSocket to the         │
│     Notification Service (sticky routing via load balancer).  │
└────────────────────────────────────────────────────────────────┘
```

**WebSocket vs SSE vs Long Polling:**

| | WebSocket | SSE | Long Polling |
|---|---|---|---|
| **Direction** | Bidirectional | Server → Client only | Server → Client |
| **Complexity** | High | Low | Medium |
| **Browser support** | Universal | Universal | Universal |
| **Use case** | Chat, live reactions | Notifications, feed updates | Fallback |

> **Choice:** WebSocket for notifications (we also need it for Messenger).
> SSE is simpler but can't send client → server messages.

---

### Deep Dive 5: Scaling the Social Graph

Facebook uses **TAO (The Associations and Objects)** — a distributed, write-through
cache built on top of MySQL sharded by object ID.

For an interview, the simplified version:

```
Social Graph DB (MySQL, sharded by userId):
  Table: friendships
    userId      BIGINT
    friendId    BIGINT
    createdAt   TIMESTAMP
    INDEX(userId)          ← all friends of a user

  Table: follows
    followerId  BIGINT
    followeeId  BIGINT
    INDEX(followeeId)      ← all followers of a page/celebrity
```

**Sharding strategy:** Hash shard by `userId` → all friendships for a user on one shard.
Fan-out workers need cross-shard reads (follower list) → cache aggressively in Redis.

---

## Step 6 — Database Choices & Justification

| Data | Database | Why |
|---|---|---|
| User profiles | **PostgreSQL** | Relational, ACID, user data is structured |
| Posts | **Cassandra** | Write-heavy (500M posts/day), wide-column for time-series reads |
| Social graph | **MySQL (sharded)** | Relational edges, strong consistency for friendship state |
| Feed cache | **Redis Cluster** (Sorted Sets) | Sub-millisecond reads, TTL eviction, fan-out writes |
| Media blobs | **S3 + CDN** | Infinite storage, CDN for global low-latency reads |
| Notification history | **Cassandra** | Append-only, time-range queries per user |
| Sessions/tokens | **Redis** | Fast auth on every request |

---

## Step 7 — Scalability Analysis

### Estimation

```
Users:         3B total, 1B DAU
Posts/day:     ~500M  →  ~6,000 posts/second (peak ~30,000/s)
Feed reads:    1B users × 10 reads/day = 10B reads/day  →  ~115,000 reads/second
Average friends: 300  →  fan-out per post = 300 writes to Redis
Fan-out writes: 6,000 posts/s × 300 = 1.8M Redis writes/second (sustained)
Media storage: 500M posts × avg 500KB = 250TB/day → ~90PB/year (CDN absorbs reads)
```

### Bottleneck Analysis

| Bottleneck | Solution |
|---|---|
| Feed reads at 115k RPS | Redis Cluster (easily handles 1M+ ops/sec per cluster) |
| Fan-out write amplification (celebrities) | Hybrid fan-out — skip fan-out for high-follower accounts |
| Cassandra write throughput for posts | Cassandra designed for this — horizontal partitioning by (userId, postId) |
| Social graph cross-shard reads for fan-out | Cache follower lists in Redis with 60s TTL |
| WebSocket connections at scale | Sticky sessions via L7 load balancer; 1 WS server handles ~50k connections |
| Media bandwidth | CDN serves 95%+ of media — origin S3 only on cache miss |

---

## Step 8 — Key Trade-offs Summary

| Decision | Choice | Trade-off Accepted |
|---|---|---|
| Fan-out strategy | Hybrid (write for regular, pull for celebrities) | Slight staleness for celebrity posts vs. write amplification |
| Feed storage | Denormalized Redis Sorted Sets | Stale avatars/names in feed vs. zero-latency reads |
| Media upload | Client → pre-signed S3 (bypass servers) | Client complexity vs. no server bandwidth bottleneck |
| Consistency | Eventual (AP in CAP) | Feed may be 1-5s stale; reactions/comments eventually sync |
| Notification delivery | WebSocket (persistent connection) | Server resource per connection vs. true real-time push |
| Post DB | Cassandra over PostgreSQL | No ACID joins; eventual consistency vs. write scalability |

---

## Step 9 — What Is Expected at Each Level

### Mid-level (E4)
- Define clear functional/non-functional requirements.
- Design a working system: client → API server → DB, basic feed retrieval.
- Recognize the need for caching on feed reads.
- Know that media should go to blob storage, not the DB.
- Can explain fan-out on write at a basic level when prompted.

### Senior (E5)
- Drive the interview end-to-end without prompting.
- Proactively identify the **celebrity problem** and propose the hybrid solution.
- Compare fan-out on write vs read with clear trade-off articulation.
- Know *why* Cassandra over Postgres for posts (LSM tree write performance).
- Design notification flow with WebSocket and discuss fallback strategies.
- Back all decisions with the scale numbers.

### Staff+ (E6/E7)
- Frame the entire design around the **read-to-write asymmetry** from the start.
- Go beyond the textbook: discuss TAO's object/association model, edge cache warming.
- Proactively discuss operational concerns: cache stampede on Redis eviction,
  feed reconstruction on cold start for new users, data hotspots on trending posts.
- Propose a **reconciliation strategy** for eventual consistency: e.g., fan-out lag
  for celebrity followers → use a "pull anchor" that merges in the last N celebrity
  posts at read time without the full pull cost.
- Discuss multi-region replication: user shards in home region, feed cache replicated
  to read replicas in other regions, cross-region fan-out via inter-region Kafka.

---

## Step 10 — Quick Recall (Interview Cheat Sheet)

```
Facebook News Feed — Architecture in 90 seconds

WRITE PATH:
  Client → API GW → Post Service → Cassandra
                               ↓
                            Kafka (post-created)
                               ↓
                        Fan-out Workers
                               ↓ (skip celebrities)
                  Redis Sorted Set per follower (feed:{userId})

READ PATH:
  Client → API GW → Feed Service → Redis (cache hit, ~5ms)
                                 → [cache miss] Social Graph + Cassandra + merge

MEDIA:
  Client → POST /media/upload-url → pre-signed S3 URL
  Client → PUT (directly to S3, bypasses servers)
  CDN (CloudFront) serves all reads

NOTIFICATIONS:
  Kafka (reaction/comment events) → Notification Service
  → WebSocket push to connected client
  → Redis queue for offline users

KEY NUMBERS:
  500M posts/day | 10B feed reads/day | 1.8M Redis writes/sec (fan-out)
  Feed latency target: <200ms | Availability: 99.99%

KEY TRADE-OFF:
  Fan-out on Write  = fast reads, write amplification for celebrities
  Fan-out on Read   = fresh data, slow reads at scale
  Hybrid            = best of both worlds ✓
```

---

*References: Hello Interview — FB News Feed, Facebook Engineering Blog (TAO, Haystack, Dragon), ByteByteGo System Design Vol. 1*
