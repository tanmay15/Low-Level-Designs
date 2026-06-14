// =============================================================================
// LLD: ELEVATOR SYSTEM — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Building has N floors and M elevators
//   2. TWO types of requests:
//        Hall call  — person OUTSIDE presses UP/DOWN button on a floor
//        Cabin call — person INSIDE elevator presses a destination floor
//   3. ElevatorController assigns the best elevator for hall calls (scheduling)
//   4. Each elevator runs the SCAN algorithm:
//        → serves all destinations in current direction before reversing
//        → upQueue for going-up stops, downQueue for going-down stops
//   5. Elevator states: IDLE → MOVING_UP ↔ MOVING_DOWN → IDLE
//   6. Movement is simulated tick-by-tick (one floor per step)
//
// Non-Functional:
//   - Scheduling algorithm is swappable (Strategy)
//   - ElevatorController is Singleton (one controller per building)
//   - Multiple elevators work independently but are coordinated by the controller
//
// Out of scope: Emergency stop, weight sensors, real-time display, express floors
// =============================================================================
//
// SCAN ALGORITHM (elevator algorithm):
//   Like disk scheduling in OS. Elevator moves in one direction, serves all
//   requests in that direction, then reverses and serves the other direction.
//   Two sorted queues: upQueue (ascending stops), downQueue (descending stops).
//
// TWO REQUEST TYPES:
//   Hall call  → controller picks best elevator → elevator.addDestination(floor)
//   Cabin call → directly to that elevator     → elevator.addDestination(floor)
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum Direction    { UP, DOWN }
enum ElevatorState { IDLE, MOVING_UP, MOVING_DOWN }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:   Elevator
// Interface:  ElevatorScheduler  (Strategy pattern)
// Concrete:   NearestElevatorScheduler
// Singleton:  ElevatorController
//
// KEY DESIGN DECISIONS:
//   1. TWO QUEUES per elevator (upQueue + downQueue) implement SCAN algorithm
//   2. TreeSet for queues → sorted order → O(log n) insert, O(log n) floor check
//   3. ElevatorController assigns hall requests via Strategy (scheduler)
//   4. Cabin requests bypass scheduler — go directly to the specific elevator
//   5. step() moves elevator one floor; demo calls it in a loop to simulate time
//
// Relationships:
//   ElevatorController HAS-A (Composition) List<Elevator>
//   ElevatorController USES                ElevatorScheduler (swappable)
//   Elevator           HAS-A               TreeSet upQueue, downQueue
// =============================================================================


// ── Elevator (State Machine + SCAN) ──────────────────────────────────────────
// The core entity. Each elevator runs independently.
//
// upQueue   — floors to stop at while moving UP   (TreeSet, ascending iteration)
// downQueue — floors to stop at while moving DOWN  (TreeSet, descending iteration)
//
// step() moves the elevator one floor per call and opens doors if it's a stop.

class Elevator {
    public int           id;
    public int           currentFloor;
    public ElevatorState state;
    public TreeSet<Integer> upQueue;    // ascending order — next stop is first()
    public TreeSet<Integer> downQueue;  // descending order — next stop is last()

    public Elevator(int id, int startFloor) {
        this.id           = id;
        this.currentFloor = startFloor;
        this.state        = ElevatorState.IDLE;
        this.upQueue      = new TreeSet<>();
        this.downQueue    = new TreeSet<>();
    }

    // Add a stop to the appropriate queue based on direction
    public void addDestination(int floor, Direction direction) {
        if (floor == currentFloor) {
            System.out.println("  [E" + id + "] Already at floor " + floor + " — doors open");
            return;
        }
        if (direction == Direction.UP || floor > currentFloor) {
            upQueue.add(floor);
        } else {
            downQueue.add(floor);
        }
        // Kick elevator into motion if it was idle
        if (state == ElevatorState.IDLE) {
            state = (!upQueue.isEmpty()) ? ElevatorState.MOVING_UP : ElevatorState.MOVING_DOWN;
        }
    }

    // One simulation tick — moves one floor, opens doors if it's a scheduled stop.
    // SCAN logic: serve all UP stops first, then reverse to serve DOWN stops (or go IDLE).
    public void step() {
        if (state == ElevatorState.IDLE) return; // nothing to do

        if (state == ElevatorState.MOVING_UP) {
            currentFloor++;
            if (upQueue.remove(currentFloor)) { // O(log n) check + remove
                System.out.println("  [E" + id + "] 🔔 Arrived at floor " + currentFloor + " ↑ (doors open)");
            }
            // If no more UP stops, reverse or go IDLE
            if (upQueue.isEmpty()) {
                state = downQueue.isEmpty() ? ElevatorState.IDLE : ElevatorState.MOVING_DOWN;
            }
        } else { // MOVING_DOWN
            currentFloor--;
            if (downQueue.remove(currentFloor)) {
                System.out.println("  [E" + id + "] 🔔 Arrived at floor " + currentFloor + " ↓ (doors open)");
            }
            if (downQueue.isEmpty()) {
                state = upQueue.isEmpty() ? ElevatorState.IDLE : ElevatorState.MOVING_UP;
            }
        }
    }

    public String statusLine() {
        String direction = state == ElevatorState.MOVING_UP   ? "↑"
                         : state == ElevatorState.MOVING_DOWN ? "↓" : "—";
        return String.format("[E%d] Floor:%2d %s | %-12s | Up:%s Down:%s",
                id, currentFloor, direction, state, upQueue, downQueue);
    }
}


// ── ElevatorScheduler (Strategy Pattern) ─────────────────────────────────────
// Decides which elevator to assign for a HALL request.
// Swap the implementation to change the scheduling algorithm — nothing else changes.

interface ElevatorScheduler {
    Elevator assign(List<Elevator> elevators, int requestFloor, Direction direction);
}

// Nearest available elevator. Prefers:
//   1. Elevator already moving toward the request in the right direction
//   2. IDLE elevator (by distance)
//   3. Any elevator (penalised score)
class NearestElevatorScheduler implements ElevatorScheduler {

    @Override
    public Elevator assign(List<Elevator> elevators, int requestFloor, Direction direction) {
        Elevator best      = null;
        int      bestScore = Integer.MAX_VALUE;

        for (Elevator e : elevators) {
            int score = computeScore(e, requestFloor, direction);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private int computeScore(Elevator e, int requestFloor, Direction direction) {
        int distance = Math.abs(e.currentFloor - requestFloor);

        // IDLE elevator is best — score = raw distance
        if (e.state == ElevatorState.IDLE) return distance;

        // Elevator moving in SAME direction TOWARD the request floor — great candidate
        if (e.state == ElevatorState.MOVING_UP
                && direction == Direction.UP
                && e.currentFloor <= requestFloor) {
            return distance; // same scoring as IDLE — already heading there
        }
        if (e.state == ElevatorState.MOVING_DOWN
                && direction == Direction.DOWN
                && e.currentFloor >= requestFloor) {
            return distance;
        }

        // Moving wrong direction or already past floor — penalize
        return distance + 100;
    }
}


// ── ElevatorController (Singleton) ────────────────────────────────────────────
// One controller per building. Manages all elevators and dispatches requests.
//
// Hall call  → controller asks scheduler to pick best elevator, adds stop to it
// Cabin call → directly adds destination to the specific elevator
// step()     → advances all elevators one tick

class ElevatorController {
    private static ElevatorController instance;

    private List<Elevator>      elevators;
    private ElevatorScheduler   scheduler;

    private ElevatorController(ElevatorScheduler scheduler) {
        this.elevators = new ArrayList<>();
        this.scheduler = scheduler;
    }

    public static ElevatorController getInstance(ElevatorScheduler scheduler) {
        if (instance == null) instance = new ElevatorController(scheduler);
        return instance;
    }

    // Allow reset between demo scenarios
    public static void reset() { instance = null; }

    public void addElevator(Elevator elevator) {
        elevators.add(elevator);
        System.out.println("[SETUP] Added Elevator E" + elevator.id +
                " starting at floor " + elevator.currentFloor);
    }

    public void setScheduler(ElevatorScheduler scheduler) {
        this.scheduler = scheduler;
        System.out.println("[SCHEDULER] Switched to " + scheduler.getClass().getSimpleName());
    }

    // Hall call: person outside presses UP or DOWN button on a floor
    public void hallRequest(int floor, Direction direction) {
        Elevator assigned = scheduler.assign(elevators, floor, direction);
        if (assigned == null) throw new RuntimeException("No elevators available");
        assigned.addDestination(floor, direction);
        System.out.println("[HALL CALL]  Floor " + floor + " " + direction +
                " → assigned to E" + assigned.id);
    }

    // Cabin call: person inside elevator presses a destination floor button
    public void cabinRequest(int elevatorId, int toFloor) {
        for (Elevator e : elevators) {
            if (e.id == elevatorId) {
                Direction dir = toFloor > e.currentFloor ? Direction.UP : Direction.DOWN;
                e.addDestination(toFloor, dir);
                System.out.println("[CABIN CALL] E" + elevatorId + " → floor " + toFloor);
                return;
            }
        }
        throw new RuntimeException("Elevator E" + elevatorId + " not found");
    }

    // Advance all elevators one floor (one simulation tick)
    public void step() {
        for (Elevator e : elevators) e.step();
    }

    // Run N ticks and print status after each
    public void runSteps(int n) {
        for (int tick = 1; tick <= n; tick++) {
            System.out.println("  -- Tick " + tick + " --");
            step();
            printStatus();
        }
    }

    public void printStatus() {
        for (Elevator e : elevators) {
            System.out.println("  " + e.statusLine());
        }
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: ElevatorSystemSolution.java
// =============================================================================

public class ElevatorSystemSolution {
    public static void main(String[] args) {
        System.out.println("=== Elevator System Demo ===\n");

        // ── Scenario 1: Single elevator, SCAN in action ───────────────────────
        System.out.println("════ Scenario 1: SCAN algorithm (1 elevator) ════");
        ElevatorController.reset();
        ElevatorController ctrl = ElevatorController.getInstance(new NearestElevatorScheduler());
        Elevator e1 = new Elevator(1, 0); // starts at ground floor
        ctrl.addElevator(e1);
        System.out.println();

        // Hall calls from floors 3 (UP), 5 (UP), 7 (UP)
        ctrl.hallRequest(3, Direction.UP);
        ctrl.hallRequest(5, Direction.UP);
        ctrl.hallRequest(7, Direction.UP);

        // Cabin call — person gets in at floor 3 and presses floor 6
        // (will be processed when E1 stops at floor 3)
        ctrl.cabinRequest(1, 6);

        System.out.println("\n── Running 10 ticks ──");
        ctrl.runSteps(10);

        System.out.println();

        // ── Scenario 2: Mixed UP and DOWN requests — SCAN reversal ────────────
        System.out.println("════ Scenario 2: UP stops then DOWN reversal (1 elevator) ════");
        ElevatorController.reset();
        ctrl = ElevatorController.getInstance(new NearestElevatorScheduler());
        Elevator e2 = new Elevator(1, 5); // starts at floor 5
        ctrl.addElevator(e2);
        System.out.println();

        ctrl.hallRequest(8, Direction.UP);   // goes up first
        ctrl.hallRequest(10, Direction.UP);
        ctrl.hallRequest(2, Direction.DOWN); // then reverses down
        ctrl.hallRequest(1, Direction.DOWN);

        System.out.println("\n── Running 14 ticks ──");
        ctrl.runSteps(14);

        System.out.println();

        // ── Scenario 3: Multiple elevators + scheduler assignment ─────────────
        System.out.println("════ Scenario 3: 2 elevators — scheduler picks best one ════");
        ElevatorController.reset();
        ctrl = ElevatorController.getInstance(new NearestElevatorScheduler());

        Elevator eA = new Elevator(1, 1);  // E1 at floor 1
        Elevator eB = new Elevator(2, 8);  // E2 at floor 8

        ctrl.addElevator(eA);
        ctrl.addElevator(eB);
        System.out.println();

        // Floor 3 going UP → E1 is closer (at 1, dist=2 vs E2 at 8, dist=5)
        ctrl.hallRequest(3, Direction.UP);

        // Floor 6 going UP → E2 is already at 8 and going DOWN — penalized.
        // E1 just got request for floor 3 and will pass through 6 going UP.
        ctrl.hallRequest(6, Direction.UP);

        // Floor 9 going UP → E2 at floor 8 is much closer
        ctrl.hallRequest(9, Direction.UP);

        // Cabin calls
        ctrl.cabinRequest(1, 5); // person in E1 going to floor 5
        ctrl.cabinRequest(2, 7); // person in E2 going to floor 7

        System.out.println("\n── Status at start ──");
        ctrl.printStatus();

        System.out.println("\n── Running 10 ticks ──");
        ctrl.runSteps(10);
    }
}
