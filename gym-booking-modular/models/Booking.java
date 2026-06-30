package models;

import enums.BookingStatus;

public class Booking {

    private String        id;
    private String        customerId;
    private String        gymId;
    private String        classId;
    private BookingStatus status;
    private long          bookedAt;

    public Booking(String id, String customerId, String gymId, String classId) {
        this.id         = id;
        this.customerId = customerId;
        this.gymId      = gymId;
        this.classId    = classId;
        this.status     = BookingStatus.CONFIRMED;
        this.bookedAt   = System.currentTimeMillis();
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String        getId()         { return id; }
    public String        getCustomerId() { return customerId; }
    public String        getGymId()      { return gymId; }
    public String        getClassId()    { return classId; }
    public BookingStatus getStatus()     { return status; }
    public long          getBookedAt()   { return bookedAt; }

    // ── Setter (only status changes after creation) ────────────────────────
    public void setStatus(BookingStatus status) { this.status = status; }

    @Override
    public String toString() {
        return String.format("Booking[%s | customer=%s | class=%s | %s]",
                id, customerId, classId, status);
    }
}
