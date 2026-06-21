// =============================================================================
// LLD: RATE LIMITER — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Given a clientId, allow or deny a request based on a rate limit rule
//   2. Support Token Bucket algorithm: smooth burst handling, lazy refill
//   3. Support Fixed Window algorithm: count requests in a fixed time window
//   4. Multiple clients each get their own independent limit state
//
// Non-Functional:
//   - Algorithm is swappable without changing RateLimiterService (Strategy)
//   - O(1) per-request check — no external storage, no background threads
//   - Token Bucket uses lazy refill: state updated only when a request arrives
//
// Out of scope: Distributed rate limiting (Redis), persistence, sliding window
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — DATA HOLDERS (BucketState, WindowState)
// =============================================================================
// TypeScript used interfaces as lightweight data shapes.
// In Java we use simple package-level classes with public fields — same intent.

class BucketState {
    public double tokens;
    public long lastRefillTime;

    public BucketState(double tokens, long lastRefillTime) {
        this.tokens = tokens;
        this.lastRefillTime = lastRefillTime;
    }
}

class WindowState {
    public int count;
    public long windowStart;

    public WindowState(int count, long windowStart) {
        this.count = count;
        this.windowStart = windowStart;
    }
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Interface:  RateLimiter  (Strategy pattern)
// Classes:    TokenBucketRateLimiter, FixedWindowRateLimiter
// Service:    RateLimiterService
//
// Relationships:
//   RateLimiterService USES RateLimiter (swappable via setAlgorithm)
//   TokenBucketRateLimiter  OWNS Map<clientId, BucketState>
//   FixedWindowRateLimiter  OWNS Map<clientId, WindowState>
// =============================================================================


// ── RateLimiter (Strategy Pattern) ───────────────────────────────────────────

interface RateLimiter {
    boolean isAllowed(String clientId);
}


// ── TokenBucketRateLimiter ────────────────────────────────────────────────────
// Allows short bursts (up to capacity).
// Lazy refill: tokens are added only when a request arrives — no background thread.

class TokenBucketRateLimiter implements RateLimiter {
+    private double capacity;
    private double refillRatePerMs; // tokens added per millisecond
    private Map<String, BucketState> buckets;

    public TokenBucketRateLimiter(double capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerMs = refillRatePerSecond / 1000.0;
        this.buckets = new HashMap<>();
    }

    @Override
    public boolean isAllowed(String clientId) {
        long now = System.currentTimeMillis();

        if (!buckets.containsKey(clientId)) {
            buckets.put(clientId, new BucketState(capacity, now));
        }

        BucketState state = buckets.get(clientId);

        // Lazy refill: add tokens proportional to elapsed time
        long elapsed = now - state.lastRefillTime;
        state.tokens = Math.min(capacity, state.tokens + elapsed * refillRatePerMs);
        state.lastRefillTime = now;

        if (state.tokens >= 1) {
            state.tokens -= 1;
            return true;
        }
        return false;
    }
}


// ── FixedWindowRateLimiter ────────────────────────────────────────────────────
// Simple counter per time window. Resets at window boundary.
// Known weakness: two bursts at window edges can double effective rate.

class FixedWindowRateLimiter implements RateLimiter {
    private int maxRequests;
    private long windowSizeMs;
    private Map<String, WindowState> windows;

    public FixedWindowRateLimiter(int maxRequests, long windowSizeMs) {
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.windows = new HashMap<>();
    }

    @Override
    public boolean isAllowed(String clientId) {
        long now = System.currentTimeMillis();

        if (!windows.containsKey(clientId)) {
            windows.put(clientId, new WindowState(0, now));
        }

        WindowState state = windows.get(clientId);

        // If window expired, reset
        if (now - state.windowStart >= windowSizeMs) {
            state.count = 0;
            state.windowStart = now;
        }

        if (state.count < maxRequests) {
            state.count++;
            return true;
        }
        return false;
    }
}


// ── RateLimiterService ────────────────────────────────────────────────────────
// Delegates to the active RateLimiter algorithm.
// Switching algorithm = one call to setAlgorithm(). Nothing else changes.

class RateLimiterService {
    private RateLimiter algorithm;

    public RateLimiterService(RateLimiter algorithm) {
        this.algorithm = algorithm;
    }

    public void setAlgorithm(RateLimiter algorithm) {
        this.algorithm = algorithm;
    }

    public boolean handleRequest(String clientId) {
        boolean allowed = algorithm.isAllowed(clientId);
        System.out.println("[" + clientId + "] " + (allowed ? "ALLOWED" : "DENIED "));
        return allowed;
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: RateLimiterSolution.java
// =============================================================================

public class RateLimiterSolution {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Rate Limiter Demo ===\n");

        // --- Token Bucket: capacity=3, refill=1 token/sec ---
        System.out.println("── Token Bucket (capacity=3, refill=1/sec) ──");
        RateLimiterService service = new RateLimiterService(
                new TokenBucketRateLimiter(3, 1.0)
        );

        // Burst: 3 requests pass, 4th is denied
        service.handleRequest("client-A");
        service.handleRequest("client-A");
        service.handleRequest("client-A");
        service.handleRequest("client-A"); // DENIED — bucket empty

        // After 1.5 seconds, 1 token refills → next request passes
        System.out.println("  (waiting 1.5s for refill...)");
        Thread.sleep(1500);
        service.handleRequest("client-A"); // ALLOWED

        System.out.println();

        // --- Fixed Window: 3 requests per 1 second ---
        System.out.println("── Fixed Window (max=3 per 1 second) ──");
        service.setAlgorithm(new FixedWindowRateLimiter(3, 1000));

        service.handleRequest("client-B");
        service.handleRequest("client-B");
        service.handleRequest("client-B");
        service.handleRequest("client-B"); // DENIED

        System.out.println("  (waiting 1.1s for window reset...)");
        Thread.sleep(1100);
        service.handleRequest("client-B"); // ALLOWED — new window

        System.out.println();

        // --- Multiple independent clients ---
        System.out.println("── Multiple Clients (Fixed Window) ──");
        service.setAlgorithm(new FixedWindowRateLimiter(2, 1000));

        service.handleRequest("client-C");
        service.handleRequest("client-C");
        service.handleRequest("client-C"); // DENIED

        service.handleRequest("client-D"); // ALLOWED — independent state
        service.handleRequest("client-D");
        service.handleRequest("client-D"); // DENIED
    }
}
