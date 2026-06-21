// =============================================================================
// LLD: BOOKMYSHOW — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. User can search and select a movie
//   2. User can view shows for a movie at a given theatre
//   3. User can see available seats for a show
//   4. User can select seats and create a booking
//   5. Booking is confirmed after payment; seats become permanently booked
//   6. Cancelled booking releases all seats back to available
//
// Non-Functional:
//   - Two users cannot book the same seat for the same show → seat LOCKING
//   - Seat pricing varies by category → Strategy pattern
//   - Adding new seat category or pricing model requires no change to existing classes
//
// Out of scope: Payment gateway, movie search/filter, user authentication
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum SeatCategory { SILVER, GOLD, PLATINUM }

enum ShowSeatStatus { AVAILABLE, LOCKED, BOOKED }

enum BookingStatus { PENDING, CONFIRMED, CANCELLED }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:   Movie, Seat, Screen, Theatre, ShowSeat, Show, User, Booking
// Interface:  PricingStrategy  (Strategy pattern)
// Service:    BookingService
//
// Relationships:
//   Theatre      HAS-A (Composition)   Screen[]
//   Screen       HAS-A (Composition)   Seat[]
//   Show         HAS-A (Aggregation)   Movie, Screen
//   Show         HAS-A (Composition)   ShowSeat[]
//   Booking      HAS-A (Aggregation)   User, Show, ShowSeat[]
//   BookingService  USES               PricingStrategy
// =============================================================================


// ── Movie ─────────────────────────────────────────────────────────────────────

class Movie {
    public String id;
    public String title;
    public int durationMin;

    public Movie(String id, String title, int durationMin) {
        this.id = id;
        this.title = title;
        this.durationMin = durationMin;
    }
}


// ── Seat ──────────────────────────────────────────────────────────────────────
// A physical seat fixed to a Screen. Has a category for pricing.

class Seat {
    public String id;
    public String row;
    public int col;
    public SeatCategory category;
    public String screenId;

    public Seat(String id, String row, int col, SeatCategory category, String screenId) {
        this.id = id;
        this.row = row;
        this.col = col;
        this.category = category;
        this.screenId = screenId;
    }
}


// ── Screen ────────────────────────────────────────────────────────────────────

class Screen {
    public String id;
    public String name;
    private List<Seat> seats;

    public Screen(String id, String name) {
        this.id = id;
        this.name = name;
        this.seats = new ArrayList<>();
    }

    public void addSeat(Seat seat) {
        seats.add(seat);
    }

    public List<Seat> getSeats() {
        return seats;
    }
}


// ── Theatre ───────────────────────────────────────────────────────────────────

class Theatre {
    public String id;
    public String name;
    public String location;
    private List<Screen> screens;

    public Theatre(String id, String name, String location) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.screens = new ArrayList<>();
    }

    public void addScreen(Screen screen) {
        screens.add(screen);
    }

    public List<Screen> getScreens() {
        return screens;
    }
}


// ── ShowSeat ──────────────────────────────────────────────────────────────────
// Per-show availability snapshot of a physical Seat.
// Owns its own state — nobody sets status from outside.
// LOCKED state prevents two users booking the same seat simultaneously.

class ShowSeat {
    public String id;
    public Seat seat;
    public String showId;
    public double price;
    private ShowSeatStatus status;

    public ShowSeat(String id, Seat seat, String showId, double price) {
        this.id = id;
        this.seat = seat;
        this.showId = showId;
        this.price = price;
        this.status = ShowSeatStatus.AVAILABLE;
    }

    public ShowSeatStatus getStatus() {
        return this.status;
    }

    public boolean isAvailable() {
        return this.status == ShowSeatStatus.AVAILABLE;
    }

    public void lock() {
        if (!isAvailable()) {
            throw new RuntimeException("Seat " + seat.id + " is not available (status: " + status + ")");
        }
        this.status = ShowSeatStatus.LOCKED;
    }




    public void book() {
        if (this.status != ShowSeatStatus.LOCKED) {
            throw new RuntimeException("Seat " + seat.id + " must be locked before booking");
        }
        this.status = ShowSeatStatus.BOOKED;
    }

    public void release() {
        this.status = ShowSeatStatus.AVAILABLE;
    }
}


// ── Show ──────────────────────────────────────────────────────────────────────
// A specific screening of a Movie on a Screen at a given time.
// Holds ShowSeats — the per-show availability map for every seat in the screen.

class Show {
    public String id;
    public Movie movie;
    public Screen screen;
    public Date startTime;
    private Map<String, ShowSeat> showSeats; // seatId → ShowSeat

    public Show(String id, Movie movie, Screen screen, Date startTime) {
        this.id = id;
        this.movie = movie;
        this.screen = screen;
        this.startTime = startTime;
        this.showSeats = new HashMap<>();
    }

    public void addShowSeat(ShowSeat showSeat) {
        showSeats.put(showSeat.seat.id, showSeat);
    }

    public ShowSeat getShowSeat(String seatId) {
        return showSeats.get(seatId);
    }

    public List<ShowSeat> getAvailableSeats() {
        List<ShowSeat> available = new ArrayList<>();
        for (ShowSeat ss : showSeats.values()) {
            if (ss.isAvailable()) available.add(ss);
        }
        return available;
    }
}


// ── User ──────────────────────────────────────────────────────────────────────

class User {
    public String id;
    public String name;
    public String email;

    public User(String id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }
}


// ── Booking ───────────────────────────────────────────────────────────────────
// State machine: PENDING → CONFIRMED or CANCELLED

class Booking {
    public String id;
    public User user;
    public Show show;
    public List<ShowSeat> showSeats;
    public double totalAmount;
    public BookingStatus status;

    public Booking(String id, User user, Show show, List<ShowSeat> showSeats) {
        this.id = id;
        this.user = user;
        this.show = show;
        this.showSeats = showSeats;
        double total = 0;
        for (ShowSeat ss : showSeats) total += ss.price;
        this.totalAmount = total;
        this.status = BookingStatus.PENDING;
    }

    public void confirm() {
        if (this.status != BookingStatus.PENDING) {
            throw new RuntimeException("Cannot confirm booking in status: " + status);
        }
        for (ShowSeat ss : showSeats) ss.book();
        this.status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        if (this.status == BookingStatus.CANCELLED) {
            throw new RuntimeException("Booking is already cancelled");
        }
        for (ShowSeat ss : showSeats) ss.release();
        this.status = BookingStatus.CANCELLED;
    }
}


// ── PricingStrategy (Strategy Pattern) ───────────────────────────────────────

interface PricingStrategy {
    double getPrice(Seat seat);
}

class CategoryBasedPricing implements PricingStrategy {
    private Map<SeatCategory, Integer> prices;

    public CategoryBasedPricing() {
        prices = new HashMap<>();
        prices.put(SeatCategory.SILVER, 150);
        prices.put(SeatCategory.GOLD, 250);
        prices.put(SeatCategory.PLATINUM, 400);
    }

    @Override
    public double getPrice(Seat seat) {
        return prices.getOrDefault(seat.category, 150);
    }
}


// ── BookingService ────────────────────────────────────────────────────────────
// Separate service class — Show, Theatre, Movie are just participants here.
// Orchestrates: initialize show seats, lock, book, confirm, cancel.

class BookingService {
    private Map<String, Booking> bookings;
    private int bookingCounter;
    private PricingStrategy pricingStrategy;

    public BookingService() {
        this.bookings = new HashMap<>();
        this.bookingCounter = 0;
        this.pricingStrategy = new CategoryBasedPricing();
    }

    public void setPricingStrategy(PricingStrategy strategy) {
        this.pricingStrategy = strategy;
    }

    // Call this when a Show is created — sets up ShowSeats for every seat in the screen
    public void initializeShowSeats(Show show) {
        for (Seat seat : show.screen.getSeats()) {
            double price = pricingStrategy.getPrice(seat);
            ShowSeat showSeat = new ShowSeat("SS-" + show.id + "-" + seat.id, seat, show.id, price);
            show.addShowSeat(showSeat);
        }
    }

    // Lock seats → create PENDING booking
    public Booking createBooking(User user, Show show, List<String> seatIds) {
        List<ShowSeat> showSeats = new ArrayList<>();

        for (String seatId : seatIds) {
            ShowSeat showSeat = show.getShowSeat(seatId);
            if (showSeat == null) throw new RuntimeException("Seat " + seatId + " does not belong to this show");
            showSeat.lock(); // throws if not AVAILABLE
            showSeats.add(showSeat);
        }

        Booking booking = new Booking("BKG-" + (++bookingCounter), user, show, showSeats);
        bookings.put(booking.id, booking);

        StringBuilder seats = new StringBuilder();
        for (int i = 0; i < seatIds.size(); i++) {
            if (i > 0) seats.append(", ");
            seats.append(seatIds.get(i));
        }
        System.out.println("[BOOKING CREATED] " + booking.id + " | User: " + user.name +
                " | Seats: " + seats + " | Total: " + (int) booking.totalAmount);
        return booking;
    }

    // Simulate payment success → confirm
    public void confirmBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null) throw new RuntimeException("Booking " + bookingId + " not found");
        booking.confirm();
        System.out.println("[CONFIRMED] " + bookingId + " — seats locked permanently");
    }

    public void cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null) throw new RuntimeException("Booking " + bookingId + " not found");
        booking.cancel();
        System.out.println("[CANCELLED] " + bookingId + " — seats released back to AVAILABLE");
    }

    public void printAvailableSeats(Show show) {
        List<ShowSeat> available = show.getAvailableSeats();
        System.out.println("\n── Available seats for \"" + show.movie.title + "\" ──");
        if (available.isEmpty()) {
            System.out.println("  No seats available");
        } else {
            for (ShowSeat ss : available) {
                System.out.println("  Seat " + ss.seat.id + " (" + ss.seat.row + ss.seat.col + ")" +
                        " | " + ss.seat.category + " | " + (int) ss.price + " | " + ss.getStatus());
            }
        }
        System.out.println();
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: BookMyShowSolution.java
// =============================================================================

public class BookMyShowSolution {
    public static void main(String[] args) {
        System.out.println("=== BookMyShow Demo ===\n");

        Theatre theatre = new Theatre("T1", "PVR Phoenix", "Mumbai");
        Screen screen1 = new Screen("S1", "Audi 1");

        screen1.addSeat(new Seat("A1", "A", 1, SeatCategory.SILVER, "S1"));
        screen1.addSeat(new Seat("A2", "A", 2, SeatCategory.SILVER, "S1"));
        screen1.addSeat(new Seat("B1", "B", 1, SeatCategory.GOLD, "S1"));
        screen1.addSeat(new Seat("B2", "B", 2, SeatCategory.GOLD, "S1"));
        screen1.addSeat(new Seat("C1", "C", 1, SeatCategory.PLATINUM, "S1"));
        theatre.addScreen(screen1);

        Movie movie = new Movie("M1", "Interstellar", 169);
        Show show = new Show("SH1", movie, screen1, new Date());

        BookingService bookingService = new BookingService();
        bookingService.initializeShowSeats(show);

        User alice = new User("U1", "Alice", "alice@email.com");
        User bob = new User("U2", "Bob", "bob@email.com");

        bookingService.printAvailableSeats(show);

        // Alice books B1 and C1
        Booking aliceBooking = bookingService.createBooking(alice, show,
                new ArrayList<>(Arrays.asList("B1", "C1")));

        bookingService.printAvailableSeats(show);

        // Bob tries to book B1 (locked by Alice) — should throw
        try {
            bookingService.createBooking(bob, show, new ArrayList<>(Arrays.asList("B1")));
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage() + "\n");
        }

        // Alice confirms
        bookingService.confirmBooking(aliceBooking.id);

        // Bob books available seats
        Booking bobBooking = bookingService.createBooking(bob, show,
                new ArrayList<>(Arrays.asList("A1", "A2")));
        bookingService.confirmBooking(bobBooking.id);

        bookingService.printAvailableSeats(show);

        // Alice cancels — seats go back to AVAILABLE
        bookingService.cancelBooking(aliceBooking.id);

        bookingService.printAvailableSeats(show);

        // Try cancelling again — should throw
        try {
            bookingService.cancelBooking(aliceBooking.id);
        } catch (RuntimeException e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
    }
}
