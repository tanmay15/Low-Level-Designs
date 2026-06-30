package repository;

import models.Gym;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class GymRepository {

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static GymRepository instance;

    private GymRepository() {}

    public static GymRepository getInstance() {
        if (instance == null) instance = new GymRepository();
        return instance;
    }

    // ── In-memory store ────────────────────────────────────────────────────────
    private final Map<String, Gym>  store   = new HashMap<>();
    private final AtomicInteger     counter = new AtomicInteger(0);

    public String nextId()                     { return "GYM-" + counter.incrementAndGet(); }
    public void   save(Gym gym)                { store.put(gym.getId(), gym); }
    public Gym    findById(String id)          { return store.get(id); }
    public void   delete(String id)            { store.remove(id); }
    public Collection<Gym> findAll()           { return store.values(); }
}
