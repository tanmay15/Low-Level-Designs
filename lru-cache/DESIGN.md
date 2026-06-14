# LLD: LRU Cache

> **Code file:** `LRUCacheSolution.java` — keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
| # | Requirement |
|---|---|
| 1 | `get(key)` → return cached value in O(1), or null on miss |
| 2 | `put(key, value)` → insert or update in O(1); evict if at capacity |
| 3 | LRU eviction: Least Recently Used key is evicted first when full |
| 4 | On `get()` hit, the accessed key becomes Most Recently Used |
| 5 | On `put()` of an existing key, update value in-place (no eviction) |

### Non-Functional
- BOTH `get()` and `put()` must be **O(1)** — this is the core constraint
- Eviction algorithm is swappable (Strategy pattern)

### Out of Scope
TTL (time-to-live), thread safety, distributed cache, persistence

---

## Step 2 — The Core Insight (Why HashMap + DLL)

### Why each structure alone is insufficient

| Structure | What it gives you | What it can't do |
|---|---|---|
| HashMap only | O(1) get/put by key | Cannot tell which key is "least recently used" |
| LinkedList only | Maintains insertion/access order | Finding a specific key is O(n) |
| **HashMap + DLL** | **O(1) key lookup AND O(1) order tracking** | — |

### Why Doubly Linked (not singly)?

To move a node from the middle to the front when it's accessed, you need to:
1. Remove it from its current position
2. Insert it at the front

Step 1 requires knowing the **predecessor** of the node. With a singly linked list, finding the predecessor is O(n). With a doubly linked list, `node.prev` is O(1).

### Why does Node store the key?

When evicting the LRU node (`tail.prev`), you must also remove it from the HashMap. Without the key stored in the Node, you'd have no way to call `map.remove(????)`.

---

## Step 3 — Class Design

### Entities

| Entity | Type | Role |
|---|---|---|
| `Node<K, V>` | Class | DLL node: holds key, value, prev, next |
| `EvictionPolicy<K, V>` | Interface | Strategy: onAccess, onInsert, evict() |
| `LRUEvictionPolicy<K, V>` | Class | DLL with sentinel nodes; MRU at front, LRU at back |
| `FIFOEvictionPolicy<K, V>` | Class | Queue; first inserted = first evicted |
| `CacheStats` | Class | Tracks hits, misses, evictions; computes hit rate |
| `Cache<K, V>` | Class | `Map<K, Node>` + EvictionPolicy + capacity enforcement |

### The DLL Structure (LRU)

```
head(dummy) ↔ [MRU] ↔ [recently used] ↔ ... ↔ [LRU] ↔ tail(dummy)
```

**Sentinel nodes** (dummy head and tail) avoid null checks on boundary operations:
- `head.next` = first real node (MRU)
- `tail.prev` = last real node (LRU, eviction candidate)
- When list is empty: `head.next == tail` and `tail.prev == head`

### Relationships

```
Cache<K, V>
  ├── OWNS Map<K, Node<K,V>>     ← HashMap: O(1) node access by key
  └── USES EvictionPolicy<K,V>   ← Strategy: decides what to evict and when

LRUEvictionPolicy
  └── OWNS DLL (via head + tail sentinel nodes)

FIFOEvictionPolicy
  └── OWNS Queue<Node<K,V>>
```

### Attributes and Methods

**`Node<K, V>`**
- `K key`, `V value`, `Node prev`, `Node next`

**`EvictionPolicy<K, V>`**
- `onAccess(node)` — called on cache hit or key update
- `onInsert(node)` — called when brand new key is added
- `evict()` — removes and returns the candidate node

**`Cache<K, V>`**
- `get(key)` → HashMap.get → if null: miss; else: policy.onAccess → return value
- `put(key, value)` → if exists: update + onAccess; else: if full: evict(); insert + onInsert

---

## Step 4 — Design Patterns

### 1. Strategy — EvictionPolicy

Swapping eviction algorithm requires zero changes to `Cache`:
```java
Cache<String, Integer> lru  = new Cache<>(3, new LRUEvictionPolicy<>());
Cache<String, Integer> fifo = new Cache<>(3, new FIFOEvictionPolicy<>());
```

| Policy | `onAccess` | `onInsert` | `evict()` |
|---|---|---|---|
| LRU | Detach + insert at front | Insert at front | Remove tail.prev |
| FIFO | No-op | Add to queue back | Remove queue front |

---

## Step 5 — Operation Traces (LRU, capacity = 3)

### put(A), put(B), put(C)
```
head ↔ [C] ↔ [B] ↔ [A] ↔ tail
         MRU           LRU
```

### get(A) → A becomes MRU
```
head ↔ [A] ↔ [C] ↔ [B] ↔ tail
         MRU           LRU
```

### put(D) → B is LRU → B evicted
```
head ↔ [D] ↔ [A] ↔ [C] ↔ tail
         MRU           LRU
HashMap: remove B
```

### put(E) → C is LRU → C evicted
```
head ↔ [E] ↔ [D] ↔ [A] ↔ tail
```

---

## Step 6 — LRU vs FIFO: The Critical Difference

```
Operations: put(A), put(B), put(C), get(A), put(D)

LRU:  After get(A), order is [A, C, B]. put(D) evicts B (LRU).
FIFO: get(A) is a no-op for ordering. Order stays [A, B, C]. put(D) evicts A (oldest).

In LRU: recently accessed items are protected from eviction.
In FIFO: access pattern has NO effect on eviction order.
```

---

## Step 7 — What Makes This Different From Other Problems

| Aspect | LRU Cache | Other problems |
|---|---|---|
| **NEW: Design IS the data structure** | The problem IS implementing HashMap + DLL | Other problems design systems around entities |
| **NEW: O(1) complexity constraint** | Forces a specific implementation choice | Other problems don't have hard algorithmic constraints |
| Generics `<K, V>` | Parameterized types | Other problems use concrete types |
| Strategy pattern | EvictionPolicy | Same as FeeStrategy (Parking Lot), FineStrategy (Library) |
| No service class | Cache IS the service | Other problems have separate service + entities |

---

## Step 8 — Extensibility

| Feature | How to add |
|---|---|
| **LFU (Least Frequently Used)** | New `LFUEvictionPolicy` with frequency counter map |
| **TTL (Time To Live)** | Add `expiresAt: Date` to Node; check on `get()`; background thread cleans expired |
| **Thread safety** | Wrap `get()` and `put()` with `synchronized` or use `ReentrantReadWriteLock` |
| **Max value size** | Track total size in bytes; evict until enough space available |
| **Eviction callback** | Add `onEvict(K key, V value)` hook to EvictionPolicy |

---

## Key Interview Points

- **Why HashMap + DLL?** "HashMap gives O(1) key-to-node lookup. DLL gives O(1) move/remove given a node pointer. Together they achieve O(1) for both get and put. Either alone cannot satisfy both."
- **Why doubly linked?** "To remove a node from the middle in O(1), I need the previous node. A singly linked list would require O(n) traversal to find the predecessor."
- **Why store key in Node?** "When evicting the LRU node from the tail, I also need to remove it from the HashMap. The node must carry its key for `map.remove(node.key)` — otherwise there's no reverse lookup."
- **Sentinel nodes:** "Dummy head and tail eliminate boundary null checks. head.next is always MRU, tail.prev is always LRU. Insertion/removal code is the same regardless of list size."
- **Update existing key:** "Put on an existing key is an in-place update — no eviction happens, size stays the same. Only the recency order changes (key moves to MRU)."
