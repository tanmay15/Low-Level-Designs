# LLD: Custom Promise (MyPromise)

> **Code file:** `MyPromiseSolution.java` — keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
| # | Requirement |
|---|---|
| 1 | MyPromise wraps an operation and tracks result/failure |
| 2 | State transitions: PENDING → FULFILLED (resolve) or REJECTED (reject) |
| 3 | `.then(onFulfilled, onRejected)` registers callbacks, returns a NEW promise |
| 4 | Callbacks stored when PENDING; fired immediately when already settled |
| 5 | `.then()` chains — each step receives the previous step's return value |
| 6 | `MyPromise.all(list)` resolves when all resolve; rejects if any reject |

### Non-Functional
- State is strictly one-way (no reversal, no double-firing)
- Executor exceptions are auto-forwarded to `reject`

### Out of Scope
Async threading, microtask queue, `Promise.race`, `Promise.any`

---

## Step 2 — Entities

| Entity | Type | Role |
|---|---|---|
| `PromiseState` | Enum | PENDING / FULFILLED / REJECTED |
| `Executor` | Interface | Receives `(resolve, reject)` callbacks; called immediately in constructor |
| `MyPromise` | Class | Holds state, value, callback lists; exposes `then()` and `all()` |

---

## Step 3 — Class Design

### The Core Connection (what most people miss)

```
then(callback)  →  stores callback in list          ← SUBSCRIBE
resolve(value)  →  fires all stored callbacks       ← PUBLISH
reject(reason)  →  fires all stored callbacks       ← PUBLISH
```

`resolve()` and `reject()` ARE the triggers. `then()` IS the subscriber.
Without the stored callback lists, there is no connection between them.

### State Machine

```
              resolve(value)
   PENDING ─────────────────► FULFILLED
      │
      │ reject(reason)
      ▼
   REJECTED

Transitions are strictly one-way. Second resolve()/reject() calls are no-ops.
```

### How Chaining Works

```
p1 = new MyPromise(executor)       ← PENDING
p2 = p1.then(callbackA)            ← p2 is a NEW promise, PENDING
p3 = p2.then(callbackB)            ← p3 is a NEW promise, PENDING

executor calls resolve("data")
  → p1 becomes FULFILLED
  → fires callbackA("data"), result = callbackA("data")
  → p2.resolve(result)             ← p2 becomes FULFILLED
  → fires callbackB(result)
  → p3.resolve(...)                ← p3 becomes FULFILLED
```

### Attributes

**`MyPromise`**
- `PromiseState state` — current state
- `Object value` — resolved value or rejection reason (set once)
- `List<Consumer<Object>> onFulfilledCallbacks` — stored by `.then()`, fired by `resolve()`
- `List<Consumer<Object>> onRejectedCallbacks` — stored by `.then()`, fired by `reject()`

**Methods**
- `private resolve(value)` — sets FULFILLED, fires all onFulfilled callbacks
- `private reject(reason)` — sets REJECTED, fires all onRejected callbacks
- `public then(onFulfilled, onRejected)` — stores or immediately runs callbacks; returns new promise
- `static all(list)` — resolves when all promises resolve; rejects on first rejection

---

## Step 4 — Design Patterns

### 1. Observer (Primary pattern)
`MyPromise` is the subject. Callbacks registered via `.then()` are the observers.
`resolve()`/`reject()` is the event that notifies all observers.

Unlike our Notification Service (where observers are named objects), here observers are anonymous lambdas stored in a list.

### 2. State Machine
```
PENDING → FULFILLED  (one-way, one-time via resolve)
PENDING → REJECTED   (one-way, one-time via reject)
No other transitions exist.
```
Guard: `if (state != PENDING) return;` — same pattern as ShowSeat's lock/book/release.

---

## Step 5 — What Makes This Different From Other Problems

| Aspect | Other LLD problems | MyPromise |
|---|---|---|
| Primary challenge | Entity design + relationships | Callback wiring + state machine |
| State owner | Dedicated entity (ShowSeat, BookCopy) | The promise itself |
| "Observer" | Named classes implementing interface | Anonymous lambdas in a list |
| Chaining | Not applicable | Core feature — each .then() returns a new promise |
| Static combinator | Not applicable | `all()` — coordinates multiple promises |

### The `int[]` trick in `all()`
Java lambdas require variables to be effectively final. `int resolvedCount` cannot be mutated inside a lambda. Wrapping in `int[] resolvedCount = {0}` makes the array reference final while the content is mutable.

---

## Step 6 — Extensibility

| Feature | How to add |
|---|---|
| `Promise.race(list)` | Same as `all()` but resolve/reject on the FIRST settled promise |
| `Promise.any(list)` | Resolve on first success; reject only if ALL reject |
| Retry on rejection | In `then()`, if rejected, re-execute executor up to N times |
| Async (real threading) | Wrap executor in a Thread; use `synchronized` on state changes |
