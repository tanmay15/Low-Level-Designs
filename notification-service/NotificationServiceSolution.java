// =============================================================================
// LLD: NOTIFICATION SERVICE — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Users can subscribe/unsubscribe to notification types per channel
//   2. System can send notifications to a user via subscribed channels
//   3. Support multiple notification types: ORDER_UPDATE, PROMOTION, ALERT
//   4. Support multiple delivery channels: EMAIL, SMS, PUSH
//   5. Each notification has a status: PENDING, SENT, FAILED
//
// Non-Functional:
//   - Adding a new channel must not change NotificationService (Strategy pattern)
//   - NotificationService fans out to all subscribed channels (Observer pattern)
//   - Each channel handles delivery failure independently
//
// Out of scope: Retry logic, notification history/persistence, rate limiting
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum NotificationType { ORDER_UPDATE, PROMOTION, ALERT }

enum NotificationStatus { PENDING, SENT, FAILED }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:   User, Notification
// Interface:  NotificationChannel  (Strategy pattern)
// Channels:   EmailChannel, SMSChannel, PushChannel
// Service:    NotificationService  (Observer subject)
//
// Relationships:
//   NotificationService HAS-A (Composition) subscriptions Map
//   subscriptions: userId → { NotificationType → NotificationChannel[] }
//   NotificationChannel implementations carry delivery logic (Strategy)
// =============================================================================


// ── User ──────────────────────────────────────────────────────────────────────

class User {
    public String id;
    public String name;
    public String email;
    public String phone;
    public String deviceToken;

    public User(String id, String name, String email, String phone, String deviceToken) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.deviceToken = deviceToken;
    }
}


// ── Notification ──────────────────────────────────────────────────────────────

class Notification {
    public String id;
    public String userId;
    public NotificationType type;
    public String title;
    public String message;
    public NotificationStatus status;
    public Date createdAt;

    public Notification(String id, String userId, NotificationType type, String title, String message) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.status = NotificationStatus.PENDING;
        this.createdAt = new Date();
    }
}


// ── NotificationChannel (Strategy Pattern) ───────────────────────────────────
// Each channel encapsulates a delivery mechanism.
// Adding a new channel = add a new class. Nothing else changes.

interface NotificationChannel {
    void send(User user, Notification notification);
}

class EmailChannel implements NotificationChannel {
    @Override
    public void send(User user, Notification notification) {
        notification.status = NotificationStatus.SENT;
        System.out.println("  [EMAIL] → " + user.email + " | " + notification.title);
    }
}

class SMSChannel implements NotificationChannel {
    @Override
    public void send(User user, Notification notification) {
        notification.status = NotificationStatus.SENT;
        System.out.println("  [SMS]   → " + user.phone + " | " + notification.title);
    }
}

class PushChannel implements NotificationChannel {
    @Override
    public void send(User user, Notification notification) {
        if (user.deviceToken == null || user.deviceToken.isEmpty()) {
            notification.status = NotificationStatus.FAILED;
            System.out.println("  [PUSH]  → FAILED (no device token for " + user.name + ")");
            return;
        }
        notification.status = NotificationStatus.SENT;
        System.out.println("  [PUSH]  → " + user.deviceToken + " | " + notification.title);
    }
}


// ── NotificationService (Observer Subject) ────────────────────────────────────
// Manages subscriptions and fans out notifications to all subscribed channels.
// This is the Observer subject — users and channels are the observers.

class NotificationService {
    // userId → { NotificationType → List<NotificationChannel> }
    private Map<String, Map<NotificationType, List<NotificationChannel>>> subscriptions;
    private int notifCounter;

    public NotificationService() {
        this.subscriptions = new HashMap<>();
        this.notifCounter = 0;
    }

    public void subscribe(String userId, NotificationType type, NotificationChannel channel) {
        subscriptions.putIfAbsent(userId, new HashMap<>());
        Map<NotificationType, List<NotificationChannel>> userSubs = subscriptions.get(userId);
        userSubs.putIfAbsent(type, new ArrayList<>());
        userSubs.get(type).add(channel);
        System.out.println("[SUBSCRIBE] User " + userId + " → " + type + " via " + channel.getClass().getSimpleName());
    }

    public void unsubscribe(String userId, NotificationType type, NotificationChannel channel) {
        Map<NotificationType, List<NotificationChannel>> userSubs = subscriptions.get(userId);
        if (userSubs == null) return;
        List<NotificationChannel> channels = userSubs.get(type);
        if (channels == null) return;
        channels.remove(channel);
        System.out.println("[UNSUBSCRIBE] User " + userId + " → " + type + " via " + channel.getClass().getSimpleName());
    }

    // Fan-out: deliver to every subscribed channel for this type
    public void notify(User user, NotificationType type, String title, String message) {
        Map<NotificationType, List<NotificationChannel>> userSubs = subscriptions.get(user.id);
        if (userSubs == null || !userSubs.containsKey(type)) {
            System.out.println("[NOTIFY] No subscriptions for user " + user.id + " on type " + type);
            return;
        }

        Notification notification = new Notification("N-" + (++notifCounter), user.id, type, title, message);
        System.out.println("[NOTIFY] " + user.name + " | " + type + " | \"" + title + "\"");

        List<NotificationChannel> channels = userSubs.get(type);
        for (NotificationChannel channel : channels) {
            channel.send(user, notification);
        }
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: NotificationServiceSolution.java
// =============================================================================

public class NotificationServiceSolution {
    public static void main(String[] args) {
        System.out.println("=== Notification Service Demo ===\n");

        NotificationService ns = new NotificationService();

        User alice = new User("U1", "Alice", "alice@email.com", "+91-9000000001", "device-token-alice");
        User bob = new User("U2", "Bob", "bob@email.com", "+91-9000000002", "");

        NotificationChannel email = new EmailChannel();
        NotificationChannel sms = new SMSChannel();
        NotificationChannel push = new PushChannel();

        // Alice subscribes to ORDER_UPDATE via Email + Push
        ns.subscribe(alice.id, NotificationType.ORDER_UPDATE, email);
        ns.subscribe(alice.id, NotificationType.ORDER_UPDATE, push);
        // Alice subscribes to PROMOTION via SMS only
        ns.subscribe(alice.id, NotificationType.PROMOTION, sms);

        // Bob subscribes to ALERT via Email + SMS
        ns.subscribe(bob.id, NotificationType.ALERT, email);
        ns.subscribe(bob.id, NotificationType.ALERT, sms);
        // Bob has no device token — push will fail
        ns.subscribe(bob.id, NotificationType.ORDER_UPDATE, push);

        System.out.println();

        // Send ORDER_UPDATE to Alice → Email + Push
        ns.notify(alice, NotificationType.ORDER_UPDATE, "Order Shipped", "Your order #1234 has been shipped");
        System.out.println();

        // Send PROMOTION to Alice → SMS
        ns.notify(alice, NotificationType.PROMOTION, "50% Off Today!", "Use code SAVE50 at checkout");
        System.out.println();

        // Send ALERT to Bob → Email + SMS
        ns.notify(bob, NotificationType.ALERT, "Login from new device", "New login detected on your account");
        System.out.println();

        // Bob gets ORDER_UPDATE via Push → fails (no device token)
        ns.notify(bob, NotificationType.ORDER_UPDATE, "Order Confirmed", "Your order has been placed");
        System.out.println();

        // Alice has no ALERT subscription
        ns.notify(alice, NotificationType.ALERT, "Price Drop", "Item in your wishlist is cheaper now");
        System.out.println();

        // Alice unsubscribes from ORDER_UPDATE push, then send again
        ns.unsubscribe(alice.id, NotificationType.ORDER_UPDATE, push);
        System.out.println();
        ns.notify(alice, NotificationType.ORDER_UPDATE, "Order Delivered", "Your order has been delivered");
    }
}
