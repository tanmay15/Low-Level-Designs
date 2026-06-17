// =============================================================================
// LLD: WHATSAPP
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Register users (name, phone)
//   2. 1-on-1 chat: send and receive messages between two users
//   3. Message status state machine: SENT → DELIVERED → READ
//      - SENT: message stored by server
//      - DELIVERED: recipient's device received it (recipient is online)
//      - READ: recipient opened the chat
//   4. Group chat: create group, add members, send group message
//   5. Group message READ when all recipients (except sender) have read it
//   6. User online/offline status (LAST SEEN)
//
// Non-Functional:
//   - getOrCreateChat: no duplicate 1-on-1 chats between same two users
//   - userChats map: O(1) lookup of all chats for a user
//
// Out of scope: end-to-end encryption, media/file sharing, voice/video calls,
//   message deletion, disappearing messages, status stories
//
// KEY DESIGN DECISIONS:
//   1-on-1 vs Group: Chat (2 fixed participants) vs Group (dynamic members).
//      Different because group READ = all members read; 1-on-1 READ = just the other person.
//   Message DELIVERED check: if recipient is ONLINE when message is sent → auto-DELIVERED.
//      Otherwise stays SENT until recipient comes online (simulated by markDelivered()).
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum MessageStatus { SENT, DELIVERED, READ }
enum UserStatus    { ONLINE, OFFLINE }


// =============================================================================
// ENTITIES
// =============================================================================

// ── User ──────────────────────────────────────────────────────────────────────
class WAUser {
    public String     id;
    public String     name;
    public String     phone;
    public UserStatus status;
    public long       lastSeen;

    public WAUser(String id, String name, String phone) {
        this.id       = id;
        this.name     = name;
        this.phone    = phone;
        this.status   = UserStatus.OFFLINE;
        this.lastSeen = System.currentTimeMillis();
    }

    public void goOnline()  { status = UserStatus.ONLINE; }
    public void goOffline() { status = UserStatus.OFFLINE; lastSeen = System.currentTimeMillis(); }

    @Override
    public String toString() {
        return String.format("User[%s | %s | %s]", id, name, status);
    }
}

// ── Message ───────────────────────────────────────────────────────────────────
// State machine: SENT → DELIVERED → READ
// For group messages, readBy tracks per-recipient read status.
class Message {
    public String              id;
    public String              senderId;
    public String              content;
    public MessageStatus       status;
    public long                timestamp;
    public Map<String, Boolean> readBy;  // only used in group context

    public Message(String id, String senderId, String content) {
        this.id        = id;
        this.senderId  = senderId;
        this.content   = content;
        this.status    = MessageStatus.SENT;
        this.timestamp = System.currentTimeMillis();
        this.readBy    = new HashMap<>();
    }

    @Override
    public String toString() {
        return String.format("[%s] %s: \"%s\" [%s]", id, senderId, content, status);
    }
}

// ── Chat (1-on-1) ─────────────────────────────────────────────────────────────
// Exactly 2 participants. Created on first message between two users.
class Chat {
    public String        id;
    public String        user1Id;
    public String        user2Id;
    public List<Message> messages;

    public Chat(String id, String user1Id, String user2Id) {
        this.id       = id;
        this.user1Id  = user1Id;
        this.user2Id  = user2Id;
        this.messages = new ArrayList<>();
    }

    public boolean involves(String u1, String u2) {
        return (user1Id.equals(u1) && user2Id.equals(u2))
            || (user1Id.equals(u2) && user2Id.equals(u1));
    }
}

// ── Group ─────────────────────────────────────────────────────────────────────
// Dynamic membership. Message READ = all non-sender members have read it.
class Group {
    public String        id;
    public String        name;
    public String        adminId;
    public List<String>  memberIds;
    public List<Message> messages;

    public Group(String id, String name, String adminId) {
        this.id        = id;
        this.name      = name;
        this.adminId   = adminId;
        this.memberIds = new ArrayList<>();
        this.messages  = new ArrayList<>();
        this.memberIds.add(adminId);
    }

    public void addMember(String userId) { memberIds.add(userId); }
}


// =============================================================================
// WHATSAPP SERVICE
// =============================================================================

class WhatsAppService {
    private Map<String, WAUser>  users      = new HashMap<>();
    private Map<String, Chat>    chats      = new HashMap<>();
    private Map<String, Group>   groups     = new HashMap<>();
    private Map<String, List<String>> userChatIds = new HashMap<>(); // userId → chatIds

    private int msgCounter   = 0;
    private int chatCounter  = 0;
    private int groupCounter = 0;

    // ── Registration ──────────────────────────────────────────────────────────

    public void registerUser(WAUser user) {
        users.put(user.id, user);
        userChatIds.put(user.id, new ArrayList<>());
        System.out.println("[WA] Registered: " + user);
    }

    public void setOnline(String userId)  {
        users.get(userId).goOnline();
        System.out.println("[WA] " + userId + " → ONLINE");
    }

    public void setOffline(String userId) {
        users.get(userId).goOffline();
        System.out.println("[WA] " + userId + " → OFFLINE");
    }

    // ── 1-on-1 Messaging ──────────────────────────────────────────────────────

    // getOrCreateChat: ensures at most one chat exists between any two users.
    private Chat getOrCreateChat(String u1, String u2) {
        for (String chatId : userChatIds.getOrDefault(u1, new ArrayList<>())) {
            if (chats.get(chatId).involves(u1, u2)) return chats.get(chatId);
        }
        String chatId = "CHAT-" + (++chatCounter);
        Chat chat = new Chat(chatId, u1, u2);
        chats.put(chatId, chat);
        userChatIds.get(u1).add(chatId);
        userChatIds.get(u2).add(chatId);
        System.out.println("[WA] Created chat " + chatId + " between " + u1 + " & " + u2);
        return chat;
    }

    public Message sendMessage(String senderId, String receiverId, String content) {
        Chat    chat     = getOrCreateChat(senderId, receiverId);
        Message msg      = new Message("MSG-" + (++msgCounter), senderId, content);
        WAUser  receiver = users.get(receiverId);

        // Auto-deliver if receiver is online
        if (receiver != null && receiver.status == UserStatus.ONLINE) {
            msg.status = MessageStatus.DELIVERED;
        }

        chat.messages.add(msg);
        System.out.println("[WA] " + users.get(senderId).name
                + " → " + users.get(receiverId).name
                + ": \"" + content + "\" [" + msg.status + "]");
        return msg;
    }

    // Called when the recipient opens the chat / reads the message
    public void markRead(String chatId, String messageId, String readerId) {
        Chat chat = chats.get(chatId);
        if (chat == null) return;
        for (Message msg : chat.messages) {
            if (msg.id.equals(messageId) && !msg.senderId.equals(readerId)) {
                msg.status = MessageStatus.READ;
                System.out.println("[WA] " + readerId + " READ message " + messageId);
            }
        }
    }

    // ── Group Messaging ───────────────────────────────────────────────────────

    public Group createGroup(String adminId, String name, List<String> memberIds) {
        String groupId = "GRP-" + (++groupCounter);
        Group  group   = new Group(groupId, name, adminId);
        for (String memberId : memberIds) group.addMember(memberId);
        groups.put(groupId, group);
        System.out.println("[WA] Group \"" + name + "\" created [" + groupId
                + "] — " + group.memberIds.size() + " members: " + group.memberIds);
        return group;
    }

    public Message sendGroupMessage(String senderId, String groupId, String content) {
        Group group = groups.get(groupId);
        if (group == null)                        throw new RuntimeException("Group not found");
        if (!group.memberIds.contains(senderId))  throw new RuntimeException("Not a member");

        Message msg = new Message("MSG-" + (++msgCounter), senderId, content);
        group.messages.add(msg);
        System.out.println("[WA-GRP] " + users.get(senderId).name
                + " → " + group.name + ": \"" + content + "\"");
        return msg;
    }

    // Group READ: tracked per member. Status flips to READ when all recipients have read.
    public void markGroupRead(String groupId, String messageId, String readerId) {
        Group group = groups.get(groupId);
        if (group == null) return;
        for (Message msg : group.messages) {
            if (!msg.id.equals(messageId)) continue;
            if (msg.senderId.equals(readerId)) return; // sender can't "read" own message

            msg.readBy.put(readerId, true);

            long readCount       = msg.readBy.values().stream().filter(v -> v).count();
            long totalRecipients = group.memberIds.stream()
                                       .filter(id -> !id.equals(msg.senderId)).count();

            if (readCount >= totalRecipients) {
                msg.status = MessageStatus.READ;
                System.out.println("[WA-GRP] " + messageId + " → READ by all");
            } else {
                msg.status = MessageStatus.DELIVERED;
                System.out.println("[WA-GRP] " + readerId + " read " + messageId
                        + " [" + readCount + "/" + totalRecipients + " members]");
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public void printChatHistory(String chatId) {
        Chat chat = chats.get(chatId);
        if (chat == null) { System.out.println("Chat not found"); return; }
        System.out.println("── Chat [" + chatId + "] ──");
        chat.messages.forEach(m -> System.out.println("  " + m));
    }

    public void printGroupHistory(String groupId) {
        Group group = groups.get(groupId);
        if (group == null) { System.out.println("Group not found"); return; }
        System.out.println("── Group [" + group.name + "] ──");
        group.messages.forEach(m -> System.out.println("  " + m));
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class WhatsAppSolution {
    public static void main(String[] args) {
        System.out.println("=== WhatsApp Demo ===\n");

        WhatsAppService wa = new WhatsAppService();

        WAUser alice   = new WAUser("U1", "Alice",   "+91-9000");
        WAUser bob     = new WAUser("U2", "Bob",     "+91-9001");
        WAUser charlie = new WAUser("U3", "Charlie", "+91-9002");
        wa.registerUser(alice);
        wa.registerUser(bob);
        wa.registerUser(charlie);
        System.out.println();

        // ── Scenario 1: 1-on-1 chat, both online ──────────────────────────────
        System.out.println("── Scenario 1: 1-on-1 Chat (both online) ──");
        wa.setOnline("U1");
        wa.setOnline("U2");
        Message m1 = wa.sendMessage("U1", "U2", "Hey Bob!");
        Message m2 = wa.sendMessage("U2", "U1", "Hey Alice! How are you?");
        wa.markRead("CHAT-1", m1.id, "U2");
        wa.markRead("CHAT-1", m2.id, "U1");
        wa.printChatHistory("CHAT-1");
        System.out.println();

        // ── Scenario 2: message to offline user stays SENT ────────────────────
        System.out.println("── Scenario 2: Message to offline user ──");
        wa.setOffline("U2");
        Message m3 = wa.sendMessage("U1", "U2", "Are you there?");
        System.out.println("  Status: " + m3.status + " (Bob is offline — no delivery yet)");
        System.out.println();

        // ── Scenario 3: group chat ────────────────────────────────────────────
        System.out.println("── Scenario 3: Group Chat ──");
        wa.setOnline("U3");
        Group group = wa.createGroup("U1", "Friends", Arrays.asList("U2", "U3"));

        Message gm1 = wa.sendGroupMessage("U1", group.id, "Hey everyone!");
        wa.markGroupRead(group.id, gm1.id, "U2");  // Bob reads
        wa.markGroupRead(group.id, gm1.id, "U3");  // Charlie reads → all read → READ
        System.out.println();

        Message gm2 = wa.sendGroupMessage("U2", group.id, "Hey Alice!");
        wa.markGroupRead(group.id, gm2.id, "U1");  // Alice reads — Charlie hasn't
        System.out.println();

        wa.printGroupHistory(group.id);
    }
}
