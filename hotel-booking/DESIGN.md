# LLD: Hotel Booking System (OYO / Airbnb style)

## Step 1 — Requirements

### Functional
1. Add rooms of different types (SINGLE, DOUBLE, SUITE) with price per night
2. Search available rooms by date range and type
3. Book a room — creates a `Booking` with total price
4. Cancel a booking — frees up the room for those dates
5. Check in and check out — update booking status

### Non-Functional
- Date overlap check per room: O(bookings per room)
- `PricingStrategy` is swappable at runtime

### Out of Scope
- Real payment processing
- Multi-hotel / multi-property management
- Room amenities or room comparison
- Seasonal pricing calendar in detail
- Reviews and ratings

---

## Step 2 — Entities

| Entity    | Role                                                            |
|-----------|-----------------------------------------------------------------|
| `Room`    | Physical room with type, price, floor, and list of bookings     |
| `Guest`   | Person making the booking                                       |
| `Booking` | Audit entity — who booked which room for which dates at what price |

---

## Step 3 — Class Design

### Attributes

#### `Room`
| Attribute        | Type             | Notes                                     |
|------------------|------------------|-------------------------------------------|
| `id`             | String           |                                           |
| `type`           | `RoomType`       | SINGLE / DOUBLE / SUITE                   |
| `pricePerNight`  | double           |                                           |
| `floor`          | int              |                                           |
| `bookings`       | `List<Booking>`  | All bookings for this room                |

**`Room.isAvailable(checkIn, checkOut)`** — the date overlap algorithm:
```
A room is UNAVAILABLE if any CONFIRMED or CHECKED_IN booking B satisfies:
  B.checkIn < requested.checkOut  AND  B.checkOut > requested.checkIn

Equivalent to: ranges [B_in, B_out) and [req_in, req_out) overlap.
CANCELLED and CHECKED_OUT bookings are skipped.
```

#### `Booking`
| Attribute     | Type            | Notes                                    |
|---------------|-----------------|------------------------------------------|
| `guestId`     | String          |                                          |
| `roomId`      | String          |                                          |
| `checkIn`     | long            | Epoch-day (simplified — use LocalDate in production) |
| `checkOut`    | long            |                                          |
| `totalPrice`  | double          | Calculated by `PricingStrategy` at booking time |
| `status`      | `BookingStatus` | CONFIRMED → CHECKED_IN → CHECKED_OUT     |

#### `HotelService`
| Method                                            | Notes                                   |
|---------------------------------------------------|-----------------------------------------|
| `addRoom(room)`                                   | Add room to inventory                   |
| `registerGuest(guest)`                            | Add guest                               |
| `searchAvailable(checkIn, checkOut, type)`        | Returns rooms that pass `isAvailable()` |
| `bookRoom(guestId, roomId, checkIn, checkOut)`    | Creates Booking if room is available    |
| `cancelBooking(bookingId)`                        | Sets status to CANCELLED                |
| `checkIn(bookingId)`                              | CONFIRMED → CHECKED_IN                  |
| `checkOut(bookingId)`                             | CHECKED_IN → CHECKED_OUT                |

### Booking State Machine
```
CONFIRMED → CHECKED_IN → CHECKED_OUT
    ↓
CANCELLED  (only before CHECKED_IN)
```

### Design Patterns
| Pattern     | Where                       | Why                                            |
|-------------|-----------------------------|------------------------------------------------|
| **Strategy**| `PricingStrategy` interface | Swap standard/weekend/seasonal pricing at runtime |

---

## Step 4 — How It Differs from Other Problems

| Feature             | Hotel Booking                       | Amazon Locker                        |
|---------------------|-------------------------------------|--------------------------------------|
| Availability check  | Date overlap on room                | Locker size match + occupied status  |
| Resource type       | Room (reusable across dates)        | Locker (one package at a time)       |
| Transaction entity  | `Booking` (date range)              | `Delivery` (one delivery at a time)  |
| Cancellation        | Anytime before check-in             | Not supported after deposit          |

---

## Step 5 — Extensibility
- **Multiple hotels**: Add `Hotel` entity, `HotelService` works per hotel or across chain
- **Amenity filter**: Add `Set<Amenity> amenities` to `Room`, filter in `searchAvailable()`
- **Real payment**: Add `Payment` entity, link to `Booking`
- **Seasonal pricing**: `PricingStrategy` can accept check-in date for calendar-based pricing
- **Ratings**: `Review` entity with roomId, guestId, rating, comment

---

## Quick Recall
1. Date overlap formula: `B.checkIn < req.checkOut AND B.checkOut > req.checkIn`
2. `Room.bookings` holds ALL bookings — `isAvailable()` iterates and skips CANCELLED/CHECKED_OUT
3. Total price is computed **at booking time** using `PricingStrategy` → stored on `Booking`
4. Cancellation before check-in only — same rule as most real booking systems
5. This is functionally similar to **seat booking in BookMyShow** — replace date ranges with seat IDs
