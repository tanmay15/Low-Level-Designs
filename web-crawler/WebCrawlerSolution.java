// =============================================================================
// LLD: WEB CRAWLER
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Start from a seed URL and crawl all reachable pages (BFS)
//   2. Extract all links from each page
//   3. Skip already-visited URLs (deduplication via Set)
//   4. Respect a max depth and max pages limit
//   5. Normalize URLs before storing (lowercase, strip trailing slash)
//   6. Track crawl status per URL: PENDING → CRAWLING → DONE / FAILED
//
// Non-Functional:
//   - BFS order ensures shallow pages are crawled before deep ones
//   - Set<normalizedUrl> gives O(1) deduplication
//   - HtmlParser is swappable (Strategy pattern)
//
// Out of scope: robots.txt enforcement, politeness delays, distributed crawling,
//   actual HTTP fetching, JavaScript rendering, auth pages
//
// KEY INSIGHT:
//   visited Set stores NORMALIZED url (lowercase + no trailing slash).
//   Raw url is kept for display. This prevents crawling the same page
//   via differently-cased or trailing-slash variants.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum CrawlStatus { PENDING, CRAWLING, DONE, FAILED }


// =============================================================================
// ENTITIES
// =============================================================================

// ── CrawlUrl ──────────────────────────────────────────────────────────────────
// One entry in the frontier. Tracks depth and parent for link graph reconstruction.
class CrawlUrl {
    public String      url;
    public String      normalizedUrl; // used as key in visited set
    public int         depth;
    public String      parentUrl;     // null for seed
    public CrawlStatus status;

    public CrawlUrl(String url, int depth, String parentUrl) {
        this.url           = url;
        this.normalizedUrl = normalize(url);
        this.depth         = depth;
        this.parentUrl     = parentUrl;
        this.status        = CrawlStatus.PENDING;
    }

    // Normalize: lowercase + strip trailing slash — prevents duplicate visits
    public static String normalize(String url) {
        url = url.toLowerCase().trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }
}

// ── CrawlResult ───────────────────────────────────────────────────────────────
// Snapshot of what was found on a page after crawling.
class CrawlResult {
    public String       url;
    public List<String> extractedLinks;
    public int          wordCount;
    public boolean      success;

    public CrawlResult(String url, List<String> links, int wordCount) {
        this.url            = url;
        this.extractedLinks = links;
        this.wordCount      = wordCount;
        this.success        = true;
    }

    @Override
    public String toString() {
        return String.format("%-45s | words=%-4d | links=%d",
                url, wordCount, extractedLinks.size());
    }
}


// =============================================================================
// HTML PARSER (Strategy — swappable)
// =============================================================================
// In production: parse actual HTML DOM for <a href="..."> tags.
// In LLD demo: simulated with a hard-coded map.

interface HtmlParser {
    List<String> extractLinks(String url);
    int          getWordCount(String url);
}

class SimulatedHtmlParser implements HtmlParser {
    private static final Map<String, List<String>> MOCK_LINKS = new HashMap<>();
    static {
        MOCK_LINKS.put("https://example.com", Arrays.asList(
                "https://example.com/about",
                "https://example.com/products",
                "https://other.com/page1"
        ));
        MOCK_LINKS.put("https://example.com/about", Arrays.asList(
                "https://example.com/team",
                "https://example.com"   // already visited — will be skipped
        ));
        MOCK_LINKS.put("https://example.com/products", Arrays.asList(
                "https://example.com/products/shoes",
                "https://example.com/products/bags"
        ));
        MOCK_LINKS.put("https://example.com/team", new ArrayList<>());
        MOCK_LINKS.put("https://other.com/page1", Arrays.asList(
                "https://other.com/page2"
        ));
    }

    @Override
    public List<String> extractLinks(String url) {
        return MOCK_LINKS.getOrDefault(url, new ArrayList<>());
    }

    @Override
    public int getWordCount(String url) {
        // Simulated word count — deterministic for demo
        return 100 + (url.length() * 7) % 400;
    }
}


// =============================================================================
// WEB CRAWLER
// =============================================================================
// BFS traversal using a Queue<CrawlUrl> as the frontier.
// visited: Set<normalizedUrl> — O(1) deduplication.
// Stops when frontier is empty, maxPages reached, or maxDepth exceeded.

class WebCrawler {
    private Queue<CrawlUrl>   frontier;  // BFS queue — next pages to visit
    private Set<String>       visited;   // normalized URLs — prevents re-crawl
    private HtmlParser        parser;
    private List<CrawlResult> results;
    private int               maxDepth;
    private int               maxPages;

    public WebCrawler(int maxDepth, int maxPages) {
        this.frontier  = new LinkedList<>();
        this.visited   = new HashSet<>();
        this.parser    = new SimulatedHtmlParser();
        this.results   = new ArrayList<>();
        this.maxDepth  = maxDepth;
        this.maxPages  = maxPages;
    }

    public void setParser(HtmlParser parser) { this.parser = parser; }

    // ── Entry point ───────────────────────────────────────────────────────────
    public void crawl(String seedUrl) {
        CrawlUrl seed = new CrawlUrl(seedUrl, 0, null);
        frontier.add(seed);
        visited.add(seed.normalizedUrl);   // mark seed as seen before crawling

        System.out.println("[CRAWLER] Seed: " + seedUrl);
        System.out.println("[CRAWLER] maxDepth=" + maxDepth + ", maxPages=" + maxPages + "\n");

        while (!frontier.isEmpty() && results.size() < maxPages) {
            crawlPage(frontier.poll());
        }

        System.out.println("\n[CRAWLER] Finished. Pages crawled: " + results.size()
                + " | Unique URLs seen: " + visited.size());
    }

    // ── Crawl one page ────────────────────────────────────────────────────────
    private void crawlPage(CrawlUrl crawlUrl) {
        crawlUrl.status = CrawlStatus.CRAWLING;
        System.out.println("  [depth=" + crawlUrl.depth + "] " + crawlUrl.url);

        try {
            List<String> links     = parser.extractLinks(crawlUrl.url);
            int          wordCount = parser.getWordCount(crawlUrl.url);

            results.add(new CrawlResult(crawlUrl.url, links, wordCount));
            crawlUrl.status = CrawlStatus.DONE;
            System.out.println("    → " + links.size() + " links, " + wordCount + " words");

            // Enqueue unvisited links within depth limit
            if (crawlUrl.depth < maxDepth) {
                for (String link : links) {
                    CrawlUrl next = new CrawlUrl(link, crawlUrl.depth + 1, crawlUrl.url);
                    if (visited.add(next.normalizedUrl)) { // add() returns false if already present
                        frontier.add(next);
                        System.out.println("    + queued: " + link);
                    } else {
                        System.out.println("    ~ skip (visited): " + link);
                    }
                }
            } else {
                System.out.println("    ~ maxDepth reached — not enqueuing links");
            }

        } catch (Exception e) {
            crawlUrl.status = CrawlStatus.FAILED;
            System.out.println("    ✗ FAILED: " + e.getMessage());
        }
    }

    public List<CrawlResult> getResults() { return results; }
    public Set<String>       getVisited() { return visited; }
}


// =============================================================================
// DEMO
// =============================================================================

public class WebCrawlerSolution {
    public static void main(String[] args) {
        System.out.println("=== Web Crawler Demo ===\n");

        WebCrawler crawler = new WebCrawler(2, 10);
        crawler.crawl("https://example.com");

        System.out.println("\n── Crawl Results ──");
        for (CrawlResult r : crawler.getResults()) {
            System.out.println("  " + r);
        }

        System.out.println("\n── All Unique URLs Seen (including skipped) ──");
        crawler.getVisited().forEach(url -> System.out.println("  " + url));
    }
}
