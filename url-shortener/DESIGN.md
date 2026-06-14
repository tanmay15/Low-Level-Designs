# LLD Design: URL Shortener

> **Sync note:** Design companion to `url-shortener.ts`. Keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
1. User submits a long URL → system returns a unique short URL
2. Visiting the short URL resolves to the original long URL
3. Same long URL submitted again → return existing short URL (deduplication)
4. URLs can optionally have an expiry date
5. System tracks click count per short URL (basic analytics)
6. User can delete a short URL

### Non-Functional
- Short codes must be globally unique
- 6 characters in Base62 = 62^6 ≈ 56 billion unique combinations
- Encoding algorithm should be swappable (Strategy pattern)
- Both forward and reverse lookups must be O(1)

### Out of Scope
- Custom aliases (`short.ly/my-brand`)
- User authentication
- Geographic click analytics
- QR code generation

---

## Step 2 — Entities

| Noun | Becomes | Reason |
|---|---|---|
| ShortenedUrl | Class | Core entity — owns shortCode, longUrl, clickCount, expiry |
| CodeGenerator | **Interface** | Encoding algorithm varies → Strategy pattern |
| Base62CodeGenerator | Class | Counter + Base62 — primary, collision-free algorithm |
| RandomCodeGenerator | Class | Random string — alternative, collision handled by retry |
| UrlService | Service Class | CRUD orchestrator |

No `User` class — `userId` is stored as a plain string on `ShortenedUrl`. User has no behavior here.

---

## Step 3 — Class Design

---

### `ShortenedUrl`
- **Attributes:** `shortCode`, `longUrl`, `userId`, `createdAt: Date`, `expiresAt: Date | null`, `clickCount: number`
- **Methods:** `isExpired(): boolean`, `recordClick(): void`
- **Note:** Entity owns its expiry check and click tracking — these are not the service's job.

---

### `CodeGenerator` *(Interface — Strategy)*
- **Method:** `generate(): string`
- **Note:** No params. Each implementation manages its own internal state (counter or randomness).

---

### `Base62CodeGenerator`
- **Attributes:** `CHARS: string` (62-char alphabet), `CODE_LENGTH: number` (6), `counter: number` (private)
- **Methods:** `generate()`, `private encode(num: number): string`
- **Algorithm:**
  1. `counter++`
  2. While num > 0: prepend `CHARS[num % 62]`, `num = floor(num / 62)`
  3. Pad result to 6 chars
- **Why no collision:** Counter always increments → each value is mathematically unique.

---

### `RandomCodeGenerator`
- **Attributes:** `CHARS: string`, `CODE_LENGTH: number`
- **Methods:** `generate()`
- **Algorithm:** Pick 6 random characters from CHARS
- **Collision possible:** Handled by retry loop in `UrlService` (max 10 attempts)

---

### `UrlService`
- **Attributes:**
  - `urlStore: Map<shortCode, ShortenedUrl>` — private (forward lookup)
  - `reverseStore: Map<longUrl, shortCode>` — private (reverse lookup for deduplication)
  - `codeGenerator: CodeGenerator` — private
  - `BASE_URL: string` — constant
- **Methods:** `shorten(longUrl, userId, expiryDays?)`, `resolve(shortCode)`, `delete(shortCode)`, `getStats(shortCode)`, `setCodeGenerator(generator)`

---

## Step 4 — Relationships

| From | To | Type | Why |
|---|---|---|---|
| `UrlService` | `ShortenedUrl` (via Map) | **Composition** | URLs have no life outside the service |
| `UrlService` | `CodeGenerator` | **Dependency (Uses)** | Strategy injected, swappable via `setCodeGenerator()` |

Simplest relationship structure of all problems. One service, one entity, one strategy.

---

## Step 5 — Design Patterns

### Strategy → `CodeGenerator`
- **Why:** Base62 (counter-based) and Random are both valid approaches with different trade-offs
- **How:** `CodeGenerator` interface with `generate()`. Swap via `setCodeGenerator()`
- **Interview line:** *"Strategy on the generator means I can swap Base62 for random strings without touching UrlService."*

No Observer, No Singleton, No State Machine.

---

## Step 6 — Key Algorithm Decisions

### Decision 1: Base62 over MD5
MD5 hashes the URL and truncates to 6 chars → collision possible (two URLs → same prefix).
Base62 with counter → every code is unique by construction. No retry needed.

### Decision 2: Two Maps for O(1) both ways
```
urlStore:     shortCode → ShortenedUrl   (resolve)
reverseStore: longUrl   → shortCode      (deduplication)
```
Without `reverseStore`, deduplication requires O(n) scan of all entries.

### Decision 3: Retry loop for RandomCodeGenerator
Base62 generator never needs a retry. Random generator might collide.
`UrlService.shorten()` runs a `do...while (urlStore.has(shortCode))` loop — max 10 attempts before throwing.

### HLD Bridge — say this in interview
> "In production, both Maps become DB tables. `urlStore` is the primary table with shortCode as the primary key. `reverseStore` is handled by a unique index on longUrl. The counter for Base62 becomes an auto-increment DB sequence — atomic across servers, no distributed collision risk."

---

## Step 7 — Extensibility

| Change Request | What changes |
|---|---|
| Add custom aliases | `shorten()` accepts optional `customCode`. Validate it's not taken, use it directly. |
| Add geo click analytics | `recordClick(country: string)` stores `Map<country, count>` on `ShortenedUrl` |
| Add rate limiting on shorten | Inject `RateLimiterService`. Call `isAllowed(userId)` before shortening. |
| Add DB persistence | Extract `UrlRepository` interface. Replace both Maps with DB-backed implementation. |
| Re-shorten after delete | Already handled — `reverseStore` entry is deleted, so next shorten creates a fresh code. |

---

## Quick Recall

```
ShortenedUrl: shortCode, longUrl, userId, createdAt, expiresAt, clickCount
  isExpired() → checks expiresAt vs now
  recordClick() → increments clickCount

UrlService:
  urlStore:     Map<shortCode, ShortenedUrl>   ← resolve
  reverseStore: Map<longUrl,   shortCode>      ← deduplication

shorten(longUrl):
  → check reverseStore → if exists and not expired, return existing
  → generate shortCode (retry if collision)
  → create ShortenedUrl, store in both Maps

resolve(shortCode):
  → lookup urlStore → check expired → recordClick() → return longUrl

Base62: counter++ → encode(counter) → 62^6 = 56B unique codes, no collision
Random: random 6 chars → UrlService retries on collision (max 10)

Pattern: Strategy only (CodeGenerator)
```
