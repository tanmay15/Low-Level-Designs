# LLD: Elevator System

> Implementation: `ElevatorSystemSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Building has **N floors** and **M elevators** |
| 2 | **Hall call** — person outside presses UP/DOWN on a floor; system assigns best elevator |
| 3 | **Cabin call** — person inside presses a destination floor; goes directly to that elevator |
| 4 | **SCAN algorithm** — elevator serves all stops in current direction before reversing |
| 5 | Elevator states: IDLE → MOVING_UP ↔ MOVING_DOWN → IDLE |
| 6 | Movement is simulated tick-by-tick (one floor per `step()` call) |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Scheduling algorithm is **swappable** (Strategy) — change without touching controller |
| 2 | ElevatorController is **Singleton** (one controller per building) |
| 3 | Multiple elevators operate **independently** but are coordinated by the controller |

### Out of Scope
- Emergency stop, overload sensors, real-time floor display
- Express elevators, service elevators
- Passenger tracking (who entered / exited which elevator)

---

## Step 2 — Key Concept: SCAN Algorithm

> Also called the "elevator algorithm" — same idea as disk scheduling in operating systems.

```
Elevator starts at floor 2, moving UP.
Requests: floor 4 ↑,  floor 6 ↑,  floor 8 ↑,  floor 1 ↓,  floor 3 ↓

SCAN behaviour:
  2 → 3 → 4 ↑ STOP → 5 → 6 ↑ STOP → 7 → 8 ↑ STOP
  ↓ reverses ↓
  7 → 6 → 5 → 4 → 3 ↓ STOP → 2 → 1 ↓ STOP
```

**Why two queues?**

| Queue | TreeSet order | Purpose |
|-------|---------------|---------|
| `upQueue` | ascending | next stop going UP is `first()` |
| `downQueue` | descending | next stop going DOWN is `last()` |

When elevator is `MOVING_UP`:
- Move floor++ each tick
- If `upQueue.remove(currentFloor)` → open doors
- If `upQueue` is now empty → reverse to `MOVING_DOWN` (or go `IDLE`)

---

## Step 3 — Entities & Class Design

### Entities

| Class | Role |
|-------|------|
| `Elevator` | Core entity — state machine + SCAN queues |
| `ElevatorController` | Singleton — manages all elevators, dispatches requests |
| `ElevatorScheduler` | Interface (Strategy) — which elevator to assign for hall calls |
| `NearestElevatorScheduler` | Concrete strategy — picks nearest/best-direction elevator |

### Enums

| Enum | Values |
|------|--------|
| `Direction` | UP, DOWN |
| `ElevatorState` | IDLE, MOVING_UP, MOVING_DOWN |

---

## Step 4 — Elevator State Machine

```
         hallRequest / cabinRequest (to higher floor)
IDLE ──────────────────────────────────────────────► MOVING_UP
  ▲                                                       │
  │ (all queues empty)           upQueue exhausted        │
  │                         ┌────────────────────────────►│
  │                         │                              ▼
  │           downQueue exhausted                    MOVING_DOWN
  └──────────────────────────────────────────────────────►┘
              (all queues empty → IDLE)
```

**Transitions:**

| From | To | When |
|------|----|------|
| IDLE | MOVING_UP | new request added and destination > currentFloor |
| IDLE | MOVING_DOWN | new request added and destination < currentFloor |
| MOVING_UP | MOVING_DOWN | upQueue becomes empty and downQueue has stops |
| MOVING_DOWN | MOVING_UP | downQueue becomes empty and upQueue has stops |
| MOVING_UP / MOVING_DOWN | IDLE | both queues become empty |

---

## Step 5 — Class Attributes & Methods

### `Elevator`

| Member | Type | Description |
|--------|------|-------------|
| `id` | int | elevator identifier |
| `currentFloor` | int | current position |
| `state` | ElevatorState | IDLE / MOVING_UP / MOVING_DOWN |
| `upQueue` | TreeSet\<Integer\> | sorted ascending stops while going UP |
| `downQueue` | TreeSet\<Integer\> | sorted descending stops while going DOWN |
| `addDestination(floor, dir)` | void | add a stop to the correct queue |
| `step()` | void | move one floor, open doors if scheduled stop |
| `statusLine()` | String | formatted status for display |

### `ElevatorScheduler` (interface)

| Method | Returns | Description |
|--------|---------|-------------|
| `assign(elevators, floor, direction)` | Elevator | pick best elevator for a hall call |

### `NearestElevatorScheduler`

| Scoring rule | Score |
|-------------|-------|
| IDLE elevator | `\|currentFloor − requestFloor\|` |
| Moving in same direction toward request | `\|currentFloor − requestFloor\|` (no penalty) |
| Moving wrong direction or past floor | distance + 100 (heavy penalty) |

### `ElevatorController`

| Member | Type | Description |
|--------|------|-------------|
| `instance` | static | Singleton holder |
| `elevators` | List\<Elevator\> | all elevators in building |
| `scheduler` | ElevatorScheduler | current dispatching strategy |
| `addElevator(e)` | void | register an elevator |
| `setScheduler(s)` | void | hot-swap scheduling algorithm |
| `hallRequest(floor, dir)` | void | assign best elevator for hall call |
| `cabinRequest(elevatorId, toFloor)` | void | add stop directly to specific elevator |
| `step()` | void | advance all elevators one tick |
| `runSteps(n)` | void | simulate N ticks with status prints |

---

## Step 6 — Design Patterns

### 1. Strategy — `ElevatorScheduler`

Scheduling algorithm is plugged in at construction time and can be hot-swapped.

```java
interface ElevatorScheduler {
    Elevator assign(List<Elevator> elevators, int requestFloor, Direction direction);
}
// Swap without changing ElevatorController:
ctrl.setScheduler(new SCANPriorityScheduler());
```

**Alternative strategies you can mention in interview:**
- `NearestElevatorScheduler` — simple distance-based (implemented)
- `DirectionalSCANScheduler` — always prefer elevator already on the way
- `LeastLoadedScheduler` — prefer elevator with fewest pending stops

### 2. Singleton — `ElevatorController`

One controller per building. Pattern identical to ParkingLot.

```java
ElevatorController.getInstance(new NearestElevatorScheduler());
```

### 3. State Machine — `Elevator`

The most complex state machine in this problem set because **state + direction + both queues** interact:

```java
// step() — SCAN direction logic
if (state == MOVING_UP) {
    currentFloor++;
    if (upQueue.remove(currentFloor)) openDoors();
    if (upQueue.isEmpty())
        state = downQueue.isEmpty() ? IDLE : MOVING_DOWN;
}
```

---

## Step 7 — Key Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Two queues vs one sorted queue | Two queues | Cleanly separates UP and DOWN stops; avoids interleaving logic |
| TreeSet for queues | Yes | Sorted → O(log n) insert/remove; ascending iteration for UP, last() for DOWN |
| Hall call → scheduler | Yes | Person outside only presses UP/DOWN; controller picks the right elevator |
| Cabin call → direct to elevator | Yes | Person inside already on the elevator; no scheduling needed |
| `step()` simulation | Yes | Elevator movement is time-based; demo must call step() in a loop |
| ElevatorController Singleton | Yes | One controller per building |

---

## Step 8 — How This Differs From Other Problems

| Aspect | Elevator | Other Problems |
|--------|----------|----------------|
| Multiple instances of same entity | ✅ (multiple Elevators) | ❌ (one ParkingLot, one VendingMachine) |
| Two request types with different handlers | ✅ (Hall vs Cabin) | ❌ (all requests handled same way) |
| Simulation ticks (time-based movement) | ✅ (`step()` loop) | ❌ (operations complete instantly) |
| Sorted queue (TreeSet) for algorithm | ✅ (SCAN) | ❌ (usually ArrayList or HashMap) |
| No transaction entity | ✅ (no ticket/receipt) | ❌ (ParkingTicket, BorrowRecord, etc.) |

---

## Step 9 — Extensibility

| Extension | How to add |
|-----------|-----------|
| New scheduling algorithm | Implement `ElevatorScheduler`, plug into `ElevatorController` — zero other changes |
| Emergency stop | Add `EMERGENCY` state, method `emergencyStop()` on `Elevator` |
| Express elevators | Subclass `Elevator` → `ExpressElevator` skips non-express floors in `step()` |
| Maintenance mode | Add `MAINTENANCE` state; scheduler skips elevators in this state |
| Real-time display | Observer pattern — `ElevatorController` notifies floor display panels on each `step()` |
| Passenger tracking | Add `ElevatorRide` entity — who entered which elevator at which floor, exited at which floor |

---

## Quick Recall — 3 Main Takeaways

1. **SCAN algorithm**: Two sorted queues (`upQueue`, `downQueue`) — serve all UP stops before reversing. The `step()` method is the entire algorithm.

2. **Hall call vs Cabin call**: The only LLD problem with two distinct request types — controller dispatches hall calls, elevator directly receives cabin calls.

3. **step() simulation**: Elevators don't teleport. You must call `step()` repeatedly in the demo. In real systems this is async/time-driven.
