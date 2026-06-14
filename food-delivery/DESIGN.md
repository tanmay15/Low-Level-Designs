# LLD: Food Delivery System

> **Code file:** `FoodDeliverySolution.java` — keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
| # | Requirement |
|---|---|
| 1 | Customers browse restaurants and their menus |
| 2 | Place an order with multiple items from one restaurant |
| 3 | Order state machine: PLACED → PREPARING → OUT_FOR_DELIVERY → DELIVERED |
| 4 | Assign available delivery partner when order is ready |
| 5 | Cancel order only before it reaches PREPARING |
| 6 | Delivery fee is configurable by strategy |
| 7 | Observers notified on every order status change |

### Non-Functional
- State transitions are validated — invalid moves throw exceptions
- Delivery partner availability managed like a resource (like ParkingSpot)
- Delivery fee is swappable (Strategy)
- Notification type extensible without changing OrderService (Observer)

### Out of Scope
Payment gateway, restaurant ratings, GPS tracking, surge pricing

---

## Step 2 — Entities

| Entity | Type | Role |
|---|---|---|
| `OrderStatus` | Enum | PLACED / PREPARING / OUT_FOR_DELIVERY / DELIVERED / CANCELLED |
| `MenuItem` | Class | id, name, price, category |
| `Restaurant` | Class | id, name, location, menu (Map of MenuItems) |
| `Customer` | Class | id, name, phone, address |
| `DeliveryPartner` | Class | id, name, availability flag; markBusy/markAvailable |
| `OrderItem` | Class | MenuItem + quantity + subtotal (line item within an order) |
| `Order` | Class | State machine entity: holds items, status, partner, totals |
| `DeliveryFeeStrategy` | Interface | Strategy for calculating delivery fee |
| `FlatFeeStrategy` | Class | Always charges a fixed fee |
| `FreeAboveThresholdStrategy` | Class | Free delivery above min order value |
| `OrderStatusObserver` | Interface | Observer notified on status changes |
| `CustomerNotifier` | Class | Sends SMS to customer |
| `RestaurantNotifier` | Class | Alerts restaurant on new order |
| `OrderService` | Service | Orchestrates everything |

---

## Step 3 — Class Design

### Relationships

```
OrderService
  ├── OWNS Map<id, Restaurant>
  ├── OWNS Map<id, Customer>
  ├── OWNS Map<id, DeliveryPartner>
  ├── OWNS Map<id, Order>
  ├── OWNS List<OrderStatusObserver>   ← Observer pattern
  └── USES DeliveryFeeStrategy         ← Strategy pattern

Order
  ├── HAS-A Customer, Restaurant       (Aggregation)
  ├── HAS-A List<OrderItem>            (Composition)
  └── HAS-A DeliveryPartner            (Aggregation, assigned later)

Restaurant
  └── HAS-A Map<id, MenuItem>          (Composition)
```

### State Machine — Order (most complex in all our problems)

```
           updateStatus(PREPARING)       updateStatus(OUT_FOR_DELIVERY)
PLACED ──────────────────────────► PREPARING ──────────────────────────► OUT_FOR_DELIVERY
  │                                                                              │
  │ updateStatus(CANCELLED)                                       updateStatus(DELIVERED)
  ▼                                                                              ▼
CANCELLED                                                                    DELIVERED

DELIVERED and CANCELLED are terminal — no further transitions allowed.
```

Valid transitions enforced in `Order.isValidTransition()`.

### Attributes and Methods

**`Order`**
- `status`, `items`, `customer`, `restaurant`, `deliveryPartner`, `totalAmount`, `deliveryFee`
- `updateStatus(newStatus)` — validates transition before applying
- `assignPartner(partner)` — sets deliveryPartner
- `private isValidTransition(from, to)` — the state machine guard

**`DeliveryPartner`**
- `private boolean available`
- `isAvailable()`, `markBusy()`, `markAvailable()`

**`OrderService`**
- `placeOrder(customerId, restaurantId, Map<itemId, qty>)` — resolves items, calculates fee, creates Order
- `updateOrderStatus(orderId, newStatus)` — delegates to Order, then notifies observers
- `assignDeliveryPartner(orderId)` — finds available partner, marks busy
- `completeDelivery(orderId)` — updates to DELIVERED, frees partner
- `cancelOrder(orderId)` — only if PLACED; updates status, notifies
- `addObserver(observer)`, `notifyObservers(order, status)`

---

## Step 4 — Design Patterns

### 1. State Machine — `Order`
Most complex state machine across all problems — 5 states, strict valid-transition guard.

```java
private boolean isValidTransition(OrderStatus from, OrderStatus to) {
    switch (from) {
        case PLACED:           return to == PREPARING || to == CANCELLED;
        case PREPARING:        return to == OUT_FOR_DELIVERY;
        case OUT_FOR_DELIVERY: return to == DELIVERED;
        default:               return false; // terminal states
    }
}
```

Compare to BookMyShow's `ShowSeat` (3 states) and `Booking` (3 states) — both simpler.

### 2. Strategy — `DeliveryFeeStrategy`
Calculated at order placement time. Swap without changing OrderService:
```java
service.setFeeStrategy(new FreeAboveThresholdStrategy(500, 40));
```

Note: fee is calculated on `itemsTotal` (before fee) — avoid circular dependency.

### 3. Observer — `OrderStatusObserver`
`OrderService` is the subject. Every status change fans out to all registered observers.

```java
service.addObserver(new CustomerNotifier());  // SMS customer
service.addObserver(new RestaurantNotifier()); // alert restaurant
```

New observer type (e.g., analytics logger) = new class implementing `OrderStatusObserver`. Nothing else changes. Same pattern as `NotificationService` in our notification service problem.

### 4. Resource Management — `DeliveryPartner`
Same concept as `ParkingSpot`:
- `isAvailable()` → query
- `markBusy()` → take the resource
- `markAvailable()` → release the resource

---

## Step 5 — What Makes This Different From Other Problems

| Aspect | Food Delivery | Most similar to |
|---|---|---|
| State machine | 5 states, most complex | BookMyShow Booking (3 states) |
| Resource management | DeliveryPartner availability | Parking Lot: ParkingSpot |
| Line items | OrderItem (qty × price) | BookMyShow: multiple ShowSeats |
| Observer for events | OrderStatusObserver | Notification Service |
| Fee strategy | DeliveryFeeStrategy | Parking Lot: FeeStrategy |
| **NEW: Cancellation rules** | Only from PLACED state | Not in other problems |
| **NEW: Resource release on completion** | Partner freed after DELIVERED | Not explicitly in others |

### Cancellation business rule
```
PLACED → CANCELLED   ✓ (customer cancels before restaurant starts)
PREPARING → CANCELLED ✗ (restaurant already started — not allowed)
```
This is a state machine constraint, enforced by `isValidTransition()`. No special cancel method needed — the state machine rejects it automatically.

---

## Step 6 — Extensibility

| Change | What to do |
|---|---|
| New delivery fee model | Implement `DeliveryFeeStrategy`. One line in `setFeeStrategy()` |
| New notification type (push/email) | Implement `OrderStatusObserver`. One line `addObserver()` |
| Order tracking (ETA) | Add `estimatedDeliveryTime` to Order; update on each status change |
| Multi-restaurant orders | Move items to `Map<Restaurant, List<OrderItem>>`; one `DeliveryPartner` per restaurant |
| Surge pricing | Pass time/demand context into `DeliveryFeeStrategy.calculate()` |
| Partner rating | Add `rating`, `totalDeliveries` to `DeliveryPartner`; update on DELIVERED |

---

## Key Interview Points

- **State machine guard in Order, not in Service:** `isValidTransition()` lives in `Order` because the Order owns its state. Service just calls `updateStatus()` — it doesn't decide what's valid.
- **DeliveryPartner = resource:** Exact same pattern as ParkingSpot. `markBusy()` on assign, `markAvailable()` on delivery complete.
- **Observer vs direct notify calls:** Without Observer, OrderService would have `if (smsEnabled) sendSMS(); if (emailEnabled) sendEmail();` — coupled and unextensible. Observer makes each notification type independent.
- **Fee before fee (circular dependency):** The delivery fee depends on `itemsTotal`, not `totalAmount`. This avoids a circular: you calculate fee AFTER totaling items, then add fee to get totalAmount.
- **Cancellation is just a state transition:** No special `cancel()` logic in service. The state machine's guard rejects invalid cancellations automatically.
