// =============================================================================
// LLD: SOCIAL MEDIA FEED (Twitter/Instagram style)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Create user accounts
//   2. Follow / unfollow users
//   3. Create posts (text + optional image URL)
//   4. Like / unlike posts
//   5. Comment on posts
//   6. Get timeline: posts from all users you follow, sorted by recency
//   7. Get a user's own posts (profile page)
//
// Non-Functional:
//   - Timeline uses PULL model: generated on request by querying followed users' posts
//   - No global ordering lock — each user's post list is independent
//
// Out of scope: retweet/reshare, hashtags, search, notifications,
//   direct messages, media storage, pagination (conceptual only)
//
// KEY DESIGN QUESTION — Pull vs Push model for timeline:
//   PULL (implemented here):
//     → On getTimeline(userId): fetch posts from all followed users, merge, sort
//     → Read: O(followees × posts), Write: O(1)
//     → Good when: follows are few, read frequency low
//   PUSH (fan-out on write):
//     → On createPost(userId): push post to all followers' feed stores
//     → Read: O(1), Write: O(followers)
//     → Good when: followers are few, read frequency high
//   In LLD interviews: implement pull model — it's simpler and more explainable.
// =============================================================================

import java.util.*;
import java.util.stream.Collectors;


// =============================================================================
// ENTITIES
// =============================================================================

// ── User ──────────────────────────────────────────────────────────────────────
// Owns its follow graph. The list of who IT follows (not who follows IT).

class User {
    public String      id;
    public String      username;
    public String      bio;
    public Set<String> followingIds;  // users THIS user follows (not followers)

    public User(String id, String username, String bio) {
        this.id           = id;
        this.username     = username;
        this.bio          = bio;
        this.followingIds = new HashSet<>();
    }
}

// ── Post ──────────────────────────────────────────────────────────────────────

class Post {
    public String       id;
    public String       authorId;
    public String       content;
    public long         timestamp;
    public Set<String>  likedByUserIds;  // who liked this post
    public List<Comment> comments;

    public Post(String id, String authorId, String content, long timestamp) {
        this.id             = id;
        this.authorId       = authorId;
        this.content        = content;
        this.timestamp      = timestamp;
        this.likedByUserIds = new HashSet<>();
        this.comments       = new ArrayList<>();
    }

    public int likeCount()    { return likedByUserIds.size(); }
    public int commentCount() { return comments.size(); }
}

// ── Comment ───────────────────────────────────────────────────────────────────

class Comment {
    public String id;
    public String postId;
    public String authorId;
    public String content;
    public long   timestamp;

    public Comment(String id, String postId, String authorId, String content, long timestamp) {
        this.id        = id;
        this.postId    = postId;
        this.authorId  = authorId;
        this.content   = content;
        this.timestamp = timestamp;
    }
}


// =============================================================================
// SOCIAL MEDIA SERVICE
// =============================================================================

class SocialMediaService {
    private Map<String, User>        users;        // userId → User
    private Map<String, Post>        posts;        // postId → Post
    private Map<String, List<Post>>  postsByUser;  // userId → their posts (sorted by time desc)
    private int                      userCounter;
    private int                      postCounter;
    private int                      commentCounter;

    public SocialMediaService() {
        this.users          = new HashMap<>();
        this.posts          = new HashMap<>();
        this.postsByUser    = new HashMap<>();
        this.userCounter    = 0;
        this.postCounter    = 0;
        this.commentCounter = 0;
    }

    // ── User management ───────────────────────────────────────────────────────

    public User createUser(String username, String bio) {
        String id   = "U" + (++userCounter);
        User   user = new User(id, username, bio);
        users.put(id, user);
        postsByUser.put(id, new ArrayList<>());
        System.out.println("[USER] @" + username + " created (" + id + ")");
        return user;
    }

    // ── Follow / Unfollow ─────────────────────────────────────────────────────
    // Stored on the FOLLOWER's User object (who they follow).
    // followingIds lives inside User because it's intrinsic to that user's state.

    public void follow(String followerId, String followeeId) {
        if (followerId.equals(followeeId))
            throw new RuntimeException("Cannot follow yourself");
        User follower = getUser(followerId);
        getUser(followeeId); // validate followee exists
        follower.followingIds.add(followeeId);
        System.out.println("  @" + follower.username + " followed @" + getUser(followeeId).username);
    }

    public void unfollow(String followerId, String followeeId) {
        getUser(followerId).followingIds.remove(followeeId);
        System.out.println("  @" + getUser(followerId).username
                + " unfollowed @" + getUser(followeeId).username);
    }

    // ── Post ──────────────────────────────────────────────────────────────────

    public Post createPost(String authorId, String content) {
        return createPost(authorId, content, System.currentTimeMillis());
    }

    public Post createPost(String authorId, String content, long timestamp) {
        User   author = getUser(authorId);
        String id     = "P" + (++postCounter);
        Post   post   = new Post(id, authorId, content, timestamp);

        posts.put(id, post);
        // Add to front of user's post list (newest first)
        postsByUser.get(authorId).add(0, post);

        System.out.println("  [POST] @" + author.username + ": \"" + content + "\" (" + id + ")");
        return post;
    }

    // ── Like / Unlike ─────────────────────────────────────────────────────────

    public void likePost(String userId, String postId) {
        User user = getUser(userId);
        Post post = getPost(postId);
        if (post.likedByUserIds.add(userId)) {
            System.out.println("  @" + user.username + " liked " + postId
                    + " (total: " + post.likeCount() + ")");
        } else {
            System.out.println("  @" + user.username + " already liked " + postId);
        }
    }

    public void unlikePost(String userId, String postId) {
        getUser(userId);
        getPost(postId).likedByUserIds.remove(userId);
        System.out.println("  @" + getUser(userId).username + " unliked " + postId);
    }

    // ── Comment ───────────────────────────────────────────────────────────────

    public Comment addComment(String authorId, String postId, String content) {
        User    author  = getUser(authorId);
        Post    post    = getPost(postId);
        String  id      = "C" + (++commentCounter);
        Comment comment = new Comment(id, postId, authorId, content, System.currentTimeMillis());

        post.comments.add(comment);
        System.out.println("  @" + author.username + " commented on " + postId
                + ": \"" + content + "\"");
        return comment;
    }

    // ── Timeline (PULL model) ─────────────────────────────────────────────────
    // Fetch posts from all users that `userId` follows, merge, sort by time desc.
    // This is the entire "feed algorithm" for a pull model.
    //
    // Time complexity: O(F × P) where F = number of followees, P = posts per user.
    // For HLD with millions of users, this becomes a push model (fan-out on write).

    public List<Post> getTimeline(String userId, int limit) {
        User user = getUser(userId);

        List<Post> feed = new ArrayList<>();
        for (String followeeId : user.followingIds) {
            List<Post> followeePosts = postsByUser.getOrDefault(followeeId, new ArrayList<>());
            feed.addAll(followeePosts);
        }

        // Sort by timestamp descending (newest first)
        feed.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        List<Post> result = feed.subList(0, Math.min(limit, feed.size()));
        return result;
    }

    // ── Profile: user's own posts ─────────────────────────────────────────────

    public List<Post> getUserPosts(String userId, int limit) {
        getUser(userId);
        List<Post> userPosts = postsByUser.getOrDefault(userId, new ArrayList<>());
        return userPosts.subList(0, Math.min(limit, userPosts.size()));
    }

    // ── Display helpers ───────────────────────────────────────────────────────

    public void printTimeline(String userId, int limit) {
        User       user = getUser(userId);
        List<Post> feed = getTimeline(userId, limit);

        System.out.println("── Feed for @" + user.username + " ──");
        if (feed.isEmpty()) {
            System.out.println("  (no posts from people you follow)");
            return;
        }
        for (Post post : feed) {
            User author = getUser(post.authorId);
            System.out.printf("  @%-12s | %s | ❤ %d | 💬 %d%n",
                    author.username, post.content,
                    post.likeCount(), post.commentCount());
        }
    }

    public void printUserProfile(String userId) {
        User user = getUser(userId);
        System.out.println("── Profile: @" + user.username + " ──");
        System.out.println("  Bio: " + user.bio);
        System.out.println("  Following: " + user.followingIds.size());
        List<Post> myPosts = getUserPosts(userId, 5);
        System.out.println("  Recent posts (" + myPosts.size() + "):");
        for (Post post : myPosts) {
            System.out.printf("    %s | ❤ %d | 💬 %d%n",
                    post.content, post.likeCount(), post.commentCount());
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private User getUser(String id) {
        User u = users.get(id);
        if (u == null) throw new RuntimeException("User not found: " + id);
        return u;
    }

    private Post getPost(String id) {
        Post p = posts.get(id);
        if (p == null) throw new RuntimeException("Post not found: " + id);
        return p;
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class SocialMediaFeedSolution {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Social Media Feed Demo ===\n");

        SocialMediaService service = new SocialMediaService();

        // ── Create users ──────────────────────────────────────────────────────
        User alice   = service.createUser("alice",   "Coffee & code ☕");
        User bob     = service.createUser("bob",     "Photography 📷");
        User charlie = service.createUser("charlie", "Traveller 🌍");
        User diana   = service.createUser("diana",   "Chef 👩‍🍳");
        System.out.println();

        // ── Follow relationships ───────────────────────────────────────────────
        System.out.println("── Follow Graph ──");
        service.follow(alice.id, bob.id);     // Alice follows Bob
        service.follow(alice.id, charlie.id); // Alice follows Charlie
        service.follow(bob.id, alice.id);     // Bob follows Alice
        service.follow(charlie.id, diana.id); // Charlie follows Diana
        System.out.println();

        // ── Posts ─────────────────────────────────────────────────────────────
        System.out.println("── Creating Posts ──");
        long t = System.currentTimeMillis();

        Post p1 = service.createPost(bob.id,     "Just got my new camera! 📷",       t);
        Post p2 = service.createPost(charlie.id, "Arrived in Bali 🌴",                t + 1000);
        Post p3 = service.createPost(alice.id,   "Morning coffee ritual ☕",          t + 2000);
        Post p4 = service.createPost(bob.id,     "Sunset shots from the hill 🌅",     t + 3000);
        Post p5 = service.createPost(diana.id,   "Homemade pasta recipe posted! 🍝", t + 4000);
        System.out.println();

        // ── Likes and Comments ────────────────────────────────────────────────
        System.out.println("── Interactions ──");
        service.likePost(alice.id, p1.id);
        service.likePost(alice.id, p4.id);
        service.likePost(charlie.id, p4.id);
        service.addComment(alice.id, p2.id, "Jealous! 😍");
        service.addComment(bob.id, p3.id, "Same! Can't start the day without it");
        service.likePost(bob.id, p3.id);
        System.out.println();

        // ── Alice's timeline ───────────────────────────────────────────────────
        // Alice follows Bob and Charlie → sees their posts
        System.out.println("════ Alice's Timeline (follows Bob + Charlie) ════");
        service.printTimeline(alice.id, 10);
        System.out.println();

        // ── Bob's timeline ────────────────────────────────────────────────────
        // Bob follows Alice → sees only Alice's posts
        System.out.println("════ Bob's Timeline (follows Alice only) ════");
        service.printTimeline(bob.id, 10);
        System.out.println();

        // ── Charlie's timeline ─────────────────────────────────────────────────
        // Charlie follows Diana → sees Diana's posts
        System.out.println("════ Charlie's Timeline (follows Diana) ════");
        service.printTimeline(charlie.id, 10);
        System.out.println();

        // ── User profile ──────────────────────────────────────────────────────
        System.out.println("════ Bob's Profile ════");
        service.printUserProfile(bob.id);
        System.out.println();

        // ── Unfollow ──────────────────────────────────────────────────────────
        System.out.println("════ Alice unfollows Charlie ════");
        service.unfollow(alice.id, charlie.id);
        System.out.println("Alice's timeline after unfollow:");
        service.printTimeline(alice.id, 10);
    }
}
