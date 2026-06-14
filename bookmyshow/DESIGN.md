# LLD Design: BookMyShow

> **Sync note:** Design companion to `bookmyshow.ts`. Keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
1. User can search and select a movie
2. User can view shows for a movie at a given theatre
3. User can see available seats for a show
4. User can select seats and create a booking
5. Booking is confirmed after payment; seats become permanently booked
6. Cancelled booking releases all seats back to available

### Non-Functional
- Two users cannot book the same seat for the same show → handled via **seat locking** (LOCKED state)
- Seat pricing varies by category (SILVER / GOLD / PLATINUM) → Strategy pattern
- Adding a new seat category or pricing model requires no change to existing classes

### Out of Scope
- Payment gateway integration
- Movie search and filtering
- User authentication and session management

---

## Step 2 — Entities

| Noun | Becomes | Reason |
|---|---|---|
| Movie | Class | Has its own data (title, duration); real-world object |
| Theatre | Class | Groups screens; has location data |
| Screen | Class | Groups physical seats; belongs to a theatre |
| Seat | Class | Physical seat with fixed position and category |
| Show | Class | A specific screening — ties Movie + Screen + time together |
| ShowSeat | Class | Per-show availability of a physical seat (join entity) |
| User | Class | Actor in the system; makes bookings |
| Booking | Class | Lifecycle entity with state machine (PENDING → CONFIRMED / CANCELLED) |
| Seat category | Enum | Fixed set: SILVER, GOLD, PLATINUM |
| Show seat status | Enum | Fixed states: AVAILABLE → LOCKED → BOOKED |
| Booking status | Enum | Fixed states: PENDING → CONFIRMED / CANCELLED |

---

## Step 3 — Class Design

---

### `Movie`
- **Attributes:** `id: string`, `title: string`, `durationMin: number`
- **Methods:** None
- **Access:** All public
- **Note:** Pure data holder.

---

### `Seat`
- **Attributes:** `id: string`, `row: string`, `col: number`, `category: SeatCategory`, `screenId: string`
- **Methods:** None
- **Access:** All public
- **Note:** Represents a physical seat fixed to a screen. `screenId` is stored as a primitive (data, not a relationship reference).

---

### `Screen`
- **Attributes:** `id: string`, `name: string`, `seats: Seat[]` (private)
- **Methods:** `addSeat(seat)`, `getSeats(): Seat[]`
- **Note:** Groups physical seats. Does not know about shows or bookings.

---

### `Theatre`
- **Attributes:** `id: string`, `name: string`, `location: string`, `screens: Screen[]` (private)
- **Methods:** `addScreen(screen)`, `getScreens(): Screen[]`
- **Note:** Top-level venue entity. Pure aggregation of screens.

---

### `ShowSeat` *(key join entity)*
- **Attributes:**
  - `id: string`, `seat: Seat`, `showId: string`, `price: number` — public
  - `status: ShowSeatStatus` — **private** *(only changed via `lock()`, `book()`, `release()`)*
- **Methods:** `isAvailable()`, `lock()`, `book()`, `release()`, `getStatus()`
- **Key decisions:**
  - `lock()` throws if not AVAILABLE → prevents two users claiming the same seat
  - `book()` only works if already LOCKED → enforces the lock-then-book flow
  - `release()` always resets to AVAILABLE → used on cancellation
  - Status is private — the seat owns its own state transitions

---

### `Show`
- **Attributes:**
  - `id: string`, `movie: Movie`, `screen: Screen`, `startTime: Date` — public
  - `showSeats: Map<seatId, ShowSeat>` — private
- **Methods:** `addShowSeat(showSeat)`, `getShowSeat(seatId)`, `getAvailableSeats()`
- **Note:** Holds the ShowSeat map for the show. `BookingService` populates this via `initializeShowSeats()`.

---

### `User`
- **Attributes:** `id: string`, `name: string`, `email: string`
- **Methods:** None
- **Access:** All public
- **Note:** Pure data holder.

---

### `Booking` *(state machine)*
- **Attributes:**
  - `id: string`, `user: User`, `show: Show`, `showSeats: ShowSeat[]` — public
  - `totalAmount: number` — computed in constructor from showSeats
  - `status: BookingStatus` — public (readable), transitions via methods
- **Methods:** `confirm()`, `cancel()`
- **State machine:**
  ```
  PENDING → confirm() → CONFIRMED
  PENDING → cancel()  → CANCELLED
  CONFIRMED → cancel() → CANCELLED
  ```
- **Key decision:** `confirm()` calls `ss.book()` on all seats; `cancel()` calls `ss.release()`. Booking coordinates seat state changes.

---

### `PricingStrategy` *(Interface)*
- **Method:** `getPrice(seat: Seat): number`
- **Implementation:** `CategoryBasedPricing` — fixed prices per `SeatCategory`
- **Note:** Strategy pattern. Swap via `bookingService.setPricingStrategy()` for weekend pricing, surge pricing, etc.

---

### `BookingService` *(Service class)*
- **Attributes:**
  - `bookings: Map<string, Booking>` — private
  - `bookingCounter: number` — private
  - `pricingStrategy: PricingStrategy` — private
- **Methods:** `initializeShowSeats(show)`, `createBooking(user, show, seatIds)`, `confirmBooking(bookingId)`, `cancelBooking(bookingId)`, `printAvailableSeats(show)`
- **Note:** Orchestrator. Entities (Movie, Show, Seat) are just participants — none of them own the booking logic. BookingService is the reason a separate service class exists here (unlike Parking Lot where `ParkingLot` itself was the service).

---

## Step 4 — Relationships

| From | To | Type | Explanation |
|---|---|---|---|
| `Theatre` | `Screen[]` | **Composition** | Screens exist only as part of a theatre |
| `Screen` | `Seat[]` | **Composition** | Seats are physically fixed to a screen |
| `Show` | `Movie` | **Aggregation** | Movie exists independently of any show |
| `Show` | `Screen` | **Aggregation** | Screen exists independently; a show uses it |
| `Show` | `ShowSeat[]` | **Composition** | ShowSeats are created per-show; no life outside |
| `ShowSeat` | `Seat` | **Aggregation** | Physical seat exists independently |
| `Booking` | `User` | **Aggregation** | User exists independently |
| `Booking` | `Show` | **Aggregation** | Show exists independently |
| `Booking` | `ShowSeat[]` | **Aggregation** | ShowSeats exist on the show; booking references them |
| `BookingService` | `PricingStrategy` | **Dependency (Uses)** | Injected strategy, swappable at runtime |

### Why `ShowSeat` exists (the join entity)
A physical `Seat` belongs to a `Screen` — it exists regardless of any show.
But the **availability** of that seat is specific to each show.
`ShowSeat` is created fresh for each show and tracks that show-specific availability.

```
Screen.Seat (physical)
     ↓ one per show
Show.ShowSeat (availability snapshot)
```

---

## Step 5 — Design Patterns

### Strategy → `PricingStrategy`
- **Why:** Seat price varies by category, day, peak hours — should change without touching `BookingService`
- **How:** `PricingStrategy` interface with `getPrice(seat)`. Default: `CategoryBasedPricing`. Swap via `setPricingStrategy()`
- **Interview line:** *"I used Strategy for pricing so we can plug in surge pricing or weekend pricing without changing any booking logic."*

### State Machine → `ShowSeat`
- **States:** `AVAILABLE → LOCKED → BOOKED`, or `LOCKED → AVAILABLE` (release on cancel)
- **Why:** Prevents two users from booking the same seat simultaneously — you must lock before booking
- **Interview line:** *"The LOCKED state is how we handle concurrency. Two users can't both lock the same seat since `lock()` throws if status isn't AVAILABLE."*

### State Machine → `Booking`
- **States:** `PENDING → CONFIRMED` or `PENDING/CONFIRMED → CANCELLED`
- **Why:** Booking has a clear lifecycle that should be explicitly modelled
- **Interview line:** *"I modelled Booking status as a state machine so invalid transitions — like confirming an already cancelled booking — throw an error rather than silently corrupting state."*

---

## Step 6 — Service Class Decision

`BookingService` is a **separate service class** — unlike Parking Lot where `ParkingLot` was both entity and service.

**Why?**
- `Movie`, `Theatre`, `Show`, `Seat`, `User` are all participants
- None of them naturally owns the booking orchestration logic
- `BookingService` coordinates all of them

| Entity | Can it own booking logic? | Reason |
|---|---|---|
| `Movie` | No | Movie has no knowledge of users or seats |
| `Show` | No | Show manages seat availability, not the booking flow |
| `Theatre` | No | Theatre is just a venue |
| `BookingService` | **Yes** | Dedicated orchestrator — this is why it exists |

---

## Step 7 — Extensibility

| Change Request | What changes |
|---|---|
| Add new seat category (e.g. `VIP`) | Add to `SeatCategory` enum + update pricing map. Zero structural changes. |
| Add surge pricing on weekends | Create `SurgePricingStrategy implements PricingStrategy`. Call `setPricingStrategy()`. Nothing else changes. |
| Add seat reservation (hold for 10 min) | Add a timer on LOCKED state inside `ShowSeat`. Lock auto-releases on expiry. |
| Add multiple shows per screen | Already supported — `Show` is tied to a `Screen` + `startTime`. Create multiple `Show` instances per screen. |
| Add payment processing | Create `PaymentService`. `BookingService.confirmBooking()` calls `paymentService.charge()` before calling `booking.confirm()`. |
| Add DB persistence | Extract `BookingRepository` interface. Replace in-memory `Map<string, Booking>` with a DB-backed implementation. |

---

## Quick Recall

```
Theatre → Screen[] → Seat[]         (physical setup)
Movie + Screen + time  →  Show
Show creates ShowSeat per Seat      (per-show availability)

createBooking(user, show, seatIds):
  → lock each ShowSeat              (AVAILABLE → LOCKED, throws if not available)
  → create Booking (PENDING)

confirmBooking:
  → Booking.confirm()
  → each ShowSeat: LOCKED → BOOKED

cancelBooking:
  → Booking.cancel()
  → each ShowSeat: → AVAILABLE

Patterns:  Strategy (PricingStrategy)  |  State Machine (ShowSeat, Booking)
Service:   BookingService  (separate — entities are just participants)
```
