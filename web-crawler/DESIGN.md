# LLD: Web Crawler

> Implementation: `WebCrawlerSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Start from a seed URL and crawl all reachable pages (BFS) |
| 2 | Extract all outbound links from each crawled page |
| 3 | Skip already-visited URLs — deduplication using `Set<normalizedUrl>` |
| 4 | Respect `maxDepth` (how deep to follow links) and `maxPages` (total pages limit) |
| 5 | Normalize URLs before dedup check (lowercase + strip trailing slash) |
| 6 | Track crawl status per URL: PENDING → CRAWLING → DONE / FAILED |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | BFS order — shallow pages crawled before deep ones |
| 2 | O(1) deduplication using `HashSet` |
| 3 | `HtmlParser` is swappable (Strategy pattern) |

### Out of Scope
robots.txt enforcement, politeness delays, distributed crawling, actual HTTP fetching, JavaScript rendering, auth-gated pages

---

## Step 2 — This Problem's Unique Character

Web Crawler is the only pure **graph traversal** LLD problem. Everything else in this set is about state machines, booking systems, or service design. Here, the entire insight is:

```
Web pages = Nodes
Links between pages = Edges
Crawling = BFS traversal on this graph
visited Set = prevents cycles (some pages link back to each other)
```

The challenge: the graph is **live** (you discover edges only when you visit a node) and potentially **infinite** (any page can link to more pages). `maxDepth` and `maxPages` are how you bound the traversal.

---

## Step 3 — Why BFS and not DFS?

| Traversal | Behavior | Problem for crawling |
|-----------|----------|----------------------|
| DFS | Go deep before going wide | Can get stuck crawling one domain 100 levels deep |
| **BFS** | Cover current level before going deeper | Ensures important (shallow) pages are crawled first |

Production crawlers use **priority queues** where each URL has a score (PageRank, freshness) — effectively a weighted BFS.

---

## Step 4 — Entities

| Class | Role |
|-------|------|
| `CrawlUrl` | One URL in the frontier. Has `depth`, `parentUrl`, `status`, and `normalizedUrl`. |
| `CrawlResult` | Snapshot of what was found: extracted links, word count, success flag. |
| `HtmlParser` (interface) | Strategy — extracts links from a fetched page. |
| `SimulatedHtmlParser` | Mock implementation using a hardcoded map for demo. |
| `WebCrawler` | Main service — owns frontier queue, visited set, results list. |

### Enum

| Enum | Values |
|------|--------|
| `CrawlStatus` | PENDING, CRAWLING, DONE, FAILED |

---

## Step 5 — Core Data Structures

```
frontier: Queue<CrawlUrl>   ← BFS queue (LinkedList) — next pages to visit
visited:  Set<String>        ← normalized URLs already seen — O(1) dedup
results:  List<CrawlResult>  ← crawled pages with extracted content
```

### The BFS loop (from `crawl()` and `crawlPage()`)

```
frontier.add(seed)
visited.add(seed.normalizedUrl)

while frontier not empty AND results.size < maxPages:
    current = frontier.poll()
    current.status = CRAWLING
    links = parser.extractLinks(current.url)
    results.add(new CrawlResult(current.url, links, wordCount))
    current.status = DONE

    if current.depth < maxDepth:
        for each link in links:
            next = new CrawlUrl(link, current.depth + 1)
            if visited.add(next.normalizedUrl):   // add() returns false if already present
                frontier.add(next)
```

---

## Step 6 — URL Normalization (Key Detail)

Without normalization, these 3 URLs would be crawled as different pages:
```
https://Example.com/About
https://example.com/about
https://example.com/about/
```

After normalization:
```
https://example.com/about   ← all three become this
```

```java
public static String normalize(String url) {
    url = url.toLowerCase().trim();
    if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
    return url;
}
```

The `visited` Set uses `normalizedUrl` as key, not the raw URL.

---

## Step 7 — Strategy Pattern: HtmlParser

```java
interface HtmlParser {
    List<String> extractLinks(String url);
    int          getWordCount(String url);
}
```

To swap parsers:
```java
crawler.setParser(new JsoupHtmlParser());      // real HTTP + HTML parsing
crawler.setParser(new SimulatedHtmlParser());  // mock for testing
```

The `WebCrawler` doesn't know which parser it's using — it just calls `parser.extractLinks(url)`.

---

## Step 8 — Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| BFS with `Queue` | Yes | Shallow-first, fair across domains |
| `Set<normalizedUrl>` for visited | Yes | O(1) dedup — critical for performance |
| URL normalization before adding to Set | Yes | Prevents re-crawling same page via URL variants |
| `visited.add()` returns false if duplicate | Yes | Java `Set.add()` is atomic check + insert — no separate `contains()` needed |
| `maxDepth` AND `maxPages` limits | Both | maxDepth: prevent going too deep per domain; maxPages: overall budget |
| `CrawlResult` separate from `CrawlUrl` | Yes | `CrawlUrl` is the frontier entry; `CrawlResult` is what was found |

---

## Step 9 — Extensibility

| Extension | How |
|-----------|-----|
| Domain-restricted crawling | Check `url.contains(allowedDomain)` before enqueuing |
| Priority-based crawling | Replace `Queue` with `PriorityQueue<CrawlUrl>` scored by PageRank or freshness |
| robots.txt | Before crawling a domain, fetch `/robots.txt`, parse `Disallow:` rules, filter links |
| Politeness delay | `Thread.sleep(1000)` between fetches to same domain |
| Distributed crawling | Replace in-memory frontier with Redis queue; visited Set with Redis SET |
| Content indexing | On `CrawlResult`, send to `IndexService` for search indexing |

---

## Quick Recall — 3 Main Takeaways

1. **Web crawling = BFS on a live graph**. `frontier: Queue<CrawlUrl>` is the BFS queue. Every newly discovered link goes into it. Poll from front to get next page to crawl.

2. **`visited: Set<normalizedUrl>` is the entire dedup strategy**. Normalize before adding. `Set.add()` returns false if duplicate — use that, no separate `contains()` needed.

3. **`maxDepth` and `maxPages` are the two stop conditions** alongside an empty frontier. Without them the crawler runs forever on a real web graph.
