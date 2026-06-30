package repository;

import models.GymClass;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class GymClassRepository {

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static GymClassRepository instance;

    private GymClassRepository() {}

    public static GymClassRepository getInstance() {
        if (instance == null) instance = new GymClassRepository();
        return instance;
    }

    // ── In-memory store ────────────────────────────────────────────────────────
    private final Map<String, GymClass> store   = new HashMap<>();
    private final AtomicInteger         counter = new AtomicInteger(0);

    public String   nextId()                    { return "CLS-" + counter.incrementAndGet(); }
    public void     save(GymClass gc)           { store.put(gc.getId(), gc); }
    public GymClass findById(String id)         { return store.get(id); }
    public void     delete(String id)           { store.remove(id); }
    public Collection<GymClass> findAll()       { return store.values(); }
}
