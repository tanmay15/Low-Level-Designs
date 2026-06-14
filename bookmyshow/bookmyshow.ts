// =============================================================================
// LLD: BOOKMYSHOW
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================


// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. User can search and select a movie
//   2. User can view shows for a movie at a given theatre
//   3. User can see available seats for a show
//   4. User can select seats and create a booking
//   5. Booking is confirmed after payment; cancelled if payment fails or user cancels
//   6. Cancelled booking releases the seats back to available
//
// Non-Functional:
//   - Two users should not be able to book the same seat for the same show
//     → handled via seat LOCKING before booking
//   - Seat pricing varies by category (SILVER / GOLD / PLATINUM) → Strategy pattern
//   - Adding a new seat category or pricing model should not change existing logic
//
// Out of scope: Payment gateway, search/filter for movies, user authentication
// =============================================================================


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum SeatCategory {
  SILVER = "SILVER",
  GOLD = "GOLD",
  PLATINUM = "PLATINUM",
}

enum ShowSeatStatus {
  AVAILABLE = "AVAILABLE",
  LOCKED = "LOCKED",     // temporarily held while user is paying
  BOOKED = "BOOKED",
}

enum BookingStatus {
  PENDING = "PENDING",       // seats locked, payment not done
  CONFIRMED = "CONFIRMED",   // payment done, seats booked
  CANCELLED = "CANCELLED",   // user cancelled or payment failed
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:   Movie, Theatre, Screen, Seat, Show, ShowSeat, User, Booking
// Interface:  PricingStrategy  (Strategy pattern)
// Service:    BookingService   (orchestrates the booking flow)
//
// Relationships:
//   Theatre      HAS-A (Composition)   Screen[]
//   Screen       HAS-A (Composition)   Seat[]
//   Show         HAS-A (Aggregation)   Movie, Screen
//   Show         HAS-A (Composition)   ShowSeat[]   (show-specific seat availability)
//   Booking      HAS-A (Aggregation)   User, Show, ShowSeat[]
//   BookingService  USES               PricingStrategy
//
// Key concept — ShowSeat:
//   A physical Seat lives on a Screen.
//   Each Show creates its own ShowSeat per physical Seat to track availability.
//   This is the join entity between Show and Seat.
// =============================================================================


// ── Movie ─────────────────────────────────────────────────────────────────────

class Movie {
  public id: string;
  public title: string;
  public durationMin: number;

  constructor(id: string, title: string, durationMin: number) {
    this.id = id;
    this.title = title;
    this.durationMin = durationMin;
  }
}


// ── Seat ──────────────────────────────────────────────────────────────────────
// A physical seat that belongs to a Screen. Has a category for pricing.

class Seat {
  public id: string;
  public row: string;
  public col: number;
  public category: SeatCategory;
  public screenId: string;

  constructor(id: string, row: string, col: number, category: SeatCategory, screenId: string) {
    this.id = id;
    this.row = row;
    this.col = col;
    this.category = category;
    this.screenId = screenId;
  }
}


// ── Screen ────────────────────────────────────────────────────────────────────

class Screen {
  public id: string;
  public name: string;
  private seats: Seat[];

  constructor(id: string, name: string) {
    this.id = id;
    this.name = name;
    this.seats = [];
  }

  addSeat(seat: Seat): void {
    this.seats.push(seat);
  }

  getSeats(): Seat[] {
    return this.seats;
  }
}


// ── Theatre ───────────────────────────────────────────────────────────────────

class Theatre {
  public id: string;
  public name: string;
  public location: string;
  private screens: Screen[];

  constructor(id: string, name: string, location: string) {
    this.id = id;
    this.name = name;
    this.location = location;
    this.screens = [];
  }

  addScreen(screen: Screen): void {
    this.screens.push(screen);
  }

  getScreens(): Screen[] {
    return this.screens;
  }
}


// ── ShowSeat ──────────────────────────────────────────────────────────────────
// Per-show availability snapshot of a physical Seat.
// Owns its own state — nobody sets status from outside.
// LOCKED state prevents two users booking the same seat simultaneously.

class ShowSeat {
  public id: string;
  public seat: Seat;
  public showId: string;
  public price: number;
  private status: ShowSeatStatus;

  constructor(id: string, seat: Seat, showId: string, price: number) {
    this.id = id;
    this.seat = seat;
    this.showId = showId;
    this.price = price;
    this.status = ShowSeatStatus.AVAILABLE;
  }

  getStatus(): ShowSeatStatus {
    return this.status;
  }

  isAvailable(): boolean {
    return this.status === ShowSeatStatus.AVAILABLE;
  }

  lock(): void {
    if (!this.isAvailable()) {
      throw new Error(`Seat ${this.seat.id} is not available (status: ${this.status})`);
    }
    this.status = ShowSeatStatus.LOCKED;
  }

  book(): void {
    if (this.status !== ShowSeatStatus.LOCKED) {
      throw new Error(`Seat ${this.seat.id} must be locked before booking`);
    }
    this.status = ShowSeatStatus.BOOKED;
  }

  release(): void {
    this.status = ShowSeatStatus.AVAILABLE;
  }
}


// ── Show ──────────────────────────────────────────────────────────────────────
// A specific screening of a Movie on a Screen at a given time.
// Holds ShowSeats — the per-show availability map for every seat in the screen.

class Show {
  public id: string;
  public movie: Movie;
  public screen: Screen;
  public startTime: Date;
  private showSeats: Map<string, ShowSeat>; // seatId → ShowSeat

  constructor(id: string, movie: Movie, screen: Screen, startTime: Date) {
    this.id = id;
    this.movie = movie;
    this.screen = screen;
    this.startTime = startTime;
    this.showSeats = new Map();
  }

  addShowSeat(showSeat: ShowSeat): void {
    this.showSeats.set(showSeat.seat.id, showSeat);
  }

  getShowSeat(seatId: string): ShowSeat | null {
    return this.showSeats.get(seatId) ?? null;
  }

  getAvailableSeats(): ShowSeat[] {
    return Array.from(this.showSeats.values()).filter((ss) => ss.isAvailable());
  }
}


// ── User ──────────────────────────────────────────────────────────────────────

class User {
  public id: string;
  public name: string;
  public email: string;

  constructor(id: string, name: string, email: string) {
    this.id = id;
    this.name = name;
    this.email = email;
  }
}


// ── Booking ───────────────────────────────────────────────────────────────────
// State machine: PENDING → CONFIRMED or CANCELLED
// Booking owns the transition logic — nobody changes status from outside.

class Booking {
  public id: string;
  public user: User;
  public show: Show;
  public showSeats: ShowSeat[];
  public totalAmount: number;
  public status: BookingStatus;

  constructor(id: string, user: User, show: Show, showSeats: ShowSeat[]) {
    this.id = id;
    this.user = user;
    this.show = show;
    this.showSeats = showSeats;
    this.totalAmount = showSeats.reduce((sum, ss) => sum + ss.price, 0);
    this.status = BookingStatus.PENDING;
  }

  confirm(): void {
    if (this.status !== BookingStatus.PENDING) {
      throw new Error(`Cannot confirm booking in status: ${this.status}`);
    }
    this.showSeats.forEach((ss) => ss.book());
    this.status = BookingStatus.CONFIRMED;
  }

  cancel(): void {
    if (this.status === BookingStatus.CANCELLED) {
      throw new Error("Booking is already cancelled");
    }
    this.showSeats.forEach((ss) => ss.release());
    this.status = BookingStatus.CANCELLED;
  }
}


// ── PricingStrategy (Strategy Pattern) ───────────────────────────────────────

interface PricingStrategy {
  getPrice(seat: Seat): number;
}

class CategoryBasedPricing implements PricingStrategy {
  private prices: Map<SeatCategory, number>;

  constructor() {
    this.prices = new Map([
      [SeatCategory.SILVER, 150],
      [SeatCategory.GOLD, 250],
      [SeatCategory.PLATINUM, 400],
    ]);
  }

  getPrice(seat: Seat): number {
    return this.prices.get(seat.category) ?? 150;
  }
}


// ── BookingService ────────────────────────────────────────────────────────────
// Separate service class — Show, Theatre, Movie are just participants here.
// BookingService orchestrates: initialize show seats, lock, book, confirm, cancel.

class BookingService {
  private bookings: Map<string, Booking>;
  private bookingCounter: number;
  private pricingStrategy: PricingStrategy;

  constructor() {
    this.bookings = new Map();
    this.bookingCounter = 0;
    this.pricingStrategy = new CategoryBasedPricing();
  }

  setPricingStrategy(strategy: PricingStrategy): void {
    this.pricingStrategy = strategy;
  }

  // Call this when a Show is created — sets up ShowSeats for every seat in the screen
  initializeShowSeats(show: Show): void {
    for (const seat of show.screen.getSeats()) {
      const price = this.pricingStrategy.getPrice(seat);
      const showSeat = new ShowSeat(`SS-${show.id}-${seat.id}`, seat, show.id, price);
      show.addShowSeat(showSeat);
    }
  }

  // Lock seats → create PENDING booking
  createBooking(user: User, show: Show, seatIds: string[]): Booking {
    const showSeats: ShowSeat[] = [];

    for (const seatId of seatIds) {
      const showSeat = show.getShowSeat(seatId);
      if (!showSeat) throw new Error(`Seat ${seatId} does not belong to this show`);
      showSeat.lock(); // throws if not AVAILABLE
      showSeats.push(showSeat);
    }

    const booking = new Booking(
      `BKG-${++this.bookingCounter}`,
      user,
      show,
      showSeats
    );
    this.bookings.set(booking.id, booking);

    console.log(`[BOOKING CREATED] ${booking.id} | User: ${user.name} | Seats: ${seatIds.join(", ")} | Total: ₹${booking.totalAmount}`);
    return booking;
  }

  // Simulate payment success → confirm
  confirmBooking(bookingId: string): void {
    const booking = this.bookings.get(bookingId);
    if (!booking) throw new Error(`Booking ${bookingId} not found`);
    booking.confirm();
    console.log(`[CONFIRMED] ${bookingId} — seats locked permanently`);
  }

  cancelBooking(bookingId: string): void {
    const booking = this.bookings.get(bookingId);
    if (!booking) throw new Error(`Booking ${bookingId} not found`);
    booking.cancel();
    console.log(`[CANCELLED] ${bookingId} — seats released back to AVAILABLE`);
  }

  printAvailableSeats(show: Show): void {
    const available = show.getAvailableSeats();
    console.log(`\n── Available seats for "${show.movie.title}" at ${show.startTime.toLocaleTimeString()} ──`);
    if (available.length === 0) {
      console.log("  No seats available");
    } else {
      available.forEach((ss) =>
        console.log(`  Seat ${ss.seat.id} (${ss.seat.row}${ss.seat.col}) | ${ss.seat.category} | ₹${ss.price} | ${ss.getStatus()}`)
      );
    }
    console.log();
  }
}


// =============================================================================
// STEP 4 — DEMO
// =============================================================================

console.log("=== BookMyShow Demo ===\n");

// Setup: Theatre → Screen → Seats
const theatre = new Theatre("T1", "PVR Phoenix", "Mumbai");
const screen1 = new Screen("S1", "Audi 1");

screen1.addSeat(new Seat("A1", "A", 1, SeatCategory.SILVER, "S1"));
screen1.addSeat(new Seat("A2", "A", 2, SeatCategory.SILVER, "S1"));
screen1.addSeat(new Seat("B1", "B", 1, SeatCategory.GOLD, "S1"));
screen1.addSeat(new Seat("B2", "B", 2, SeatCategory.GOLD, "S1"));
screen1.addSeat(new Seat("C1", "C", 1, SeatCategory.PLATINUM, "S1"));

theatre.addScreen(screen1);

// Movie and Show
const movie = new Movie("M1", "Interstellar", 169);
const show = new Show("SH1", movie, screen1, new Date("2026-06-04T18:00:00"));

// Initialize show seats via service
const bookingService = new BookingService();
bookingService.initializeShowSeats(show);

// Users
const alice = new User("U1", "Alice", "alice@email.com");
const bob = new User("U2", "Bob", "bob@email.com");

bookingService.printAvailableSeats(show);

// Alice books B1 and C1
const aliceBooking = bookingService.createBooking(alice, show, ["B1", "C1"]);

bookingService.printAvailableSeats(show);

// Bob tries to book B1 (already locked by Alice) — should throw
try {
  bookingService.createBooking(bob, show, ["B1"]);
} catch (e: any) {
  console.log(`[ERROR] ${e.message}\n`);
}

// Alice confirms her booking (payment success)
bookingService.confirmBooking(aliceBooking.id);

// Bob books available seats
const bobBooking = bookingService.createBooking(bob, show, ["A1", "A2"]);
bookingService.confirmBooking(bobBooking.id);

bookingService.printAvailableSeats(show);

// Alice cancels — seats should go back to AVAILABLE
bookingService.cancelBooking(aliceBooking.id);

bookingService.printAvailableSeats(show);

// Try cancelling again — should throw
try {
  bookingService.cancelBooking(aliceBooking.id);
} catch (e: any) {
  console.log(`[ERROR] ${e.message}`);
}
