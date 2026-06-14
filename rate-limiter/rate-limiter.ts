// =============================================================================
// LLD: RATE LIMITER
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================


// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Each client can make a limited number of requests in a time window
//   2. Requests within the limit are allowed; beyond the limit → rejected (429)
//   3. Support multiple algorithms: Token Bucket, Fixed Window Counter
//   4. Algorithm should be swappable without changing the service
//
// Non-Functional:
//   - Per-client state maintained independently (no client affects another)
//   - Lazy refill — no background timer needed; calculated on each request
//   - Algorithm swap should not affect in-flight client state
//
// Out of scope: Distributed rate limiting (needs Redis), per-endpoint limits,
//               IP-based limiting, rate limit headers in HTTP response
// =============================================================================


// =============================================================================
// STEP 2 — STATE SHAPES (internal per-client data, not domain entities)
// =============================================================================

// Token Bucket: each client has tokens + the time they were last refilled
interface BucketState {
  tokens: number;
  lastRefillTime: number; // Date.now() in ms
}

// Fixed Window: each client has a request count + when the current window started
interface WindowState {
  count: number;
  windowStart: number; // Date.now() in ms
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Interface:  RateLimiter          (Strategy pattern — one method: isAllowed)
// Algorithms: TokenBucketRateLimiter, FixedWindowRateLimiter
// Service:    RateLimiterService   (holds the strategy, exposes handleRequest)
//
// Key insight: No rich domain objects here. The complexity lives in the
// algorithm logic (the math inside isAllowed), not in class relationships.
// =============================================================================


// ── RateLimiter (Strategy Interface) ─────────────────────────────────────────
// One method contract. Every algorithm implements this.
// To add Sliding Window: implement this interface. Nothing else changes.

interface RateLimiter {
  isAllowed(clientId: string): boolean;
}


// ── TokenBucketRateLimiter ────────────────────────────────────────────────────
// Each client has a bucket of tokens.
// Tokens refill at a fixed rate over time. Each request consumes 1 token.
// Allows bursting up to capacity — good for user experience.
//
// LAZY REFILL: No background timer. On every request, we calculate how many
// tokens should have been added since the last request and add them.

class TokenBucketRateLimiter implements RateLimiter {
  private capacity: number;
  private refillRatePerSecond: number;
  private buckets: Map<string, BucketState>;

  constructor(capacity: number, refillRatePerSecond: number) {
    this.capacity = capacity;
    this.refillRatePerSecond = refillRatePerSecond;
    this.buckets = new Map();
  }

  isAllowed(clientId: string): boolean {
    const now = Date.now();

    // First request from this client — give them a full bucket
    if (!this.buckets.has(clientId)) {
      this.buckets.set(clientId, { tokens: this.capacity, lastRefillTime: now });
    }

    const bucket = this.buckets.get(clientId)!;

    // Lazy refill: how much time has passed? How many tokens have accumulated?
    const elapsedSeconds = (now - bucket.lastRefillTime) / 1000;
    const tokensToAdd = elapsedSeconds * this.refillRatePerSecond;

    // Add tokens but never exceed capacity
    bucket.tokens = Math.min(this.capacity, bucket.tokens + tokensToAdd);
    bucket.lastRefillTime = now;

    if (bucket.tokens >= 1) {
      bucket.tokens -= 1;
      return true;
    }
    return false;
  }

  // Utility: see current token count for a client (useful for debugging/demo)
  getTokens(clientId: string): number {
    return Math.floor(this.buckets.get(clientId)?.tokens ?? this.capacity);
  }
}


// ── FixedWindowRateLimiter ────────────────────────────────────────────────────
// Time is divided into fixed-size windows (e.g., 60 seconds).
// Each client gets a counter per window. Counter resets when window expires.
//
// WEAKNESS: At window boundary, a client can send maxRequests just before
// and maxRequests just after reset — effectively 2x in a short span.

class FixedWindowRateLimiter implements RateLimiter {
  private maxRequests: number;
  private windowSizeMs: number;
  private windows: Map<string, WindowState>;

  constructor(maxRequests: number, windowSizeSeconds: number) {
    this.maxRequests = maxRequests;
    this.windowSizeMs = windowSizeSeconds * 1000;
    this.windows = new Map();
  }

  isAllowed(clientId: string): boolean {
    const now = Date.now();

    // First request from this client — create a fresh window
    if (!this.windows.has(clientId)) {
      this.windows.set(clientId, { count: 0, windowStart: now });
    }

    const window = this.windows.get(clientId)!;

    // If the window has expired, reset it
    if (now - window.windowStart >= this.windowSizeMs) {
      window.count = 0;
      window.windowStart = now;
    }

    if (window.count < this.maxRequests) {
      window.count++;
      return true;
    }
    return false;
  }

  getCount(clientId: string): number {
    return this.windows.get(clientId)?.count ?? 0;
  }
}


// ── RateLimiterService ────────────────────────────────────────────────────────
// Thin service layer. Holds the strategy and provides a clean request API.
// Algorithm is injected and can be swapped at runtime (Strategy pattern).

class RateLimiterService {
  private rateLimiter: RateLimiter;

  constructor(rateLimiter: RateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  setRateLimiter(rateLimiter: RateLimiter): void {
    this.rateLimiter = rateLimiter;
  }

  handleRequest(clientId: string, endpoint: string): boolean {
    const allowed = this.rateLimiter.isAllowed(clientId);
    if (allowed) {
      console.log(`  ✓ ALLOWED  | ${clientId} → ${endpoint}`);
    } else {
      console.log(`  ✗ BLOCKED  | ${clientId} → ${endpoint}  [429 Too Many Requests]`);
    }
    return allowed;
  }
}


// =============================================================================
// STEP 4 — DEMO
// =============================================================================

console.log("=== Rate Limiter Demo ===\n");

// ── Demo 1: Token Bucket ──────────────────────────────────────────────────────
// capacity=3, refillRate=1/sec → client starts with 3 tokens
// 3 rapid requests → all allowed (burst)
// 4th rapid request → blocked (no tokens left yet)

console.log("── Token Bucket (capacity=3, refill=1 token/sec) ──");
const tokenBucket = new TokenBucketRateLimiter(3, 1);
const service = new RateLimiterService(tokenBucket);

service.handleRequest("client-A", "GET /movies");        // token 3→2 ✓
service.handleRequest("client-A", "GET /movies");        // token 2→1 ✓
service.handleRequest("client-A", "GET /movies");        // token 1→0 ✓
service.handleRequest("client-A", "GET /movies");        // 0 tokens  ✗

// Different client — has its own independent bucket
service.handleRequest("client-B", "POST /booking");      // fresh bucket ✓
service.handleRequest("client-B", "POST /booking");      // ✓
service.handleRequest("client-B", "POST /booking");      // ✓
service.handleRequest("client-B", "POST /booking");      // ✗

console.log();

// ── Demo 2: Fixed Window ──────────────────────────────────────────────────────
// maxRequests=3 per 60 second window
// 3 requests → all allowed; 4th → blocked until window resets

console.log("── Fixed Window (maxRequests=3 per 60s window) ──");
const fixedWindow = new FixedWindowRateLimiter(3, 60);
service.setRateLimiter(fixedWindow);  // swap algorithm via Strategy

service.handleRequest("client-A", "GET /shows");   // count 0→1 ✓
service.handleRequest("client-A", "GET /shows");   // count 1→2 ✓
service.handleRequest("client-A", "GET /shows");   // count 2→3 ✓
service.handleRequest("client-A", "GET /shows");   // count=3, blocked ✗
service.handleRequest("client-A", "GET /shows");   // still blocked ✗

// client-B has its own window — unaffected
service.handleRequest("client-B", "GET /shows");   // ✓

console.log();

// ── Demo 3: Multiple clients, independent state ───────────────────────────────
console.log("── Multiple clients are independent ──");
const tb2 = new TokenBucketRateLimiter(2, 1);
const s2 = new RateLimiterService(tb2);

s2.handleRequest("user-1", "DELETE /booking");  // ✓
s2.handleRequest("user-2", "DELETE /booking");  // ✓ (own bucket)
s2.handleRequest("user-1", "DELETE /booking");  // ✓
s2.handleRequest("user-1", "DELETE /booking");  // ✗ (user-1 exhausted)
s2.handleRequest("user-2", "DELETE /booking");  // ✓ (user-2 still has tokens)

console.log("\n── Token count after burst (both clients depleted their 2 tokens) ──");
console.log(`  user-1 tokens remaining: ${tb2.getTokens("user-1")}`);
console.log(`  user-2 tokens remaining: ${tb2.getTokens("user-2")}`);
