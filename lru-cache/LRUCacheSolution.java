// =============================================================================
// LLD: LRU CACHE — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. get(key)  → return cached value in O(1), or null on miss
//   2. put(key, value) → insert or update in O(1); evict if at capacity
//   3. LRU eviction: the Least Recently Used key is evicted first
//   4. On get() hit, the accessed key becomes the Most Recently Used
//   5. On put() of an existing key, value is updated in-place (not a new entry)
//
// Non-Functional:
//   - BOTH get() and put() must be O(1) — this forces HashMap + Doubly Linked List
//   - Eviction algorithm is swappable (Strategy pattern) → LRU, FIFO, etc.
//   - Cache tracks hit/miss statistics
//
// Out of scope: TTL (time-to-live), thread safety, distributed cache, persistence
// =============================================================================
//
// WHY HashMap + Doubly Linked List? (the core insight)
//   HashMap alone: O(1) lookup, but can't tell which key is "least recently used"
//   LinkedList alone: maintains order, but finding a key is O(n)
//   Together: HashMap maps key → Node pointer (O(1) any-node access)
//             DLL maintains recency order (O(1) move/remove given node pointer)
//
// WHY doubly linked (not singly)?
//   To remove a node from the middle in O(1), you need BOTH prev and next.
//   Singly linked: removing needs finding predecessor → O(n).
//
// WHY does Node store the key?
//   When evicting the LRU node (tail.prev), we must remove it from the HashMap too.
//   Without the key in the Node, we'd have no way to do HashMap.remove(key).
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — NODE (Doubly Linked List node)
// =============================================================================
// Stores BOTH key and value.
// Key is needed so that when we evict a node, we can clean it from the HashMap.
// prev/next pointers make it a DLL node.

class Node<K, V> {
    K key;
    V value;
    Node<K, V> prev;
    Node<K, V> next;

    Node(K key, V value) {
        this.key   = key;
        this.value = value;
    }
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Interface:  EvictionPolicy<K, V>      (Strategy pattern)
// Concrete:   LRUEvictionPolicy         uses DLL: head ↔ [MRU]...[LRU] ↔ tail
//             FIFOEvictionPolicy        uses Queue: first-in first-evicted
// Class:      CacheStats               hit, miss, eviction counters
// Class:      Cache<K, V>              Map<K, Node> + EvictionPolicy + stats
//
// Relationships:
//   Cache       HAS-A   Map<K, Node<K, V>>   (O(1) key → node lookup)
//   Cache       USES    EvictionPolicy        (swappable eviction algorithm)
//   LRUEviction HAS-A   DLL via sentinel head/tail nodes
//   FIFOEviction HAS-A  Queue<Node<K, V>>
// =============================================================================


// ── EvictionPolicy (Strategy Pattern) ────────────────────────────────────────
// Three lifecycle hooks called by Cache:
//   onAccess(node) → called on cache HIT (get existing key, or update existing key)
//   onInsert(node) → called when a BRAND NEW key is added
//   evict()        → called when cache is full; removes and returns the chosen node

interface EvictionPolicy<K, V> {
    void onAccess(Node<K, V> node);   // cache hit or key update
    void onInsert(Node<K, V> node);   // new key inserted
    Node<K, V> evict();               // remove and return the node to be evicted
}


// ── LRUEvictionPolicy ─────────────────────────────────────────────────────────
// Doubly Linked List with dummy sentinel nodes at both ends.
//
// Layout:  head(dummy) ↔ [MRU] ↔ [recently] ↔ [LRU] ↔ tail(dummy)
//
// Sentinels avoid null checks on boundary operations:
//   head.next = first real node (MRU)
//   tail.prev = last real node (LRU) → eviction candidate
//
// onAccess: detach node from current position → insertAtFront → now MRU
// onInsert: insertAtFront → brand new node is immediately MRU
// evict():  detach tail.prev (LRU node) and return it

class LRUEvictionPolicy<K, V> implements EvictionPolicy<K, V> {
    private Node<K, V> head = new Node<>(null, null); // dummy sentinel — MRU side
    private Node<K, V> tail = new Node<>(null, null); // dummy sentinel — LRU side

    public LRUEvictionPolicy() {
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public void onAccess(Node<K, V> node) {
        detach(node);
        insertAtFront(node); // move to MRU position
    }

    @Override
    public void onInsert(Node<K, V> node) {
        insertAtFront(node); // new node → immediately MRU
    }

    @Override
    public Node<K, V> evict() {
        if (tail.prev == head) return null; // list is empty
        Node<K, V> lru = tail.prev;        // least recently used
        detach(lru);
        return lru;
    }

    // Remove node from its current position in the list
    private void detach(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    // Insert node right after head (MRU position)
    private void insertAtFront(Node<K, V> node) {
        node.next       = head.next;
        node.prev       = head;
        head.next.prev  = node;
        head.next       = node;
    }

    // For display purposes — traverses DLL from MRU to LRU
    public List<K> getOrderMRUtoLRU() {
        List<K> order = new ArrayList<>();
        Node<K, V> curr = head.next;
        while (curr != tail) {
            order.add(curr.key);
            curr = curr.next;
        }
        return order;
    }
}


// ── FIFOEvictionPolicy ────────────────────────────────────────────────────────
// Evicts the key that was inserted FIRST, regardless of how recently it was used.
// onAccess is a no-op — access order doesn't change eviction order in FIFO.

class FIFOEvictionPolicy<K, V> implements EvictionPolicy<K, V> {
    private Queue<Node<K, V>> queue = new LinkedList<>();

    @Override
    public void onAccess(Node<K, V> node) {
        // No-op — FIFO doesn't care about access frequency, only insertion order
    }

    @Override
    public void onInsert(Node<K, V> node) {
        queue.offer(node); // add to back of queue
    }

    @Override
    public Node<K, V> evict() {
        return queue.poll(); // remove and return oldest (front of queue)
    }
}


// ── CacheStats ────────────────────────────────────────────────────────────────

class CacheStats {
    int hits      = 0;
    int misses    = 0;
    int evictions = 0;

    double hitRate() {
        int total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total * 100;
    }

    void print() {
        System.out.println("  Hits: " + hits + " | Misses: " + misses +
                " | Evictions: " + evictions +
                " | Hit Rate: " + String.format("%.1f", hitRate()) + "%");
    }

    void reset() { hits = 0; misses = 0; evictions = 0; }
}


// ── Cache<K, V> ───────────────────────────────────────────────────────────────
// The main class. Delegates recency tracking and eviction to EvictionPolicy.
//
// Internal Map: key → Node (gives O(1) access to any node in the DLL)
// This is why both get() and put() are O(1):
//   get: HashMap.get(key) → node pointer → DLL.detach+insertFront → O(1)
//   put: HashMap.get(key) to check exists → if full: evict() → O(1)

class Cache<K, V> {
    private int                 capacity;
    private Map<K, Node<K, V>> store;    // key → node (the combined structure)
    private EvictionPolicy<K, V> policy;
    private CacheStats          stats;

    public Cache(int capacity, EvictionPolicy<K, V> policy) {
        this.capacity = capacity;
        this.store    = new HashMap<>();
        this.policy   = policy;
        this.stats    = new CacheStats();
    }

    // O(1) — HashMap lookup + DLL move
    public V get(K key) {
        Node<K, V> node = store.get(key);
        if (node == null) {
            stats.misses++;
            return null;
        }
        policy.onAccess(node); // LRU: move to front; FIFO: no-op
        stats.hits++;
        return node.value;
    }

    // O(1) — HashMap lookup/insert + DLL insert/move + optional evict
    public void put(K key, V value) {
        Node<K, V> existing = store.get(key);

        if (existing != null) {
            // Key already in cache → update value in-place, refresh recency
            existing.value = value;
            policy.onAccess(existing);
            return;
        }

        // New key — evict if at capacity
        if (store.size() >= capacity) {
            Node<K, V> evicted = policy.evict();
            if (evicted != null) {
                store.remove(evicted.key); // MUST remove from HashMap using node's key
                stats.evictions++;
                System.out.println("  [EVICT] key=" + evicted.key + ", value=" + evicted.value);
            }
        }

        // Insert new node
        Node<K, V> newNode = new Node<>(key, value);
        store.put(key, newNode);
        policy.onInsert(newNode);
    }

    public int size()       { return store.size(); }
    public int capacity()   { return capacity; }
    public CacheStats stats() { return stats; }

    public void printCache(String label) {
        System.out.println("[" + label + "] size=" + store.size() + "/" + capacity +
                " | keys=" + store.keySet());
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: LRUCacheSolution.java
// =============================================================================

public class LRUCacheSolution {
    public static void main(String[] args) {
        System.out.println("=== LRU Cache Demo ===\n");

        // ── LRU Eviction (capacity = 3) ──────────────────────────────────────
        System.out.println("── LRU Eviction Policy ──");
        LRUEvictionPolicy<String, Integer> lruPolicy = new LRUEvictionPolicy<>();
        Cache<String, Integer> lruCache = new Cache<>(3, lruPolicy);

        lruCache.put("A", 1);  // DLL: [A]
        lruCache.put("B", 2);  // DLL: [B, A]
        lruCache.put("C", 3);  // DLL: [C, B, A]
        lruCache.printCache("after A,B,C inserted");
        System.out.println("  DLL order (MRU→LRU): " + lruPolicy.getOrderMRUtoLRU());

        // Access A → A becomes MRU
        lruCache.get("A");     // DLL: [A, C, B]
        System.out.println("  After get(A): " + lruPolicy.getOrderMRUtoLRU());

        // Insert D → B is LRU → B gets evicted
        lruCache.put("D", 4);  // DLL: [D, A, C]
        System.out.println("  After put(D): " + lruPolicy.getOrderMRUtoLRU());
        lruCache.printCache("after put(D)");

        // B was evicted — cache miss
        Integer val = lruCache.get("B");
        System.out.println("  get(B) = " + val + " (null = evicted, cache miss)");

        // Insert E → C is now LRU → C gets evicted
        lruCache.put("E", 5);  // DLL: [E, D, A]
        System.out.println("  After put(E): " + lruPolicy.getOrderMRUtoLRU());

        System.out.println("  get(A)=" + lruCache.get("A") +
                " | get(D)=" + lruCache.get("D") +
                " | get(E)=" + lruCache.get("E"));
        System.out.println();

        // Update existing key — doesn't count as new entry
        System.out.println("── Update existing key ──");
        lruCache.put("A", 999); // update in-place, A moves to MRU
        System.out.println("  get(A) after update = " + lruCache.get("A"));
        System.out.println("  DLL order: " + lruPolicy.getOrderMRUtoLRU());
        System.out.println("  Cache size still: " + lruCache.size() + " (no eviction on update)");
        System.out.println();

        // Stats
        System.out.println("── Stats (LRU) ──");
        lruCache.stats().print();
        System.out.println();

        // ── FIFO Eviction (same capacity, different policy) ───────────────────
        System.out.println("── FIFO Eviction Policy (same operations, different eviction) ──");
        Cache<String, Integer> fifoCache = new Cache<>(3, new FIFOEvictionPolicy<>());

        fifoCache.put("A", 1); // insertion order: A
        fifoCache.put("B", 2); // insertion order: A, B
        fifoCache.put("C", 3); // insertion order: A, B, C
        fifoCache.printCache("after A,B,C inserted");

        // Access A — in FIFO, this does NOT change eviction order
        fifoCache.get("A");
        System.out.println("  get(A) called — in FIFO, A's position doesn't change");

        // Insert D → A is evicted (first inserted, regardless of recent access)
        fifoCache.put("D", 4);
        fifoCache.printCache("after put(D)");
        System.out.println("  get(A) = " + fifoCache.get("A") + " (evicted by FIFO, even though recently accessed)");
        System.out.println();

        // Stats comparison
        System.out.println("── Stats (FIFO) ──");
        fifoCache.stats().print();
        System.out.println();

        // ── Edge cases ────────────────────────────────────────────────────────
        System.out.println("── Edge cases ──");
        Cache<String, String> edgeCache = new Cache<>(1, new LRUEvictionPolicy<>());

        // Capacity 1 — every new key evicts the previous one
        edgeCache.put("X", "alpha");
        edgeCache.put("Y", "beta");   // evicts X
        System.out.println("  Capacity-1 cache: get(X)=" + edgeCache.get("X") +
                " | get(Y)=" + edgeCache.get("Y"));
    }
}
