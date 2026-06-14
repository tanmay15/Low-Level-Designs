# LLD: Job Scheduler (Cron Job)

> Implementation: `JobSchedulerSolution.java`

---

## Step 1 — Requirements

### Functional

| # | Requirement |
|---|-------------|
| 1 | Schedule a job to run: immediately (`scheduleNow`), at a future time (`scheduleAt`), or on a recurring interval (`scheduleRecurring`) |
| 2 | Execute jobs at their scheduled time via `tick(nowMs)` |
| 3 | Track job status: PENDING → RUNNING → COMPLETED / FAILED / PENDING (recurring) |
| 4 | Recurring jobs automatically re-compute and re-enqueue after each execution |
| 5 | Query any job's current status by ID via `getJob(jobId)` |

### Non-Functional

| # | Requirement |
|---|-------------|
| 1 | Jobs stored in `PriorityQueue<Job>` sorted by `nextExecutionTime` — O(log n) insert/poll |
| 2 | Schedule type is pluggable (Strategy pattern) — `OneTimeSchedule` vs `RecurringSchedule` |

### Out of Scope
Distributed execution across workers, retry with exponential backoff, job cancellation, cron expression parsing (`0 9 * * MON` syntax), persistence

---

## Step 2 — Cron Job vs Job Scheduler

At LLD level, they are the same problem:

- **Cron job** = OS concept — run a script at fixed times using a cron expression
- **Job Scheduler** = application-level system managing `Task` and `Job` entities

A "cron job" in this implementation is simply a `Job` with a `RecurringSchedule`. The scheduler handles both one-time and recurring jobs identically — the `Schedule` interface abstracts the difference.

---

## Step 3 — The Key Data Structure: PriorityQueue

```
PriorityQueue<Job> ordered by nextExecutionTime (min-heap)

Front of queue always = job due soonest

tick(nowMs):
  while queue.peek().nextExecutionTime <= nowMs:
    job = queue.poll()   // O(log n)
    execute job
    if RECURRING → re-enqueue with new nextExecutionTime  // O(log n)
    if ONE_TIME  → mark COMPLETED, do not re-enqueue
```

**Why not a List or Map?**
- List: O(n) scan to find due jobs
- PriorityQueue: O(log n) insert + O(log n) poll. Front of queue always = earliest due job. No scanning needed.

---

## Step 4 — Entities

| Class | Role |
|-------|------|
| `Task` | What to execute. Reusable — multiple Jobs can use the same Task. Contains a `Runnable action`. |
| `Job` | A scheduled instance of a Task. Implements `Comparable<Job>` for PriorityQueue ordering. |
| `Schedule` | Interface (Strategy) — encapsulates "when to run next" |
| `OneTimeSchedule` | Returns the same fixed `scheduledAtMs` always |
| `RecurringSchedule` | Returns `lastExecutionTime + intervalMs` — next run shifts with each execution |
| `JobScheduler` | Maintains the PriorityQueue and `allJobs` map. Executes via `tick()`. |

### Enums

| Enum | Values |
|------|--------|
| `JobStatus` | PENDING, RUNNING, COMPLETED, FAILED |
| `ScheduleType` | ONE\_TIME, RECURRING |

---

## Step 5 — Job State Machine

```
                      ┌──────────────────────────────┐
                      │          (RECURRING)          │
                      ▼                               │
PENDING ──[tick()]──► RUNNING ──────────────────────►┘ PENDING (re-enqueued)
                         │
                         ├──► COMPLETED  (ONE_TIME, success)
                         │
                         └──► FAILED     (exception thrown)
```

**Key rule from code:** Failed jobs are NOT re-enqueued regardless of schedule type. Only successful recurring jobs get re-enqueued.

---

## Step 6 — Task vs Job Distinction

| | `Task` | `Job` |
|-|--------|-------|
| What it is | Abstract concept of work ("send email") | A specific scheduled run of a Task |
| Reusability | Multiple Jobs can share one Task | One Job per scheduled run |
| State | Stateless (just a `Runnable`) | Has status, nextExecutionTime, executionCount |
| Lifecycle | No lifecycle | PENDING → RUNNING → COMPLETED / FAILED |

```java
// Same Task used by multiple Jobs:
Task sendEmail = new Task("T1", "Send-Email", () -> System.out.println("Email sent"));

Job j1 = scheduler.scheduleAt(sendEmail, now);          // one-time
Job j2 = scheduler.scheduleRecurring(sendEmail, 86400000); // daily recurring
```

---

## Step 7 — Strategy Pattern: `Schedule`

```java
interface Schedule {
    ScheduleType getType();
    long getNextExecutionTime(long lastExecutionTime);
}

// OneTimeSchedule: always returns the same fixed time
class OneTimeSchedule implements Schedule {
    public long getNextExecutionTime(long last) { return scheduledAtMs; }
}

// RecurringSchedule: shifts with each execution
class RecurringSchedule implements Schedule {
    public long getNextExecutionTime(long last) { return last + intervalMs; }
}
```

To add a new schedule type (e.g. "run at 9am every weekday"), implement `Schedule` and pass it to `scheduler.schedule(task, newSchedule)`. No changes to `Job` or `JobScheduler`.

---

## Step 8 — Class Attributes & Methods

### `Task`

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | identifier |
| `name` | String | human-readable label |
| `action` | Runnable | the work to execute |

### `Job` (implements `Comparable<Job>`)

| Member | Type | Description |
|--------|------|-------------|
| `id` | String | JOB-N auto-generated |
| `task` | Task | what to run |
| `schedule` | Schedule | when to run |
| `status` | JobStatus | current state |
| `nextExecutionTime` | long | epoch ms — used for PriorityQueue ordering |
| `lastExecutionTime` | long | set after each run |
| `executionCount` | int | how many times this job has run |
| `lastError` | String | set on FAILED |
| `compareTo(other)` | int | `Long.compare(nextExecutionTime, other.nextExecutionTime)` |

### `JobScheduler`

| Member / Method | Description |
|-----------------|-------------|
| `queue` | `PriorityQueue<Job>` — min-heap by `nextExecutionTime` |
| `allJobs` | `Map<String, Job>` — jobId → Job for status queries |
| `scheduleNow(task)` | convenience: `scheduleAt(task, now)` |
| `scheduleAt(task, runAtMs)` | one-time job at fixed time |
| `scheduleRecurring(task, intervalMs)` | recurring job every `intervalMs` ms |
| `schedule(task, schedule)` | general: create Job, add to queue and allJobs |
| `tick(nowMs)` | execute all jobs with `nextExecutionTime <= nowMs`; re-enqueue recurring |
| `getJob(jobId)` | O(1) status query from `allJobs` map |
| `printStatus()` | display all jobs and their states |

---

## Step 9 — How tick() Works (step by step)

```
tick(nowMs):
  while queue is not empty AND queue.peek().nextExecutionTime <= nowMs:
    job = queue.poll()              // remove front job — O(log n)
    job.status = RUNNING
    try:
      job.task.action.run()         // execute the Runnable
      job.executionCount++
      job.lastExecutionTime = nowMs
      if RECURRING:
        job.nextExecutionTime = schedule.getNextExecutionTime(nowMs)
        job.status = PENDING
        queue.add(job)              // re-enqueue — O(log n)
      else:
        job.status = COMPLETED      // not re-enqueued
    catch Exception:
      job.status = FAILED
      job.lastError = e.getMessage()
      // not re-enqueued
```

---

## Step 10 — Extensibility

| Extension | How |
|-----------|-----|
| Retry on failure | On FAILED: decrement `retriesLeft`, re-enqueue with `nextExecutionTime = now + retryDelayMs` |
| Job cancellation | Add `cancelled` flag on Job; `tick()` skips cancelled jobs when polling |
| Cron expression (`0 9 * * MON`) | Implement `CronSchedule implements Schedule` with a cron parser |
| Priority within same time | Add `priority` field on Job; `compareTo` uses time first, then priority |
| Distributed execution | Replace in-memory PriorityQueue with a distributed queue (Redis sorted set) |

---

## Quick Recall — 3 Main Takeaways

1. **PriorityQueue sorted by `nextExecutionTime`** — this is the entire data structure insight. Front of queue = next job to run. O(log n) insert and poll.

2. **Recurring jobs re-enqueue themselves**: after successful execution, `nextExecutionTime = schedule.getNextExecutionTime(nowMs)` and the job goes back into the queue. One-time jobs just set status = COMPLETED.

3. **Task vs Job**: Task = what to do (reusable, stateless). Job = one scheduled execution of a Task (has its own lifecycle, execution count, and next run time).
