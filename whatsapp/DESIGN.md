# LLD: WhatsApp

> Implementation: `WhatsAppSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Register users with name and phone |
| 2 | 1-on-1 chat: send messages between two users |
| 3 | Message status state machine: SENT → DELIVERED → READ |
| 4 | Group chat: create group, add members, send group messages |
| 5 | Group message READ when ALL recipients (not sender) have read it |
| 6 | User online/offline status tracking |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | At most one Chat between any two users — `getOrCreateChat()` enforces this |
| 2 | `userChatIds` map: O(1) lookup of all chats for a user |

### Out of Scope
End-to-end encryption, media/file sharing, voice/video calls, message deletion, disappearing messages, status stories, broadcast lists

---

## Step 2 — The Core: Message State Machine

```
SENT ──[receiver device gets it]──► DELIVERED ──[receiver opens chat]──► READ
```

| Transition | Trigger |
|------------|---------|
| SENT → DELIVERED | Receiver is ONLINE when message arrives (auto-delivered) OR receiver comes online |
| DELIVERED → READ | Receiver opens the chat and reads the message (`markRead()`) |

**1-on-1 READ**: recipient explicitly calls `markRead(chatId, messageId, readerId)`.

**Group READ**: tracked per member via `message.readBy: Map<userId, Boolean>`. Status becomes READ only when ALL recipients (excluding sender) have read it.

---

## Step 3 — 1-on-1 Chat vs Group Chat

| Aspect | `Chat` (1-on-1) | `Group` |
|--------|----------------|---------|
| Participants | Exactly 2, fixed | Dynamic — add/remove members |
| READ semantics | Other person read it | ALL non-sender members read it |
| Lookup | `getOrCreateChat(u1, u2)` | Created explicitly, group ID used |
| `readBy` tracking | Not needed | `Map<userId, Boolean>` on Message |

The key difference is READ semantics. 1-on-1 READ is simple (one person). Group READ is a collective state — must track individually.

---

## Step 4 — Entities

| Class | Role |
|-------|------|
| `WAUser` | Account with name, phone, online status, lastSeen |
| `Message` | One message — senderId, content, status, timestamp, readBy (group) |
| `Chat` | 1-on-1 container — 2 fixed user IDs, ordered message list |
| `Group` | Group container — dynamic memberIds, ordered message list |
| `WhatsAppService` | Orchestrator — all creation, messaging, status update logic |

### Enums

| Enum | Values |
|------|--------|
| `MessageStatus` | SENT, DELIVERED, READ |
| `UserStatus` | ONLINE, OFFLINE |

---

## Step 5 — `getOrCreateChat()` — Preventing Duplicate Chats

```java
private Chat getOrCreateChat(String u1, String u2) {
    for (String chatId : userChatIds.get(u1)) {
        if (chats.get(chatId).involves(u1, u2)) return chats.get(chatId); // reuse
    }
    // Create new chat — first message between these two users
    Chat chat = new Chat("CHAT-" + (++chatCounter), u1, u2);
    userChatIds.get(u1).add(chat.id);
    userChatIds.get(u2).add(chat.id);
    return chat;
}
```

Without this, two users could accumulate multiple separate chats — a data model error.

---

## Step 6 — Group READ Logic

```java
public void markGroupRead(String groupId, String messageId, String readerId) {
    msg.readBy.put(readerId, true);

    long readCount       = msg.readBy.values().stream().filter(v -> v).count();
    long totalRecipients = group.memberIds.stream()
                               .filter(id -> !id.equals(msg.senderId)).count();

    if (readCount >= totalRecipients) {
        msg.status = MessageStatus.READ;    // all read → READ
    } else {
        msg.status = MessageStatus.DELIVERED; // partial → DELIVERED
    }
}
```

This mirrors real WhatsApp: single grey tick = SENT, double grey = DELIVERED (all received), double blue = READ (all opened).

---

## Step 7 — Class Attributes & Methods

### `WAUser`

| Member | Type | Description |
|--------|------|-------------|
| `id`, `name`, `phone` | String | identity |
| `status` | UserStatus | ONLINE / OFFLINE |
| `lastSeen` | long | epoch ms — set on `goOffline()` |
| `goOnline()` / `goOffline()` | void | update status and lastSeen |

### `Message`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | MSG-N |
| `senderId` | String | who sent it |
| `content` | String | text |
| `status` | MessageStatus | SENT/DELIVERED/READ |
| `readBy` | Map\<String, Boolean\> | per-member read status (group only) |

### `WhatsAppService`

| Method | Description |
|--------|-------------|
| `registerUser(user)` | add user, init userChatIds |
| `setOnline(userId)` / `setOffline(userId)` | toggle status |
| `sendMessage(senderId, receiverId, content)` | 1-on-1 — auto-DELIVERED if receiver online |
| `markRead(chatId, messageId, readerId)` | flip to READ |
| `createGroup(adminId, name, memberIds)` | create Group entity |
| `sendGroupMessage(senderId, groupId, content)` | validate membership, append message |
| `markGroupRead(groupId, messageId, readerId)` | update readBy, check if all have read |

---

## Step 8 — Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| `Chat` and `Group` as separate classes | Yes | Different READ semantics — combining them would add messy conditionals |
| `Message.readBy` only used in group | Yes | Avoids overhead for 1-on-1 where only one person needs to read |
| Auto-DELIVERED if receiver ONLINE | Yes | Simulates real behaviour — delivery happens before explicit open |
| `userChatIds: Map<userId, List<chatId>>` | Yes | O(1) lookup of all chats for a user — avoids scanning all chats |
| `getOrCreateChat()` private | Yes | Internal detail — callers just call `sendMessage()` |

---

## Step 9 — Extensibility

| Extension | How |
|-----------|-----|
| Push notifications | Observer — on `sendMessage()`, notify `PushNotificationService` if receiver OFFLINE |
| Message deletion | Add `deleted` flag on Message — filter in `printChatHistory()` |
| Media messages | Add `mediaUrl` and `mediaType` on Message |
| Group admin operations | Add `removeUser(adminId, groupId, userId)` with admin check |
| Read receipts toggle | Add `readReceiptsEnabled` on WAUser — skip `markRead` update if disabled |

---

## Quick Recall — 3 Main Takeaways

1. **Message state machine**: SENT → DELIVERED → READ. Auto-DELIVERED if receiver is ONLINE when message arrives. READ is always explicit (receiver opens chat).

2. **1-on-1 vs Group READ**: 1-on-1: one person reads → READ. Group: ALL non-sender members must call `markGroupRead()` before status becomes READ. `message.readBy` tracks this per member.

3. **`getOrCreateChat(u1, u2)`**: always called before `sendMessage()` to ensure at most one Chat exists between any two users. Without this you'd accumulate duplicate chats.
