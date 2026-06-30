import enums.ClassType;
import models.Booking;
import models.Customer;
import models.Gym;
import models.GymClass;
import service.AdminService;
import service.BookingService;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Gym Booking System (Modular) ===\n");

        AdminService   admin   = new AdminService();
        BookingService booking = new BookingService();

        // ── Admin: setup ───────────────────────────────────────────────────────
        System.out.println("── Admin: Setup ──");
        Gym gym1 = admin.addGym("FitLife Mumbai", "Andheri West");
        Gym gym2 = admin.addGym("PowerZone",      "Bandra");

        GymClass yoga   = admin.addClass(gym1.getId(), ClassType.YOGA,   3, 420, 480);
        GymClass zumba  = admin.addClass(gym1.getId(), ClassType.ZUMBA,  5, 540, 630);
        GymClass cardio = admin.addClass(gym2.getId(), ClassType.CARDIO, 2, 600, 660);

        admin.addClass(gym1.getId(), ClassType.PILATES, 10, 200, 300); // invalid time
        System.out.println();

        // ── Register customers ─────────────────────────────────────────────────
        System.out.println("── Customers ──");
        Customer alice = booking.registerCustomer("Alice");
        Customer bob   = booking.registerCustomer("Bob");
        Customer carol = booking.registerCustomer("Carol");
        Customer dave  = booking.registerCustomer("Dave");
        System.out.println();

        // ── Normal bookings ────────────────────────────────────────────────────
        System.out.println("── Normal Bookings ──");
        booking.bookClass(alice.getId(), gym1.getId(), yoga.getId());
        booking.bookClass(bob.getId(),   gym1.getId(), yoga.getId());
        booking.bookClass(alice.getId(), gym1.getId(), zumba.getId());
        System.out.println();

        // ── Edge cases ────────────────────────────────────────────────────────
        System.out.println("── Edge Cases ──");
        booking.bookClass(alice.getId(), gym1.getId(), yoga.getId()); // duplicate → rejected
        booking.bookClass(alice.getId(), gym2.getId(), cardio.getId());
        booking.bookClass(bob.getId(),   gym2.getId(), cardio.getId());
        booking.bookClass(carol.getId(), gym2.getId(), cardio.getId()); // full → rejected
        System.out.println();

        // ── Concurrency: race for last slot ───────────────────────────────────
        System.out.println("── Concurrency: Race for Last Yoga Slot ──");
        System.out.println("  Yoga before race: " + yoga);

        Thread t1 = new Thread(() -> booking.bookClass(carol.getId(), gym1.getId(), yoga.getId()));
        Thread t2 = new Thread(() -> booking.bookClass(dave.getId(),  gym1.getId(), yoga.getId()));
        t1.start(); t2.start();
        t1.join();  t2.join();

        System.out.println("  Yoga after race:  " + yoga);
        System.out.println();

        // ── Cancel & re-book ───────────────────────────────────────────────────
        System.out.println("── Cancel Then Re-book ──");
        Booking bobYoga = bob.getBookings().stream()
                .filter(b -> b.getClassId().equals(yoga.getId())).findFirst().orElse(null);
        if (bobYoga != null) booking.cancelBooking(bobYoga.getId());
        booking.bookClass(carol.getId(), gym1.getId(), yoga.getId());
        booking.bookClass(dave.getId(),  gym1.getId(), yoga.getId());
        System.out.println();

        // ── Customer history ───────────────────────────────────────────────────
        System.out.println("── Alice's Booking History ──");
        booking.getAllBookings(alice.getId());
        System.out.println();

        // ── Admin removes gym: cascades to cancel all bookings ────────────────
        System.out.println("── Admin Removes Gym2 (cascade cancel) ──");
        admin.removeGym(gym2.getId());
        System.out.println("  Alice after gym2 removed:");
        booking.getAllBookings(alice.getId());
    }
}
