// =============================================================================
// LLD: HOTEL BOOKING SYSTEM (OYO / Airbnb style)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Add rooms of different types (SINGLE, DOUBLE, SUITE) with price per night
//   2. Search available rooms for a given check-in/check-out date range and type
//   3. Book a room — creates a Booking with total price calculated
//   4. Cancel a booking — room becomes available again for that period
//   5. Check in and check out — update booking status
//
// Non-Functional:
//   - Availability check: O(bookings per room) overlap detection
//   - PricingStrategy is swappable (Strategy pattern)
//
// Out of scope: real payment processing, multiple hotels, room amenities,
//   review/rating system, seasonal pricing in calendar detail
//
// KEY INSIGHT — Date overlap check:
//   Two date ranges [A_in, A_out) and [B_in, B_out) OVERLAP when:
//     A_in < B_out AND A_out > B_in
//   A room is AVAILABLE if NO confirmed booking overlaps the requested range.
//   This is the equivalent of "seat is AVAILABLE" in BookMyShow.
//
// Dates are stored as "days since epoch" (long) for simplicity.
// In a real solution use LocalDate from java.time.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum RoomType      { SINGLE, DOUBLE, SUITE }
enum BookingStatus { CONFIRMED, CANCELLED, CHECKED_IN, CHECKED_OUT }


// =============================================================================
// ENTITIES
// =============================================================================

// ── Room ──────────────────────────────────────────────────────────────────────
class Room {
    public String        id;
    public RoomType      type;
    public double        pricePerNight;
    public int           floor;
    public List<Booking> bookings;    // all bookings for this room

    public Room(String id, RoomType type, double pricePerNight, int floor) {
        this.id            = id;
        this.type          = type;
        this.pricePerNight = pricePerNight;
        this.floor         = floor;
        this.bookings      = new ArrayList<>();
    }

    // A room is available if no CONFIRMED or CHECKED_IN booking overlaps the range
    public boolean isAvailable(long checkIn, long checkOut) {
        for (Booking b : bookings) {
            if (b.status == BookingStatus.CANCELLED || b.status == BookingStatus.CHECKED_OUT)
                continue;
            // Overlap condition: existing.checkIn < requested.checkOut AND existing.checkOut > requested.checkIn
            if (b.checkIn < checkOut && b.checkOut > checkIn) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Room[%s | %s | ₹%.0f/night | floor=%d]",
                id, type, pricePerNight, floor);
    }
}

// ── Guest ─────────────────────────────────────────────────────────────────────
class Guest {
    public String id;
    public String name;
    public String email;
    public String phone;

    public Guest(String id, String name, String email) {
        this.id    = id;
        this.name  = name;
        this.email = email;
    }

    @Override
    public String toString() { return "Guest[" + id + " | " + name + "]"; }
}

// ── Booking ───────────────────────────────────────────────────────────────────
// Audit entity — records who booked which room for which dates at what price.
class Booking {
    public String        id;
    public String        guestId;
    public String        roomId;
    public long          checkIn;   // days since epoch (simplified date)
    public long          checkOut;
    public double        totalPrice;
    public BookingStatus status;

    public Booking(String id, String guestId, String roomId,
                   long checkIn, long checkOut, double totalPrice) {
        this.id         = id;
        this.guestId    = guestId;
        this.roomId     = roomId;
        this.checkIn    = checkIn;
        this.checkOut   = checkOut;
        this.totalPrice = totalPrice;
        this.status     = BookingStatus.CONFIRMED;
    }

    public long nights() { return checkOut - checkIn; }

    @Override
    public String toString() {
        return String.format("Booking[%s | guest=%s | room=%s | %d nights | ₹%.0f | %s]",
                id, guestId, roomId, nights(), totalPrice, status);
    }
}


// =============================================================================
// STRATEGY PATTERN — PricingStrategy
// =============================================================================

interface PricingStrategy {
    double calculate(Room room, long nights);
}

// Standard: pricePerNight × nights
class StandardPricing implements PricingStrategy {
    @Override
    public double calculate(Room room, long nights) {
        return room.pricePerNight * nights;
    }
}

// Weekend discount: 10% off
class WeekendDiscountPricing implements PricingStrategy {
    @Override
    public double calculate(Room room, long nights) {
        return room.pricePerNight * nights * 0.90;
    }
}


// =============================================================================
// HOTEL SERVICE
// =============================================================================

class HotelService {
    private Map<String, Room>    rooms    = new HashMap<>();
    private Map<String, Guest>   guests   = new HashMap<>();
    private Map<String, Booking> bookings = new HashMap<>();
    private PricingStrategy      pricing  = new StandardPricing();
    private int                  bookingCounter = 0;

    public void setPricingStrategy(PricingStrategy strategy) { this.pricing = strategy; }

    // ── Setup ─────────────────────────────────────────────────────────────────

    public void addRoom(Room room) {
        rooms.put(room.id, room);
        System.out.println("[HOTEL] Added: " + room);
    }

    public void registerGuest(Guest guest) {
        guests.put(guest.id, guest);
        System.out.println("[HOTEL] Registered: " + guest);
    }

    // ── Search ────────────────────────────────────────────────────────────────
    // Returns all rooms of the requested type that are available for the date range.

    public List<Room> searchAvailable(long checkIn, long checkOut, RoomType type) {
        List<Room> available = new ArrayList<>();
        for (Room room : rooms.values()) {
            if (type != null && room.type != type) continue;
            if (room.isAvailable(checkIn, checkOut)) available.add(room);
        }
        System.out.println("[HOTEL] Available " + type + " rooms for "
                + checkIn + "→" + checkOut + ": " + available.size());
        return available;
    }

    // ── Book ──────────────────────────────────────────────────────────────────

    public Booking bookRoom(String guestId, String roomId, long checkIn, long checkOut) {
        Room  room  = rooms.get(roomId);
        Guest guest = guests.get(guestId);

        if (room == null)  throw new RuntimeException("Room not found: " + roomId);
        if (guest == null) throw new RuntimeException("Guest not found: " + guestId);
        if (checkIn >= checkOut) throw new RuntimeException("Check-out must be after check-in");

        if (!room.isAvailable(checkIn, checkOut))
            throw new RuntimeException("Room " + roomId + " not available for requested dates");

        long   nights     = checkOut - checkIn;
        double totalPrice = pricing.calculate(room, nights);
        String bookingId  = "BKG-" + (++bookingCounter);

        Booking booking = new Booking(bookingId, guestId, roomId, checkIn, checkOut, totalPrice);
        room.bookings.add(booking);
        bookings.put(bookingId, booking);

        System.out.println("[HOTEL] Booked: " + booking);
        return booking;
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    public void cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null) throw new RuntimeException("Booking not found");
        if (booking.status == BookingStatus.CHECKED_IN)
            throw new RuntimeException("Cannot cancel a booking that has already checked in");

        booking.status = BookingStatus.CANCELLED;
        System.out.println("[HOTEL] Cancelled: " + bookingId);
    }

    // ── Check In / Check Out ──────────────────────────────────────────────────

    public void checkIn(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null || booking.status != BookingStatus.CONFIRMED)
            throw new RuntimeException("Cannot check in: " + bookingId);
        booking.status = BookingStatus.CHECKED_IN;
        System.out.println("[HOTEL] Checked in: " + bookingId);
    }

    public void checkOut(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null || booking.status != BookingStatus.CHECKED_IN)
            throw new RuntimeException("Cannot check out: " + bookingId);
        booking.status = BookingStatus.CHECKED_OUT;
        System.out.println("[HOTEL] Checked out: " + bookingId + " | paid ₹" + booking.totalPrice);
    }

    public void printBooking(String bookingId) {
        System.out.println("  " + bookings.get(bookingId));
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class HotelBookingSolution {
    public static void main(String[] args) {
        System.out.println("=== Hotel Booking Demo ===\n");

        HotelService hotel = new HotelService();

        // Add rooms
        hotel.addRoom(new Room("R101", RoomType.SINGLE, 1500, 1));
        hotel.addRoom(new Room("R102", RoomType.SINGLE, 1500, 1));
        hotel.addRoom(new Room("R201", RoomType.DOUBLE, 2500, 2));
        hotel.addRoom(new Room("R301", RoomType.SUITE,  5000, 3));
        System.out.println();

        // Register guests
        hotel.registerGuest(new Guest("G1", "Alice", "alice@email.com"));
        hotel.registerGuest(new Guest("G2", "Bob",   "bob@email.com"));
        hotel.registerGuest(new Guest("G3", "Carol", "carol@email.com"));
        System.out.println();

        // ── Scenario 1: Normal booking ─────────────────────────────────────────
        // Days represented as integers: 1=Jan1, 2=Jan2, etc. (simplified)
        System.out.println("── Scenario 1: Book and Check In/Out ──");
        List<Room> available = hotel.searchAvailable(1, 4, RoomType.SINGLE);
        Booking b1 = hotel.bookRoom("G1", "R101", 1, 4); // Jan 1-4 (3 nights)
        hotel.checkIn(b1.id);
        hotel.checkOut(b1.id);
        System.out.println();

        // ── Scenario 2: Double booking attempt on same room ───────────────────
        System.out.println("── Scenario 2: Overlapping Dates (should fail) ──");
        hotel.bookRoom("G2", "R201", 10, 15); // Jan 10-15
        try {
            hotel.bookRoom("G3", "R201", 12, 16); // Jan 12-16 — OVERLAPS!
        } catch (RuntimeException e) {
            System.out.println("[HOTEL] ✗ " + e.getMessage());
        }
        System.out.println();

        // ── Scenario 3: Cancellation frees up the room ────────────────────────
        System.out.println("── Scenario 3: Cancel and Re-book ──");
        Booking b3 = hotel.bookRoom("G2", "R102", 20, 25);
        hotel.cancelBooking(b3.id);
        // Now someone else can book the same room for same dates
        hotel.bookRoom("G3", "R102", 20, 25);
        System.out.println();

        // ── Scenario 4: Weekend discount pricing ──────────────────────────────
        System.out.println("── Scenario 4: Weekend Discount (10% off) ──");
        hotel.setPricingStrategy(new WeekendDiscountPricing());
        Booking b4 = hotel.bookRoom("G1", "R301", 30, 33); // Suite, 3 nights
        hotel.printBooking(b4.id);
    }
}
