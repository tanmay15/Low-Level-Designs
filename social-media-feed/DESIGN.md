# LLD: Social Media Feed (Twitter / Instagram style)

> Implementation: `SocialMediaFeedSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Create user accounts |
| 2 | Follow / unfollow other users |
| 3 | Create posts with text content |
| 4 | Like / unlike posts |
| 5 | Comment on posts |
| 6 | Get timeline: posts from all followed users, sorted by recency (newest first) |
| 7 | Get a user's own posts (profile page) |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Timeline uses **pull model** — generated on request by querying followed users' posts |
| 2 | Like deduplication: `Set<userId>` on Post ensures one like per user |

### Out of Scope
Retweet/reshare, hashtags, search, notifications, direct messages, media storage, pagination

---

## Step 2 — Pull Model vs Push Model (Key Design Decision)

The most important architectural question in Social Media Feed is how to generate the timeline.

### Pull Model (implemented here)

```
getTimeline(userId):
  for each followeeId in user.followingIds:
    add all followee's posts to feed list
  sort feed by timestamp descending
  return top N
```

- **Read cost**: O(F × P) where F = followees, P = posts per user
- **Write cost**: O(1) — just store the post
- **When to use**: Simple to implement, good for LLD interviews

### Push Model (fan-out on write — HLD optimization)

```
createPost(authorId, content):
  save post
  for each followerId in author.followers:
    prepend post to follower's feed store
```

- **Read cost**: O(1) — pre-computed feed stored per user
- **Write cost**: O(followers) — expensive for celebrities with millions of followers
- **When to use**: Production systems with high read:write ratio

**At LLD level: always implement pull model.** Mention push model as an HLD scalability discussion.

---

## Step 3 — Entities

| Class | Role |
|-------|------|
| `User` | Account. Owns `followingIds` (who THEY follow — not who follows them). |
| `Post` | A user's post. Owns `likedByUserIds` (Set) and `comments` (List). |
| `Comment` | A comment on a post. Immutable once created. |
| `SocialMediaService` | Orchestrator — all user, post, and social graph operations. |

---

## Step 4 — Key Data Structures

### `User.followingIds: Set<String>`

The follow graph is stored on the User who is DOING the following, not the one being followed.

```java
user.followingIds.add(followeeId);   // Alice follows Bob
user.followingIds.remove(followeeId); // Alice unfollows Bob
```

Why `Set` not `List`? Prevents duplicate follows (following the same person twice).

### `Post.likedByUserIds: Set<String>`

```java
post.likedByUserIds.add(userId);    // like — returns false if already liked (dedup)
post.likedByUserIds.remove(userId); // unlike
post.likedByUserIds.size();         // like count — O(1)
```

### `SocialMediaService.postsByUser: Map<String, List<Post>>`

```
postsByUser: Map<userId → List<Post>>  (newest first — prepended on creation)
```

Timeline generation:
```java
for (String followeeId : user.followingIds) {
    feed.addAll(postsByUser.get(followeeId));
}
feed.sort((a, b) -> Long.compare(b.timestamp, a.timestamp)); // newest first
```

---

## Step 5 — Class Attributes & Methods

### `User`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | U1, U2, ... |
| `username` | String | @handle |
| `bio` | String | profile description |
| `followingIds` | Set\<String\> | userIds this user follows |

### `Post`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | P1, P2, ... |
| `authorId` | String | who created it |
| `content` | String | text content |
| `timestamp` | long | epoch ms — used for timeline sorting |
| `likedByUserIds` | Set\<String\> | who liked this post |
| `comments` | List\<Comment\> | ordered by creation time |
| `likeCount()` | int | `likedByUserIds.size()` |
| `commentCount()` | int | `comments.size()` |

### `Comment`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | C1, C2, ... |
| `postId` | String | which post |
| `authorId` | String | who commented |
| `content` | String | text |
| `timestamp` | long | epoch ms |

### `SocialMediaService`

| Method | Description |
|--------|-------------|
| `createUser(username, bio)` | create User, init postsByUser entry |
| `follow(followerId, followeeId)` | add followeeId to follower's `followingIds` |
| `unfollow(followerId, followeeId)` | remove followeeId from follower's `followingIds` |
| `createPost(authorId, content)` | create Post, prepend to `postsByUser[authorId]` |
| `createPost(authorId, content, timestamp)` | overload with explicit timestamp (for demo ordering) |
| `likePost(userId, postId)` | add userId to `post.likedByUserIds` |
| `unlikePost(userId, postId)` | remove userId from `post.likedByUserIds` |
| `addComment(authorId, postId, content)` | create Comment, add to `post.comments` |
| `getTimeline(userId, limit)` | collect posts from all followees, sort by timestamp desc, return top N |
| `getUserPosts(userId, limit)` | return user's own posts from `postsByUser` |
| `printTimeline(userId, limit)` | formatted feed display |
| `printUserProfile(userId)` | display profile with bio, following count, recent posts |

---

## Step 6 — Why `followingIds` Is on User (not a separate relationship entity)

Following is **intrinsic to the user's state** — it describes who they follow, not an event or transaction. It belongs on the User entity.

Compare to `BorrowRecord` in Library Management — borrowing is a *transaction* (has a start, end, fine). Following has no lifecycle — it's just a current fact.

If follower count ever needs to be tracked separately (e.g. "user has 10M followers"), you'd add `followerCount` as a counter on User — not a separate entity.

---

## Step 7 — Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Pull model for timeline | Yes | Simpler to implement; correct for LLD scope |
| `followingIds` on User | Yes | Following is intrinsic user state, not a transaction |
| `likedByUserIds` as `Set` on Post | Yes | Automatic deduplication + O(1) like check |
| `postsByUser` as separate map | Yes | O(1) access to a specific user's posts — no scan of all posts |
| Posts prepended (newest first) | Yes | Timeline sort is then mostly pre-done; `sort()` just merges across users |
| No `followers` count stored | Yes | Can be computed from scanning all `followingIds` — acceptable at LLD scale |

---

## Step 8 — Extensibility

| Extension | How |
|-----------|-----|
| Push model (fan-out) | On `createPost`: iterate `followers` (need reverse map), push to their `feedStore` |
| Hashtag support | Extract hashtags from content on `createPost`, store `Map<hashtag, List<Post>>` |
| Pagination | Add `cursor` (timestamp) to `getTimeline` — return posts older than cursor |
| Notifications | Observer — `SocialMediaService` notifies `NotificationService` on like/comment |
| Post deletion | Mark `post.deleted = true`, filter in `getTimeline` — don't physically remove (audit) |

---

## Quick Recall — 3 Main Takeaways

1. **Pull model**: `getTimeline()` collects posts from all followed users, merges, and sorts. O(F×P) read, O(1) write. Simple and correct for LLD. Mention push model (fan-out on write) as the HLD optimization.

2. **`followingIds` is a Set on User**: Following is an intrinsic state of the user. No separate relationship entity needed. `Set` prevents duplicate follows automatically.

3. **Like = `Set<userId>` on Post**: `set.add(userId)` returns false if already liked (deduplication). `set.size()` is the like count. O(1) for all like operations.
