// =============================================================================
// LLD: CARPOOLING SERVICE (Meesho SDE2/SDE3 Machine Coding)
// =============================================================================
// REQUIREMENTS:
//   1. Driver offers a ride: startLat/Lng, endLat/Lng, driverName, maxSeats
//      - Duplicate rideId rejected (idempotency)
//   2. Find nearby rides: passenger provides their start location
//      - Returns rides whose driver start is within 5km, sorted by distance ASC
//   3. Book nearest ride: auto-picks closest eligible ride with available seats
//      - If nearest is full, tries next nearest automatically
//   4. Cancel a ride (driver cancels entire ride → all bookings cancelled)
//   5. Get ride history for a passenger
//
// KEY DESIGN DECISIONS:
//
// 1. Distance = Euclidean × 111 (degree → km approximation)
//    sqrt((lat2-lat1)² + (lng2-lng1)²) × 111
//    "Nearby" threshold = 5.0 km
//
// 2. findNearbyRides returns a sorted List — not a PriorityQueue —
//    so we can iterate from nearest to farthest when booking.
//
// 3. CONCURRENCY — synchronized(ride) when booking a seat:
//    Two passengers simultaneously book last seat of same ride.
//    Without lock: both see availableSeats=1, both book → seats go to -1.
//    Fix: synchronized(ride) wraps check + decrement atomically.
//
// 4. AtomicInteger for rideId, bookingId counters.
// =============================================================================

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


// =============================================================================
// ENUMS
// =============================================================================

enum RideStatus    { AVAILABLE, FULL, CANCELLED }
enum BookingStatus { CONFIRMED, CANCELLED }


// =============================================================================
// VALUE OBJECT — Location
// =============================================================================

class CLocation {
    public double lat;
    public double lng;

    public CLocation(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
    }

    // Euclidean × 111 for degree → km approximation
    public double distanceKm(CLocation other) {
        double dLat = this.lat - other.lat;
        double dLng = this.lng - other.lng;
        return Math.sqrt(dLat * dLat + dLng * dLng) * 111.0;
    }

    @Override
    public String toString() { return String.format("(%.4f, %.4f)", lat, lng); }
}


// =============================================================================
// ENTITIES
// =============================================================================

// ── Ride ──────────────────────────────────────────────────────────────────────
// Offered by a driver. This object is the LOCK for concurrency.
class Ride {
    public String     id;
    public String     driverName;
    public CLocation  start;
    public CLocation  end;
    public int        totalSeats;
    public int        availableSeats;   // decremented on booking, incremented on cancel
    public RideStatus status;
    public List<RideBooking> bookings;

    public Ride(String id, String driverName, CLocation start, CLocation end, int totalSeats) {
        this.id             = id;
        this.driverName     = driverName;
        this.start          = start;
        this.end            = end;
        this.totalSeats     = totalSeats;
        this.availableSeats = totalSeats;
        this.status         = RideStatus.AVAILABLE;
        this.bookings       = new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("Ride[%s | driver=%s | %s→%s | seats=%d/%d | %s]",
                id, driverName, start, end, availableSeats, totalSeats, status);
    }
}

// ── Passenger ─────────────────────────────────────────────────────────────────
class Passenger {
    public String            id;
    public String            name;
    public List<RideBooking> history;

    public Passenger(String id, String name) {
        this.id      = id;
        this.name    = name;
        this.history = new ArrayList<>();
    }
}

// ── RideBooking ───────────────────────────────────────────────────────────────
class RideBooking {
    public String        id;
    public String        passengerId;
    public String        rideId;
    public double        distanceFromPassenger;  // for display
    public BookingStatus status;
    public long          bookedAt;

    public RideBooking(String id, String passengerId, String rideId,
                       double distanceFromPassenger) {
        this.id                   = id;
        this.passengerId          = passengerId;
        this.rideId               = rideId;
        this.distanceFromPassenger= distanceFromPassenger;
        this.status               = BookingStatus.CONFIRMED;
        this.bookedAt             = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Booking[%s | passenger=%s | ride=%s | dist=%.2fkm | %s]",
                id, passengerId, rideId, distanceFromPassenger, status);
    }
}


// =============================================================================
// CARPOOLING SERVICE
// =============================================================================

class CarpoolingService {
    private Map<String, Ride>      rides      = new LinkedHashMap<>(); // insertion order preserved
    private Map<String, Passenger> passengers = new HashMap<>();
    private Map<String, RideBooking> bookings = new HashMap<>();

    private static final double MAX_DISTANCE_KM = 5.0;

    private int           passengerCounter = 0;
    private AtomicInteger bookingCounter   = new AtomicInteger(0);

    // ── Driver operations ─────────────────────────────────────────────────────

    public Ride offerRide(String rideId, String driverName,
                          double startLat, double startLng,
                          double endLat,   double endLng,
                          int maxSeats) {
        // Idempotency: reject duplicate rideId
        if (rides.containsKey(rideId)) {
            System.out.println("[RIDE] Duplicate rideId rejected: " + rideId);
            return null;
        }
        Ride ride = new Ride(rideId, driverName,
                new CLocation(startLat, startLng),
                new CLocation(endLat,   endLng),
                maxSeats);
        rides.put(rideId, ride);
        System.out.println("[RIDE] Offered: " + ride);
        return ride;
    }

    // Driver cancels the entire ride → all bookings cancelled, seats freed
    public void cancelRide(String rideId) {
        Ride ride = rides.get(rideId);
        if (ride == null) { System.out.println("[RIDE] Not found: " + rideId); return; }

        synchronized (ride) {
            if (ride.status == RideStatus.CANCELLED) {
                System.out.println("[RIDE] Already cancelled");
                return;
            }
            ride.status = RideStatus.CANCELLED;
            for (RideBooking b : ride.bookings) {
                if (b.status == BookingStatus.CONFIRMED) {
                    b.status = BookingStatus.CANCELLED;
                    System.out.println("[RIDE] Auto-cancelled booking: " + b.id);
                }
            }
        }
        System.out.println("[RIDE] Cancelled: " + rideId);
    }

    // ── Passenger operations ──────────────────────────────────────────────────

    public Passenger registerPassenger(String name) {
        String id = "P" + (++passengerCounter);
        Passenger p = new Passenger(id, name);
        passengers.put(id, p);
        System.out.println("[CARPOOL] Passenger registered: " + name + " (" + id + ")");
        return p;
    }

    // Returns rides within 5km of passenger's start, sorted by distance ASC
    public List<Ride> findNearbyRides(double passengerLat, double passengerLng) {
        CLocation passengerStart = new CLocation(passengerLat, passengerLng);

        List<Ride> nearby = rides.values().stream()
                .filter(r -> r.status == RideStatus.AVAILABLE)
                .filter(r -> r.start.distanceKm(passengerStart) <= MAX_DISTANCE_KM)
                .sorted(Comparator.comparingDouble(r -> r.start.distanceKm(passengerStart)))
                .collect(Collectors.toList());

        System.out.printf("[FIND] Rides within %.0fkm of %s: %d found%n",
                MAX_DISTANCE_KM, passengerStart, nearby.size());
        nearby.forEach(r -> System.out.printf("  %s | dist=%.2fkm%n",
                r, r.start.distanceKm(passengerStart)));
        return nearby;
    }

    // Auto-books the nearest ride with available seats.
    // If nearest is full, tries the next nearest automatically.
    // CONCURRENCY: synchronized(ride) so two passengers don't race for last seat.
    public RideBooking bookNearestRide(String passengerId,
                                       double startLat, double startLng,
                                       double endLat,   double endLng) {
        Passenger passenger = passengers.get(passengerId);
        if (passenger == null) {
            System.out.println("[BOOK] Passenger not found: " + passengerId);
            return null;
        }

        List<Ride> nearby = findNearbyRides(startLat, startLng);
        if (nearby.isEmpty()) {
            System.out.println("[BOOK] No nearby rides for " + passenger.name);
            return null;
        }

        CLocation passengerStart = new CLocation(startLat, startLng);

        for (Ride ride : nearby) {
            // Try to book this ride — synchronized to prevent race on last seat
            synchronized (ride) {
                if (ride.status == RideStatus.CANCELLED)  continue;
                if (ride.availableSeats <= 0) {
                    System.out.println("  [BOOK] Ride " + ride.id + " full, trying next...");
                    continue;
                }

                // Book this seat
                String bId = "BKG-" + bookingCounter.incrementAndGet();
                double dist = ride.start.distanceKm(passengerStart);
                RideBooking booking = new RideBooking(bId, passengerId, ride.id, dist);

                ride.availableSeats--;
                if (ride.availableSeats == 0) ride.status = RideStatus.FULL;

                ride.bookings.add(booking);
                passenger.history.add(booking);
                bookings.put(bId, booking);

                System.out.println("[BOOK] " + passenger.name + " booked: " + booking);
                return booking;
            }
        }

        System.out.println("[BOOK] No available rides for " + passenger.name);
        return null;
    }

    // Passenger cancels their booking
    public void cancelBooking(String bookingId) {
        RideBooking booking = bookings.get(bookingId);
        if (booking == null) { System.out.println("[CANCEL] Not found"); return; }

        Ride ride = rides.get(booking.rideId);
        if (ride != null) {
            synchronized (ride) {
                if (booking.status == BookingStatus.CANCELLED) {
                    System.out.println("[CANCEL] Already cancelled");
                    return;
                }
                booking.status = BookingStatus.CANCELLED;
                ride.availableSeats++;
                if (ride.status == RideStatus.FULL) ride.status = RideStatus.AVAILABLE;
            }
        } else {
            booking.status = BookingStatus.CANCELLED;
        }
        System.out.println("[CANCEL] Cancelled booking: " + bookingId);
    }

    public void getRideHistory(String passengerId) {
        Passenger p = passengers.get(passengerId);
        if (p == null) return;
        System.out.println("[HISTORY] " + p.name + ":");
        p.history.stream()
                .sorted(Comparator.comparingLong(b -> b.bookedAt))
                .forEach(b -> System.out.println("  " + b));
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class CarpoolingSolution {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Carpooling Service Demo ===\n");

        CarpoolingService service = new CarpoolingService();

        // ── Register passengers ────────────────────────────────────────────────
        Passenger alice = service.registerPassenger("Alice");
        Passenger bob   = service.registerPassenger("Bob");
        Passenger carol = service.registerPassenger("Carol");
        Passenger dave  = service.registerPassenger("Dave");
        System.out.println();

        // ── Drivers offer rides ────────────────────────────────────────────────
        // Mumbai area: Andheri ~ (19.11, 72.86)
        System.out.println("── Drivers Offering Rides ──");
        service.offerRide("R1", "Ramesh", 19.110, 72.860, 19.050, 72.830, 2); // near Alice
        service.offerRide("R2", "Suresh", 19.115, 72.865, 19.080, 72.850, 3); // near Alice
        service.offerRide("R3", "Mahesh", 19.200, 72.900, 19.150, 72.870, 1); // far away
        service.offerRide("R4", "Naresh", 19.112, 72.858, 19.040, 72.820, 1); // near, 1 seat only

        // Idempotency: duplicate rideId rejected
        service.offerRide("R1", "Duplicate", 19.000, 72.000, 19.050, 72.050, 5);
        System.out.println();

        // ── Scenario 1: Find nearby rides ─────────────────────────────────────
        System.out.println("── Scenario 1: Find Nearby Rides (Alice at 19.113, 72.862) ──");
        service.findNearbyRides(19.113, 72.862);
        System.out.println();

        // ── Scenario 2: Book nearest ride ─────────────────────────────────────
        System.out.println("── Scenario 2: Alice Books Nearest Available Ride ──");
        service.bookNearestRide(alice.id, 19.113, 72.862, 19.050, 72.830);
        System.out.println();

        // ── Scenario 3: Book — if nearest full, auto-tries next ────────────────
        System.out.println("── Scenario 3: Bob Books (R4 has 1 seat, will fill up) ──");
        service.bookNearestRide(bob.id, 19.112, 72.859, 19.050, 72.830);
        // Now R4 is full — next passenger should auto-skip it
        service.bookNearestRide(carol.id, 19.112, 72.859, 19.050, 72.830);
        System.out.println();

        // ── Scenario 4: CONCURRENCY — two passengers race for last seat ────────
        System.out.println("── Scenario 4: Concurrent Booking — Race for Last Seat ──");
        // R2 has 3 seats. Book 2 manually first, leaving 1 seat.
        service.bookNearestRide(alice.id, 19.115, 72.865, 19.080, 72.850); // R2 now has 2 seats
        service.bookNearestRide(bob.id,   19.115, 72.865, 19.080, 72.850); // R2 now has 1 seat

        // Carol and Dave simultaneously race for the last seat
        Thread t1 = new Thread(() ->
                service.bookNearestRide(carol.id, 19.115, 72.865, 19.080, 72.850), "Thread-Carol");
        Thread t2 = new Thread(() ->
                service.bookNearestRide(dave.id,  19.115, 72.865, 19.080, 72.850), "Thread-Dave");
        t1.start(); t2.start(); t1.join(); t2.join();

        Ride r2 = service.findNearbyRides(19.115, 72.865).stream()
                .filter(r -> r.id.equals("R2")).findFirst().orElse(null);
        if (r2 != null) System.out.println("  R2 after race: " + r2);
        System.out.println();

        // ── Scenario 5: Cancel booking frees seat ─────────────────────────────
        System.out.println("── Scenario 5: Cancel Booking → Seat Freed ──");
        RideBooking aliceBooking = alice.history.stream()
                .filter(b -> b.status == BookingStatus.CONFIRMED).findFirst().orElse(null);
        if (aliceBooking != null) service.cancelBooking(aliceBooking.id);
        System.out.println();

        // ── Scenario 6: Driver cancels entire ride ─────────────────────────────
        System.out.println("── Scenario 6: Driver Cancels Ride R1 ──");
        service.cancelRide("R1");
        System.out.println();

        // ── History ────────────────────────────────────────────────────────────
        System.out.println("── Ride History ──");
        service.getRideHistory(alice.id);
    }
}
