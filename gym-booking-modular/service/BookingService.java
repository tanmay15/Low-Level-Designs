package service;

import enums.BookingStatus;
import models.Booking;
import models.Customer;
import models.GymClass;
import repository.BookingRepository;
import repository.CustomerRepository;
import repository.GymClassRepository;

import java.util.List;

public class BookingService {

    private final GymClassRepository classRepo    = GymClassRepository.getInstance();
    private final CustomerRepository customerRepo = CustomerRepository.getInstance();
    private final BookingRepository  bookingRepo  = BookingRepository.getInstance();

    // ── Customer registration ─────────────────────────────────────────────────

    public Customer registerCustomer(String name) {
        String   id       = customerRepo.nextId();
        Customer customer = new Customer(id, name);
        customerRepo.save(customer);
        System.out.println("[BOOKING] Registered: " + customer);
        return customer;
    }

    // ── Book class ────────────────────────────────────────────────────────────
    // THREAD-SAFE: synchronized(gymClass) — fine-grained lock per class.
    // Protects confirmedCount and bookedCustomerIds from concurrent modification.

    public Booking bookClass(String customerId, String gymId, String classId) {
        Customer customer = customerRepo.findById(customerId);
        GymClass gymClass = classRepo.findById(classId);

        if (customer == null) {
            System.out.println("[BOOKING] Customer not found: " + customerId);
            return null;
        }
        if (gymClass == null || !gymClass.getGymId().equals(gymId)) {
            System.out.println("[BOOKING] Class not found in gym: " + classId);
            return null;
        }

        // CRITICAL SECTION — lock on specific GymClass object
        synchronized (gymClass) {

            if (gymClass.getBookedCustomerIds().contains(customerId)) {
                System.out.println("[BOOKING] " + customer.getName()
                        + " already booked class " + classId);
                return null;
            }

            if (gymClass.getConfirmedCount() >= gymClass.getMaxLimit()) {
                System.out.println("[BOOKING] Class " + classId + " is FULL ("
                        + gymClass.getConfirmedCount() + "/" + gymClass.getMaxLimit() + ")");
                return null;
            }

            String  bookingId = bookingRepo.nextId();
            Booking booking   = new Booking(bookingId, customerId, gymId, classId);

            gymClass.getBookings().add(booking);
            gymClass.getBookedCustomerIds().add(customerId);
            gymClass.incrementConfirmedCount();

            customer.getBookings().add(booking);
            bookingRepo.save(booking);

            System.out.println("[BOOKING] Confirmed: " + booking
                    + " | slots=" + gymClass.getConfirmedCount() + "/" + gymClass.getMaxLimit());
            return booking;
        }
    }

    // ── Get all bookings ──────────────────────────────────────────────────────

    public List<Booking> getAllBookings(String customerId) {
        Customer customer = customerRepo.findById(customerId);
        if (customer == null) { System.out.println("[BOOKING] Not found"); return null; }

        System.out.println("[BOOKING] History for " + customer.getName() + ":");
        customer.getBookings().forEach(b -> System.out.println("  " + b));
        return customer.getBookings();
    }

    // ── Cancel booking ────────────────────────────────────────────────────────
    // THREAD-SAFE: same lock (gymClass) as bookClass — protects shared state.

    public void cancelBooking(String bookingId) {
        Booking  booking  = bookingRepo.findById(bookingId);
        if (booking == null) { System.out.println("[CANCEL] Not found: " + bookingId); return; }

        GymClass gymClass = classRepo.findById(booking.getClassId());

        if (gymClass != null) {
            synchronized (gymClass) {
                if (booking.getStatus() == BookingStatus.CANCELLED) {
                    System.out.println("[CANCEL] Already cancelled");
                    return;
                }
                booking.setStatus(BookingStatus.CANCELLED);
                gymClass.decrementConfirmedCount();
                gymClass.getBookedCustomerIds().remove(booking.getCustomerId());
            }
        } else {
            booking.setStatus(BookingStatus.CANCELLED);
        }

        System.out.println("[CANCEL] Cancelled: " + bookingId
                + (gymClass != null ? " | slots=" + gymClass.getConfirmedCount()
                                                 + "/" + gymClass.getMaxLimit() : ""));
    }
}
