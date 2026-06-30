package repository;

import models.Booking;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class BookingRepository {

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static BookingRepository instance;

    private BookingRepository() {}

    public static BookingRepository getInstance() {
        if (instance == null) instance = new BookingRepository();
        return instance;
    }

    // ── In-memory store ────────────────────────────────────────────────────────
    private final Map<String, Booking> store   = new HashMap<>();
    private final AtomicInteger        counter = new AtomicInteger(0);

    public String  nextId()                     { return "BKG-" + counter.incrementAndGet(); }
    public void    save(Booking b)              { store.put(b.getId(), b); }
    public Booking findById(String id)          { return store.get(id); }
    public Collection<Booking> findAll()        { return store.values(); }
}
