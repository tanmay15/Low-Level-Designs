// =============================================================================
// LLD: WHATSAPP
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Register users (name, phone)
//   2. 1-on-1 chat: send messages, mark delivered, mark read
//   3. Message state machine: SENT → DELIVERED → READ
//   4. Group chat: create group, add members, send group messages
//   5. Group message READ when all recipients (except sender) have read it
//   6. User online/offline status
//
// Out of scope: encryption, media, voice/video, message deletion, stories
//
// KEY DESIGN DECISIONS:
//   Chat dedup: chatByPair uses "minId:maxId" as key → O(1) lookup, no loop needed
//   Group READ: readBy Set per message — READ when set.size() == memberCount - 1
//   1-on-1 vs Group: different READ semantics — kept as separate classes
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

class WAUser {
    public String     id;
    public String     name;
    public String     phone;
    public UserStatus status;

    public WAUser(String id, String name, String phone) {
        this.id     = id;
        this.name   = name;
        this.phone  = phone;
        this.status = UserStatus.OFFLINE;
    }

    public void goOnline()  { status = UserStatus.ONLINE; }
    public void goOffline() { status = UserStatus.OFFLINE; }

    @Override
    public String toString() { return name + "[" + status + "]"; }
}

// ── Message ───────────────────────────────────────────────────────────────────
// readBy: only meaningful in group context (tracks who has read the message).
class Message {
    public String          id;
    public String          senderId;
    public String          content;
    public MessageStatus   status;
    public long            timestamp;
    public Set<String>     readBy;   // group only: Set of userIds who have read

    public Message(String id, String senderId, String content) {
        this.id        = id;
        this.senderId  = senderId;
        this.content   = content;
        this.status    = MessageStatus.SENT;
        this.timestamp = System.currentTimeMillis();
        this.readBy    = new HashSet<>();
    }

    @Override
    public String toString() {
        return String.format("[%s] %s: \"%s\" [%s]", id, senderId, content, status);
    }
}

// ── Chat (1-on-1) ─────────────────────────────────────────────────────────────
class Chat {
    public String        id;
    public String        user1Id;
    public String        user2Id;
    public List<Message> messages = new ArrayList<>();

    public Chat(String id, String user1Id, String user2Id) {
        this.id      = id;
        this.user1Id = user1Id;
        this.user2Id = user2Id;
    }
}

// ── Group ─────────────────────────────────────────────────────────────────────
class Group {
    public String        id;
    public String        name;
    public String        adminId;
    public List<String>  memberIds = new ArrayList<>();
    public List<Message> messages  = new ArrayList<>();

    public Group(String id, String name, String adminId) {
        this.id      = id;
        this.name    = name;
        this.adminId = adminId;
        this.memberIds.add(adminId);
    }

    public void addMember(String userId) { memberIds.add(userId); }
}


// =============================================================================
// WHATSAPP SERVICE
// =============================================================================

class WhatsAppService {
    private Map<String, WAUser>  users      = new HashMap<>();
    private Map<String, Chat>    chatByPair = new HashMap<>(); // "minId:maxId" → Chat
    private Map<String, Chat>    chats      = new HashMap<>(); // chatId → Chat
    private Map<String, Group>   groups     = new HashMap<>();

    private int msgCounter   = 0;
    private int chatCounter  = 0;
    private int groupCounter = 0;

    // ── Registration ──────────────────────────────────────────────────────────

    public void registerUser(WAUser user) {
        users.put(user.id, user);
        System.out.println("[WA] Registered: " + user.name);
    }

    public void setOnline(String userId)  { users.get(userId).goOnline();  System.out.println("[WA] " + userId + " ONLINE"); }
    public void setOffline(String userId) { users.get(userId).goOffline(); System.out.println("[WA] " + userId + " OFFLINE"); }

    // ── 1-on-1 Messaging ──────────────────────────────────────────────────────

    // Pair key: always "smallerId:largerId" — same key regardless of who initiates
    private String pairKey(String u1, String u2) {
        return u1.compareTo(u2) < 0 ? u1 + ":" + u2 : u2 + ":" + u1;
    }

    private Chat getOrCreateChat(String u1, String u2) {
        String key = pairKey(u1, u2);
        if (!chatByPair.containsKey(key)) {
            String chatId = "CHAT-" + (++chatCounter);
            Chat chat = new Chat(chatId, u1, u2);
            chatByPair.put(key, chat);
            chats.put(chatId, chat);
            System.out.println("[WA] New chat " + chatId);
        }
        return chatByPair.get(key);
    }

    public Message sendMessage(String senderId, String receiverId, String content) {
        Chat    chat     = getOrCreateChat(senderId, receiverId);
        Message msg      = new Message("MSG-" + (++msgCounter), senderId, content);
        WAUser  receiver = users.get(receiverId);

        if (receiver != null && receiver.status == UserStatus.ONLINE)
            msg.status = MessageStatus.DELIVERED; // auto-deliver if receiver online

        chat.messages.add(msg);
        System.out.println("[WA] " + users.get(senderId).name + " → "
                + users.get(receiverId).name + ": \"" + content + "\" [" + msg.status + "]");
        return msg;
    }

    public void markRead(String chatId, String messageId, String readerId) {
        Chat chat = chats.get(chatId);
        if (chat == null) return;
        for (Message msg : chat.messages) {
            if (msg.id.equals(messageId) && !msg.senderId.equals(readerId)) {
                msg.status = MessageStatus.READ;
                System.out.println("[WA] " + readerId + " READ " + messageId);
            }
        }
    }

    // ── Group Messaging ───────────────────────────────────────────────────────

    public Group createGroup(String adminId, String name, List<String> memberIds) {
        String groupId = "GRP-" + (++groupCounter);
        Group  group   = new Group(groupId, name, adminId);
        for (String m : memberIds) group.addMember(m);
        groups.put(groupId, group);
        System.out.println("[WA] Group \"" + name + "\" [" + groupId + "] members: " + group.memberIds);
        return group;
    }

    public Message sendGroupMessage(String senderId, String groupId, String content) {
        Group group = groups.get(groupId);
        if (group == null || !group.memberIds.contains(senderId))
            throw new RuntimeException("Group not found or not a member");

        Message msg = new Message("MSG-" + (++msgCounter), senderId, content);
        group.messages.add(msg);
        System.out.println("[WA-GRP] " + users.get(senderId).name
                + " → " + group.name + ": \"" + content + "\"");
        return msg;
    }

    // Group message READ when all recipients (non-sender members) have read it
    public void markGroupRead(String groupId, String messageId, String readerId) {
        Group group = groups.get(groupId);
        if (group == null) return;
        for (Message msg : group.messages) {
            if (!msg.id.equals(messageId) || msg.senderId.equals(readerId)) continue;

            msg.readBy.add(readerId);

            int recipients = group.memberIds.size() - 1; // exclude sender
            if (msg.readBy.size() >= recipients) {
                msg.status = MessageStatus.READ;
                System.out.println("[WA-GRP] " + messageId + " READ by all");
            } else {
                msg.status = MessageStatus.DELIVERED;
                System.out.println("[WA-GRP] " + readerId + " read "
                        + messageId + " [" + msg.readBy.size() + "/" + recipients + "]");
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public void printChatHistory(String chatId) {
        Chat chat = chats.get(chatId);
        if (chat == null) return;
        System.out.println("── Chat [" + chatId + "] ──");
        chat.messages.forEach(m -> System.out.println("  " + m));
    }

    public void printGroupHistory(String groupId) {
        Group group = groups.get(groupId);
        if (group == null) return;
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

        wa.registerUser(new WAUser("U1", "Alice",   "+91-9000"));
        wa.registerUser(new WAUser("U2", "Bob",     "+91-9001"));
        wa.registerUser(new WAUser("U3", "Charlie", "+91-9002"));
        System.out.println();

        // ── 1-on-1 chat: both online ───────────────────────────────────────────
        System.out.println("── Scenario 1: 1-on-1 Chat ──");
        wa.setOnline("U1");
        wa.setOnline("U2");
        Message m1 = wa.sendMessage("U1", "U2", "Hey Bob!");
        Message m2 = wa.sendMessage("U2", "U1", "Hey Alice!");
        wa.markRead("CHAT-1", m1.id, "U2");
        wa.markRead("CHAT-1", m2.id, "U1");
        wa.printChatHistory("CHAT-1");
        System.out.println();

        // ── Message to offline user stays SENT ────────────────────────────────
        System.out.println("── Scenario 2: Offline user ──");
        wa.setOffline("U2");
        Message m3 = wa.sendMessage("U1", "U2", "Are you there?");
        System.out.println("  Status: " + m3.status + " (Bob offline — stays SENT)");
        System.out.println();

        // ── Group chat ────────────────────────────────────────────────────────
        System.out.println("── Scenario 3: Group Chat ──");
        wa.setOnline("U3");
        Group group = wa.createGroup("U1", "Friends", Arrays.asList("U2", "U3"));

        Message gm1 = wa.sendGroupMessage("U1", group.id, "Hey everyone!");
        wa.markGroupRead(group.id, gm1.id, "U2");
        wa.markGroupRead(group.id, gm1.id, "U3"); // all read → READ
        System.out.println();

        Message gm2 = wa.sendGroupMessage("U2", group.id, "What's up?");
        wa.markGroupRead(group.id, gm2.id, "U1"); // Charlie hasn't read yet → DELIVERED
        System.out.println();

        wa.printGroupHistory(group.id);
    }
}
