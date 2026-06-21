// =============================================================================
// LLD: RIDE SHARING (Uber / Ola)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Register drivers and riders
//   2. Rider requests a ride (pickup + dropoff location)
//   3. System finds nearest AVAILABLE driver and assigns the trip
//   4. Driver accepts → trip starts → trip ends → fare calculated
//   5. Cancellation allowed before trip starts
//   6. Fare calculated via pluggable Strategy (base / surge pricing)
//
// Non-Functional:
//   - Trip is the central state machine
//   - FareStrategy is swappable (Strategy pattern)
//   - Driver availability tracked via DriverStatus
//
// Out of scope: real GPS, maps API, driver rating, payment gateway,
//   split fare, scheduled rides, driver earnings dashboard
//
// KEY INSIGHT — Trip state machine:
//   REQUESTED → DRIVER_ASSIGNED → STARTED → COMPLETED
//                    ↓                  ↓
//               CANCELLED           CANCELLED
//   Driver status mirrors trip:
//   AVAILABLE → ON_TRIP (on startTrip) → AVAILABLE (on endTrip)
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum TripStatus   { REQUESTED, DRIVER_ASSIGNED, STARTED, COMPLETED, CANCELLED }
enum DriverStatus { AVAILABLE, ON_TRIP, OFFLINE }


// =============================================================================
// VALUE OBJECT — Location
// =============================================================================

class Location {
    public double lat;
    public double lng;

    public Location(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
    }

    // Euclidean distance (simplified — no Haversine for LLD)
    public double distanceTo(Location other) {
        double dLat = this.lat - other.lat;
        double dLng = this.lng - other.lng;
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    @Override
    public String toString() { return "(" + lat + ", " + lng + ")"; }
}


// =============================================================================
// ENTITIES
// =============================================================================

// ── Driver ────────────────────────────────────────────────────────────────────
class Driver {
    public String        id;
    public String        name;
    public String        vehicleNumber;
    public DriverStatus  status;
    public Location      location;

    public Driver(String id, String name, String vehicleNumber, Location location) {
        this.id            = id;
        this.name          = name;
        this.vehicleNumber = vehicleNumber;
        this.location      = location;
        this.status        = DriverStatus.AVAILABLE;
    }

    @Override
    public String toString() {
        return String.format("Driver[%s | %s | %s | %s]", id, name, status, location);
    }
}

// ── Rider ─────────────────────────────────────────────────────────────────────
class Rider {
    public String   id;
    public String   name;
    public Location location;

    public Rider(String id, String name, Location location) {
        this.id       = id;
        this.name     = name;
        this.location = location;
    }

    @Override
    public String toString() {
        return String.format("Rider[%s | %s]", id, name);
    }
}

// ── Trip ──────────────────────────────────────────────────────────────────────
// Central state machine. Records everything about one ride.
class Trip {
    public String     id;
    public String     riderId;
    public String     driverId;       // null until assigned
    public Location   pickup;
    public Location   dropoff;
    public TripStatus status;
    public double     distanceKm;
    public double     fare;           // computed on endTrip
    public long       startTime;
    public long       endTime;

    public Trip(String id, String riderId, Location pickup, Location dropoff) {
        this.id       = id;
        this.riderId  = riderId;
        this.pickup   = pickup;
        this.dropoff  = dropoff;
        this.status   = TripStatus.REQUESTED;
    }

    @Override
    public String toString() {
        return String.format("Trip[%s | rider=%s | driver=%s | %s | fare=₹%.2f]",
                id, riderId, driverId, status, fare);
    }
}


// =============================================================================
// STRATEGY PATTERN — FareStrategy
// =============================================================================

interface FareStrategy {
    double calculate(double distanceKm);
}

// Standard fare: flat base + per-km rate
class BaseFareStrategy implements FareStrategy {
    private static final double BASE    = 50.0;  // ₹50 base
    private static final double PER_KM  = 12.0;  // ₹12/km

    @Override
    public double calculate(double distanceKm) {
        return BASE + PER_KM * distanceKm;
    }
}

// Surge: same formula but with a multiplier (e.g. peak hours = 1.5×)
class SurgeFareStrategy implements FareStrategy {
    private double multiplier;

    public SurgeFareStrategy(double multiplier) { this.multiplier = multiplier; }

    @Override
    public double calculate(double distanceKm) {
        return (50.0 + 12.0 * distanceKm) * multiplier;
    }
}


// =============================================================================
// RIDE SERVICE
// =============================================================================

class RideService {
    private Map<String, Driver>  drivers          = new HashMap<>();
    private Map<String, Rider>   riders           = new HashMap<>();
    private Map<String, Trip>    trips            = new HashMap<>();
    private Map<String, String>  driverActiveTrip = new HashMap<>(); // driverId → tripId
    private FareStrategy         fareStrategy;
    private int                  tripCounter = 0;

    public RideService() {
        this.fareStrategy = new BaseFareStrategy(); // default
    }

    public void setFareStrategy(FareStrategy strategy) { this.fareStrategy = strategy; }

    // ── Registration ──────────────────────────────────────────────────────────

    public void registerDriver(Driver driver) {
        drivers.put(driver.id, driver);
        System.out.println("[RIDE] Registered: " + driver);
    }

    public void registerRider(Rider rider) {
        riders.put(rider.id, rider);
        System.out.println("[RIDE] Registered: " + rider);
    }

    // ── Request ride ──────────────────────────────────────────────────────────
    // Finds the nearest AVAILABLE driver and creates a trip in REQUESTED state.

    public Trip requestRide(String riderId, Location pickup, Location dropoff) {
        Rider rider = riders.get(riderId);
        if (rider == null) throw new RuntimeException("Rider not found");

        Driver nearest = findNearestDriver(pickup);
        if (nearest == null) {
            System.out.println("[RIDE] No drivers available for " + riderId);
            return null;
        }

        String tripId = "TRIP-" + (++tripCounter);
        Trip   trip   = new Trip(tripId, riderId, pickup, dropoff);
        trip.driverId = nearest.id;
        trip.status   = TripStatus.DRIVER_ASSIGNED;

        trips.put(tripId, trip);
        driverActiveTrip.put(nearest.id, tripId);
        nearest.status = DriverStatus.ON_TRIP;

        System.out.println("[RIDE] " + rider.name + " matched with driver " + nearest.name
                + " | trip=" + tripId);
        return trip;
    }

    // Nearest = smallest distanceTo(pickup) among AVAILABLE drivers
    private Driver findNearestDriver(Location pickup) {
        Driver nearest  = null;
        double minDist  = Double.MAX_VALUE;

        for (Driver driver : drivers.values()) {
            if (driver.status != DriverStatus.AVAILABLE) continue;
            double dist = driver.location.distanceTo(pickup);
            if (dist < minDist) {
                minDist = dist;
                nearest = driver;
            }
        }
        return nearest;
    }

    // ── Trip lifecycle ────────────────────────────────────────────────────────

    public void startTrip(String tripId) {
        Trip trip = trips.get(tripId);
        if (trip == null || trip.status != TripStatus.DRIVER_ASSIGNED)
            throw new RuntimeException("Cannot start trip: " + tripId);

        trip.status    = TripStatus.STARTED;
        trip.startTime = System.currentTimeMillis();
        System.out.println("[RIDE] Trip STARTED: " + tripId);
    }

    public void endTrip(String tripId) {
        Trip   trip   = trips.get(tripId);
        if (trip == null || trip.status != TripStatus.STARTED)
            throw new RuntimeException("Cannot end trip: " + tripId);

        trip.status    = TripStatus.COMPLETED;
        trip.endTime   = System.currentTimeMillis();

        // Calculate distance and fare
        trip.distanceKm = trip.pickup.distanceTo(trip.dropoff) * 111; // rough km conversion
        trip.fare       = fareStrategy.calculate(trip.distanceKm);

        // Free up the driver
        Driver driver = drivers.get(trip.driverId);
        if (driver != null) {
            driver.status = DriverStatus.AVAILABLE;
            driver.location = trip.dropoff; // driver is now at dropoff location
        }
        driverActiveTrip.remove(trip.driverId);

        System.out.printf("[RIDE] Trip COMPLETED: %s | dist=%.1fkm | fare=₹%.2f%n",
                tripId, trip.distanceKm, trip.fare);
    }

    public void cancelTrip(String tripId, String requestedBy) {
        Trip trip = trips.get(tripId);
        if (trip == null) throw new RuntimeException("Trip not found");
        if (trip.status == TripStatus.STARTED || trip.status == TripStatus.COMPLETED)
            throw new RuntimeException("Cannot cancel an ongoing/completed trip");

        trip.status = TripStatus.CANCELLED;

        // Free up the driver if one was assigned
        if (trip.driverId != null) {
            Driver driver = drivers.get(trip.driverId);
            if (driver != null) driver.status = DriverStatus.AVAILABLE;
            driverActiveTrip.remove(trip.driverId);
        }
        System.out.println("[RIDE] Trip CANCELLED: " + tripId + " by " + requestedBy);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public void printTrip(String tripId) {
        System.out.println("  " + trips.get(tripId));
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class RideSharingSolution {
    public static void main(String[] args) {
        System.out.println("=== Ride Sharing Demo ===\n");

        RideService service = new RideService();

        // Register drivers
        service.registerDriver(new Driver("D1", "Ramesh", "MH-12-AB-1234", new Location(18.52, 73.85)));
        service.registerDriver(new Driver("D2", "Suresh", "MH-14-CD-5678", new Location(18.55, 73.88)));
        service.registerDriver(new Driver("D3", "Mahesh", "MH-15-EF-9012", new Location(18.50, 73.90)));

        // Register riders
        service.registerRider(new Rider("R1", "Alice", new Location(18.53, 73.86)));
        service.registerRider(new Rider("R2", "Bob",   new Location(18.56, 73.89)));
        System.out.println();

        // ── Scenario 1: Normal ride ────────────────────────────────────────────
        System.out.println("── Scenario 1: Normal Ride ──");
        Trip trip1 = service.requestRide("R1",
                new Location(18.53, 73.86),  // pickup
                new Location(18.60, 73.95)); // dropoff
        service.startTrip(trip1.id);
        service.endTrip(trip1.id);
        service.printTrip(trip1.id);
        System.out.println();

        // ── Scenario 2: Surge pricing ─────────────────────────────────────────
        System.out.println("── Scenario 2: Surge Pricing (1.5×) ──");
        service.setFareStrategy(new SurgeFareStrategy(1.5));
        Trip trip2 = service.requestRide("R2",
                new Location(18.56, 73.89),
                new Location(18.65, 73.98));
        service.startTrip(trip2.id);
        service.endTrip(trip2.id);
        service.printTrip(trip2.id);
        System.out.println();

        // ── Scenario 3: Cancellation before start ─────────────────────────────
        System.out.println("── Scenario 3: Cancel Before Start ──");
        service.setFareStrategy(new BaseFareStrategy());
        Trip trip3 = service.requestRide("R1",
                new Location(18.53, 73.86),
                new Location(18.58, 73.92));
        service.cancelTrip(trip3.id, "R1");
        service.printTrip(trip3.id);
    }
}
