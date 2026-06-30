package models;

import java.util.ArrayList;
import java.util.List;

public class Gym {

    private String       id;
    private String       name;
    private String       location;
    private List<String> classIds;

    public Gym(String id, String name, String location) {
        this.id       = id;
        this.name     = name;
        this.location = location;
        this.classIds = new ArrayList<>();
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String       getId()       { return id; }
    public String       getName()     { return name; }
    public String       getLocation() { return location; }
    public List<String> getClassIds() { return classIds; }

    @Override
    public String toString() {
        return String.format("Gym[%s | %s | %s | classes=%d]",
                id, name, location, classIds.size());
    }
}
