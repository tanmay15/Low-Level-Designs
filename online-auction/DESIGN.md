# LLD: Online Auction System

> Implementation: `OnlineAuctionSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Seller creates an auction for an `Item` with a starting price and duration |
| 2 | Bidders place bids — each bid must exceed the current highest bid |
| 3 | Auction state transitions OPEN → CLOSED when `endTime` passes (time-driven) |
| 4 | Winner = the bidder with the highest bid when the auction closes |
| 5 | Seller can close auction manually before `endTime` via `closeAuction()` |
| 6 | Seller cannot bid on their own auction |
| 7 | Cancellation allowed only if no bids have been placed yet |
| 8 | `checkAndCloseExpired(nowMs)` simulates a background sweep that auto-closes expired auctions |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Bids are immutable once placed (complete audit trail on the Auction) |
| 2 | State machine: SCHEDULED → OPEN → CLOSED / CANCELLED |

### Out of Scope
Payment processing, bid retraction, reserve price, buy-now price, real-time streaming, proxy (automatic) bidding

---

## Step 2 — The Unique Aspect: Time-Driven State Transition

In most LLD problems, state transitions are triggered by **user actions**:
- Vending Machine: user inserts coin → state changes
- ATM: user enters PIN → state changes
- Order: restaurant accepts → state changes

In Online Auction, OPEN → CLOSED is triggered by **time passing**:
```java
// Auction closes when now >= endTime
public boolean isExpired(long nowMs) {
    return nowMs >= endTime;
}
```

This means the demo must simulate time passing by calling `checkAndCloseExpired(nowMs)` with a future timestamp, or by calling `closeAuction(id, nowMs)` directly.

---

## Step 3 — Entities

| Class | Role |
|-------|------|
| `Item` | The thing being auctioned — id, name, description |
| `Bidder` | Person who can place bids — id, name |
| `Bid` | Immutable record of one bid — bidderId, auctionId, amount, timestamp |
| `Auction` | Core entity and state machine — holds all bids, current highest, status, timing |
| `AuctionService` | Orchestrator — creates auctions, processes bids, closes auctions, sweeps expired |

### Enums

| Enum | Values |
|------|--------|
| `AuctionStatus` | SCHEDULED, OPEN, CLOSED, CANCELLED |

---

## Step 4 — Auction State Machine

```
SCHEDULED ──[openAuction()]──► OPEN ──[closeAuction() or endTime passed]──► CLOSED
    │                           │
    │[cancelAuction()]          │[cancelAuction()] ← only if no bids
    ▼                           ▼
CANCELLED                   CANCELLED
```

**Transitions:**

| From | To | Trigger |
|------|----|---------|
| SCHEDULED | OPEN | `openAuction(id, nowMs)` — manual (or scheduler in production) |
| OPEN | CLOSED | `closeAuction(id, nowMs)` or `checkAndCloseExpired(nowMs)` when `now >= endTime` |
| SCHEDULED | CANCELLED | `cancelAuction(id)` with no bids |
| OPEN | CANCELLED | `cancelAuction(id)` only if `auction.bids.isEmpty()` |

---

## Step 5 — Bid Validation Rules (in `placeBid()`)

All four validations run before a bid is accepted:

```java
if (auction.status != AuctionStatus.OPEN)          → "Auction is not open for bidding"
if (auction.isExpired(nowMs))                        → auto-close then "Auction has expired"
if (auction.sellerId.equals(bidderId))               → "Seller cannot bid on their own auction"
if (amount <= auction.currentHighest)                → "Bid must exceed current highest"
```

If all pass: bid is added to `auction.bids`, `currentHighest` and `highestBidderId` updated.

---

## Step 6 — Class Attributes & Methods

### `Bid` (immutable once placed)

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | BID-N auto-generated |
| `bidderId` | String | who placed the bid |
| `auctionId` | String | which auction |
| `amount` | int | in paise — no floating point |
| `timestamp` | long | when placed |

### `Auction`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | AUC-N auto-generated |
| `sellerId` | String | who created the auction |
| `item` | Item | what's being auctioned |
| `startingPrice` | int | in paise — minimum bid |
| `currentHighest` | int | in paise — updated on each bid |
| `highestBidderId` | String | winner candidate |
| `winningBid` | Bid | set on `autoClose()` |
| `status` | AuctionStatus | current state |
| `startTime`, `endTime` | long | epoch ms |
| `bids` | List\<Bid\> | ordered by placement time — full audit trail |
| `isExpired(nowMs)` | boolean | `nowMs >= endTime` |

### `AuctionService`

| Method | Description |
|--------|-------------|
| `registerBidder(bidder)` | add bidder to registry |
| `createAuction(sellerId, item, startingPriceRs, startMs, durationMs)` | create SCHEDULED auction |
| `openAuction(auctionId, nowMs)` | SCHEDULED → OPEN |
| `placeBid(auctionId, bidderId, amountRs, nowMs)` | validate + record bid |
| `closeAuction(auctionId, nowMs)` | OPEN → CLOSED, determine winner |
| `checkAndCloseExpired(nowMs)` | sweep all OPEN auctions, close expired ones |
| `cancelAuction(auctionId)` | cancel if no bids placed |
| `printAuctionStatus(auctionId)` | display auction summary |

---

## Step 7 — How Winner Is Determined

```java
// In autoClose():
auction.status    = AuctionStatus.CLOSED;
auction.winningBid = auction.bids.get(auction.bids.size() - 1); // last bid = highest
```

Since `placeBid()` rejects any bid that doesn't exceed `currentHighest`, the last bid in the list is always the highest. No need to scan — O(1) winner retrieval.

If no bids were placed: `winningBid` remains null, item is unsold.

---

## Step 8 — Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Amount in paise (int) | Yes | Same as ATM — never use float for money |
| Bids stored as List on Auction | Yes | Bids belong to the auction (not to the bidder) — full audit trail in one place |
| `isExpired()` on Auction | Yes | Auction owns the timing — Information Expert principle |
| `winningBid` as last element of `bids` | Yes | `placeBid()` enforces ascending amounts, so last = highest — O(1) |
| `checkAndCloseExpired()` sweeps all auctions | Yes | Simulates production cron job that runs periodically |
| `autoClose()` is private | Yes | Only called from `closeAuction()` and `checkAndCloseExpired()` — not a public API |

---

## Step 9 — How This Differs From Other Problems

| Aspect | Online Auction | Other Problems |
|--------|---------------|----------------|
| State transition trigger | TIME (`endTime`) | User action |
| Bid validation chain | 4 sequential checks before accepting | Simple existence checks |
| Winner determination | Last bid in list (ascending enforced) | No "winner" concept |
| Time-based sweep operation | `checkAndCloseExpired()` | Only ATM/Scheduler have time-based operations |
| Cancel only if no activity | Yes | Parking Lot / Library allow cancel anytime |

---

## Step 10 — Extensibility

| Extension | How |
|-----------|-----|
| Reserve price | Add `reservePrice` on Auction — if `winningBid.amount < reservePrice` on close → item unsold |
| Buy-now price | Add `buyNowPrice` on Auction — `placeBid()` at `buyNowPrice` immediately closes auction |
| Proxy bidding | Add `maxBid` per bidder — system auto-bids on their behalf up to `maxBid` |
| Real-time updates | Observer — `AuctionService` notifies `AuctionListener` on each bid and on close |
| Payment on close | Call `PaymentService.charge(winner, winningBid.amount)` inside `autoClose()` |

---

## Quick Recall — 3 Main Takeaways

1. **Time drives OPEN → CLOSED**, not user action. `isExpired(nowMs)` on Auction. `checkAndCloseExpired(nowMs)` is the background sweep — simulate it in demo by passing a future timestamp.

2. **Bid validation order**: OPEN? → not expired? → not seller? → amount exceeds current highest? All four must pass. If auction is expired, auto-close it first before throwing the error.

3. **Winner = last bid in list**. `placeBid()` enforces ascending amounts, so last = highest. O(1) winner retrieval on close — no scan needed.
