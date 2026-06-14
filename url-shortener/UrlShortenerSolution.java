// =============================================================================
// LLD: URL SHORTENER — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Shorten a long URL → return a unique short URL
//   2. Redirect a short URL → return the original long URL
//   3. Same long URL by the same user returns the same short code (deduplication)
//   4. Short URLs can have an optional expiry
//   5. Support custom aliases (user-provided short code)
//   6. Retrieve or delete a shortened URL by its short code
//
// Non-Functional:
//   - Code generation algorithm is swappable (Strategy)
//   - O(1) lookup in both directions using two Maps
//   - Base62 encoding is counter-based → collision-free by design
//
// Out of scope: Analytics/click tracking, user authentication, distributed counter
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — DATA MODEL
// =============================================================================

class ShortenedUrl {
    public String id;
    public String longUrl;
    public String shortCode;
    public String shortUrl;
    public String userId;
    public Date createdAt;
    public Date expiresAt; // null = no expiry

    public ShortenedUrl(String id, String longUrl, String shortCode,
                        String shortUrl, String userId, Date expiresAt) {
        this.id = id;
        this.longUrl = longUrl;
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.userId = userId;
        this.createdAt = new Date();
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt != null && new Date().after(expiresAt);
    }
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Interface:  CodeGenerator  (Strategy pattern)
// Classes:    Base62CodeGenerator, RandomCodeGenerator
// Service:    UrlService
//
// Relationships:
//   UrlService USES   CodeGenerator (swappable via setCodeGenerator)
//   UrlService OWNS   Map<shortCode, ShortenedUrl>   (resolution in O(1))
//   UrlService OWNS   Map<userId+longUrl, shortCode> (deduplication in O(1))
// =============================================================================


// ── CodeGenerator (Strategy Pattern) ─────────────────────────────────────────

interface CodeGenerator {
    String generate(String longUrl);
}

// Counter-based Base62 encoding — collision-free because every counter value
// is unique. No random collisions, no retry loops needed.
class Base62CodeGenerator implements CodeGenerator {
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private int counter;

    public Base62CodeGenerator() {
        this.counter = 1;
    }

    @Override
    public String generate(String longUrl) {
        return encode(counter++);
    }

    private String encode(int num) {
        StringBuilder result = new StringBuilder();
        while (num > 0) {
            result.insert(0, CHARS.charAt(num % 62));
            num = num / 62;
        }
        while (result.length() < CODE_LENGTH) {
            result.insert(0, CHARS.charAt(0));
        }
        return result.toString();
    }
}

// Random code generator — may collide (retry responsibility is on UrlService)
class RandomCodeGenerator implements CodeGenerator {
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private Random random;

    public RandomCodeGenerator() {
        this.random = new Random();
    }

    @Override
    public String generate(String longUrl) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            result.append(CHARS.charAt(random.nextInt(62)));
        }
        return result.toString();
    }
}


// ── UrlService ────────────────────────────────────────────────────────────────
// Two Maps for O(1) operations in both directions:
//   shortCodeMap: shortCode → ShortenedUrl  (for redirect/resolve)
//   dedupeMap:    userId:longUrl → shortCode (for deduplication on shorten)

class UrlService {
    private static final String BASE_URL = "https://short.ly/";
    private Map<String, ShortenedUrl> shortCodeMap; // shortCode → ShortenedUrl
    private Map<String, String> dedupeMap;           // userId:longUrl → shortCode
    private int urlCounter;
    private CodeGenerator codeGenerator;

    public UrlService() {
        this.shortCodeMap = new HashMap<>();
        this.dedupeMap = new HashMap<>();
        this.urlCounter = 0;
        this.codeGenerator = new Base62CodeGenerator();
    }

    public void setCodeGenerator(CodeGenerator generator) {
        this.codeGenerator = generator;
    }

    public ShortenedUrl shorten(String longUrl, String userId, Date expiresAt) {
        return shorten(longUrl, userId, expiresAt, null);
    }

    public ShortenedUrl shorten(String longUrl, String userId, Date expiresAt, String customAlias) {
        // Deduplication: same user + same long URL → return existing
        String dedupeKey = userId + ":" + longUrl;
        if (dedupeMap.containsKey(dedupeKey)) {
            String existingCode = dedupeMap.get(dedupeKey);
            ShortenedUrl existing = shortCodeMap.get(existingCode);
            if (existing != null && !existing.isExpired()) {
                System.out.println("[DEDUPE] Already shortened → " + existing.shortUrl);
                return existing;
            }
        }

        // Determine short code (custom alias or generated)
        String shortCode;
        if (customAlias != null && !customAlias.isEmpty()) {
            if (shortCodeMap.containsKey(customAlias)) {
                throw new RuntimeException("Alias \"" + customAlias + "\" is already taken");
            }
            shortCode = customAlias;
        } else {
            shortCode = codeGenerator.generate(longUrl);
            // For RandomCodeGenerator: retry on collision
            while (shortCodeMap.containsKey(shortCode)) {
                shortCode = codeGenerator.generate(longUrl);
            }
        }

        String shortUrl = BASE_URL + shortCode;
        ShortenedUrl url = new ShortenedUrl("URL-" + (++urlCounter), longUrl, shortCode, shortUrl, userId, expiresAt);
        shortCodeMap.put(shortCode, url);
        dedupeMap.put(dedupeKey, shortCode);

        System.out.println("[SHORTEN] " + longUrl + " → " + shortUrl);
        return url;
    }

    public String resolve(String shortCode) {
        ShortenedUrl url = shortCodeMap.get(shortCode);
        if (url == null) throw new RuntimeException("Short code \"" + shortCode + "\" not found");
        if (url.isExpired()) throw new RuntimeException("Short URL has expired");
        System.out.println("[RESOLVE] " + BASE_URL + shortCode + " → " + url.longUrl);
        return url.longUrl;
    }

    public void delete(String shortCode) {
        ShortenedUrl url = shortCodeMap.get(shortCode);
        if (url == null) throw new RuntimeException("Short code \"" + shortCode + "\" not found");
        shortCodeMap.remove(shortCode);
        dedupeMap.remove(url.userId + ":" + url.longUrl);
        System.out.println("[DELETE] Removed " + BASE_URL + shortCode);
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: UrlShortenerSolution.java
// =============================================================================

public class UrlShortenerSolution {
    public static void main(String[] args) {
        System.out.println("=== URL Shortener Demo ===\n");

        UrlService service = new UrlService();

        // Basic shortening
        ShortenedUrl u1 = service.shorten("https://www.example.com/very/long/path/to/a/page", "user-1", null);
        ShortenedUrl u2 = service.shorten("https://www.google.com/search?q=lld+interview", "user-2", null);

        System.out.println();

        // Deduplication — same user + same URL → same short code
        ShortenedUrl u1Again = service.shorten("https://www.example.com/very/long/path/to/a/page", "user-1", null);
        System.out.println("  Same code returned: " + u1.shortCode.equals(u1Again.shortCode));

        System.out.println();

        // Resolve
        service.resolve(u1.shortCode);
        service.resolve(u2.shortCode);

        System.out.println();

        // Custom alias
        ShortenedUrl custom = service.shorten("https://www.github.com/tanmay", "user-1", null, "github");
        System.out.println();

        // Conflict on same alias
        try {
            service.shorten("https://www.linkedin.com/in/tanmay", "user-2", null, "github");
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }

        System.out.println();

        // Delete and resolve after delete
        service.delete(u1.shortCode);
        try {
            service.resolve(u1.shortCode);
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }

        System.out.println();

        // Switch to RandomCodeGenerator
        System.out.println("── Switching to RandomCodeGenerator ──");
        service.setCodeGenerator(new RandomCodeGenerator());
        service.shorten("https://www.random-example.com/page", "user-3", null);
    }
}
