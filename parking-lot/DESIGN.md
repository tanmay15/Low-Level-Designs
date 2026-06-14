# LLD Design: Parking Lot

> **Sync note:** Design companion to `parking-lot.ts`.
> Any structural change in the code must be reflected here, and vice versa.

---

## Step 1 — Requirements

### Functional
1. A vehicle can enter the lot and receive a ticket
2. System assigns the nearest available spot based on vehicle type (scans floor by floor)
3. A vehicle can exit using the ticket — fee is calculated and spot is freed
4. Support multiple floors, each with multiple spots
5. Support multiple vehicle types: `BIKE`, `CAR`, `TRUCK`
6. Show available spot count per vehicle type per floor

### Non-Functional
- Only one `ParkingLot` instance should exist across the system *(Singleton)*
- Fee calculation logic should be swappable without changing core lot logic *(Strategy pattern)*
- Adding a new vehicle type or pricing model should not require changes to existing classes

### Out of Scope
- Payment gateway integration *(fee is calculated, not processed)*
- Spot reservations in advance
- Real-time display boards

---

## Step 2 — Entities

> Technique: underline all **nouns** in the requirements, then decide what each becomes.

| Noun           | Becomes           | Reason                                                    |
|----------------|-------------------|-----------------------------------------------------------|
| Vehicle        | Class             | Has its own data (plate, type), real-world object         |
| Parking Spot   | Class             | Has state (available/occupied), owns park/unpark behavior |
| Floor          | Class             | Groups spots, responsible for finding available ones      |
| Ticket         | Class             | Holds entry/exit data, has its own lifecycle              |
| Fee / Pricing  | Interface + Class | Behavior that varies → Strategy pattern                   |
| Parking Lot    | Singleton Class   | The system itself; only one instance should exist         |
| Vehicle type   | Enum              | Fixed set of values, not a full object                    |
| Spot status    | Enum              | Fixed set of states                                       |
| Ticket status  | Enum              | Fixed set of states                                       |

---

## Step 3 — Class Design

---

### `Vehicle`
- **Attributes:** `licensePlate: string`, `type: VehicleType`
- **Methods:** None
- **Access:** All public
- **Note:** Pure data holder. Vehicles have no behavior in this system.

---

### `ParkingSpot`
- **Attributes:**
  - `spotId: string` — public
  - `spotType: VehicleType` — public
  - `floorId: number` — public *(stored for display, not a relationship)*
  - `status: SpotStatus` — **private** *(only changed via `park()` / `unpark()`)*
  - `parkedVehicle: Vehicle | null` — **private**
- **Methods:** `isAvailable()`, `park(vehicle)`, `unpark()`
- **Key decision:** Nobody sets `status = OCCUPIED` from outside. The spot owns its own state.

---

### `Ticket`
- **Attributes:**
  - `ticketId: string`, `vehicle: Vehicle`, `spot: ParkingSpot`
  - `entryTime: Date`, `exitTime: Date | null`, `status: TicketStatus`
- **Methods:** `close(exitTime: Date)`
- **Note:** Created on entry, closed on exit. Holds a `spot` reference so `unpark()` can be called at exit.

---

### `FeeStrategy` *(Interface)*
- **Method:** `calculate(ticket: Ticket): number`
- **Implementations:** `HourlyFeeStrategy` — charges per hour (min 1 hr), different rates per vehicle type
- **Note:** To add a new pricing model, just implement this interface. `ParkingLot` never changes.

---

### `ParkingFloor`
- **Attributes:**
  - `floorId: number` — public
  - `spots: ParkingSpot[]` — **private**
- **Methods:** `addSpot(spot)`, `findAvailableSpot(vehicleType)`, `getAvailableCount(vehicleType)`
- **Note:** Responsible for spot search. `ParkingLot` delegates "find a spot" to floors. Floor knows nothing about tickets or fees.

---

### `ParkingLot` *(Singleton)*
- **Attributes:**
  - `name: string` — public
  - `floors: ParkingFloor[]` — private
  - `activeTickets: Map<string, Ticket>` — private
  - `ticketCounter: number` — private
  - `feeStrategy: FeeStrategy` — private
- **Methods:** `getInstance()` *(static)*, `addFloor()`, `setFeeStrategy()`, `parkVehicle()`, `unparkVehicle()`, `printAvailability()`
- **Note:** Acts as both the main entity AND the service/orchestrator. Constructor is `private` to enforce Singleton.

---

## Step 4 — Relationships

| From            | To               | Type                  | Explanation                                                        |
|-----------------|------------------|-----------------------|--------------------------------------------------------------------|
| `ParkingLot`    | `ParkingFloor[]` | **Composition**       | Floors cannot exist without the lot                                |
| `ParkingFloor`  | `ParkingSpot[]`  | **Composition**       | Spots belong to a floor and have no life outside it                |
| `Ticket`        | `Vehicle`        | **Aggregation**       | Vehicle exists before and after the ticket                         |
| `Ticket`        | `ParkingSpot`    | **Aggregation**       | Spot exists independently; ticket references it to call `unpark()` |
| `ParkingLot`    | `FeeStrategy`    | **Dependency (Uses)** | Injected, swappable at runtime via `setFeeStrategy()`              |

### Parent-Child direction rule
> *"Who needs to search or manage whom?"* — that class holds the list.

- `ParkingLot` searches floors → `ParkingLot` holds `floors[]`
- `ParkingFloor` searches spots → `ParkingFloor` holds `spots[]`
- A `ParkingSpot` never needs to call the floor → no back-reference needed
- `floorId` in `ParkingSpot` is just a number for display — it is data, not a relationship

---

## Step 5 — Design Patterns

### Singleton → `ParkingLot`
- **Why:** Only one source of truth for spot availability and active tickets
- **How:** Constructor is `private`. `getInstance()` creates the instance once, returns it forever after
- **Interview line:** *"I made ParkingLot a Singleton because there should be only one source of truth for spot availability across the entire system."*

### Strategy → `FeeStrategy`
- **Why:** Pricing logic varies (hourly, flat, peak-hour) and changes independently from lot logic
- **How:** `FeeStrategy` interface with `calculate(ticket)`. Default is `HourlyFeeStrategy`. Swap anytime via `setFeeStrategy()`
- **Interview line:** *"I used Strategy for fees so the pricing model can change without touching ParkingLot. Open/Closed Principle."*

---

## Step 6 — Service Class Decision

`ParkingLot` is both the entity and the service here. No separate `ParkingService` needed.

**Why no separate service?**
The lot *is* the system — entry, exit, and availability naturally belong to it.

**When to create a separate Service class:**
When entities are just *participants* in an operation, not the operation itself.

| Problem         | Entities                          | Service needed        |
|-----------------|-----------------------------------|-----------------------|
| Parking Lot     | `ParkingLot` IS the system        | No — lot IS the service |
| BookMyShow      | `Theatre`, `Show`, `User`, `Seat` | Yes → `BookingService` |
| Notifications   | `User`, `Channel`                 | Yes → `NotificationService` |

**Rule:** Entity IS the system → it can be the service. Entity is just a participant → extract a service.

---

## Step 7 — Extensibility

| Change Request                             | What changes                                                                       |
|--------------------------------------------|------------------------------------------------------------------------------------|
| Add new vehicle type (e.g. `ELECTRIC_CAR`) | Add to `VehicleType` enum + create spots of that type. Zero changes elsewhere.     |
| Add new pricing model (peak-hour)          | Create `PeakHourFeeStrategy implements FeeStrategy`. Call `setFeeStrategy()`. Done.|
| Add DB persistence                         | Extract `SpotRepository` interface. Swap in-memory `spots[]` with DB-backed impl.  |
| Support multiple parking lots              | Remove Singleton. Add `ParkingLotManager` to manage a map of lots.                 |
| Add handicapped spots                      | Add `HANDICAPPED` to `VehicleType`. `findAvailableSpot` extends naturally.         |

---

## Quick Recall

```
ParkingLot  (Singleton)
  └── ParkingFloor[]
        └── ParkingSpot[]   ← owns its own status (AVAILABLE / OCCUPIED)

parkVehicle(vehicle)   → scan floors → find spot → create Ticket → return Ticket
unparkVehicle(ticketId) → close Ticket → unpark Spot → calculate Fee via FeeStrategy

Patterns used:  Singleton (ParkingLot)   |   Strategy (FeeStrategy)
```
