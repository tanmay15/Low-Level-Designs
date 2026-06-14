// =============================================================================
// LLD: CUSTOM PROMISE (MyPromise) — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. MyPromise wraps an async operation and tracks its result/failure
//   2. State transitions: PENDING → FULFILLED (via resolve) or REJECTED (via reject)
//   3. .then(onFulfilled, onRejected) registers callbacks and returns a new promise
//   4. Callbacks stored when PENDING, fired immediately if already FULFILLED/REJECTED
//   5. .then() chains — each .then() receives the previous .then()'s return value
//   6. MyPromise.all(list) resolves when all resolve; rejects if any reject
//
// Non-Functional:
//   - State is strictly one-way: PENDING → FULFILLED/REJECTED (never reverses)
//   - resolve/reject calls after first one are ignored (idempotent)
//   - Executor exceptions are caught and forwarded to reject automatically
//
// Out of scope: Async threading, Promise.race, Promise.any, microtask queue
// =============================================================================

import java.util.*;
import java.util.function.*;


// =============================================================================
// STEP 2 — ENUM
// =============================================================================

enum PromiseState { PENDING, FULFILLED, REJECTED }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Executor interface: receives (resolve, reject) and is called immediately
// MyPromise:
//   - state, value (resolved result or rejection reason)
//   - onFulfilledCallbacks, onRejectedCallbacks  → the key "subscriber" lists
//   - resolve() / reject()  → the "publisher" — fires stored callbacks
//   - then()                → subscriber + returns new Promise for chaining
//   - all()                 → static combinator
//
// THE CORE CONNECTION:
//   then(callback)   → stores callback in list        (subscribe)
//   resolve(value)   → fires all stored callbacks     (publish)
//   reject(reason)   → fires all stored callbacks     (publish)
//
// Relationships:
//   MyPromise HAS-A Executor (called once in constructor)
//   MyPromise HAS-A List<onFulfilledCallbacks>
//   MyPromise HAS-A List<onRejectedCallbacks>
//   then() creates and returns a child MyPromise
//   child MyPromise's resolve/reject are wired into parent's callback lists
//
// Design Pattern:
//   Observer — parent promise is the subject; callbacks stored by then() are observers
//   State Machine — PENDING → FULFILLED | REJECTED (strict one-way)
// =============================================================================


// ── Executor ──────────────────────────────────────────────────────────────────
// Receives resolve and reject as arguments. Called immediately inside constructor.
// The caller invokes resolve(value) or reject(reason) when the operation completes.

interface Executor {
    void execute(Consumer<Object> resolve, Consumer<Object> reject);
}


// ── MyPromise ─────────────────────────────────────────────────────────────────

class MyPromise {
    private PromiseState state;
    private Object value; // resolved value OR rejection reason — set once

    // Subscriber lists — populated by then(), fired by resolve()/reject()
    private List<Consumer<Object>> onFulfilledCallbacks;
    private List<Consumer<Object>> onRejectedCallbacks;

    public MyPromise(Executor executor) {
        this.state = PromiseState.PENDING;
        this.onFulfilledCallbacks = new ArrayList<>();
        this.onRejectedCallbacks  = new ArrayList<>();

        // Executor is called IMMEDIATELY with our resolve/reject as callbacks
        try {
            executor.execute(this::resolve, this::reject);
        } catch (Exception e) {
            reject(e); // uncaught executor exceptions auto-reject
        }
    }

    // ── resolve / reject  (Publishers) ───────────────────────────────────────
    // These are the triggers — they fire all callbacks stored by then().
    // Calling them after the first time is a no-op (state is already set).

    private void resolve(Object value) {
        if (state != PromiseState.PENDING) return; // one-way transition only
        this.state = PromiseState.FULFILLED;
        this.value = value;
        for (Consumer<Object> cb : onFulfilledCallbacks) cb.accept(value);
    }

    private void reject(Object reason) {
        if (state != PromiseState.PENDING) return;
        this.state = PromiseState.REJECTED;
        this.value = reason;
        for (Consumer<Object> cb : onRejectedCallbacks) cb.accept(reason);
    }

    // ── .then()  (Subscriber + Chain link) ───────────────────────────────────
    // Stores callbacks if PENDING, runs them immediately if already settled.
    // Returns a NEW MyPromise that resolves with onFulfilled's return value.
    // This is what makes chaining possible: p.then(...).then(...).then(...)

    public MyPromise then(Function<Object, Object> onFulfilled,
                          Consumer<Object> onRejected) {
        return new MyPromise((resolve, reject) -> {

            // Wraps onFulfilled: runs it, then resolves the next promise in chain
            Consumer<Object> handleFulfill = (val) -> {
                try {
                    Object result = onFulfilled.apply(val);
                    resolve.accept(result); // resolve the child promise → triggers next .then()
                } catch (Exception e) {
                    reject.accept(e);
                }
            };

            // Wraps onRejected: if handled → continue chain; if not → propagate
            Consumer<Object> handleReject = (reason) -> {
                if (onRejected != null) {
                    onRejected.accept(reason);
                    resolve.accept(null); // error handled → keep chain alive
                } else {
                    reject.accept(reason); // propagate rejection down chain
                }
            };

            if (state == PromiseState.FULFILLED) {
                handleFulfill.accept(value);      // already resolved → run now
            } else if (state == PromiseState.REJECTED) {
                handleReject.accept(value);
            } else {
                // PENDING → store for when resolve/reject is eventually called
                onFulfilledCallbacks.add(handleFulfill);
                onRejectedCallbacks.add(handleReject);
            }
        });
    }

    // Convenience: then with only onFulfilled (no error handler)
    public MyPromise then(Function<Object, Object> onFulfilled) {
        return then(onFulfilled, null);
    }

    // ── MyPromise.all() ───────────────────────────────────────────────────────
    // Resolves when ALL promises in the list resolve (with list of all values).
    // Rejects immediately if ANY promise rejects.
    // Counter trick: int[] instead of int because lambdas need effectively-final variables.

    public static MyPromise all(List<MyPromise> promises) {
        return new MyPromise((resolve, reject) -> {
            if (promises.isEmpty()) {
                resolve.accept(new ArrayList<>());
                return;
            }

            List<Object> results    = new ArrayList<>(Collections.nCopies(promises.size(), null));
            int[]        doneCount  = {0};   // int[] trick — mutable counter inside lambda

            for (int i = 0; i < promises.size(); i++) {
                final int idx = i;
                promises.get(i).then((val) -> {
                    results.set(idx, val);
                    doneCount[0]++;
                    if (doneCount[0] == promises.size()) {
                        resolve.accept(results); // all done → resolve with result list
                    }
                    return null;
                }, (reason) -> {
                    reject.accept(reason); // any rejection → reject all
                    return null;
                });
            }
        });
    }

    public PromiseState getState() { return state; }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: MyPromiseSolution.java
// =============================================================================

public class MyPromiseSolution {
    public static void main(String[] args) {
        System.out.println("=== MyPromise Demo ===\n");

        // ── Basic: resolve and chain ─────────────────────────────────────────
        System.out.println("── Basic resolve + chain ──");
        new MyPromise((resolve, reject) -> resolve.accept("user-data-from-db"))
            .then((val) -> {
                System.out.println("Step 1 got: " + val);
                return "processed: " + val;          // passed to next .then()
            })
            .then((val) -> {
                System.out.println("Step 2 got: " + val);
                return null;
            });

        System.out.println();

        // ── Rejection propagates down chain ──────────────────────────────────
        System.out.println("── Rejection propagation ──");
        new MyPromise((resolve, reject) -> reject.accept("Network timeout"))
            .then((val) -> { System.out.println("won't run"); return null; })
            .then((val) -> { System.out.println("won't run either"); return null; },
                  (err) -> System.out.println("Caught at step 2: " + err));

        System.out.println();

        // ── Executor exception auto-rejects ──────────────────────────────────
        System.out.println("── Executor exception → auto reject ──");
        new MyPromise((resolve, reject) -> {
            throw new RuntimeException("DB connection failed");
        }).then(
            (val) -> { System.out.println("won't run"); return null; },
            (err) -> System.out.println("Auto-rejected: " + ((Exception) err).getMessage())
        );

        System.out.println();

        // ── State is one-way: second resolve call is ignored ─────────────────
        System.out.println("── Second resolve ignored ──");
        MyPromise p = new MyPromise((resolve, reject) -> {
            resolve.accept("first");
            resolve.accept("second"); // ignored — already FULFILLED
        });
        p.then((val) -> {
            System.out.println("Value: " + val); // always "first"
            return null;
        });

        System.out.println();

        // ── Promise.all: all succeed ──────────────────────────────────────────
        System.out.println("── Promise.all: all succeed ──");
        MyPromise p1 = new MyPromise((res, rej) -> res.accept("Users loaded"));
        MyPromise p2 = new MyPromise((res, rej) -> res.accept("Orders loaded"));
        MyPromise p3 = new MyPromise((res, rej) -> res.accept("Products loaded"));

        MyPromise.all(Arrays.asList(p1, p2, p3)).then((results) -> {
            System.out.println("All resolved: " + results);
            return null;
        });

        System.out.println();

        // ── Promise.all: one rejects ──────────────────────────────────────────
        System.out.println("── Promise.all: one rejects ──");
        MyPromise p4 = new MyPromise((res, rej) -> res.accept("A ok"));
        MyPromise p5 = new MyPromise((res, rej) -> rej.accept("B failed"));
        MyPromise p6 = new MyPromise((res, rej) -> res.accept("C ok"));

        MyPromise.all(Arrays.asList(p4, p5, p6)).then(
            (results) -> { System.out.println("won't run"); return null; },
            (err)     -> System.out.println("all() rejected: " + err)
        );
    }
}
