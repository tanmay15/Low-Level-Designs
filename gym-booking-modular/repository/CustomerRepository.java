package repository;

import models.Customer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class CustomerRepository {

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static CustomerRepository instance;

    private CustomerRepository() {}

    public static CustomerRepository getInstance() {
        if (instance == null) instance = new CustomerRepository();
        return instance;
    }

    // ── In-memory store ────────────────────────────────────────────────────────
    private final Map<String, Customer> store   = new HashMap<>();
    private final AtomicInteger         counter = new AtomicInteger(0);

    public String   nextId()                    { return "CUST-" + counter.incrementAndGet(); }
    public void     save(Customer c)            { store.put(c.getId(), c); }
    public Customer findById(String id)         { return store.get(id); }
    public Collection<Customer> findAll()       { return store.values(); }
}
