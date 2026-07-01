// =============================================================================
// LLD: GYM SLOT BOOKING SYSTEM (Meesho SDE3 Machine Coding)
// =============================================================================
// STEP 1 — REQUIREMENTS
//
// ADMIN VIEW:
//   1. addGym(name, location)
//   2. removeGym(gymId)         → also cancels all bookings in all classes
//   3. addClass(gymId, classType, maxLimit, startTime, endTime)
//   4. removeClass(gymId, classId) → also cancels all bookings in that class
//
// CUSTOMER VIEW:
//   1. bookClass(customerId, gymId, classId) → Booking
//   2. getAllBookings(customerId)             → List<Booking>
//   3. cancelBooking(bookingId)
//
// CONSTRAINTS:
//   - Classes only between 6:00am (360 min) and 8:00pm (1200 min)
//   - One customer can book a given class ONLY ONCE
//   - maxLimit = max simultaneous people in a class
//   - Cancellation frees up the slot (another customer can now book)
//
// NON-FUNCTIONAL: Thread-safe — multiple customers booking same class concurrently
//
// OUT OF SCOPE: payment, waitlist, class schedule conflicts, gym locations search
//
// =============================================================================
// CONCURRENCY — WHERE AND WHY
//
// THE PROBLEM:
//   Two customers (Thread A and Thread B) simultaneously try to book the LAST
//   available slot in a class (confirmedCount = maxLimit - 1).
//
//   Without synchronization:
//     Thread A: reads confirmedCount = 4, maxLimit = 5 → 4 < 5 → OK to book
//     Thread B: reads confirmedCount = 4, maxLimit = 5 → 4 < 5 → OK to book
//     Thread A: confirmedCount++ → 5
//     Thread B: confirmedCount++ → 6    ← OVER-CAPACITY BUG
//
//   With synchronized(gymClass):
//     Thread A acquires lock → checks → books → confirmedCount = 5 → releases
//     Thread B acquires lock → checks → confirmedCount = 5 = maxLimit → REJECTED
//
// THE FIX:
//   synchronized(gymClass) block wrapping the CHECK + MODIFY together.
//   Lock is on the specific GymClass object — other classes are unaffected.
//
// DO YOU NEED ACTUAL MULTITHREADING IN THE INTERVIEW?
//   NO — you need THREAD-SAFE CODE (synchronized blocks).
//   In the demo, we create two Java Threads to PROVE the synchronized block
//   works correctly. This is the expected SDE3 demonstration.
// =============================================================================

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


// =============================================================================
// ENUMS
// =============================================================================

enum ClassType    { YOGA, ZUMBA, PILATES, CARDIO, STRENGTH }
enum BookingStatus { CONFIRMED, CANCELLED }


// =============================================================================
// ENTITIES
// =============================================================================

// ── Gym ───────────────────────────────────────────────────────────────────────
class Gym {
    public String       id;
    public String       name;
    public String       location;
    public List<String> classIds;   // IDs of classes offered in this gym

    public Gym(String id, String name, String location) {
        this.id       = id;
        this.name     = name;
        this.location = location;
        this.classIds = new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("Gym[%s | %s | %s | classes=%d]",
                id, name, location, classIds.size());
    }
}

// ── GymClass ──────────────────────────────────────────────────────────────────
// Owns its booking state. The synchronized lock is taken ON this object
// during bookClass() and cancelBooking() to protect confirmedCount.
class GymClass {
    public String     id;
    public String     gymId;
    public ClassType  classType;
    public int        maxLimit;
    public int        startTime;        // minutes from midnight: 360 = 6:00am
    public int        endTime;          // minutes from midnight: 1200 = 8:00pm
    public List<Booking>  bookings;         // all bookings (confirmed + cancelled)
    public Set<String>    bookedCustomerIds; // O(1) duplicate detection
    public int        confirmedCount;   // live count of confirmed bookings

    public GymClass(String id, String gymId, ClassType classType,
                    int maxLimit, int startTime, int endTime) {
        this.id                = id;
        this.gymId             = gymId;
        this.classType         = classType;
        this.maxLimit          = maxLimit;
        this.startTime         = startTime;
        this.endTime           = endTime;
        this.bookings          = new ArrayList<>();
        this.bookedCustomerIds = new HashSet<>();
        this.confirmedCount    = 0;
    }

    public String formatTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    @Override
    public String toString() {
        return String.format("Class[%s | %s | %s–%s | slots=%d/%d]",
                id, classType,
                formatTime(startTime), formatTime(endTime),
                confirmedCount, maxLimit);
    }
}

// ── Customer ──────────────────────────────────────────────────────────────────
class Customer {
    public String        id;
    public String        name;
    public List<Booking> bookings;   // customer's own booking history

    public Customer(String id, String name) {
        this.id       = id;
        this.name     = name;
        this.bookings = new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("Customer[%s | %s]", id, name);
    }
}

// ── Booking ───────────────────────────────────────────────────────────────────
// Audit record. Once created, id/customerId/classId never change.
// Only status changes (CONFIRMED → CANCELLED).
class Booking {
    public String        id;
    public String        customerId;
    public String        gymId;
    public String        classId;
    public BookingStatus status;
    public long          bookedAt;

    public Booking(String id, String customerId, String gymId, String classId) {
        this.id         = id;
        this.customerId = customerId;
        this.gymId      = gymId;
        this.classId    = classId;
        this.status     = BookingStatus.CONFIRMED;
        this.bookedAt   = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Booking[%s | customer=%s | class=%s | %s]",
                id, customerId, classId, status);
    }
}


// =============================================================================
// SHARED DATA STORE
// =============================================================================
// Holds all Maps and ID counters. Both AdminService and BookingService get a
// reference to the SAME GymStore instance — this is how they share state
// without passing Maps around individually.

class GymStore {
    Map<String, Gym>      gyms      = new HashMap<>();
    Map<String, GymClass> classes   = new HashMap<>();
    Map<String, Customer> customers = new HashMap<>();
    Map<String, Booking>  bookings  = new HashMap<>();

    int           gymCounter      = 0;
    int           classCounter    = 0;
    int           customerCounter = 0;
    // AtomicInteger — bookingCounter is incremented from DIFFERENT synchronized(gymClass)
    // blocks across different threads. Each gymClass has its own lock, so two threads
    // booking different classes can both increment this simultaneously → race condition.
    // AtomicInteger.incrementAndGet() is a single atomic CPU instruction — no synchronized needed.
    AtomicInteger bookingCounter  = new AtomicInteger(0);
}


// =============================================================================
// ADMIN SERVICE  (gym + class management)
// =============================================================================

class AdminService {
    private GymStore store;

    public AdminService(GymStore store) { this.store = store; }

    // ── GYM OPERATIONS ────────────────────────────────────────────────────────

    public Gym addGym(String name, String location) {
        String gymId = "GYM-" + (++store.gymCounter);
        Gym gym = new Gym(gymId, name, location);
        store.gyms.put(gymId, gym);
        System.out.println("[ADMIN] Added: " + gym);
        return gym;
    }

    // Cascades: removes all classes in the gym, cancels all their bookings
    public void removeGym(String gymId) {
        Gym gym = store.gyms.get(gymId);
        if (gym == null) { System.out.println("[ADMIN] Gym not found: " + gymId); return; }

        for (String classId : new ArrayList<>(gym.classIds)) {
            removeClass(gymId, classId);
        }
        store.gyms.remove(gymId);
        System.out.println("[ADMIN] Removed gym: " + gymId);
    }

    // ── CLASS OPERATIONS ──────────────────────────────────────────────────────

    public GymClass addClass(String gymId, ClassType classType,
                             int maxLimit, int startTime, int endTime) {
        Gym gym = store.gyms.get(gymId);
        if (gym == null) { System.out.println("[ADMIN] Gym not found: " + gymId); return null; }

        // Classes must run between 6:00am (360) and 8:00pm (1200)
        if (startTime < 360 || endTime > 1200 || startTime >= endTime) {
            System.out.println("[ADMIN] Invalid time: classes must be between 06:00 and 20:00");
            return null;
        }

        String classId = "CLS-" + (++store.classCounter);
        GymClass gymClass = new GymClass(classId, gymId, classType, maxLimit, startTime, endTime);
        store.classes.put(classId, gymClass);
        gym.classIds.add(classId);

        System.out.println("[ADMIN] Added: " + gymClass);
        return gymClass;
    }

    // Cascades: cancel all confirmed bookings for this class
    public void removeClass(String gymId, String classId) {
        Gym gym = store.gyms.get(gymId);
        GymClass gymClass = store.classes.get(classId);
        if (gym == null || gymClass == null) {
            System.out.println("[ADMIN] Gym or class not found");
            return;
        }

        synchronized (gymClass) {
            for (Booking b : gymClass.bookings) {
                if (b.status == BookingStatus.CONFIRMED) {
                    b.status = BookingStatus.CANCELLED;
                    System.out.println("[ADMIN] Auto-cancelled: " + b.id);
                }
            }
            gymClass.confirmedCount    = 0;
            gymClass.bookedCustomerIds.clear();
        }

        gym.classIds.remove(classId);
        store.classes.remove(classId);
        System.out.println("[ADMIN] Removed class: " + classId);
    }
}


// =============================================================================
// BOOKING SERVICE  (customer registration + booking operations)
// =============================================================================

class BookingService {
    private GymStore store;

    public BookingService(GymStore store) { this.store = store; }

    // ── CUSTOMER ──────────────────────────────────────────────────────────────

    public Customer registerCustomer(String name) {
        String customerId = "CUST-" + (++store.customerCounter);
        Customer customer = new Customer(customerId, name);
        store.customers.put(customerId, customer);
        System.out.println("[CUSTOMER] Registered: " + customer);
        return customer;
    }

    // ── BOOK ──────────────────────────────────────────────────────────────────
    // THREAD-SAFE: synchronized on the GymClass object.
    // This ensures only one thread can CHECK + MODIFY confirmedCount at a time.
    // Other classes are unaffected — their own locks are independent.

    public Booking bookClass(String customerId, String gymId, String classId) {
        Customer customer = store.customers.get(customerId);
        GymClass gymClass = store.classes.get(classId);

        if (customer == null) {
            System.out.println("[BOOKING] Customer not found: " + customerId);
            return null;
        }
        if (gymClass == null || !gymClass.gymId.equals(gymId)) {
            System.out.println("[BOOKING] Class not found in gym: " + classId);
            return null;
        }

        // CRITICAL SECTION: lock on the specific class object
        synchronized (gymClass) {
            // Check 1: Is this customer already booked in this class?
            if (gymClass.bookedCustomerIds.contains(customerId)) {
                System.out.println("[BOOKING] " + customer.name
                        + " already booked class " + classId);
                return null;
            }

            // Check 2: Is the class full?
            if (gymClass.confirmedCount >= gymClass.maxLimit) {
                System.out.println("[BOOKING] Class " + classId + " is FULL ("
                        + gymClass.confirmedCount + "/" + gymClass.maxLimit + ")");
                return null;
            }

            // Both checks passed — create booking atomically
            String  bookingId = "BKG-" + store.bookingCounter.incrementAndGet();
            Booking booking   = new Booking(bookingId, customerId, gymId, classId);

            gymClass.bookings.add(booking);
            gymClass.bookedCustomerIds.add(customerId);
            gymClass.confirmedCount++;

            customer.bookings.add(booking);
            store.bookings.put(bookingId, booking);

            System.out.println("[BOOKING] Confirmed: " + booking
                    + " | slots=" + gymClass.confirmedCount + "/" + gymClass.maxLimit);
            return booking;
        }
    }

    public List<Booking> getAllBookings(String customerId) {
        Customer customer = store.customers.get(customerId);
        if (customer == null) {
            System.out.println("[CUSTOMER] Not found: " + customerId);
            return new ArrayList<>();
        }
        System.out.println("[CUSTOMER] Bookings for " + customer.name + ":");
        for (Booking b : customer.bookings) System.out.println("  " + b);
        return customer.bookings;
    }

    // ── CANCEL ────────────────────────────────────────────────────────────────
    // THREAD-SAFE: synchronized on gymClass so confirmedCount + bookedCustomerIds
    // are updated atomically alongside status change.

    public void cancelBooking(String bookingId) {
        Booking booking = store.bookings.get(bookingId);
        if (booking == null) { System.out.println("[CANCEL] Booking not found: " + bookingId); return; }

        GymClass gymClass = store.classes.get(booking.classId);

        if (gymClass != null) {
            // CRITICAL SECTION: same lock object (gymClass) as bookClass().
            // The already-cancelled check MUST be inside the block —
            // otherwise two threads both pass it and decrement confirmedCount twice.
            synchronized (gymClass) {
                if (booking.status == BookingStatus.CANCELLED) {
                    System.out.println("[CANCEL] Already cancelled: " + bookingId);
                    return;
                }
                booking.status = BookingStatus.CANCELLED;
                gymClass.confirmedCount--;
                gymClass.bookedCustomerIds.remove(booking.customerId);
            }
        } else {
            // Class already removed (removeGym cascade) — just mark cancelled
            booking.status = BookingStatus.CANCELLED;
        }

        System.out.println("[CANCEL] Cancelled: " + bookingId
                + (gymClass != null ? " | slots=" + gymClass.confirmedCount + "/" + gymClass.maxLimit : ""));
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class GymBookingSolution {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Gym Booking System Demo ===\n");

        // Both services share the SAME store — one source of truth for all Maps
        GymStore       store   = new GymStore();
        AdminService   admin   = new AdminService(store);
        BookingService booking = new BookingService(store);

        // ── Admin: setup ───────────────────────────────────────────────────────
        System.out.println("── Admin: Setup ──");
        Gym gym1 = admin.addGym("FitLife Mumbai", "Andheri West");
        Gym gym2 = admin.addGym("PowerZone", "Bandra");

        // addClass(gymId, classType, maxLimit, startTime_mins, endTime_mins)
        // 7:00am = 420, 8:00am = 480, 8:00pm = 1200
        GymClass yoga   = admin.addClass(gym1.id, ClassType.YOGA,   3, 420, 480);   // 7-8am, 3 spots
        GymClass zumba  = admin.addClass(gym1.id, ClassType.ZUMBA,  5, 540, 630);   // 9-10:30am, 5 spots
        GymClass cardio = admin.addClass(gym2.id, ClassType.CARDIO, 2, 600, 660);   // 10-11am, 2 spots

        // Invalid time attempt
        admin.addClass(gym1.id, ClassType.PILATES, 10, 200, 300);  // before 6am → rejected
        System.out.println();

        // ── Customers register ─────────────────────────────────────────────────
        System.out.println("── Customers ──");
        Customer alice = booking.registerCustomer("Alice");
        Customer bob   = booking.registerCustomer("Bob");
        Customer carol = booking.registerCustomer("Carol");
        Customer dave  = booking.registerCustomer("Dave");
        System.out.println();

        // ── Scenario 1: Normal bookings ────────────────────────────────────────
        System.out.println("── Scenario 1: Normal Bookings ──");
        booking.bookClass(alice.id, gym1.id, yoga.id);
        booking.bookClass(bob.id,   gym1.id, yoga.id);
        booking.bookClass(alice.id, gym1.id, zumba.id);
        System.out.println();

        // ── Scenario 2: Duplicate booking (same customer, same class) ──────────
        System.out.println("── Scenario 2: Duplicate Booking (should fail) ──");
        booking.bookClass(alice.id, gym1.id, yoga.id);   // Alice already booked yoga
        System.out.println();

        // ── Scenario 3: Class full ─────────────────────────────────────────────
        System.out.println("── Scenario 3: Class Full (cardio has 2 spots) ──");
        booking.bookClass(alice.id, gym2.id, cardio.id);
        booking.bookClass(bob.id,   gym2.id, cardio.id);
        booking.bookClass(carol.id, gym2.id, cardio.id);  // full → rejected
        System.out.println();

        // ── Scenario 4: CONCURRENCY ────────────────────────────────────────────
        // Yoga class has 3 spots, 2 already taken (Alice + Bob).
        // Carol and Dave SIMULTANEOUSLY try to book the last remaining slot.
        // Only ONE should succeed. Without synchronized, both could succeed.
        System.out.println("── Scenario 4: Concurrency — Two Customers Fight for Last Slot ──");
        System.out.println("  Yoga class: " + yoga);

        Thread t1 = new Thread(() -> {
            booking.bookClass(carol.id, gym1.id, yoga.id);
        }, "Thread-Carol");

        Thread t2 = new Thread(() -> {
            booking.bookClass(dave.id, gym1.id, yoga.id);
        }, "Thread-Dave");

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("  Yoga class after race: " + yoga);
        System.out.println();

        // ── Scenario 5: Cancel frees up slot ──────────────────────────────────
        System.out.println("── Scenario 5: Cancel Then Re-book ──");
        // Bob cancels yoga — whoever lost in Scenario 4 can now book
        Booking bobYogaBooking = bob.bookings.stream()
                .filter(b -> b.classId.equals(yoga.id))
                .findFirst().orElse(null);
        if (bobYogaBooking != null) {
            booking.cancelBooking(bobYogaBooking.id);
            // Whoever lost the race in Scenario 4 — try again
            booking.bookClass(carol.id, gym1.id, yoga.id);
            booking.bookClass(dave.id,  gym1.id, yoga.id);
        }
        System.out.println("  Yoga class: " + yoga);
        System.out.println();

        // ── Scenario 6: getAllBookings ─────────────────────────────────────────
        System.out.println("── Scenario 6: Customer Booking History ──");
        booking.getAllBookings(alice.id);
        System.out.println();

        // ── Scenario 7: Admin removes a gym → cascades to cancel bookings ──────
        System.out.println("── Scenario 7: Admin Removes Gym (cascade cancel) ──");
        admin.removeGym(gym2.id);
        // Alice's cardio booking should now be CANCELLED
        System.out.println("  Alice's bookings after gym2 removed:");
        booking.getAllBookings(alice.id);
    }
}
