# LLD: Ride Sharing (Uber / Ola)

## Step 1 — Requirements

### Functional
1. Register drivers and riders
2. Rider requests a ride (pickup + dropoff location)
3. System finds **nearest AVAILABLE driver** and auto-assigns the trip
4. Trip lifecycle: Request → Driver Assigned → Started → Completed
5. Cancel before a trip starts (rider or driver side)
6. Fare calculation via pluggable **FareStrategy** (base / surge)

### Non-Functional
- O(n) driver scan for nearest (acceptable for LLD; production = geo-index)
- FareStrategy is swappable at runtime without changing RideService
- Driver location updates to dropoff after trip completion

### Out of Scope
- Real GPS, Haversine, maps API
- Driver rating and review
- Payment gateway integration
- Surge pricing based on demand density
- Scheduled / pre-booked rides

---

## Step 2 — Entities

| Entity     | Role                                                          |
|------------|---------------------------------------------------------------|
| `Driver`   | Has status (AVAILABLE/ON_TRIP/OFFLINE), current location      |
| `Rider`    | Has current location                                          |
| `Trip`     | Central state machine — owns the lifecycle of one ride        |
| `Location` | Value object with lat/lng and `distanceTo()` helper           |

---

## Step 3 — Class Design

### Attributes & Methods

#### `Driver`
| Attribute       | Type           | Notes                           |
|-----------------|----------------|---------------------------------|
| `id`, `name`    | String         |                                 |
| `vehicleNumber` | String         |                                 |
| `status`        | `DriverStatus` | AVAILABLE / ON_TRIP / OFFLINE   |
| `location`      | `Location`     | Updated to dropoff on trip end  |

#### `Trip` (State Machine)
| Attribute       | Type          | Notes                           |
|-----------------|---------------|---------------------------------|
| `riderId`       | String        |                                 |
| `driverId`      | String        | null until driver assigned      |
| `pickup/dropoff`| `Location`    |                                 |
| `status`        | `TripStatus`  | State machine (see below)       |
| `fare`          | double        | Computed on `endTrip()`         |
| `distanceKm`    | double        | Computed on `endTrip()`         |

#### `RideService`
| Method                              | Notes                                    |
|-------------------------------------|------------------------------------------|
| `requestRide(riderId, from, to)`    | Finds nearest driver, creates trip       |
| `startTrip(tripId)`                 | DRIVER_ASSIGNED → STARTED                |
| `endTrip(tripId)`                   | STARTED → COMPLETED, calculates fare     |
| `cancelTrip(tripId, who)`           | Cancel if not yet started                |
| `setFareStrategy(strategy)`         | Runtime strategy swap (surge pricing)    |

### Trip State Machine
```
REQUESTED → DRIVER_ASSIGNED → STARTED → COMPLETED
                  ↓                 ↓
             CANCELLED          CANCELLED
```
- Driver status mirrors trip state: AVAILABLE → ON_TRIP → AVAILABLE
- `driverActiveTrip` Map (driverId → tripId) ensures a driver has only one active trip

### Design Patterns
| Pattern     | Where used                            | Why                                       |
|-------------|---------------------------------------|-------------------------------------------|
| **Strategy**| `FareStrategy` interface              | Swap base/surge fare without code change  |
| **Singleton**| Could apply to `RideService`         | Single point of state management          |

### Key Data Structures
| Structure                          | Purpose                                |
|------------------------------------|----------------------------------------|
| `Map<String, Driver> drivers`      | O(1) driver lookup by id               |
| `Map<String, Trip> trips`          | O(1) trip lookup by id                 |
| `Map<String, String> driverActiveTrip` | Prevent driver from having 2 trips |

---

## Step 4 — How It Differs from Other Problems

| Feature             | Ride Sharing                          | BookMyShow / Hotel Booking          |
|---------------------|---------------------------------------|-------------------------------------|
| Resource assignment | **Auto-assigned** (nearest driver)    | User picks from search results      |
| State machine       | On `Trip` **and** `Driver`            | Only on booking/order entity        |
| Resource status     | `DriverStatus` — dynamic per trip     | Room availability — date-based      |
| Pricing             | Strategy (base/surge)                 | Strategy (standard/discount)        |

---

## Step 5 — Extensibility
- **Pool ride**: Group multiple riders into one Trip — add `List<String> riderIds` to Trip
- **Driver rating**: Add `Rating` entity, link via `tripId`
- **Real-time location**: Periodic `driver.location` updates via socket messages
- **Surge pricing**: Implement `SurgeFareStrategy` with demand density as multiplier input
- **Scheduled ride**: Add `scheduledTime` to Trip; scheduler picks up future trips

---

## Quick Recall
1. `Trip` is the **state machine** — driver status is derived from trip status
2. `findNearestDriver()` = O(n) scan of AVAILABLE drivers → production uses geo-index
3. `driverActiveTrip` Map prevents one driver from being assigned to two trips
4. FareStrategy swapped at runtime — classic Strategy pattern usage
5. Driver location updates to `dropoff` on `endTrip()` — natural real-world behavior
