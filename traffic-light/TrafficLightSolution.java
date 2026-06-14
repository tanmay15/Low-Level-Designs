// =============================================================================
// LLD: TRAFFIC LIGHT SYSTEM
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Traffic lights cycle through RED → GREEN → YELLOW → RED automatically
//   2. Each light has configurable durations per state
//   3. Multiple intersections, each with multiple lights (one per direction)
//   4. Lights at an intersection are coordinated: if North-South is GREEN,
//      East-West must be RED (opposite directions never both GREEN)
//   5. TrafficController manages all intersections
//   6. tick(seconds) advances the simulation
//
// Non-Functional:
//   - Pure state machine — no user input triggers transitions, time does
//   - Each TrafficLight is self-contained (knows its own timer and state)
//
// Out of scope: emergency vehicle preemption, pedestrian crossing signals,
//   real hardware integration, adaptive signal timing based on traffic density
//
// UNIQUE ASPECT:
//   Traffic Light is the PUREST state machine in this problem set.
//   No user action triggers transitions — TIME alone does.
//   Every other state machine (Vending Machine, ATM, Order) needs a user action.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum LightColor { RED, GREEN, YELLOW }


// =============================================================================
// TRAFFIC LIGHT (State Machine)
// =============================================================================
// Each light manages its own state and timer.
// tick(seconds) decrements the timer; when it hits 0 → transition to next state.
//
// Cycle: RED → GREEN → YELLOW → RED → ...
// Each state has its own duration (configurable).

class TrafficLight {
    public String     id;
    private LightColor state;
    private int        timer;       // seconds remaining in current state

    // Durations (seconds) for each state — configurable per light
    private int redDuration;
    private int greenDuration;
    private int yellowDuration;

    public TrafficLight(String id, LightColor initialState,
                        int redDuration, int greenDuration, int yellowDuration) {
        this.id             = id;
        this.state          = initialState;
        this.redDuration    = redDuration;
        this.greenDuration  = greenDuration;
        this.yellowDuration = yellowDuration;
        this.timer          = durationFor(initialState);
    }

    public LightColor getState() { return state; }

    // Advance by one second. Returns true if state changed.
    public boolean tick() {
        timer--;
        if (timer <= 0) {
            transition();
            return true;
        }
        return false;
    }

    // Advance by multiple seconds at once.
    public void tick(int seconds) {
        for (int i = 0; i < seconds; i++) tick();
    }

    private void transition() {
        // RED → GREEN → YELLOW → RED (fixed cycle)
        switch (state) {
            case RED:    state = LightColor.GREEN;  break;
            case GREEN:  state = LightColor.YELLOW; break;
            case YELLOW: state = LightColor.RED;    break;
        }
        timer = durationFor(state);
    }

    private int durationFor(LightColor color) {
        switch (color) {
            case RED:    return redDuration;
            case GREEN:  return greenDuration;
            case YELLOW: return yellowDuration;
            default:     return redDuration;
        }
    }

    public String statusLine() {
        String icon = state == LightColor.GREEN  ? "🟢"
                    : state == LightColor.YELLOW ? "🟡" : "🔴";
        return String.format("%s %s %-6s [%ds remaining]", icon, id, state, timer);
    }
}


// =============================================================================
// INTERSECTION
// =============================================================================
// A junction with multiple traffic lights — one per direction/road.
// Key constraint: opposite directions are NEVER both GREEN at the same time.
// We enforce this by grouping lights into two opposing sets and starting them
// out of phase (one group starts RED when the other starts GREEN).

class Intersection {
    public String             id;
    public List<TrafficLight> lights;

    public Intersection(String id) {
        this.id     = id;
        this.lights = new ArrayList<>();
    }

    public void addLight(TrafficLight light) {
        lights.add(light);
    }

    // Advance all lights by one tick
    public void tick() {
        for (TrafficLight light : lights) light.tick();
    }

    public void tick(int seconds) {
        for (int i = 0; i < seconds; i++) tick();
    }

    public void printStatus() {
        System.out.println("  Intersection " + id + ":");
        for (TrafficLight light : lights) {
            System.out.println("    " + light.statusLine());
        }
    }
}


// =============================================================================
// TRAFFIC CONTROLLER (Singleton)
// =============================================================================
// Manages all intersections. Provides a global tick() to advance everything.

class TrafficController {
    private static TrafficController instance;
    private Map<String, Intersection> intersections;
    private int                       timeElapsed; // seconds

    private TrafficController() {
        this.intersections = new LinkedHashMap<>();
        this.timeElapsed   = 0;
    }

    public static TrafficController getInstance() {
        if (instance == null) instance = new TrafficController();
        return instance;
    }

    public static void reset() { instance = null; }

    public void addIntersection(Intersection intersection) {
        intersections.put(intersection.id, intersection);
        System.out.println("[SETUP] Intersection added: " + intersection.id
                + " with " + intersection.lights.size() + " lights");
    }

    public void tick(int seconds) {
        timeElapsed += seconds;
        for (Intersection intersection : intersections.values()) {
            intersection.tick(seconds);
        }
    }

    public void printStatus() {
        System.out.println("── Traffic Status [t=" + timeElapsed + "s] ──");
        for (Intersection intersection : intersections.values()) {
            intersection.printStatus();
        }
    }

    public LightColor getLightState(String intersectionId, String lightId) {
        Intersection intersection = intersections.get(intersectionId);
        if (intersection == null) throw new RuntimeException("Intersection not found");
        for (TrafficLight light : intersection.lights) {
            if (light.id.equals(lightId)) return light.getState();
        }
        throw new RuntimeException("Light not found: " + lightId);
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class TrafficLightSolution {
    public static void main(String[] args) {
        System.out.println("=== Traffic Light System Demo ===\n");

        TrafficController.reset();
        TrafficController controller = TrafficController.getInstance();

        // ── Intersection 1: North-South vs East-West ──────────────────────────
        // North-South starts GREEN. East-West starts RED.
        // They are always out of phase — one GREEN when other is RED.
        // Durations: RED=30s, GREEN=25s, YELLOW=5s

        Intersection i1 = new Intersection("INT-1");

        // North-South road: starts GREEN (vehicles going N↔S can go)
        i1.addLight(new TrafficLight("NS-NORTH", LightColor.GREEN, 30, 25, 5));
        i1.addLight(new TrafficLight("NS-SOUTH", LightColor.GREEN, 30, 25, 5));

        // East-West road: starts RED (must wait while N-S is green)
        i1.addLight(new TrafficLight("EW-EAST",  LightColor.RED,   30, 25, 5));
        i1.addLight(new TrafficLight("EW-WEST",  LightColor.RED,   30, 25, 5));

        // ── Intersection 2: Pedestrian crossing (shorter cycles) ──────────────
        Intersection i2 = new Intersection("INT-2-PED");
        i2.addLight(new TrafficLight("VEHICLE",    LightColor.GREEN, 20, 15, 3));
        i2.addLight(new TrafficLight("PEDESTRIAN", LightColor.RED,   20, 15, 3));

        controller.addIntersection(i1);
        controller.addIntersection(i2);

        System.out.println();

        // ── Initial state (t=0) ───────────────────────────────────────────────
        System.out.println("════ Initial State ════");
        controller.printStatus();
        System.out.println();

        // ── After 10 seconds ─────────────────────────────────────────────────
        System.out.println("════ After 10 seconds ════");
        controller.tick(10);
        controller.printStatus();
        System.out.println();

        // ── After 25 more seconds (total 35s) ────────────────────────────────
        // At t=25: NS turns YELLOW. At t=30: NS turns RED, EW turns GREEN.
        System.out.println("════ After 25 more seconds (t=35) ════");
        controller.tick(25);
        controller.printStatus();
        System.out.println();

        // ── After 10 more seconds (total 45s) ─────────────────────────────────
        System.out.println("════ After 10 more seconds (t=45) ════");
        controller.tick(10);
        controller.printStatus();
        System.out.println();

        // ── Full cycle (t=65) ─────────────────────────────────────────────────
        System.out.println("════ After full cycle (t=65) ════");
        controller.tick(20);
        controller.printStatus();
        System.out.println();

        // ── Query specific light ───────────────────────────────────────────────
        System.out.println("════ Query Specific Light ════");
        LightColor nsState = controller.getLightState("INT-1", "NS-NORTH");
        System.out.println("  INT-1 / NS-NORTH: " + nsState);
    }
}
