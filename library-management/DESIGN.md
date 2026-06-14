# LLD: Library Management System

> **Code file:** `LibraryManagementSolution.java` — keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
| # | Requirement |
|---|---|
| 1 | Add books with multiple physical copies |
| 2 | Members borrow an available copy by ISBN |
| 3 | Return a copy; fine calculated if overdue |
| 4 | A member cannot borrow more than MAX_BORROW_LIMIT books |
| 5 | Search books by ISBN, title, or author |
| 6 | View a member's currently borrowed books |

### Non-Functional
- Fine calculation is swappable without changing `LibraryService` (Strategy)
- `BookCopy` owns its own status — only changed through `borrow()`/`returnCopy()`
- `BorrowRecord` is the transaction entity — tracks every borrow lifecycle

### Out of Scope
Reservations/holds, renewals, multiple branches, payment processing

---

## Step 2 — Entities

| Entity | Type | Role |
|---|---|---|
| `CopyStatus` | Enum | AVAILABLE / BORROWED |
| `BorrowStatus` | Enum | ACTIVE / RETURNED |
| `Book` | Class | Metadata: isbn, title, author, genre |
| `BookCopy` | Class | Physical copy with its own status; transitions via borrow/return |
| `Member` | Class | id, name, email, active borrow list |
| `BorrowRecord` | Class | Transaction/join entity: who borrowed which copy, when, fine |
| `FineStrategy` | Interface | Strategy for fine calculation |
| `DailyFineStrategy` | Class | Flat rate per overdue day |
| `TieredFineStrategy` | Class | Lower rate for first week, higher beyond |
| `LibraryService` | Service | Orchestrates all operations, enforces business rules |

---

## Step 3 — Class Design

### Key Design Decision — Book vs BookCopy

```
Book (metadata — one per title)
  isbn, title, author, genre
  → No status here. A book title doesn't get "borrowed". A copy does.

BookCopy (physical instance — many per Book)
  copyId, Book ref, CopyStatus
  borrow() → AVAILABLE → BORROWED
  returnCopy() → BORROWED → AVAILABLE
```

**Exact parallel to BookMyShow:** Movie (metadata) vs ShowSeat (physical instance per show).

### Relationships

```
LibraryService
  ├── OWNS Map<isbn, Book>              ← metadata store
  ├── OWNS Map<isbn, List<BookCopy>>   ← physical copies
  ├── OWNS Map<memberId, Member>
  ├── OWNS List<BorrowRecord>          ← all transaction history
  └── USES FineStrategy                ← swappable

Book       HAS-MANY (Composition)   BookCopy
Member     HAS-MANY (Aggregation)   BorrowRecord (active ones)
BorrowRecord HAS-A                  Member, BookCopy  ← join entity
```

### BorrowRecord — the join/transaction entity

```
BorrowRecord
  member    → who borrowed
  copy      → which physical copy
  borrowDate, dueDate, returnDate
  fine      → set on return
  status    → ACTIVE → RETURNED
```

Same role as `Ticket` in Parking Lot (created on entry, closed on exit with fee).

### Attributes and Methods

**`BookCopy`**
- `private CopyStatus status` → protected by `borrow()` / `returnCopy()`
- `isAvailable()`, `borrow()`, `returnCopy()`, `getStatus()`

**`LibraryService`**
- `MAX_BORROW_DAYS = 14`, `MAX_BORROW_LIMIT = 3`
- `addBook(book, numCopies)` — creates N BookCopy instances
- `borrowBook(memberId, isbn)` — finds available copy, creates BorrowRecord
- `returnBook(memberId, copyId)` — closes record, calculates fine, frees copy
- `searchByIsbn/Title/Author()`
- `printMemberBorrowings(memberId)`

---

## Step 4 — Design Patterns

### 1. Strategy — `FineStrategy`
Same as `FeeStrategy` in Parking Lot. Swap the algorithm at runtime:
```java
library.setFineStrategy(new TieredFineStrategy()); // no other code changes
```

| Strategy | Logic |
|---|---|
| `DailyFineStrategy(rate)` | `daysOverdue × rate` |
| `TieredFineStrategy` | `first 7 days × ₹5, beyond × ₹10` |

### 2. State Machine — `BookCopy` and `BorrowRecord`

`BookCopy`:
```
AVAILABLE → BORROWED  (via borrow())
BORROWED  → AVAILABLE (via returnCopy())
```

`BorrowRecord`:
```
ACTIVE → RETURNED (via close())
```

---

## Step 5 — What Makes This Different From Other Problems

| Aspect | Library | Most similar to |
|---|---|---|
| Two-tier entity | Book (metadata) + BookCopy (physical) | BookMyShow: Movie + ShowSeat |
| Transaction record | BorrowRecord (join entity) | Parking Lot: Ticket |
| Fine calculation | Strategy (same decision point as ParkingLot fee) | Parking Lot: FeeStrategy |
| Business rule enforcement | MAX_BORROW_LIMIT at service layer | BookMyShow: seat locking |
| **NEW: Search** | By ISBN (O(1) map), by title/author (linear scan) | Not in other problems |
| **NEW: Multiple copies** | One Book → many BookCopy objects | Closest: one Movie → many Shows |

### Search — what's different
Unlike all other problems, Library has a **read/query** concern (search), not just write/update.
- ISBN search: `O(1)` via `booksByIsbn` map
- Title/Author search: `O(n)` linear scan (good enough for interview; production would use an inverted index or full-text search)

---

## Step 6 — Extensibility

| Change | What to do |
|---|---|
| New fine algorithm | Implement `FineStrategy`. One line change to `setFineStrategy()` |
| Reservation/hold | Add `RESERVED` to CopyStatus, add `reservations` list to Member |
| Renewal | Add `renew(recordId)` method — extends dueDate, validates max renewals |
| Multiple branches | Add `Branch` entity; `LibraryService` becomes branch-aware |
| Fast search | Replace linear scan with secondary `Map<String, List<Book>>` indexed by author/title |

---

## Key Interview Points

- **Why split Book and BookCopy?** Because availability is a property of the physical copy, not the title. Two people can borrow two copies of the same book simultaneously.
- **Why BorrowRecord?** It's the transaction log — same reasoning as Ticket in Parking Lot. It holds the temporal snapshot (borrow time, due time, fine) that doesn't belong in either Member or BookCopy.
- **Fine strategy:** Same decision as Parking Lot's fee. Business rule for pricing is volatile → extract into Strategy so changing it requires zero modifications to LibraryService.
- **MAX_BORROW_LIMIT enforced in service:** Not in Member. Member is a data entity. Business rules belong in the service layer.
