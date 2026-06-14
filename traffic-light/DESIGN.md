# LLD: Traffic Light System

> Implementation: `TrafficLightSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Traffic lights cycle through RED → GREEN → YELLOW → RED automatically |
| 2 | Each state has a configurable duration in seconds |
| 3 | Multiple intersections, each holding multiple `TrafficLight` instances (one per direction) |
| 4 | Lights at an intersection are coordinated: opposite directions never both GREEN simultaneously |
| 5 | `TrafficController` manages all intersections and provides a global `tick(seconds)` |
| 6 | `tick(seconds)` advances all lights by the given number of seconds |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Purest state machine in the problem set — TIME alone drives all transitions, no user input |
| 2 | Each `TrafficLight` is self-contained — owns its timer and state |
| 3 | `TrafficController` is Singleton — one controller for all intersections |

### Out of Scope
Emergency vehicle preemption, pedestrian crossing signals, real hardware integration, adaptive signal timing based on traffic density

---

## Step 2 — The Unique Aspect: Time-Only State Machine

Traffic Light is the **purest state machine** in this entire problem set:

| Problem | What triggers state transition |
|---------|-------------------------------|
| Vending Machine | User action (coin insert, product select) |
| ATM | User action (PIN entry, transaction) |
| Auction | Time (endTime passes) + user action (placeBid) |
| **Traffic Light** | **Time ONLY — no user input ever** |

This means `tick()` is the ONLY way any state changes. There are no "request" methods from external actors.

---

## Step 3 — Entities

| Class | Role |
|-------|------|
| `TrafficLight` | Individual signal. Owns its state and countdown timer. Transitions on `tick()`. |
| `Intersection` | Groups multiple lights. Delegates `tick()` to each light. |
| `TrafficController` | Singleton. Manages all intersections. Provides global `tick(seconds)`. |

### Enum

| Enum | Values |
|------|--------|
| `LightColor` | RED, GREEN, YELLOW |

---

## Step 4 — TrafficLight State Machine

```
RED ──[timer=0]──► GREEN ──[timer=0]──► YELLOW ──[timer=0]──► RED
 ▲                                                               │
 └───────────────────────────────────────────────────────────────┘
```

**Fixed cycle order:** RED → GREEN → YELLOW → RED (always, no branching)

**Timer mechanics (from `tick()` in code):**
```java
public boolean tick() {
    timer--;
    if (timer <= 0) {
        transition(); // move to next color, reset timer to new color's duration
        return true;  // state changed
    }
    return false; // still in same state
}

private void transition() {
    switch (state) {
        case RED:    state = LightColor.GREEN;  break;
        case GREEN:  state = LightColor.YELLOW; break;
        case YELLOW: state = LightColor.RED;    break;
    }
    timer = durationFor(state); // reset timer for new state
}
```

---

## Step 5 — Intersection Coordination (No Both-Green Constraint)

Opposite directions are never both GREEN simultaneously. This is enforced by design — lights start out of phase:

```java
// North-South: starts GREEN (vehicles going N↔S can move)
i1.addLight(new TrafficLight("NS-NORTH", LightColor.GREEN, 30, 25, 5));
i1.addLight(new TrafficLight("NS-SOUTH", LightColor.GREEN, 30, 25, 5));

// East-West: starts RED (must wait while N-S is green)
i1.addLight(new TrafficLight("EW-EAST",  LightColor.RED,   30, 25, 5));
i1.addLight(new TrafficLight("EW-WEST",  LightColor.RED,   30, 25, 5));
```

Since all lights share the same durations, they remain permanently out of phase: when NS turns YELLOW then RED, EW turns GREEN — and vice versa. No runtime coordination logic is needed.

---

## Step 6 — Class Attributes & Methods

### `TrafficLight`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | identifier (e.g. "NS-NORTH") |
| `state` (private) | LightColor | current signal color |
| `timer` (private) | int | seconds remaining in current state |
| `redDuration` | int | seconds to stay RED |
| `greenDuration` | int | seconds to stay GREEN |
| `yellowDuration` | int | seconds to stay YELLOW |
| `getState()` | LightColor | read current color |
| `tick()` | boolean | advance 1 second; returns true if state changed |
| `tick(seconds)` | void | advance N seconds (calls single-second tick in loop) |
| `statusLine()` | String | formatted status with icon, color, timer |

### `Intersection`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | identifier (e.g. "INT-1") |
| `lights` | List\<TrafficLight\> | all lights at this intersection |
| `addLight(light)` | void | register a light |
| `tick()` | void | advance all lights by 1 second |
| `tick(seconds)` | void | advance all lights by N seconds |
| `printStatus()` | void | display all lights' status lines |

### `TrafficController` (Singleton)

| Member | Type | Description |
|--------|------|-------------|
| `instance` | static | Singleton holder |
| `intersections` | Map\<String, Intersection\> | all managed intersections |
| `timeElapsed` | int | total seconds simulated |
| `addIntersection(intersection)` | void | register intersection |
| `tick(seconds)` | void | advance all intersections by N seconds |
| `printStatus()` | void | display all intersections |
| `getLightState(intId, lightId)` | LightColor | query specific light's current color |

---

## Step 7 — Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Timer on each TrafficLight | Yes | Light owns its own timing — Information Expert |
| `tick()` returns boolean | Yes | Caller can detect state changes if needed (e.g. log transitions) |
| Fixed cycle order (no branching) | Yes | Real traffic lights follow a fixed sequence |
| Out-of-phase start for opposite directions | Yes | Simplest way to enforce no-both-GREEN — no runtime coordination needed |
| LinkedHashMap for intersections | Yes | Preserves insertion order for consistent status display |
| Singleton TrafficController | Yes | One controller per city/deployment manages all intersections |

---

## Step 8 — Timeline Example (from demo)

With NS=GREEN(25s), YELLOW(5s), RED(30s) and EW starting RED(30s):

| Time | NS-NORTH | EW-EAST |
|------|----------|---------|
| t=0 | GREEN (25s left) | RED (30s left) |
| t=10 | GREEN (15s left) | RED (20s left) |
| t=25 | YELLOW (5s left) | RED (5s left) |
| t=30 | RED | GREEN |
| t=55 | GREEN | YELLOW |
| t=60 | GREEN | RED |

---

## Step 9 — Extensibility

| Extension | How |
|-----------|-----|
| Emergency vehicle preemption | Add `forceGreen(directionId)` on Intersection — immediately transition that direction to GREEN, others to RED |
| Adaptive timing | Pass a `TrafficSensor` to `tick()` — adjust `greenDuration` based on waiting vehicle count |
| Pedestrian signal | Add a separate `PedestrianLight` class with WALK/DONT\_WALK states, synchronized with vehicle lights |
| Different cycle orders | Extract cycle as a `List<LightColor>` config on TrafficLight |

---

## Quick Recall — 3 Main Takeaways

1. **Purest state machine**: RED → GREEN → YELLOW → RED. `tick()` is the ONLY driver. No user input, no external events.

2. **Each light owns its timer**: `timer` lives on `TrafficLight`, not on `Intersection` or `TrafficController`. `transition()` resets the timer to the new state's configured duration automatically.

3. **Out-of-phase start enforces safety**: Opposite directions are initialized to opposite states (one GREEN, one RED) with the same durations — they stay permanently out of phase without any runtime coordination logic.
