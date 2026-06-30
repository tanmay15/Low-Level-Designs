package service;

import enums.BookingStatus;
import enums.ClassType;
import models.Booking;
import models.Gym;
import models.GymClass;
import repository.BookingRepository;
import repository.GymClassRepository;
import repository.GymRepository;

import java.util.ArrayList;

public class AdminService {

    // Services use repositories — never create their own Maps
    private final GymRepository      gymRepo   = GymRepository.getInstance();
    private final GymClassRepository classRepo = GymClassRepository.getInstance();
    private final BookingRepository  bkgRepo   = BookingRepository.getInstance();

    // ── Gym operations ────────────────────────────────────────────────────────

    public Gym addGym(String name, String location) {
        String id  = gymRepo.nextId();
        Gym    gym = new Gym(id, name, location);
        gymRepo.save(gym);
        System.out.println("[ADMIN] Added: " + gym);
        return gym;
    }

    // Cascade: remove all classes → cancel all their bookings
    public void removeGym(String gymId) {
        Gym gym = gymRepo.findById(gymId);
        if (gym == null) { System.out.println("[ADMIN] Gym not found: " + gymId); return; }

        for (String classId : new ArrayList<>(gym.getClassIds())) {
            removeClass(gymId, classId);
        }
        gymRepo.delete(gymId);
        System.out.println("[ADMIN] Removed gym: " + gymId);
    }

    // ── Class operations ──────────────────────────────────────────────────────

    public GymClass addClass(String gymId, ClassType classType,
                             int maxLimit, int startTime, int endTime) {
        Gym gym = gymRepo.findById(gymId);
        if (gym == null) { System.out.println("[ADMIN] Gym not found: " + gymId); return null; }

        if (startTime < 360 || endTime > 1200 || startTime >= endTime) {
            System.out.println("[ADMIN] Invalid time: classes must be between 06:00 and 20:00");
            return null;
        }

        String   id       = classRepo.nextId();
        GymClass gymClass = new GymClass(id, gymId, classType, maxLimit, startTime, endTime);
        classRepo.save(gymClass);
        gym.getClassIds().add(id);

        System.out.println("[ADMIN] Added: " + gymClass);
        return gymClass;
    }

    // Cascade: cancel all confirmed bookings for this class
    public void removeClass(String gymId, String classId) {
        Gym      gym      = gymRepo.findById(gymId);
        GymClass gymClass = classRepo.findById(classId);
        if (gym == null || gymClass == null) { System.out.println("[ADMIN] Not found"); return; }

        synchronized (gymClass) {
            for (Booking b : gymClass.getBookings()) {
                if (b.getStatus() == BookingStatus.CONFIRMED) {
                    b.setStatus(BookingStatus.CANCELLED);
                    System.out.println("[ADMIN] Auto-cancelled: " + b.getId());
                }
            }
        }
        gym.getClassIds().remove(classId);
        classRepo.delete(classId);
        System.out.println("[ADMIN] Removed class: " + classId);
    }
}
