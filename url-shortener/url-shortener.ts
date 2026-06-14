// =============================================================================
// LLD: URL SHORTENER
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================


// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. User submits a long URL → system returns a unique short URL
//   2. Visiting the short URL resolves back to the original long URL
//   3. Same long URL submitted again → return existing short URL (deduplication)
//   4. URLs can optionally have an expiry date
//   5. System tracks click count per short URL (basic analytics)
//   6. User can delete a short URL
//
// Non-Functional:
//   - Short codes must be globally unique
//   - 6 characters in Base62 = 62^6 ≈ 56 billion combinations
//   - Encoding algorithm should be swappable (Strategy pattern)
//
// Out of scope: Custom aliases, user authentication, geo-analytics, QR codes
// =============================================================================


// =============================================================================
// STEP 2 — CLASS DESIGN
// =============================================================================
// Entities:   ShortenedUrl
// Interface:  CodeGenerator        (Strategy pattern)
// Generators: Base62CodeGenerator, RandomCodeGenerator
// Service:    UrlService           (CRUD orchestrator)
//
// Key structural decision — TWO Maps inside UrlService:
//   urlStore:     Map<shortCode, ShortenedUrl>  → forward lookup  (resolve)
//   reverseStore: Map<longUrl, shortCode>        → reverse lookup  (deduplication)
// =============================================================================


// ── ShortenedUrl ──────────────────────────────────────────────────────────────
// Core entity. Owns its own expiry check and click tracking.

class ShortenedUrl {
  public shortCode: string;
  public longUrl: string;
  public userId: string;
  public createdAt: Date;
  public expiresAt: Date | null;
  public clickCount: number;

  constructor(
    shortCode: string,
    longUrl: string,
    userId: string,
    expiresAt: Date | null
  ) {
    this.shortCode = shortCode;
    this.longUrl = longUrl;
    this.userId = userId;
    this.createdAt = new Date();
    this.expiresAt = expiresAt;
    this.clickCount = 0;
  }

  isExpired(): boolean {
    if (!this.expiresAt) return false;
    return new Date() > this.expiresAt;
  }

  recordClick(): void {
    this.clickCount++;
  }
}


// ── CodeGenerator (Strategy Interface) ───────────────────────────────────────
// One method — generate() returns the next unique short code.
// No params needed — each implementation manages its own state.

interface CodeGenerator {
  generate(): string;
}


// ── Base62CodeGenerator ───────────────────────────────────────────────────────
// Uses an internal counter + Base62 encoding.
// Counter always increments → every code is mathematically unique.
// No collision possible. No retry loop needed.
//
// Base62 alphabet: a-z (26) + A-Z (26) + 0-9 (10) = 62 characters
// 6-character code = 62^6 ≈ 56 billion unique combinations

class Base62CodeGenerator implements CodeGenerator {
  private readonly CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private readonly CODE_LENGTH = 6;
  private counter: number;

  constructor() {
    this.counter = 0;
  }

  generate(): string {
    this.counter++;
    return this.encode(this.counter);
  }

  private encode(num: number): string {
    let result = "";
    while (num > 0) {
      result = this.CHARS[num % 62] + result;
      num = Math.floor(num / 62);
    }
    // Pad to CODE_LENGTH with the first character ('a')
    return result.padStart(this.CODE_LENGTH, this.CHARS[0]);
  }
}


// ── RandomCodeGenerator ───────────────────────────────────────────────────────
// Generates a random 6-character alphanumeric string.
// Collision is possible (rare but non-zero) — UrlService handles retry.

class RandomCodeGenerator implements CodeGenerator {
  private readonly CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private readonly CODE_LENGTH = 6;

  generate(): string {
    let result = "";
    for (let i = 0; i < this.CODE_LENGTH; i++) {
      result += this.CHARS[Math.floor(Math.random() * 62)];
    }
    return result;
  }
}


// ── UrlService ────────────────────────────────────────────────────────────────
// Orchestrates all URL operations.
// Two Maps for O(1) both forward and reverse lookup.

class UrlService {
  private urlStore: Map<string, ShortenedUrl>;     // shortCode  → ShortenedUrl
  private reverseStore: Map<string, string>;        // longUrl    → shortCode
  private codeGenerator: CodeGenerator;
  private readonly BASE_URL = "https://short.ly/";
  private readonly MAX_RETRIES = 10;

  constructor(codeGenerator: CodeGenerator) {
    this.urlStore = new Map();
    this.reverseStore = new Map();
    this.codeGenerator = codeGenerator;
  }

  setCodeGenerator(generator: CodeGenerator): void {
    this.codeGenerator = generator;
  }

  shorten(longUrl: string, userId: string, expiryDays: number | null = null): string {
    // Deduplication: same long URL → return existing short URL if still valid
    if (this.reverseStore.has(longUrl)) {
      const existingCode = this.reverseStore.get(longUrl)!;
      const existing = this.urlStore.get(existingCode)!;
      if (!existing.isExpired()) {
        console.log(`[EXISTING]  ${longUrl}`);
        console.log(`            → ${this.BASE_URL}${existingCode} (already exists)`);
        return this.BASE_URL + existingCode;
      }
    }

    // Generate a unique short code (retry handles RandomCodeGenerator collisions)
    let shortCode: string;
    let attempts = 0;
    do {
      shortCode = this.codeGenerator.generate();
      attempts++;
      if (attempts > this.MAX_RETRIES) {
        throw new Error("Failed to generate a unique code after max retries");
      }
    } while (this.urlStore.has(shortCode));

    const expiresAt = expiryDays
      ? new Date(Date.now() + expiryDays * 24 * 60 * 60 * 1000)
      : null;

    const shortenedUrl = new ShortenedUrl(shortCode, longUrl, userId, expiresAt);
    this.urlStore.set(shortCode, shortenedUrl);
    this.reverseStore.set(longUrl, shortCode);

    const shortUrl = this.BASE_URL + shortCode;
    console.log(`[SHORTENED] ${longUrl}`);
    console.log(`            → ${shortUrl}${expiresAt ? ` (expires: ${expiresAt.toDateString()})` : ""}`);
    return shortUrl;
  }

  resolve(shortCode: string): string {
    const url = this.urlStore.get(shortCode);
    if (!url) throw new Error(`Short code '${shortCode}' not found`);
    if (url.isExpired()) throw new Error(`Short URL '${shortCode}' has expired`);

    url.recordClick();
    console.log(`[RESOLVED]  ${shortCode} → ${url.longUrl}  (click #${url.clickCount})`);
    return url.longUrl;
  }

  delete(shortCode: string): void {
    const url = this.urlStore.get(shortCode);
    if (!url) throw new Error(`Short code '${shortCode}' not found`);

    this.urlStore.delete(shortCode);
    this.reverseStore.delete(url.longUrl);
    console.log(`[DELETED]   ${shortCode}`);
  }

  getStats(shortCode: string): void {
    const url = this.urlStore.get(shortCode);
    if (!url) throw new Error(`Short code '${shortCode}' not found`);

    console.log(`[STATS]     ${shortCode}`);
    console.log(`  Long URL : ${url.longUrl}`);
    console.log(`  Created  : ${url.createdAt.toISOString()}`);
    console.log(`  Expires  : ${url.expiresAt ? url.expiresAt.toISOString() : "Never"}`);
    console.log(`  Clicks   : ${url.clickCount}`);
    console.log(`  Status   : ${url.isExpired() ? "EXPIRED" : "ACTIVE"}`);
  }
}


// =============================================================================
// STEP 4 — DEMO
// =============================================================================

console.log("=== URL Shortener Demo ===\n");

const service = new UrlService(new Base62CodeGenerator());

// Shorten different URLs
console.log("── Shortening URLs ──");
const s1 = service.shorten("https://www.google.com/search?q=lld+interview", "user-1");
const s2 = service.shorten("https://github.com/microsoft/typescript/blob/main/README.md", "user-1");
const s3 = service.shorten("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "user-2", 7); // expires in 7 days

console.log();

// Deduplication — same URL submitted again
console.log("── Deduplication ──");
service.shorten("https://www.google.com/search?q=lld+interview", "user-3"); // returns existing

console.log();

// Resolve (click tracking)
console.log("── Resolving URLs ──");
const code1 = s1.replace("https://short.ly/", "");
const code2 = s2.replace("https://short.ly/", "");
service.resolve(code1);
service.resolve(code1); // click #2
service.resolve(code2);

console.log();

// Stats
console.log("── Stats ──");




service.getStats(code1);

console.log();

// Delete
console.log("── Delete ──");
service.delete(code2);

// Try to resolve deleted URL
try {
  service.resolve(code2);
} catch (e: any) {
  console.log(`[ERROR]     ${e.message}`);
}

// After delete, same long URL can be re-shortened (new code)
console.log();
console.log("── Re-shortening after delete ──");
service.shorten("https://github.com/microsoft/typescript/blob/main/README.md", "user-1");

console.log();

// Strategy swap: switch to RandomCodeGenerator
console.log("── Strategy swap: RandomCodeGenerator ──");
service.setCodeGenerator(new RandomCodeGenerator());
service.shorten("https://www.npmjs.com/package/typescript", "user-2");
service.shorten("https://www.typescriptlang.org/docs/", "user-2");
