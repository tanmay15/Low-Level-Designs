// =============================================================================
// LLD: NOTIFICATION SERVICE
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================


// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. User can subscribe to a notification type on a specific channel
//   2. User can unsubscribe from a channel for a notification type
//   3. System sends a notification to a user via all their subscribed channels
//   4. If one channel fails, others still deliver (no all-or-nothing failure)
//
// Non-Functional:
//   - Adding a new channel must not change existing code (Open/Closed Principle)
//   - A user may subscribe to multiple channels for the same notification type
//   - Delivery failures are logged but do not crash the overall flow
//
// Out of scope: Scheduling, retry logic, read receipts, notification history UI
// =============================================================================


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum NotificationType {
  BOOKING_CONFIRMED = "BOOKING_CONFIRMED",
  BOOKING_CANCELLED = "BOOKING_CANCELLED",
  PAYMENT_SUCCESS = "PAYMENT_SUCCESS",
  PROMOTIONAL = "PROMOTIONAL",
}

enum NotificationStatus {
  PENDING = "PENDING",
  SENT = "SENT",
  FAILED = "FAILED",
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:     User, Notification
// Interface:    NotificationChannel  (Strategy pattern)
// Channels:     EmailChannel, SMSChannel, PushChannel
// Service:      NotificationService  (Observer pattern — manages subscriptions)
//
// Relationships:
//   NotificationService  USES (Dependency)   NotificationChannel[]  (via subscription map)
//   Notification         HAS-A (Aggregation) User
//
// Two patterns working together:
//   Observer  → who gets notified and when  (subscription + fan-out)
//   Strategy  → how each channel delivers   (EmailChannel, SMSChannel, PushChannel)
// =============================================================================


// ── User ──────────────────────────────────────────────────────────────────────
// Holds all channel-specific delivery addresses in one place.

class User {
  public id: string;
  public name: string;
  public email: string;
  public phone: string;
  public deviceToken: string;

  constructor(id: string, name: string, email: string, phone: string, deviceToken: string) {
    this.id = id;
    this.name = name;
    this.email = email;
    this.phone = phone;
    this.deviceToken = deviceToken;
  }
}


// ── Notification ──────────────────────────────────────────────────────────────
// The message being sent. Created fresh per notify() call.
// Status is updated after each send attempt.

class Notification {
  public id: string;
  public type: NotificationType;
  public title: string;
  public body: string;
  public recipient: User;
  public status: NotificationStatus;
  public createdAt: Date;

  constructor(
    id: string,
    type: NotificationType,
    title: string,
    body: string,
    recipient: User
  ) {
    this.id = id;
    this.type = type;
    this.title = title;
    this.body = body;
    this.recipient = recipient;
    this.status = NotificationStatus.PENDING;
    this.createdAt = new Date();
  }
}


// ── NotificationChannel (Strategy Pattern) ────────────────────────────────────
// Interface defines the contract. Each channel implements delivery differently.
// To add a new channel: implement this interface. Nothing else changes.

interface NotificationChannel {
  send(notification: Notification, user: User): void;
  getChannelName(): string;
}

class EmailChannel implements NotificationChannel {
  getChannelName(): string {
    return "EMAIL";
  }

  send(notification: Notification, user: User): void {
    // In real system: call SendGrid / AWS SES API
    console.log(`  [EMAIL]  → ${user.email} | Subject: "${notification.title}" | ${notification.body}`);
  }
}

class SMSChannel implements NotificationChannel {
  getChannelName(): string {
    return "SMS";
  }

  send(notification: Notification, user: User): void {
    // In real system: call Twilio / AWS SNS API
    console.log(`  [SMS]    → ${user.phone} | "${notification.title}: ${notification.body}"`);
  }
}

class PushChannel implements NotificationChannel {
  getChannelName(): string {
    return "PUSH";
  }

  send(notification: Notification, user: User): void {
    // In real system: call FCM (Firebase) / APNs (Apple)
    console.log(`  [PUSH]   → device:${user.deviceToken} | "${notification.title}"`);
  }
}


// ── NotificationService (Observer Pattern) ────────────────────────────────────
// Acts as the Observer subject.
// Users subscribe to notification types on specific channels.
// notify() fans out to all subscribed channels for that user + type.

class NotificationService {
  // userId → NotificationType → list of channels
  private subscriptions: Map<string, Map<NotificationType, NotificationChannel[]>>;
  private notificationCounter: number;

  constructor() {
    this.subscriptions = new Map();
    this.notificationCounter = 0;
  }

  subscribe(user: User, type: NotificationType, channel: NotificationChannel): void {
    if (!this.subscriptions.has(user.id)) {
      this.subscriptions.set(user.id, new Map());
    }

    const userSubs = this.subscriptions.get(user.id)!;

    if (!userSubs.has(type)) {
      userSubs.set(type, []);
    }

    userSubs.get(type)!.push(channel);
    console.log(`[SUBSCRIBED]   ${user.name} → ${type} via ${channel.getChannelName()}`);
  }

  unsubscribe(user: User, type: NotificationType, channelName: string): void {
    const userSubs = this.subscriptions.get(user.id);
    if (!userSubs) return;

    const channels = userSubs.get(type);
    if (!channels) return;

    userSubs.set(type, channels.filter((c) => c.getChannelName() !== channelName));
    console.log(`[UNSUBSCRIBED] ${user.name} from ${type} via ${channelName}`);
  }

  notify(user: User, type: NotificationType, title: string, body: string): void {
    const notification = new Notification(
      `NOTIF-${++this.notificationCounter}`,
      type,
      title,
      body,
      user
    );

    console.log(`\n[NOTIFY] ${notification.id} → ${user.name} | Type: ${type}`);

    const userSubs = this.subscriptions.get(user.id);
    if (!userSubs || !userSubs.has(type) || userSubs.get(type)!.length === 0) {
      console.log(`  No subscriptions for ${user.name} on type ${type}`);
      notification.status = NotificationStatus.FAILED;
      return;
    }

    const channels = userSubs.get(type)!;
    let allSent = true;

    for (const channel of channels) {
      try {
        channel.send(notification, user);
      } catch (e: any) {
        // One channel failing does not stop others
        console.log(`  [ERROR] ${channel.getChannelName()} failed: ${e.message}`);
        allSent = false;
      }
    }

    notification.status = allSent ? NotificationStatus.SENT : NotificationStatus.FAILED;
  }
}


// =============================================================================
// STEP 4 — DEMO
// =============================================================================

console.log("=== Notification Service Demo ===\n");

const service = new NotificationService();

const emailChannel = new EmailChannel();
const smsChannel = new SMSChannel();
const pushChannel = new PushChannel();

const alice = new User("U1", "Alice", "alice@email.com", "+91-9876543210", "device-token-alice");
const bob = new User("U2", "Bob", "bob@email.com", "+91-9123456789", "device-token-bob");

// Alice subscribes to booking confirmations on all three channels
service.subscribe(alice, NotificationType.BOOKING_CONFIRMED, emailChannel);
service.subscribe(alice, NotificationType.BOOKING_CONFIRMED, smsChannel);
service.subscribe(alice, NotificationType.BOOKING_CONFIRMED, pushChannel);

// Alice subscribes to promotions on email only
service.subscribe(alice, NotificationType.PROMOTIONAL, emailChannel);

// Bob subscribes to payment success on SMS + push
service.subscribe(bob, NotificationType.PAYMENT_SUCCESS, smsChannel);
service.subscribe(bob, NotificationType.PAYMENT_SUCCESS, pushChannel);

console.log();

// Trigger notifications
service.notify(alice, NotificationType.BOOKING_CONFIRMED, "Booking Confirmed!", "Your booking BKG-1 for Interstellar is confirmed.");
service.notify(alice, NotificationType.PROMOTIONAL, "Weekend Offer!", "Get 20% off on all Gold seats this weekend.");
service.notify(bob, NotificationType.PAYMENT_SUCCESS, "Payment Successful", "₹650 paid for booking BKG-2.");

// No subscription case
service.notify(bob, NotificationType.PROMOTIONAL, "Weekend Offer!", "Get 20% off on all Gold seats.");

// Unsubscribe Alice from SMS for booking confirmed
console.log();
service.unsubscribe(alice, NotificationType.BOOKING_CONFIRMED, "SMS");

// Notify again — SMS should not fire
service.notify(alice, NotificationType.BOOKING_CONFIRMED, "Booking Update", "Your seat has been upgraded to Platinum.");
