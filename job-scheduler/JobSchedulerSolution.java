// =============================================================================
// LLD: JOB SCHEDULER (= Cron Job system at LLD level)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Schedule a job to run: immediately, at a future time, or on a recurring interval
//   2. Execute jobs at their scheduled time
//   3. Track job status: PENDING → RUNNING → COMPLETED / FAILED
//   4. Recurring jobs automatically re-schedule after each execution
//   5. Query job status by ID
//
// Non-Functional:
//   - Jobs sorted by next execution time (PriorityQueue) — O(log n) insert/poll
//   - Schedule type is pluggable (Strategy pattern)
//
// Out of scope: distributed execution, retry with backoff, job cancellation,
//   cron expression parsing (we use interval-based scheduling), persistence
//
// KEY INSIGHT — PriorityQueue:
//   Jobs are stored in a min-heap ordered by nextExecutionTime.
//   The scheduler always checks the front: if now >= job.nextExecutionTime → run it.
//   This is O(log n) for all operations. Much better than scanning all jobs.
//
// CRON JOB vs JOB SCHEDULER:
//   At LLD level they are the same. A "cron job" is just a RECURRING job
//   with a fixed interval. The scheduler handles both one-time and recurring.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum JobStatus       { PENDING, RUNNING, COMPLETED, FAILED }
enum ScheduleType    { ONE_TIME, RECURRING }


// =============================================================================
// STRATEGY PATTERN — Schedule
// =============================================================================
// Encapsulates "when should this job run next?"
// Swappable without changing Job or JobScheduler.

interface Schedule {
    ScheduleType getType();
    long         getNextExecutionTime(long lastExecutionTime);
}

// ONE_TIME: run once at a specific time, then done.
class OneTimeSchedule implements Schedule {
    private long scheduledAtMs;

    public OneTimeSchedule(long scheduledAtMs) {
        this.scheduledAtMs = scheduledAtMs;
    }

    @Override public ScheduleType getType() { return ScheduleType.ONE_TIME; }

    @Override
    public long getNextExecutionTime(long lastExecutionTime) {
        return scheduledAtMs; // always the same fixed time
    }
}

// RECURRING: run every `intervalMs` milliseconds.
// This is what a "cron job" is at the LLD level.
class RecurringSchedule implements Schedule {
    private long intervalMs;

    public RecurringSchedule(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    @Override public ScheduleType getType() { return ScheduleType.RECURRING; }

    @Override
    public long getNextExecutionTime(long lastExecutionTime) {
        return lastExecutionTime + intervalMs;  // next run = last run + interval
    }
}


// =============================================================================
// ENTITIES
// =============================================================================

// ── Task ──────────────────────────────────────────────────────────────────────
// What to execute. Reusable — multiple Jobs can use the same Task.
// Runnable = standard Java functional interface for "do something"

class Task {
    public String   id;
    public String   name;
    public Runnable action; // the actual work

    public Task(String id, String name, Runnable action) {
        this.id     = id;
        this.name   = name;
        this.action = action;
    }
}

// ── Job ───────────────────────────────────────────────────────────────────────
// A scheduled instance of a Task. Has its own lifecycle (status state machine).
// Implements Comparable so PriorityQueue orders by nextExecutionTime.
//
// STATE MACHINE: PENDING → RUNNING → COMPLETED (one-time)
//                PENDING → RUNNING → PENDING   (recurring — re-enqueued)
//                PENDING → RUNNING → FAILED    (on exception)

class Job implements Comparable<Job> {
    public String    id;
    public Task      task;
    public Schedule  schedule;
    public JobStatus status;
    public long      nextExecutionTime;
    public long      lastExecutionTime;
    public int       executionCount;
    public String    lastError;

    public Job(String id, Task task, Schedule schedule) {
        this.id                = id;
        this.task              = task;
        this.schedule          = schedule;
        this.status            = JobStatus.PENDING;
        this.nextExecutionTime = schedule.getNextExecutionTime(System.currentTimeMillis());
        this.executionCount    = 0;
    }

    @Override
    public int compareTo(Job other) {
        return Long.compare(this.nextExecutionTime, other.nextExecutionTime);
    }

    @Override
    public String toString() {
        return String.format("Job[%s | task=%s | status=%s | runs=%d | next=%s]",
                id, task.name, status, executionCount,
                status == JobStatus.COMPLETED ? "N/A" :
                new Date(nextExecutionTime).toString());
    }
}


// =============================================================================
// JOB SCHEDULER
// =============================================================================
// Maintains a PriorityQueue<Job> ordered by nextExecutionTime.
// tick(nowMs) runs all jobs due at or before `nowMs`.
// Recurring jobs are re-enqueued after execution.
// One-time jobs are marked COMPLETED and not re-enqueued.

class JobScheduler {
    // Min-heap: job with smallest nextExecutionTime is at the front
    private PriorityQueue<Job>  queue;
    private Map<String, Job>    allJobs;     // jobId → Job (for status queries)
    private int                 jobCounter;

    public JobScheduler() {
        this.queue      = new PriorityQueue<>();
        this.allJobs    = new HashMap<>();
        this.jobCounter = 0;
    }

    // ── Schedule a job ────────────────────────────────────────────────────────

    public Job scheduleNow(Task task) {
        return schedule(task, new OneTimeSchedule(System.currentTimeMillis()));
    }

    public Job scheduleAt(Task task, long runAtMs) {
        return schedule(task, new OneTimeSchedule(runAtMs));
    }

    public Job scheduleRecurring(Task task, long intervalMs) {
        return schedule(task, new RecurringSchedule(intervalMs));
    }

    public Job schedule(Task task, Schedule schedule) {
        String jobId = "JOB-" + (++jobCounter);
        Job    job   = new Job(jobId, task, schedule);
        queue.add(job);
        allJobs.put(jobId, job);
        System.out.println("  [SCHEDULED] " + job.id + " | task=" + task.name
                + " | type=" + schedule.getType()
                + " | firstRun=" + new Date(job.nextExecutionTime));
        return job;
    }

    // ── tick() — simulates the scheduler loop ─────────────────────────────────
    // In production: runs in a background thread, calling tick(System.currentTimeMillis())
    // continuously. In LLD demo: called manually with explicit timestamps.
    //
    // Polls front of queue: if job.nextExecutionTime <= nowMs → execute it.

    public void tick(long nowMs) {
        System.out.println("  [TICK] " + new Date(nowMs));

        while (!queue.isEmpty() && queue.peek().nextExecutionTime <= nowMs) {
            Job job = queue.poll(); // O(log n)

            job.status = JobStatus.RUNNING;
            System.out.print("    Executing " + job.id + " (" + job.task.name + ")... ");

            try {
                job.task.action.run();
                job.executionCount++;
                job.lastExecutionTime = nowMs;

                if (job.schedule.getType() == ScheduleType.RECURRING) {
                    // Recurring: compute next run time and re-enqueue
                    job.nextExecutionTime = job.schedule.getNextExecutionTime(nowMs);
                    job.status            = JobStatus.PENDING;
                    queue.add(job); // O(log n)
                    System.out.println("→ done. Re-enqueued at " + new Date(job.nextExecutionTime));
                } else {
                    // One-time: mark COMPLETED, do not re-enqueue
                    job.status = JobStatus.COMPLETED;
                    System.out.println("→ done. COMPLETED.");
                }

            } catch (Exception e) {
                job.status    = JobStatus.FAILED;
                job.lastError = e.getMessage();
                System.out.println("→ FAILED: " + e.getMessage());
                // Failed jobs are not re-enqueued (could add retry logic here)
            }
        }
    }

    public Job getJob(String jobId) {
        return allJobs.get(jobId);
    }

    public void printStatus() {
        System.out.println("── Job Status ──");
        for (Job job : allJobs.values()) {
            System.out.println("  " + job);
        }
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class JobSchedulerSolution {
    public static void main(String[] args) {
        System.out.println("=== Job Scheduler Demo ===\n");

        JobScheduler scheduler = new JobScheduler();

        // ── Define Tasks (reusable "what to do") ──────────────────────────────
        Task sendEmail   = new Task("T1", "Send-Email",
                () -> System.out.println("✉ Email sent to customer"));

        Task generateReport = new Task("T2", "Generate-Report",
                () -> System.out.println("📊 Daily report generated"));

        Task cleanupLogs = new Task("T3", "Cleanup-Logs",
                () -> System.out.println("🗑 Old logs deleted"));

        Task failingTask = new Task("T4", "Risky-Task",
                () -> { throw new RuntimeException("Connection timeout"); });

        // ── Schedule jobs ─────────────────────────────────────────────────────
        System.out.println("── Scheduling Jobs ──");

        long now = 1000L; // simulated epoch ms (simplified for readable demo)

        // Run immediately
        Job j1 = scheduler.scheduleAt(sendEmail, now);

        // Run 5ms later (one-time)
        Job j2 = scheduler.scheduleAt(generateReport, now + 5);

        // Recurring every 10ms (simulates "every day at midnight" type cron)
        Job j3 = scheduler.scheduleRecurring(cleanupLogs, 10);

        // One-time job that will fail
        Job j4 = scheduler.scheduleAt(failingTask, now + 2);

        System.out.println();

        // ── Simulate time passing with tick() ─────────────────────────────────
        System.out.println("── Simulating ticks ──");

        scheduler.tick(now);       // t=1000: j1 runs (sendEmail), j3 runs (cleanupLogs at ~1000)
        System.out.println();
        scheduler.tick(now + 2);   // t=1002: j4 runs (failingTask → FAILED)
        System.out.println();
        scheduler.tick(now + 5);   // t=1005: j2 runs (generateReport → COMPLETED)
        System.out.println();
        scheduler.tick(now + 10);  // t=1010: j3 runs again (recurring)
        System.out.println();
        scheduler.tick(now + 20);  // t=1020: j3 runs again (recurring)
        System.out.println();

        // ── Status check ──────────────────────────────────────────────────────
        System.out.println("── Final Status ──");
        scheduler.printStatus();

        System.out.println();
        System.out.println("── Query individual job ──");
        System.out.println("  " + scheduler.getJob(j3.id)); // recurring still PENDING
        System.out.println("  " + scheduler.getJob(j4.id)); // FAILED
    }
}
