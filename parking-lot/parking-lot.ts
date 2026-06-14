// =============================================================================
// LLD: PARKING LOT
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


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum VehicleType {
  BIKE = "BIKE",
  CAR = "CAR",
  TRUCK = "TRUCK",
}

enum SpotStatus {
  AVAILABLE = "AVAILABLE",
  OCCUPIED = "OCCUPIED",
}

enum TicketStatus {
  ACTIVE = "ACTIVE",
  PAID = "PAID",
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:     Vehicle, ParkingSpot, ParkingFloor, Ticket, ParkingLot
// Interface:    FeeStrategy  (Strategy pattern)
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
  constructor(
    public licensePlate: string,
    public type: VehicleType
  ) {}
}


// ── ParkingSpot ───────────────────────────────────────────────────────────────
// Owns its own state. Nobody sets status from outside — only park() and unpark().

class ParkingSpot {
  public spotId: string;
  public spotType: VehicleType;
  public floorId: number;
  private status: SpotStatus;
  private parkedVehicle: Vehicle | null;

  constructor(spotId: string, spotType: VehicleType, floorId: number) {
    this.spotId = spotId;
    this.spotType = spotType;
    this.floorId = floorId;
    this.status = SpotStatus.AVAILABLE;
    this.parkedVehicle = null;
  }

  isAvailable(): boolean {
    return this.status === SpotStatus.AVAILABLE;
  }

  park(vehicle: Vehicle): void {
    if (!this.isAvailable()) {
      throw new Error(`Spot ${this.spotId} is already occupied`);
    }
    this.parkedVehicle = vehicle;
    this.status = SpotStatus.OCCUPIED;
  }

  unpark(): Vehicle {
    if (!this.parkedVehicle) {
      throw new Error(`No vehicle found in spot ${this.spotId}`);
    }
    const vehicle = this.parkedVehicle;
    this.parkedVehicle = null;
    this.status = SpotStatus.AVAILABLE;
    return vehicle;
  }
}


// ── Ticket ────────────────────────────────────────────────────────────────────

class Ticket {
  public ticketId: string;
  public vehicle: Vehicle;
  public spot: ParkingSpot;
  public entryTime: Date;
  public exitTime: Date | null;
  public status: TicketStatus;

  constructor(ticketId: string, vehicle: Vehicle, spot: ParkingSpot, entryTime: Date) {
    this.ticketId = ticketId;
    this.vehicle = vehicle;
    this.spot = spot;
    this.entryTime = entryTime;
    this.exitTime = null;
    this.status = TicketStatus.ACTIVE;
  }

  close(exitTime: Date): void {
    this.exitTime = exitTime;
    this.status = TicketStatus.PAID;
  }
}


// ── FeeStrategy (Strategy Pattern) ───────────────────────────────────────────
// To add a new pricing model: implement this interface. Nothing else changes.

interface FeeStrategy {
  calculate(ticket: Ticket): number;
}

class HourlyFeeStrategy implements FeeStrategy {
  private ratePerHour: Map<VehicleType, number>;

  constructor() {
    this.ratePerHour = new Map([
      [VehicleType.BIKE, 20],
      [VehicleType.CAR, 50],
      [VehicleType.TRUCK, 100],
    ]);
  }

  calculate(ticket: Ticket): number {
    if (!ticket.exitTime) throw new Error("Ticket is not closed yet");
    const durationMs = ticket.exitTime.getTime() - ticket.entryTime.getTime();
    const hours = Math.max(1, Math.ceil(durationMs / (1000 * 60 * 60)));
    const rate = this.ratePerHour.get(ticket.vehicle.type) ?? 50;
    return hours * rate;
  }
}


// ── ParkingFloor ──────────────────────────────────────────────────────────────
// Responsible for finding available spots. ParkingLot delegates search to floor.

class ParkingFloor {
  public floorId: number;
  private spots: ParkingSpot[];

  constructor(floorId: number) {
    this.floorId = floorId;
    this.spots = [];
  }

  addSpot(spot: ParkingSpot): void {
    this.spots.push(spot);
  }

  findAvailableSpot(vehicleType: VehicleType): ParkingSpot | null {
    return this.spots.find((s) => s.spotType === vehicleType && s.isAvailable()) ?? null;
  }

  getAvailableCount(vehicleType: VehicleType): number {
    return this.spots.filter((s) => s.spotType === vehicleType && s.isAvailable()).length;
  }
}


// ── ParkingLot (Singleton) ────────────────────────────────────────────────────
// Single entry point for all operations. Orchestrates entry, exit, availability.

class ParkingLot {
  private static instance: ParkingLot;

  public name: string;
  private floors: ParkingFloor[];
  private activeTickets: Map<string, Ticket>;
  private ticketCounter: number;
  private feeStrategy: FeeStrategy;

  private constructor(name: string) {
    this.name = name;
    this.floors = [];
    this.activeTickets = new Map();
    this.ticketCounter = 0;
    this.feeStrategy = new HourlyFeeStrategy();
  }

  static getInstance(name: string = "Default Parking Lot"): ParkingLot {
    if (!ParkingLot.instance) {
      ParkingLot.instance = new ParkingLot(name);
    }
    return ParkingLot.instance;
  }

  addFloor(floor: ParkingFloor): void {
    this.floors.push(floor);
  }

  setFeeStrategy(strategy: FeeStrategy): void {
    this.feeStrategy = strategy;
  }

  parkVehicle(vehicle: Vehicle): Ticket {
    for (const floor of this.floors) {
      const spot = floor.findAvailableSpot(vehicle.type);
      if (spot) {
        spot.park(vehicle);
        const ticket = new Ticket(
          `TKT-${++this.ticketCounter}`,
          vehicle,
          spot,
          new Date()
        );
        this.activeTickets.set(ticket.ticketId, ticket);
        console.log(`[ENTRY] ${vehicle.licensePlate} → Floor ${spot.floorId}, Spot ${spot.spotId} | Ticket: ${ticket.ticketId}`);
        return ticket;
      }
    }
    throw new Error(`No available spot for vehicle type: ${vehicle.type}`);
  }

  unparkVehicle(ticketId: string): number {
    const ticket = this.activeTickets.get(ticketId);
    if (!ticket) throw new Error(`Ticket ${ticketId} not found or already paid`);

    const exitTime = new Date();
    ticket.close(exitTime);
    ticket.spot.unpark();
    this.activeTickets.delete(ticketId);

    const fee = this.feeStrategy.calculate(ticket);
    console.log(`[EXIT]  ${ticket.vehicle.licensePlate} → Fee: ₹${fee}`);
    return fee;
  }

  printAvailability(): void {
    console.log(`\n── Availability: ${this.name} ──`);
    for (const floor of this.floors) {
      console.log(`  Floor ${floor.floorId}:`);
      for (const type of Object.values(VehicleType)) {
        console.log(`    ${type}: ${floor.getAvailableCount(type as VehicleType)} available`);
      }
    }
    console.log();
  }
}


// =============================================================================
// STEP 4 — DEMO
// =============================================================================

console.log("=== Parking Lot Demo ===\n");

const lot = ParkingLot.getInstance("Central Mall Parking");

const floor1 = new ParkingFloor(1);
floor1.addSpot(new ParkingSpot("F1-B1", VehicleType.BIKE, 1));
floor1.addSpot(new ParkingSpot("F1-B2", VehicleType.BIKE, 1));
floor1.addSpot(new ParkingSpot("F1-C1", VehicleType.CAR, 1));
floor1.addSpot(new ParkingSpot("F1-C2", VehicleType.CAR, 1));
floor1.addSpot(new ParkingSpot("F1-T1", VehicleType.TRUCK, 1));
lot.addFloor(floor1);

const floor2 = new ParkingFloor(2);
floor2.addSpot(new ParkingSpot("F2-C1", VehicleType.CAR, 2));
floor2.addSpot(new ParkingSpot("F2-C2", VehicleType.CAR, 2));
lot.addFloor(floor2);

lot.printAvailability();

const car1 = new Vehicle("MH-01-AB-1234", VehicleType.CAR);
const bike1 = new Vehicle("MH-02-CD-5678", VehicleType.BIKE);
const truck1 = new Vehicle("MH-03-EF-9012", VehicleType.TRUCK);

const t1 = lot.parkVehicle(car1);
const t2 = lot.parkVehicle(bike1);
const t3 = lot.parkVehicle(truck1);

lot.printAvailability();

lot.unparkVehicle(t1.ticketId);
lot.unparkVehicle(t2.ticketId);
lot.unparkVehicle(t3.ticketId);

lot.printAvailability();

// Edge case: ticket already paid
try {
  lot.unparkVehicle(t1.ticketId);
} catch (e: any) {
  console.log(`[ERROR] ${e.message}`);
}

// Edge case: no spot available for vehicle type
try {
  lot.parkVehicle(new Vehicle("MH-04-ZZ-0001", VehicleType.TRUCK));
  lot.parkVehicle(new Vehicle("MH-04-ZZ-0002", VehicleType.TRUCK)); // should throw
} catch (e: any) {
  console.log(`[ERROR] ${e.message}`);
}
