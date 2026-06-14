# LLD: Google Drive

> **Code file:** `GoogleDriveSolution.java` — keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
| # | Requirement |
|---|---|
| 1 | Users can create files and folders in a hierarchical tree (any depth) |
| 2 | Folders can contain files and other folders |
| 3 | Files support versioning — every upload adds a new version; history is preserved |
| 4 | Owner can share items with VIEW or EDIT permission |
| 5 | Every operation checks permission before proceeding |
| 6 | Rename, move, and delete items (delete folder removes all descendants recursively) |
| 7 | Search all accessible items by name keyword |

### Non-Functional
- File and Folder are treated uniformly as `DriveItem` (Composite Pattern)
- `getSize()` on a Folder recursively sums all descendants
- Permission check enforced at service level before every mutation

### Out of Scope
Binary file storage, real-time sync, offline mode, trash/recycle bin, link sharing

---

## Step 2 — Entities

| Entity | Type | Role |
|---|---|---|
| `Permission` | Enum | VIEW / EDIT / OWNER (ordered by ordinal for ≥ comparison) |
| `User` | Class | id, name, email |
| `FileVersion` | Class | versionId, size, uploadedBy, uploadedAt |
| `DriveItem` | **Abstract Class** | Shared state: id, name, ownerId, parentId, permissions Map. Shared logic: hasPermission(), grantPermission(), revokePermission() |
| `File` | Class | Extends DriveItem ← LEAF node. Holds List<FileVersion>. getSize() = latest version size |
| `Folder` | Class | Extends DriveItem ← COMPOSITE node. Holds List<DriveItem>. getSize() = recursive sum |
| `DriveService` | Service | Orchestrates all operations; enforces permissions; holds flat item map |

---

## Step 3 — Class Design

### The Composite Tree

```
DriveItem (abstract)
  ├── File   (LEAF — cannot contain children)
  └── Folder (COMPOSITE — contains List<DriveItem>)
              ├── File
              ├── File
              └── Folder
                    ├── File
                    └── File
```

Because both `File` and `Folder` extend `DriveItem`, you can write:
```java
long size = anyItem.getSize();      // works for both
anyItem.hasPermission(userId, EDIT); // works for both
```
Without Composite, you would need `instanceof` checks everywhere.

### Relationships

```
DriveService
  ├── OWNS Map<userId, User>
  └── OWNS Map<itemId, DriveItem>    ← flat store for O(1) lookup by id

Folder  HAS-A (Composition)  List<DriveItem>   ← tree structure
File    HAS-A (Composition)  List<FileVersion>  ← version history
DriveItem HAS-A              Map<userId, Permission>
```

### Two Parallel Structures

| Structure | Purpose |
|---|---|
| **Flat map** (`items`) | O(1) lookup by id — get any item instantly |
| **Parent-child references** | Tree structure — folder knows its children, item knows its parent |

Both are maintained simultaneously. When you create an item, it goes into the flat map AND into its parent's children list.

### Attributes and Methods

**`DriveItem` (abstract)**
- `id`, `name`, `ownerId`, `parentId`, `createdAt`
- `Map<String, Permission> permissions`
- `hasPermission(userId, required)` → `userId == ownerId` OR stored permission ≥ required
- `grantPermission()`, `revokePermission()`, `getPermission()`
- `abstract boolean isFolder()`
- `abstract long getSize()`

**`File`**
- `mimeType`, `List<FileVersion> versions`
- `addVersion(versionId, size, uploadedBy)` → creates new FileVersion
- `getCurrentVersion()` → last in list
- `getSize()` → last version's size

**`Folder`**
- `List<DriveItem> children`
- `addChild()`, `removeChild(itemId)`, `getChildren()`
- `getSize()` → recursive sum across all children

**`DriveService`**
- `createFile(userId, parentId, name, mimeType, size)` → permission check on parent, create, add to map + parent
- `createFolder(userId, parentId, name)` → same
- `uploadNewVersion(userId, fileId, size)` → EDIT permission, add version
- `shareItem(requesterId, itemId, targetUserId, permission)` → OWNER only can share
- `revokeAccess(requesterId, itemId, targetUserId)` → OWNER only
- `rename(userId, itemId, newName)` → EDIT permission
- `moveItem(userId, itemId, newParentId)` → EDIT on both item and destination
- `deleteItem(userId, itemId)` → OWNER, then recursive delete
- `search(userId, keyword)` → all items where user has VIEW and name contains keyword
- `printTree(userId, itemId, indent)` → recursive display

---

## Step 4 — Design Patterns

### 1. Composite Pattern (PRIMARY — unique to this problem)

The defining pattern for any hierarchical/tree structure problem.

```
DriveItem (abstract root)
  getSize()      ← defined differently in File vs Folder
  hasPermission  ← defined once in abstract class (shared)

File.getSize()   → return latestVersion.size
Folder.getSize() → return sum(child.getSize() for each child)  ← RECURSIVE
```

**Why it matters:** Without Composite, `DriveService` would need:
```java
if (item instanceof File) { ... }
else if (item instanceof Folder) { ... }  // everywhere
```
With Composite, you call `item.getSize()` and polymorphism handles it. Clean.

**The recursive delete** is also Composite behaviour:
```java
void deleteRecursive(DriveItem item) {
    if (item.isFolder())
        for each child → deleteRecursive(child)  // recurse into folder
    items.remove(item.id)  // then delete self
}
```

### 2. Abstract Class — `DriveItem`

All DriveItems share:
- State: `id`, `name`, `ownerId`, `parentId`, `permissions` map
- Logic: `hasPermission()`, `grantPermission()`, `revokePermission()`

Interface cannot hold mutable instance state. Abstract class is the right choice — same reasoning as `LogAppender` in Logger and `ExpenseSplit` in Splitwise.

### 3. Permission as Ordered Enum

```java
enum Permission { VIEW, EDIT, OWNER }
// ordinal: VIEW=0, EDIT=1, OWNER=2
```

```java
public boolean hasPermission(String userId, Permission required) {
    Permission userPerm = permissions.get(userId);
    return userPerm.ordinal() >= required.ordinal();
}
```

`OWNER ≥ EDIT ≥ VIEW` — so an EDIT user automatically satisfies a VIEW check. One line of code covers all three levels.

---

## Step 5 — What Makes This Different From Other Problems

| Aspect | Google Drive | Most similar to |
|---|---|---|
| **NEW: Composite Pattern** | File and Folder are the same type (DriveItem) | Not in any other problem |
| **NEW: Tree / recursive structure** | Folder can contain Folders | Not in any other problem |
| **NEW: Permission per item** | Every read/write checks access | BookMyShow's seat locking (access control) |
| **NEW: Versioning** | File has history of versions | Not in other problems |
| **NEW: Recursive delete** | Delete folder = delete all descendants | Not in other problems |
| **NEW: Recursive size** | Folder size = sum of all descendants | Not in other problems |
| Flat + tree dual structure | Items map + parent-child refs | URL Shortener's two maps (different purpose) |
| Service enforces rules | Permission at every method | Library's borrow limit, BookMyShow's locking |

### The Hardest Part to Get Right

**Two structures in sync:**
```
items Map    ← flat, for O(1) lookup
Folder.children ← hierarchical, for tree operations
```
When you create an item: add to BOTH.
When you delete an item: remove from BOTH.
When you move an item: update parent-child refs in BOTH parents AND update item's parentId.

Miss either one and you get orphaned items or ghost entries.

---

## Step 6 — Extensibility

| Change | What to do |
|---|---|
| Trash / soft delete | Add `isDeleted` flag to DriveItem; filter in list/search; add `restore()` method |
| Folder-level permission cascade | In `hasPermission()`, walk up parentId chain checking each parent's permissions |
| Quota per user | Add `quotaBytes` to User; track sum of all user's file sizes; check on upload |
| Sharing via link | Add `shareToken` to DriveItem; `getByToken(token)` bypasses userId permission check |
| Activity log | Observer pattern — `DriveService` publishes events; `ActivityLogger` subscribes |
| File previews | Add `thumbnailUrl` to `FileVersion` |

---

## Key Interview Points

- **Why Composite?** "File and Folder need to be treated the same way — you don't want to write `instanceof` everywhere. Composite gives you a single `DriveItem` type that behaves correctly whether it's a file or a folder."
- **Why Abstract class for DriveItem (not interface)?** "`id`, `name`, `ownerId`, and `permissions` are shared state that every DriveItem needs. Interface can't hold mutable instance state."
- **Why two structures (flat map + tree)?** "Flat map for O(1) lookup by id (used in every service method). Tree for hierarchical operations like listing folder contents and recursive delete."
- **getSize() is recursive — is that a problem?** "For an interview, no. In production, you'd cache the folder size and invalidate on every child change."
- **Permission ordinal trick:** "I order the enum VIEW < EDIT < OWNER and compare ordinals, so a single `>=` check covers all three levels. No if-else chain needed."
