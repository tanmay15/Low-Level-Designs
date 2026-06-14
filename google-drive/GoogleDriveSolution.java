// =============================================================================
// LLD: GOOGLE DRIVE — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Users can create files and folders in a hierarchical structure
//   2. Folders can contain files and other folders (tree, any depth)
//   3. Files support versioning — each upload adds a new version; history is preserved
//   4. Owner can share any item with other users: VIEW or EDIT permission
//   5. Every operation checks permission before proceeding
//   6. Users can rename, move, and delete items (delete folder removes all children)
//   7. Users can search all accessible items by name keyword
//
// Non-Functional:
//   - File and Folder are treated uniformly as DriveItem (Composite Pattern)
//   - Permission check is enforced at service level before every mutation
//   - getSize() on a folder recursively sums all descendants (Composite behaviour)
//
// Out of scope: Binary file storage, real-time sync, offline mode, trash/recycle bin
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

// Ordered: VIEW < EDIT < OWNER — ordinal() used for >= permission comparison
enum Permission { VIEW, EDIT, OWNER }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
//
// THE KEY PATTERN — Composite:
//   DriveItem  (abstract)  ← common contract for File and Folder
//   File       extends DriveItem ← LEAF node, holds data + version list
//   Folder     extends DriveItem ← COMPOSITE node, holds List<DriveItem> children
//
//   Because both extend DriveItem, you can call getSize() / hasPermission()
//   on either without knowing whether it is a File or Folder.
//   Folder.getSize() recursively sums children — the hallmark of Composite.
//
// Relationships:
//   Folder     HAS-A (Composition)   List<DriveItem>   (children)
//   File       HAS-A (Composition)   List<FileVersion> (version history)
//   DriveItem  HAS-A                 Map<userId, Permission>
//   DriveService OWNS                Map<itemId, DriveItem>  (flat store)
// =============================================================================


// ── User ──────────────────────────────────────────────────────────────────────

class User {
    public String id;
    public String name;
    public String email;

    public User(String id, String name, String email) {
        this.id    = id;
        this.name  = name;
        this.email = email;
    }
}


// ── FileVersion ───────────────────────────────────────────────────────────────

class FileVersion {
    public String versionId;
    public long   size;         // bytes
    public String uploadedBy;  // userId
    public Date   uploadedAt;

    public FileVersion(String versionId, long size, String uploadedBy) {
        this.versionId  = versionId;
        this.size       = size;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = new Date();
    }
}


// ── DriveItem (Abstract Class — Composite root) ───────────────────────────────
// Holds everything common to both File and Folder:
//   identity fields, permission map, permission helper methods.
// Subclasses implement: isFolder(), getSize().
//
// WHY ABSTRACT CLASS (not interface)?
//   Both File and Folder share state: id, name, ownerId, parentId, permissions map.
//   Interface cannot hold mutable instance state.
//   Abstract class lets us define the shared fields + shared permission logic once.

abstract class DriveItem {
    public String id;
    public String name;
    public String ownerId;
    public String parentId;  // null = root level
    public Date   createdAt;
    protected Map<String, Permission> permissions; // userId → Permission

    public DriveItem(String id, String name, String ownerId, String parentId) {
        this.id          = id;
        this.name        = name;
        this.ownerId     = ownerId;
        this.parentId    = parentId;
        this.createdAt   = new Date();
        this.permissions = new HashMap<>();
    }

    // Owner always has full access; others checked against stored permission
    public boolean hasPermission(String userId, Permission required) {
        if (userId.equals(ownerId)) return true;
        Permission userPerm = permissions.get(userId);
        if (userPerm == null) return false;
        return userPerm.ordinal() >= required.ordinal();
    }

    public void grantPermission(String userId, Permission permission) {
        permissions.put(userId, permission);
    }

    public void revokePermission(String userId) {
        permissions.remove(userId);
    }

    public Permission getPermission(String userId) {
        if (userId.equals(ownerId)) return Permission.OWNER;
        return permissions.getOrDefault(userId, null);
    }

    // Each subclass defines these two
    public abstract boolean isFolder();
    public abstract long getSize();
}


// ── File (Leaf node) ──────────────────────────────────────────────────────────
// A leaf in the Composite tree. Cannot contain children.
// Holds a version list — every upload adds a new version; current = last.

class File extends DriveItem {
    public String mimeType;
    private List<FileVersion> versions;

    public File(String id, String name, String ownerId, String parentId, String mimeType) {
        super(id, name, ownerId, parentId);
        this.mimeType = mimeType;
        this.versions = new ArrayList<>();
    }

    @Override
    public boolean isFolder() { return false; }

    @Override
    public long getSize() {
        if (versions.isEmpty()) return 0;
        return versions.get(versions.size() - 1).size; // current version size
    }

    public FileVersion addVersion(String versionId, long size, String uploadedBy) {
        FileVersion v = new FileVersion(versionId, size, uploadedBy);
        versions.add(v);
        return v;
    }

    public FileVersion getCurrentVersion() {
        if (versions.isEmpty()) return null;
        return versions.get(versions.size() - 1);
    }

    public List<FileVersion> getVersionHistory() { return versions; }
    public int getVersionCount()                  { return versions.size(); }
}


// ── Folder (Composite node) ───────────────────────────────────────────────────
// A composite in the Composite tree. Can contain Files and other Folders.
// getSize() recursively sums all descendants — the hallmark of Composite Pattern.

class Folder extends DriveItem {
    private List<DriveItem> children;

    public Folder(String id, String name, String ownerId, String parentId) {
        super(id, name, ownerId, parentId);
        this.children = new ArrayList<>();
    }

    @Override
    public boolean isFolder() { return true; }

    // Composite behaviour: size = sum of all children's sizes (recursive)
    @Override
    public long getSize() {
        long total = 0;
        for (DriveItem child : children) total += child.getSize();
        return total;
    }

    public void addChild(DriveItem item)   { children.add(item); }
    public List<DriveItem> getChildren()   { return children; }

    public boolean removeChild(String itemId) {
        return children.removeIf(c -> c.id.equals(itemId));
    }
}


// ── DriveService ──────────────────────────────────────────────────────────────
// Orchestrates all operations. Enforces permissions before every write.
// Flat items map (itemId → DriveItem) allows O(1) lookup by id.
// Tree structure is maintained via parent-child references within DriveItem objects.

class DriveService {
    private Map<String, User>      users;
    private Map<String, DriveItem> items;  // flat store: itemId → DriveItem
    private int                    itemCounter;
    private int                    versionCounter;

    public DriveService() {
        this.users          = new HashMap<>();
        this.items          = new HashMap<>();
        this.itemCounter    = 0;
        this.versionCounter = 0;
    }

    public void registerUser(User user) {
        users.put(user.id, user);
        System.out.println("[USER] Registered: " + user.name);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public Folder createFolder(String userId, String parentId, String name) {
        if (parentId != null) checkPermission(userId, parentId, Permission.EDIT);
        Folder folder = new Folder("F-" + (++itemCounter), name, userId, parentId);
        items.put(folder.id, folder);
        if (parentId != null) ((Folder) items.get(parentId)).addChild(folder);
        System.out.println("[CREATE FOLDER] \"" + name + "\" (id: " + folder.id + ")" +
                (parentId != null ? " inside " + items.get(parentId).name : " [root]"));
        return folder;
    }

    public File createFile(String userId, String parentId, String name,
                           String mimeType, long size) {
        if (parentId != null) checkPermission(userId, parentId, Permission.EDIT);
        File file = new File("FL-" + (++itemCounter), name, userId, parentId, mimeType);
        file.addVersion("V" + (++versionCounter), size, userId);
        items.put(file.id, file);
        if (parentId != null) ((Folder) items.get(parentId)).addChild(file);
        System.out.println("[CREATE FILE] \"" + name + "\" (id: " + file.id + ", " +
                formatSize(size) + ")" +
                (parentId != null ? " inside " + items.get(parentId).name : " [root]"));
        return file;
    }

    // ── Versioning ────────────────────────────────────────────────────────────

    public FileVersion uploadNewVersion(String userId, String fileId, long size) {
        checkPermission(userId, fileId, Permission.EDIT);
        DriveItem item = getItemChecked(fileId);
        if (item.isFolder()) throw new RuntimeException("Cannot upload version to a folder");
        File file = (File) item;
        FileVersion version = file.addVersion("V" + (++versionCounter), size, userId);
        System.out.println("[VERSION] \"" + file.name + "\" v" + file.getVersionCount() +
                " uploaded by " + userName(userId) + " (" + formatSize(size) + ")");
        return version;
    }

    public void printVersionHistory(String userId, String fileId) {
        checkPermission(userId, fileId, Permission.VIEW);
        File file = (File) getItemChecked(fileId);
        System.out.println("\n── Version history: \"" + file.name + "\" ──");
        List<FileVersion> history = file.getVersionHistory();
        for (int i = 0; i < history.size(); i++) {
            FileVersion v = history.get(i);
            System.out.println("  v" + (i + 1) + " | " + formatSize(v.size) +
                    " | uploaded by " + userName(v.uploadedBy) +
                    (i == history.size() - 1 ? " ← current" : ""));
        }
        System.out.println();
    }

    // ── Sharing ───────────────────────────────────────────────────────────────

    public void shareItem(String requesterId, String itemId,
                          String targetUserId, Permission permission) {
        // Only OWNER or EDIT users can share (owner always passes; EDIT can share VIEW only)
        DriveItem item = getItemChecked(itemId);
        if (!item.hasPermission(requesterId, Permission.OWNER)) {
            throw new RuntimeException("Only the owner can share items");
        }
        item.grantPermission(targetUserId, permission);
        System.out.println("[SHARE] \"" + item.name + "\" shared with " +
                userName(targetUserId) + " (" + permission + ")");
    }

    public void revokeAccess(String requesterId, String itemId, String targetUserId) {
        DriveItem item = getItemChecked(itemId);
        if (!item.hasPermission(requesterId, Permission.OWNER)) {
            throw new RuntimeException("Only the owner can revoke access");
        }
        item.revokePermission(targetUserId);
        System.out.println("[REVOKE] " + userName(targetUserId) +
                "'s access to \"" + item.name + "\" revoked");
    }

    // ── Rename / Move / Delete ────────────────────────────────────────────────

    public void rename(String userId, String itemId, String newName) {
        checkPermission(userId, itemId, Permission.EDIT);
        DriveItem item = getItemChecked(itemId);
        String oldName = item.name;
        item.name = newName;
        System.out.println("[RENAME] \"" + oldName + "\" → \"" + newName + "\"");
    }

    public void moveItem(String userId, String itemId, String newParentId) {
        checkPermission(userId, itemId, Permission.EDIT);
        checkPermission(userId, newParentId, Permission.EDIT);
        DriveItem item      = getItemChecked(itemId);
        DriveItem newParent = getItemChecked(newParentId);
        if (!newParent.isFolder()) throw new RuntimeException("Target must be a folder");

        // Remove from old parent
        if (item.parentId != null) {
            ((Folder) items.get(item.parentId)).removeChild(itemId);
        }
        // Add to new parent
        ((Folder) newParent).addChild(item);
        item.parentId = newParentId;
        System.out.println("[MOVE] \"" + item.name + "\" → \"" + newParent.name + "\"");
    }

    // Recursively deletes folder and all descendants
    public void deleteItem(String userId, String itemId) {
        checkPermission(userId, itemId, Permission.OWNER);
        DriveItem item = getItemChecked(itemId);

        // Remove from parent's children list
        if (item.parentId != null) {
            DriveItem parent = items.get(item.parentId);
            if (parent != null && parent.isFolder()) {
                ((Folder) parent).removeChild(itemId);
            }
        }
        deleteRecursive(item);
        System.out.println("[DELETE] \"" + item.name + "\" and all its contents removed");
    }

    private void deleteRecursive(DriveItem item) {
        if (item.isFolder()) {
            for (DriveItem child : new ArrayList<>(((Folder) item).getChildren())) {
                deleteRecursive(child);
            }
        }
        items.remove(item.id);
    }

    // ── List / Search / Display ───────────────────────────────────────────────

    public List<DriveItem> listFolder(String userId, String folderId) {
        checkPermission(userId, folderId, Permission.VIEW);
        Folder folder = (Folder) getItemChecked(folderId);
        System.out.println("\n── Contents of \"" + folder.name + "\" ──");
        for (DriveItem child : folder.getChildren()) {
            String access = child.getPermission(userId) != null
                    ? child.getPermission(userId).toString() : "OWNER";
            System.out.println("  " + (child.isFolder() ? "📁" : "📄") + " " +
                    child.name + " (" + formatSize(child.getSize()) + ") [" + access + "]");
        }
        System.out.println();
        return folder.getChildren();
    }

    // Searches across all items where user has at least VIEW permission
    public List<DriveItem> search(String userId, String keyword) {
        List<DriveItem> results = new ArrayList<>();
        for (DriveItem item : items.values()) {
            if (item.hasPermission(userId, Permission.VIEW) &&
                    item.name.toLowerCase().contains(keyword.toLowerCase())) {
                results.add(item);
            }
        }
        System.out.println("[SEARCH] \"" + keyword + "\" → " + results.size() + " result(s)");
        for (DriveItem r : results) {
            System.out.println("  " + (r.isFolder() ? "📁" : "📄") + " " + r.name + " (id: " + r.id + ")");
        }
        return results;
    }

    // Prints the full tree rooted at itemId — only items visible to userId
    public void printTree(String userId, String itemId, String indent) {
        DriveItem item = items.get(itemId);
        if (item == null || !item.hasPermission(userId, Permission.VIEW)) return;
        System.out.println(indent + (item.isFolder() ? "📁 " : "📄 ") +
                item.name + " (" + formatSize(item.getSize()) + ")");
        if (item.isFolder()) {
            for (DriveItem child : ((Folder) item).getChildren()) {
                printTree(userId, child.id, indent + "    ");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void checkPermission(String userId, String itemId, Permission required) {
        DriveItem item = items.get(itemId);
        if (item == null) throw new RuntimeException("Item not found: " + itemId);
        if (!item.hasPermission(userId, required)) {
            throw new RuntimeException("Access denied: " + userName(userId) +
                    " needs " + required + " on \"" + item.name + "\"");
        }
    }

    private DriveItem getItemChecked(String itemId) {
        DriveItem item = items.get(itemId);
        if (item == null) throw new RuntimeException("Item not found: " + itemId);
        return item;
    }

    private String userName(String userId) {
        User u = users.get(userId);
        return u != null ? u.name : userId;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)             return bytes + " B";
        if (bytes < 1024 * 1024)     return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: GoogleDriveSolution.java
// =============================================================================

public class GoogleDriveSolution {
    public static void main(String[] args) {
        System.out.println("=== Google Drive Demo ===\n");

        DriveService drive = new DriveService();

        User alice = new User("U1", "Alice", "alice@email.com");
        User bob   = new User("U2", "Bob",   "bob@email.com");
        drive.registerUser(alice);
        drive.registerUser(bob);
        System.out.println();

        // ── Build folder structure ────────────────────────────────────────────
        System.out.println("── Creating folder structure ──");
        Folder myDrive   = drive.createFolder("U1", null, "My Drive");
        Folder documents = drive.createFolder("U1", myDrive.id, "Documents");
        Folder projects  = drive.createFolder("U1", documents.id, "Projects");

        File resume  = drive.createFile("U1", documents.id, "Resume.pdf",   "application/pdf", 512_000);
        File notes   = drive.createFile("U1", documents.id, "Notes.txt",    "text/plain",       4_000);
        File report  = drive.createFile("U1", projects.id,  "Report.docx",  "application/docx", 2_048_000);
        System.out.println();

        // ── Tree view (only Alice's items so far) ─────────────────────────────
        System.out.println("── Alice's drive tree ──");
        drive.printTree("U1", myDrive.id, "");
        System.out.println();

        // ── Versioning ────────────────────────────────────────────────────────
        System.out.println("── Uploading new version of Resume.pdf ──");
        drive.uploadNewVersion("U1", resume.id, 520_000);
        drive.uploadNewVersion("U1", resume.id, 535_000);
        drive.printVersionHistory("U1", resume.id);

        // ── Folder size is recursive ──────────────────────────────────────────
        System.out.println("── Folder sizes (recursive) ──");
        System.out.println("  Documents folder size: " + documents.getSize() / 1024 + " KB");
        System.out.println("  My Drive total size:   " + myDrive.getSize() / 1024 + " KB");
        System.out.println();

        // ── Sharing ───────────────────────────────────────────────────────────
        System.out.println("── Sharing ──");
        drive.shareItem("U1", resume.id, "U2", Permission.VIEW);
        drive.shareItem("U1", report.id, "U2", Permission.EDIT);

        // Bob can view Resume but not edit
        drive.printVersionHistory("U2", resume.id); // VIEW → ok
        try {
            drive.uploadNewVersion("U2", resume.id, 600_000); // EDIT needed → denied
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage() + "\n");
        }

        // Bob can edit Report
        drive.uploadNewVersion("U2", report.id, 2_100_000);
        drive.printVersionHistory("U2", report.id);

        // Bob tries to access Notes (no permission)
        try {
            drive.printVersionHistory("U2", notes.id);
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage() + "\n");
        }

        // ── Revoke access ─────────────────────────────────────────────────────
        System.out.println("── Revoke Bob's access to Resume ──");
        drive.revokeAccess("U1", resume.id, "U2");
        try {
            drive.printVersionHistory("U2", resume.id);
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage() + "\n");
        }

        // ── Rename and move ───────────────────────────────────────────────────
        System.out.println("── Rename + Move ──");
        drive.rename("U1", notes.id, "MeetingNotes.txt");
        drive.moveItem("U1", notes.id, projects.id);
        System.out.println();
        drive.listFolder("U1", documents.id);
        drive.listFolder("U1", projects.id);

        // ── Search ────────────────────────────────────────────────────────────
        System.out.println("── Search ──");
        drive.search("U1", "report");  // Alice finds Report.docx
        drive.search("U2", "report");  // Bob also finds it (EDIT access)
        drive.search("U2", "resume");  // Bob can't — access was revoked
        System.out.println();

        // ── Delete folder (recursive) ─────────────────────────────────────────
        System.out.println("── Delete Projects folder (recursive) ──");
        drive.deleteItem("U1", projects.id);
        System.out.println();
        drive.listFolder("U1", documents.id); // Projects and its files gone
    }
}
