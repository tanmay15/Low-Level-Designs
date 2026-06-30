package models;

import enums.ClassType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GymClass {

    private String      id;
    private String      gymId;
    private ClassType   classType;
    private int         maxLimit;
    private int         startTime;       // minutes from midnight: 420 = 7:00am
    private int         endTime;

    // Mutable state — protected by synchronized(gymClass) in BookingService
    private List<Booking> bookings;
    private Set<String>   bookedCustomerIds;
    private int           confirmedCount;

    public GymClass(String id, String gymId, ClassType classType,
                    int maxLimit, int startTime, int endTime) {
        this.id                = id;
        this.gymId             = gymId;
        this.classType         = classType;
        this.maxLimit          = maxLimit;
        this.startTime         = startTime;
        this.endTime           = endTime;
        this.bookings          = new ArrayList<>();
        this.bookedCustomerIds = new HashSet<>();
        this.confirmedCount    = 0;
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String      getId()                 { return id; }
    public String      getGymId()              { return gymId; }
    public ClassType   getClassType()          { return classType; }
    public int         getMaxLimit()           { return maxLimit; }
    public int         getStartTime()          { return startTime; }
    public int         getEndTime()            { return endTime; }
    public List<Booking> getBookings()         { return bookings; }
    public Set<String> getBookedCustomerIds()  { return bookedCustomerIds; }
    public int         getConfirmedCount()     { return confirmedCount; }

    // ── Mutators (called inside synchronized block in BookingService) ─────────
    public void incrementConfirmedCount() { confirmedCount++; }
    public void decrementConfirmedCount() { confirmedCount--; }

    public String formatTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    @Override
    public String toString() {
        return String.format("Class[%s | %s | %s–%s | slots=%d/%d]",
                id, classType, formatTime(startTime), formatTime(endTime),
                confirmedCount, maxLimit);
    }
}
