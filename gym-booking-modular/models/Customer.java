package models;

import java.util.ArrayList;
import java.util.List;

public class Customer {

    private String        id;
    private String        name;
    private List<Booking> bookings;

    public Customer(String id, String name) {
        this.id       = id;
        this.name     = name;
        this.bookings = new ArrayList<>();
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String        getId()       { return id; }
    public String        getName()     { return name; }
    public List<Booking> getBookings() { return bookings; }

    @Override
    public String toString() {
        return String.format("Customer[%s | %s]", id, name);
    }
}
