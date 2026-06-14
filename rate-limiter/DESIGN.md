# LLD Design: Rate Limiter

> **Sync note:** Design companion to `rate-limiter.ts`. Keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
1. Each client can make a limited number of requests in a given time window
2. Requests within the limit are allowed; beyond → rejected with 429
3. Support multiple algorithms: Token Bucket, Fixed Window Counter
4. Algorithm is swappable without changing the service

### Non-Functional
- Per-client state is maintained independently — no client affects another
- Lazy refill — no background timer; tokens calculated on each request
- Algorithm swap does not affect in-flight client state

### Out of Scope
- Distributed rate limiting (needs Redis — mention in HLD)
- Per-endpoint limits
- IP-based limiting
- Rate limit headers in HTTP response

---

## Step 2 — Entities

| Noun | Becomes | Reason |
|---|---|---|
| RateLimiter | **Interface** | Algorithm varies → Strategy pattern |
| TokenBucketRateLimiter | Class | Concrete algorithm: token-based burst control |
| FixedWindowRateLimiter | Class | Concrete algorithm: hard reset per window |
| BucketState | Interface (data shape) | Per-client state for Token Bucket |
| WindowState | Interface (data shape) | Per-client state for Fixed Window |
| RateLimiterService | Service Class | Holds strategy, exposes `handleRequest()` |

No domain objects. The interesting part is the math inside `isAllowed()`, not the class structure.

---

## Step 3 — Class Design

---

### `RateLimiter` *(Interface — Strategy)*
- **Method:** `isAllowed(clientId: string): boolean`
- **Note:** Single method contract. Every algorithm implements this.

---

### `TokenBucketRateLimiter`
- **Attributes:**
  - `capacity: number` — max tokens in bucket
  - `refillRatePerSecond: number` — tokens added per second
  - `buckets: Map<clientId, BucketState>` — private, per-client state
- **Methods:** `isAllowed(clientId)`, `getTokens(clientId)` *(debug utility)*
- **Key logic (lazy refill):**
  1. Get or create bucket for client (start full)
  2. `elapsed = (now - lastRefillTime) / 1000`
  3. `tokensToAdd = elapsed * refillRatePerSecond`
  4. `bucket.tokens = min(capacity, tokens + tokensToAdd)`
  5. If tokens ≥ 1 → consume 1, allow. Else → reject

---

### `FixedWindowRateLimiter`
- **Attributes:**
  - `maxRequests: number`
  - `windowSizeMs: number`
  - `windows: Map<clientId, WindowState>` — private, per-client state
- **Methods:** `isAllowed(clientId)`, `getCount(clientId)` *(debug utility)*
- **Key logic:**
  1. Get or create window for client
  2. If `now - windowStart >= windowSizeMs` → reset count = 0
  3. If count < maxRequests → count++, allow. Else → reject

---

### `RateLimiterService`
- **Attributes:** `rateLimiter: RateLimiter` — private
- **Methods:** `handleRequest(clientId, endpoint): boolean`, `setRateLimiter(strategy): void`
- **Note:** Thin wrapper. Strategy is injected and swappable at runtime.

---

## Step 4 — Relationships

| From | To | Type | Why |
|---|---|---|---|
| `RateLimiterService` | `RateLimiter` | **Dependency (Uses)** | Strategy injected via constructor, swappable |
| `TokenBucketRateLimiter` | `BucketState` per client | **Composition** | State has no life outside the limiter |
| `FixedWindowRateLimiter` | `WindowState` per client | **Composition** | State has no life outside the limiter |

No parent-child list structures. Per-client state lives in a `Map` inside each algorithm.

---

## Step 5 — Design Patterns

### Strategy → `RateLimiter`
- **Why:** Multiple valid algorithms — Token Bucket, Fixed Window, Sliding Window. Swappable without changing the service.
- **How:** `RateLimiter` interface with `isAllowed()`. Service holds the reference. Swap via `setRateLimiter()`.
- **Interview line:** *"Strategy pattern — the service calls `isAllowed()` without knowing which algorithm runs. Adding Sliding Window is one new class."*

No Observer, No Singleton, No State Machine — structure is intentionally simple. Algorithm logic is the challenge.

---

## Step 6 — Algorithm Comparison

```
TOKEN BUCKET                          FIXED WINDOW
──────────────────────────────────    ──────────────────────────────────
State: { tokens, lastRefillTime }     State: { count, windowStart }

Lazy refill on every request          Reset count when window expires

Allows burst up to capacity           No burst — strict per window
Smooth rate over time                 Hard reset at boundary

Weakness: complex math                Weakness: 2× requests at boundary
Best for: API rate limiting           Best for: simple quota enforcement
```

---

## Step 7 — Extensibility

| Change Request | What changes |
|---|---|
| Add Sliding Window Log | Create `SlidingWindowRateLimiter implements RateLimiter`. Call `setRateLimiter()`. Done. |
| Add per-endpoint limits | Change bucket key from `clientId` to `clientId:endpoint` inside algorithm |
| Add distributed limiting | Replace in-memory `Map` with Redis reads/writes inside algorithm. Interface unchanged. |
| Different limits per client tier | Create `TieredRateLimiter` that looks up tier and delegates to appropriate strategy |

---

## HLD Bridge — say this in interview

> "In a distributed system, the in-memory Map won't work — two servers won't share state. The fix is Redis as the shared counter store. Token Bucket can be implemented with a Redis key storing token count + last refill time. The algorithm logic stays identical."

---

## Quick Recall

```
RateLimiter (interface)
  ├── TokenBucketRateLimiter   → state: { tokens, lastRefillTime } per client
  │     lazy refill: tokens += elapsed * rate, capped at capacity
  │     allow if tokens >= 1, consume 1
  └── FixedWindowRateLimiter  → state: { count, windowStart } per client
        reset count when window expires
        allow if count < max

RateLimiterService holds RateLimiter strategy → handleRequest(clientId, endpoint)

Pattern: Strategy only
No state machine, no Observer, no Singleton
Complexity: in the algorithm math, not in class relationships
```
