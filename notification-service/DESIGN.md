# LLD Design: Notification Service

> **Sync note:** Design companion to `notification-service.ts`. Keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
1. User can subscribe to a notification type on a specific channel
2. User can unsubscribe from a channel for a notification type
3. System sends a notification to a user via all their subscribed channels for that type
4. If one channel fails, others still deliver (no all-or-nothing failure)

### Non-Functional
- Adding a new channel must not change any existing classes (Open/Closed Principle)
- A user may subscribe to multiple channels for the same notification type
- Delivery failures are logged but do not crash the overall notify flow

### Out of Scope
- Notification scheduling
- Retry logic on failure
- Read receipts / delivery acknowledgement
- Notification history UI

---

## Step 2 — Entities

| Noun | Becomes | Reason |
|---|---|---|
| User | Class | Holds all channel-specific delivery addresses |
| Notification | Class | The message — has its own status (PENDING / SENT / FAILED) |
| NotificationType | Enum | Fixed set: BOOKING_CONFIRMED, PAYMENT_SUCCESS, PROMOTIONAL, etc. |
| NotificationStatus | Enum | Fixed states: PENDING / SENT / FAILED |
| NotificationChannel | **Interface** | Behavior varies per channel → Strategy pattern |
| EmailChannel, SMSChannel, PushChannel | Classes | Concrete channel implementations |
| NotificationService | Service Class | Observer subject — manages subscriptions + fans out delivery |

---

## Step 3 — Class Design

---

### `User`
- **Attributes:** `id`, `name`, `email`, `phone`, `deviceToken`
- **Methods:** None
- **Access:** All public
- **Note:** Holds all delivery addresses. In a real system, these could be a separate `UserContact` class.

---

### `Notification`
- **Attributes:** `id`, `type: NotificationType`, `title`, `body`, `recipient: User`, `status: NotificationStatus`, `createdAt: Date`
- **Methods:** None
- **Note:** Created fresh per `notify()` call. Status updated after send attempts.

---

### `NotificationChannel` *(Interface — Strategy)*
- **Method:** `send(notification, user): void`, `getChannelName(): string`
- **Implementations:**
  - `EmailChannel` → sends to `user.email`
  - `SMSChannel` → sends to `user.phone`
  - `PushChannel` → sends to `user.deviceToken`
- **Note:** To add WhatsApp — implement this interface. Zero changes to `NotificationService`.

---

### `NotificationService` *(Observer subject)*
- **Attributes:**
  - `subscriptions: Map<userId, Map<NotificationType, NotificationChannel[]>>` — private
  - `notificationCounter: number` — private
- **Methods:** `subscribe(user, type, channel)`, `unsubscribe(user, type, channelName)`, `notify(user, type, title, body)`
- **Note:** The Observer subject. Users register interest (subscribe). When `notify()` fires, service fans out to all subscribed channels for that user + type.

---

## Step 4 — Relationships

| From | To | Type | Why |
|---|---|---|---|
| `NotificationService` | `NotificationChannel[]` | **Dependency (Uses)** | Channels stored in subscription map and invoked on notify |
| `Notification` | `User` | **Aggregation** | User exists independently |

No Composition here — the service holds a subscription map, not the channels themselves permanently. Channels are shared objects (same `emailChannel` instance reused across subscriptions).

---

## Step 5 — Design Patterns

### Observer → `NotificationService`
- **Why:** Users want to be notified of events without the event source knowing about them
- **How:** `subscribe()` registers a channel for a user+type pair. `notify()` fans out to all registered channels
- **Interview line:** *"Observer pattern — users register interest in event types. The service is the subject; when an event fires it fans out to all subscribed channels for that user."*

### Strategy → `NotificationChannel`
- **Why:** Email, SMS, Push all deliver the same notification differently
- **How:** Common `NotificationChannel` interface. Each class handles its own delivery logic
- **Interview line:** *"Strategy on the channel means the service just calls `send()` — it doesn't care if it's email or SMS. Adding WhatsApp is a single new class."*

### How the two patterns work together
- **Observer** answers: *who gets notified and when?*
- **Strategy** answers: *how is each notification delivered?*

---

## Step 6 — Service Class Decision

`NotificationService` is a clear separate service. `User` is a participant, `NotificationChannel` is a delivery mechanism — neither owns subscription management or fan-out logic.

---

## Step 7 — Extensibility

| Change Request | What changes |
|---|---|
| Add WhatsApp channel | Create `WhatsAppChannel implements NotificationChannel`. Subscribe users to it. Nothing else changes. |
| Add new notification type | Add to `NotificationType` enum. All channels and subscriptions work as-is. |
| Add notification scheduling | Add `scheduledAt: Date` to `Notification`. Check in `notify()` before dispatching. |
| Add retry on failure | Wrap `channel.send()` in retry logic inside `notify()`. Channel implementations unchanged. |
| Add notification history | Add `notificationLog: Notification[]` to service. Push each notification after send. |
| Broadcast to all users | Add `notifyAll(userIds, type, title, body)` — loop over users calling `notify()`. |

---

## Quick Recall

```
NotificationService (Observer subject)
  subscriptions: Map<userId, Map<NotificationType, Channel[]>>

subscribe(user, type, channel)  → adds channel to user's subscription for that type
unsubscribe(user, type, name)   → removes channel from subscription

notify(user, type, title, body):
  → create Notification (PENDING)
  → look up subscriptions[userId][type]
  → call channel.send() for each subscribed channel
  → if one fails, log error, continue others

Patterns:  Observer (NotificationService manages subscriptions + fan-out)
           Strategy (NotificationChannel — Email / SMS / Push implement differently)
Service:   NotificationService — clearly separate, entities are participants
```
