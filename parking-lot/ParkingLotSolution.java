// =============================================================================
// LLD: PARKING LOT — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. A vehicle can enter the lot and receive a ticket
//   2. System assigns the nearest available spot based on vehicle type
//   3. A vehicle can exit using the ticket; fee is calculated on exit
//   4. Support multiple floors, each with multiple spots
//   5. Support multiple vehicle types: BIKE, CAR, TRUCK
//   6. Show availability count per vehicle type per floor
//
// Non-Functional:
//   - Single ParkingLot instance across the system (Singleton)
//   - Fee strategy should be swappable without changing core logic (Strategy)
//   - Easy to add new vehicle types or pricing models
//
// Out of scope: Payment gateway, reservations, real-time display boards
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum VehicleType { BIKE, CAR, TRUCK }

enum SpotStatus { AVAILABLE, OCCUPIED }

enum TicketStatus { ACTIVE, PAID }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:   Vehicle, ParkingSpot, ParkingFloor, Ticket
// Interface:  FeeStrategy  (Strategy pattern)
// Singleton:  ParkingLot
//
// Relationships:
//   ParkingLot    HAS-A (Composition)   ParkingFloor[]
//   ParkingFloor  HAS-A (Composition)   ParkingSpot[]
//   Ticket        HAS-A (Aggregation)   Vehicle, ParkingSpot
//   ParkingLot    USES                  FeeStrategy
//   ParkingLot    IS-A                  Singleton
// =============================================================================


// ── Vehicle ───────────────────────────────────────────────────────────────────

class Vehicle {
    public String licensePlate;
    public VehicleType type;

    public Vehicle(String licensePlate, VehicleType type) {
        this.licensePlate = licensePlate;
        this.type = type;
    }
}


// ── ParkingSpot ───────────────────────────────────────────────────────────────
// Owns its own state. Nobody sets status from outside — only park() and unpark().

class ParkingSpot {
    public String spotId;
    public VehicleType spotType;
    public int floorId;
    private SpotStatus status;
    private Vehicle parkedVehicle;

    public ParkingSpot(String spotId, VehicleType spotType, int floorId) {
        this.spotId = spotId;
        this.spotType = spotType;
        this.floorId = floorId;
        this.status = SpotStatus.AVAILABLE;
        this.parkedVehicle = null;
    }

    public boolean isAvailable() {
        return this.status == SpotStatus.AVAILABLE;
    }

    public void park(Vehicle vehicle) {
        if (!isAvailable()) {
            throw new RuntimeException("Spot " + spotId + " is already occupied");
        }
        this.parkedVehicle = vehicle;
        this.status = SpotStatus.OCCUPIED;
    }

    public Vehicle unpark() {
        if (parkedVehicle == null) {
            throw new RuntimeException("No vehicle found in spot " + spotId);
        }
        Vehicle vehicle = this.parkedVehicle;
        this.parkedVehicle = null;
        this.status = SpotStatus.AVAILABLE;
        return vehicle;
    }
}


// ── Ticket ────────────────────────────────────────────────────────────────────

class Ticket {
    public String ticketId;
    public Vehicle vehicle;
    public ParkingSpot spot;
    public Date entryTime;
    public Date exitTime;
    public TicketStatus status;

    public Ticket(String ticketId, Vehicle vehicle, ParkingSpot spot, Date entryTime) {
        this.ticketId = ticketId;
        this.vehicle = vehicle;
        this.spot = spot;
        this.entryTime = entryTime;
        this.exitTime = null;
        this.status = TicketStatus.ACTIVE;
    }

    public void close(Date exitTime) {
        this.exitTime = exitTime;
        this.status = TicketStatus.PAID;
    }
}


// ── FeeStrategy (Strategy Pattern) ───────────────────────────────────────────
// To add a new pricing model: implement this interface. Nothing else changes.

interface FeeStrategy {
    double calculate(Ticket ticket);
}

class HourlyFeeStrategy implements FeeStrategy {
    private Map<VehicleType, Integer> ratePerHour;

    public HourlyFeeStrategy() {
        ratePerHour = new HashMap<>();
        ratePerHour.put(VehicleType.BIKE, 20);
        ratePerHour.put(VehicleType.CAR, 50);
        ratePerHour.put(VehicleType.TRUCK, 100);
    }

    @Override
    public double calculate(Ticket ticket) {
        if (ticket.exitTime == null) throw new RuntimeException("Ticket is not closed yet");
        long durationMs = ticket.exitTime.getTime() - ticket.entryTime.getTime();
        long hours = Math.max(1, (long) Math.ceil(durationMs / (1000.0 * 60 * 60)));
        int rate = ratePerHour.getOrDefault(ticket.vehicle.type, 50);
        return hours * rate;
    }
}


// ── ParkingFloor ──────────────────────────────────────────────────────────────
// Responsible for finding available spots. ParkingLot delegates search to floor.

class ParkingFloor {
    public int floorId;
    private List<ParkingSpot> spots;

    public ParkingFloor(int floorId) {
        this.floorId = floorId;
        this.spots = new ArrayList<>();
    }

    public void addSpot(ParkingSpot spot) {
        spots.add(spot);
    }

    public ParkingSpot findAvailableSpot(VehicleType vehicleType) {
        for (ParkingSpot spot : spots) {
            if (spot.spotType == vehicleType && spot.isAvailable()) {
                return spot;
            }
        }
        return null;
    }

    public long getAvailableCount(VehicleType vehicleType) {
        long count = 0;
        for (ParkingSpot spot : spots) {
            if (spot.spotType == vehicleType && spot.isAvailable()) count++;
        }
        return count;
    }
}


// ── ParkingLot (Singleton) ────────────────────────────────────────────────────
// Single entry point for all operations. Orchestrates entry, exit, availability.

class ParkingLot {
    private static ParkingLot instance;

    public String name;
    private List<ParkingFloor> floors;
    private Map<String, Ticket> activeTickets;
    private int ticketCounter;
    private FeeStrategy feeStrategy;

    private ParkingLot(String name) {
        this.name = name;
        this.floors = new ArrayList<>();
        this.activeTickets = new HashMap<>();
        this.ticketCounter = 0;
        this.feeStrategy = new HourlyFeeStrategy();
    }

    public static ParkingLot getInstance(String name) {
        if (instance == null) {
            instance = new ParkingLot(name);
        }
        return instance;
    }

    public void addFloor(ParkingFloor floor) {
        floors.add(floor);
    }

    public void setFeeStrategy(FeeStrategy strategy) {
        this.feeStrategy = strategy;
    }

    public Ticket parkVehicle(Vehicle vehicle) {
        for (ParkingFloor floor : floors) {
            ParkingSpot spot = floor.findAvailableSpot(vehicle.type);
            if (spot != null) {
                spot.park(vehicle);
                String ticketId = "TKT-" + (++ticketCounter);
                Ticket ticket = new Ticket(ticketId, vehicle, spot, new Date());
                activeTickets.put(ticketId, ticket);
                System.out.println("[ENTRY] " + vehicle.licensePlate +
                        " → Floor " + spot.floorId + ", Spot " + spot.spotId +
                        " | Ticket: " + ticketId);
                return ticket;
            }
        }
        throw new RuntimeException("No available spot for vehicle type: " + vehicle.type);
    }

    public double unparkVehicle(String ticketId) {
        Ticket ticket = activeTickets.get(ticketId);
        if (ticket == null) {
            throw new RuntimeException("Ticket " + ticketId + " not found or already paid");
        }
        Date exitTime = new Date();
        ticket.close(exitTime);
        ticket.spot.unpark();
        activeTickets.remove(ticketId);
        double fee = feeStrategy.calculate(ticket);
        System.out.println("[EXIT]  " + ticket.vehicle.licensePlate + " → Fee: " + (int) fee);
        return fee;
    }

    public void printAvailability() {
        System.out.println("\n── Availability: " + name + " ──");
        for (ParkingFloor floor : floors) {
            System.out.println("  Floor " + floor.floorId + ":");
            for (VehicleType type : VehicleType.values()) {
                System.out.println("    " + type + ": " + floor.getAvailableCount(type) + " available");
            }
        }
        System.out.println();
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: ParkingLotSolution.java
// =============================================================================

public class ParkingLotSolution {
    public static void main(String[] args) {
        System.out.println("=== Parking Lot Demo ===\n");

        ParkingLot lot = ParkingLot.getInstance("Central Mall Parking");

        ParkingFloor floor1 = new ParkingFloor(1);
        floor1.addSpot(new ParkingSpot("F1-B1", VehicleType.BIKE, 1));
        floor1.addSpot(new ParkingSpot("F1-B2", VehicleType.BIKE, 1));
        floor1.addSpot(new ParkingSpot("F1-C1", VehicleType.CAR, 1));
        floor1.addSpot(new ParkingSpot("F1-C2", VehicleType.CAR, 1));
        floor1.addSpot(new ParkingSpot("F1-T1", VehicleType.TRUCK, 1));
        lot.addFloor(floor1);

        ParkingFloor floor2 = new ParkingFloor(2);
        floor2.addSpot(new ParkingSpot("F2-C1", VehicleType.CAR, 2));
        floor2.addSpot(new ParkingSpot("F2-C2", VehicleType.CAR, 2));
        lot.addFloor(floor2);

        lot.printAvailability();

        Vehicle car1   = new Vehicle("MH-01-AB-1234", VehicleType.CAR);
        Vehicle bike1  = new Vehicle("MH-02-CD-5678", VehicleType.BIKE);
        Vehicle truck1 = new Vehicle("MH-03-EF-9012", VehicleType.TRUCK);

        Ticket t1 = lot.parkVehicle(car1);
        Ticket t2 = lot.parkVehicle(bike1);
        Ticket t3 = lot.parkVehicle(truck1);

        lot.printAvailability();

        lot.unparkVehicle(t1.ticketId);
        lot.unparkVehicle(t2.ticketId);
        lot.unparkVehicle(t3.ticketId);

        lot.printAvailability();

        try {
            lot.unparkVehicle(t1.ticketId);
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }

        try {
            lot.parkVehicle(new Vehicle("MH-04-ZZ-0001", VehicleType.TRUCK));
            lot.parkVehicle(new Vehicle("MH-04-ZZ-0002", VehicleType.TRUCK));
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
    }
}
