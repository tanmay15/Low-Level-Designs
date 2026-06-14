// =============================================================================
// LLD: LIBRARY MANAGEMENT SYSTEM — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Add books to the library; each book can have multiple physical copies
//   2. Members can borrow an available copy of a book (by ISBN)
//   3. Members can return a borrowed copy; fine calculated if overdue
//   4. A member cannot borrow more than MAX_BORROW_LIMIT books at once
//   5. Search books by ISBN, title, or author
//   6. View a member's currently borrowed books
//
// Non-Functional:
//   - Fine calculation algorithm is swappable (Strategy pattern)
//   - BookCopy owns its own state — status changes only through borrow/return methods
//   - BorrowRecord is the join entity tracking every borrow transaction
//
// Out of scope: Reservations/holds, renewals, multiple library branches, payments
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum CopyStatus  { AVAILABLE, BORROWED }

enum BorrowStatus { ACTIVE, RETURNED }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:   Book, BookCopy, Member, BorrowRecord
// Interface:  FineStrategy  (Strategy pattern)
// Concrete:   DailyFineStrategy, TieredFineStrategy
// Service:    LibraryService
//
// KEY DESIGN DECISION — Book vs BookCopy:
//   Book = metadata (title, author, ISBN) — one record per title
//   BookCopy = physical instance — many per Book, each has its own status
//   Same split as Movie vs ShowSeat in BookMyShow: metadata vs physical instance
//
// Relationships:
//   Book         HAS-MANY (Composition)   BookCopy
//   Member       HAS-MANY (Aggregation)   BorrowRecord (active ones)
//   BorrowRecord HAS-A    (Aggregation)   Member, BookCopy  ← join/transaction entity
//   LibraryService USES                   FineStrategy
// =============================================================================


// ── Book ──────────────────────────────────────────────────────────────────────
// Metadata only — no status here. Status lives in BookCopy.

class Book {
    public String isbn;
    public String title;
    public String author;
    public String genre;

    public Book(String isbn, String title, String author, String genre) {
        this.isbn   = isbn;
        this.title  = title;
        this.author = author;
        this.genre  = genre;
    }
}


// ── BookCopy ──────────────────────────────────────────────────────────────────
// A physical copy of a Book. Owns its own status.
// Status transitions only through borrow() and returnCopy() — same encapsulation
// principle as ShowSeat.lock() / ShowSeat.book() / ShowSeat.release().

class BookCopy {
    public String copyId;
    public Book book;
    private CopyStatus status;

    public BookCopy(String copyId, Book book) {
        this.copyId = copyId;
        this.book   = book;
        this.status = CopyStatus.AVAILABLE;
    }

    public boolean isAvailable() { return status == CopyStatus.AVAILABLE; }

    public void borrow() {
        if (!isAvailable()) throw new RuntimeException("Copy " + copyId + " is already borrowed");
        this.status = CopyStatus.BORROWED;
    }

    public void returnCopy() {
        if (status != CopyStatus.BORROWED) throw new RuntimeException("Copy " + copyId + " is not borrowed");
        this.status = CopyStatus.AVAILABLE;
    }

    public CopyStatus getStatus() { return status; }
}


// ── Member ────────────────────────────────────────────────────────────────────

class Member {
    public String id;
    public String name;
    public String email;
    public List<BorrowRecord> activeBorrowings;

    public Member(String id, String name, String email) {
        this.id              = id;
        this.name            = name;
        this.email           = email;
        this.activeBorrowings = new ArrayList<>();
    }

    public int activeBorrowCount() { return activeBorrowings.size(); }
}


// ── BorrowRecord ──────────────────────────────────────────────────────────────
// Transaction/join entity — created on borrow, closed on return.
// Same role as Ticket in ParkingLot or ShowSeat in BookMyShow.

class BorrowRecord {
    public String id;
    public Member member;
    public BookCopy copy;
    public Date borrowDate;
    public Date dueDate;      // public so demo can manipulate for overdue simulation
    public Date returnDate;
    public double fine;
    public BorrowStatus status;

    public BorrowRecord(String id, Member member, BookCopy copy, Date borrowDate, Date dueDate) {
        this.id         = id;
        this.member     = member;
        this.copy       = copy;
        this.borrowDate = borrowDate;
        this.dueDate    = dueDate;
        this.returnDate = null;
        this.fine       = 0;
        this.status     = BorrowStatus.ACTIVE;
    }

    public void close(Date returnDate, double fine) {
        this.returnDate = returnDate;
        this.fine       = fine;
        this.status     = BorrowStatus.RETURNED;
    }
}


// ── FineStrategy (Strategy Pattern) ──────────────────────────────────────────
// Same role as FeeStrategy in ParkingLot — swappable without changing LibraryService.

interface FineStrategy {
    double calculate(BorrowRecord record);
}

// Flat rate per overdue day
class DailyFineStrategy implements FineStrategy {
    private double ratePerDay;

    public DailyFineStrategy(double ratePerDay) {
        this.ratePerDay = ratePerDay;
    }

    @Override
    public double calculate(BorrowRecord record) {
        if (record.returnDate == null) throw new RuntimeException("Record is not closed yet");
        long diffMs     = record.returnDate.getTime() - record.dueDate.getTime();
        long daysOverdue = diffMs / (1000L * 60 * 60 * 24);
        return daysOverdue > 0 ? daysOverdue * ratePerDay : 0;
    }
}

// Tiered: first 7 days ₹5/day, beyond that ₹10/day
class TieredFineStrategy implements FineStrategy {
    @Override
    public double calculate(BorrowRecord record) {
        if (record.returnDate == null) throw new RuntimeException("Record is not closed yet");
        long diffMs      = record.returnDate.getTime() - record.dueDate.getTime();
        long daysOverdue = diffMs / (1000L * 60 * 60 * 24);
        if (daysOverdue <= 0)  return 0;
        if (daysOverdue <= 7)  return daysOverdue * 5;
        return (7 * 5) + ((daysOverdue - 7) * 10);
    }
}


// ── LibraryService ────────────────────────────────────────────────────────────
// Orchestrates all operations. Enforces business rules (borrow limit, availability).

class LibraryService {
    private static final int MAX_BORROW_DAYS  = 14;
    private static final int MAX_BORROW_LIMIT = 3;

    private Map<String, Book>           booksByIsbn;    // isbn → Book
    private Map<String, List<BookCopy>> copiesByIsbn;   // isbn → copies
    private Map<String, Member>         members;        // memberId → Member
    private List<BorrowRecord>          allRecords;
    private int                         recordCounter;
    private FineStrategy                fineStrategy;

    public LibraryService() {
        this.booksByIsbn  = new HashMap<>();
        this.copiesByIsbn = new HashMap<>();
        this.members      = new HashMap<>();
        this.allRecords   = new ArrayList<>();
        this.recordCounter = 0;
        this.fineStrategy  = new DailyFineStrategy(5); // ₹5 per overdue day (default)
    }

    public void setFineStrategy(FineStrategy strategy) {
        this.fineStrategy = strategy;
    }

    public void addBook(Book book, int numCopies) {
        booksByIsbn.put(book.isbn, book);
        copiesByIsbn.putIfAbsent(book.isbn, new ArrayList<>());
        for (int i = 1; i <= numCopies; i++) {
            String copyId = book.isbn + "-C" + i;
            copiesByIsbn.get(book.isbn).add(new BookCopy(copyId, book));
        }
        System.out.println("[ADDED] \"" + book.title + "\" — " + numCopies + " copies");
    }

    public void registerMember(Member member) {
        members.put(member.id, member);
        System.out.println("[MEMBER] Registered: " + member.name);
    }

    // Borrow: finds first available copy, creates BorrowRecord, returns it
    public BorrowRecord borrowBook(String memberId, String isbn) {
        Member member = members.get(memberId);
        if (member == null) throw new RuntimeException("Member " + memberId + " not found");

        if (member.activeBorrowCount() >= MAX_BORROW_LIMIT) {
            throw new RuntimeException(member.name + " has reached the borrow limit (" + MAX_BORROW_LIMIT + ")");
        }

        List<BookCopy> copies = copiesByIsbn.get(isbn);
        if (copies == null) throw new RuntimeException("Book ISBN " + isbn + " not found");

        BookCopy availableCopy = null;
        for (BookCopy copy : copies) {
            if (copy.isAvailable()) { availableCopy = copy; break; }
        }
        if (availableCopy == null) {
            throw new RuntimeException("No available copy for ISBN: " + isbn);
        }

        availableCopy.borrow();

        Date borrowDate = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(borrowDate);
        cal.add(Calendar.DAY_OF_MONTH, MAX_BORROW_DAYS);
        Date dueDate = cal.getTime();

        BorrowRecord record = new BorrowRecord(
                "BR-" + (++recordCounter), member, availableCopy, borrowDate, dueDate);
        allRecords.add(record);
        member.activeBorrowings.add(record);

        System.out.println("[BORROW] " + member.name + " borrowed \"" + availableCopy.book.title +
                "\" (copy: " + availableCopy.copyId + ") | Due: " + dueDate);
        return record;
    }

    // Return: closes BorrowRecord, calculates fine, frees the copy
    public double returnBook(String memberId, String copyId) {
        Member member = members.get(memberId);
        if (member == null) throw new RuntimeException("Member " + memberId + " not found");

        BorrowRecord record = null;
        for (BorrowRecord r : member.activeBorrowings) {
            if (r.copy.copyId.equals(copyId) && r.status == BorrowStatus.ACTIVE) {
                record = r; break;
            }
        }
        if (record == null) throw new RuntimeException("No active borrow record for copy " + copyId);

        Date returnDate = new Date();
        record.close(returnDate, 0); // close first with 0, then calculate fine
        double fine = fineStrategy.calculate(record);
        record.fine = fine;

        record.copy.returnCopy();
        member.activeBorrowings.remove(record);

        System.out.println("[RETURN] " + member.name + " returned \"" + record.copy.book.title + "\"" +
                (fine > 0 ? " | Fine: ₹" + (int) fine : " | No fine"));
        return fine;
    }

    public Book searchByIsbn(String isbn) {
        Book book = booksByIsbn.get(isbn);
        if (book == null) System.out.println("[SEARCH] No book found for ISBN: " + isbn);
        else System.out.println("[SEARCH] Found: \"" + book.title + "\" by " + book.author);
        return book;
    }

    public List<Book> searchByTitle(String keyword) {
        List<Book> results = new ArrayList<>();
        for (Book book : booksByIsbn.values()) {
            if (book.title.toLowerCase().contains(keyword.toLowerCase())) results.add(book);
        }
        System.out.println("[SEARCH] Title \"" + keyword + "\" → " + results.size() + " result(s)");
        return results;
    }

    public List<Book> searchByAuthor(String author) {
        List<Book> results = new ArrayList<>();
        for (Book book : booksByIsbn.values()) {
            if (book.author.toLowerCase().contains(author.toLowerCase())) results.add(book);
        }
        System.out.println("[SEARCH] Author \"" + author + "\" → " + results.size() + " result(s)");
        return results;
    }

    public void printMemberBorrowings(String memberId) {
        Member member = members.get(memberId);
        if (member == null) return;
        System.out.println("\n── Active borrowings for " + member.name + " ──");
        if (member.activeBorrowings.isEmpty()) {
            System.out.println("  None");
        } else {
            for (BorrowRecord r : member.activeBorrowings) {
                System.out.println("  \"" + r.copy.book.title + "\" | Copy: " + r.copy.copyId +
                        " | Due: " + r.dueDate);
            }
        }
        System.out.println();
    }

    public void printAvailability(String isbn) {
        List<BookCopy> copies = copiesByIsbn.get(isbn);
        if (copies == null) return;
        long available = 0;
        for (BookCopy c : copies) if (c.isAvailable()) available++;
        System.out.println("[AVAILABILITY] ISBN " + isbn + ": " + available + "/" + copies.size() + " copies available");
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: LibraryManagementSolution.java
// =============================================================================

public class LibraryManagementSolution {
    public static void main(String[] args) {
        System.out.println("=== Library Management System Demo ===\n");

        LibraryService library = new LibraryService();

        // Add books
        Book b1 = new Book("ISBN-001", "Clean Code",          "Robert Martin", "Programming");
        Book b2 = new Book("ISBN-002", "The Pragmatic Programmer", "David Thomas", "Programming");
        Book b3 = new Book("ISBN-003", "Atomic Habits",       "James Clear",   "Self-Help");

        library.addBook(b1, 2); // 2 copies
        library.addBook(b2, 1); // 1 copy
        library.addBook(b3, 3); // 3 copies
        System.out.println();

        // Register members
        Member alice = new Member("M1", "Alice", "alice@email.com");
        Member bob   = new Member("M2", "Bob",   "bob@email.com");
        library.registerMember(alice);
        library.registerMember(bob);
        System.out.println();

        // Borrow books
        System.out.println("── Borrowing ──");
        BorrowRecord r1 = library.borrowBook("M1", "ISBN-001");
        BorrowRecord r2 = library.borrowBook("M1", "ISBN-002");
        BorrowRecord r3 = library.borrowBook("M2", "ISBN-001"); // 2nd copy of Clean Code
        library.printAvailability("ISBN-001");
        library.printMemberBorrowings("M1");

        // Try borrowing the only copy of ISBN-002 again
        try {
            library.borrowBook("M2", "ISBN-002");
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
        System.out.println();

        // Normal return (on time — no fine)
        System.out.println("── Return on time ──");
        library.returnBook("M1", r1.copy.copyId);
        System.out.println();

        // Simulate overdue: manually push due date 10 days into the past
        System.out.println("── Return overdue (simulated 10 days late) ──");
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -10);
        r2.dueDate = cal.getTime(); // force due date to 10 days ago
        library.returnBook("M1", r2.copy.copyId);
        System.out.println();

        // Switch to Tiered fine strategy
        System.out.println("── Switch to Tiered fine strategy ──");
        library.setFineStrategy(new TieredFineStrategy());
        Calendar cal2 = Calendar.getInstance();
        cal2.add(Calendar.DAY_OF_MONTH, -15);
        r3.dueDate = cal2.getTime(); // 15 days overdue: 7×₹5 + 8×₹10 = ₹115
        library.returnBook("M2", r3.copy.copyId);
        System.out.println();

        // Borrow limit: try to borrow more than 3
        System.out.println("── Borrow limit enforcement ──");
        BorrowRecord ra = library.borrowBook("M1", "ISBN-001");
        BorrowRecord rb = library.borrowBook("M1", "ISBN-002");
        BorrowRecord rc = library.borrowBook("M1", "ISBN-003");
        try {
            library.borrowBook("M1", "ISBN-003"); // 4th borrow → should fail
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
        System.out.println();

        // Search
        System.out.println("── Search ──");
        library.searchByIsbn("ISBN-003");
        library.searchByTitle("pragmatic");
        library.searchByAuthor("james");
    }
}
