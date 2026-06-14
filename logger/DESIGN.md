# LLD: Logging System

> **Code file:** `LoggerSolution.java` — keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
| # | Requirement |
|---|---|
| 1 | Log messages at severity levels: DEBUG, INFO, WARN, ERROR |
| 2 | Support multiple appenders (output targets): Console, File, Database |
| 3 | Each appender filters messages below its configured minimum level |
| 4 | Log format is configurable per appender: Simple text or JSON |
| 5 | Same named logger is reused across the system (Singleton per name) |

### Non-Functional
- Adding a new appender must not change `Logger` (Open/Closed Principle)
- Adding a new formatter must not change `LogAppender`
- Level-check logic is shared by ALL appenders → extracted into abstract class

### Out of Scope
Async logging, log rotation, remote log shipping, log sampling

---

## Step 2 — Entities

| Entity | Type | Role |
|---|---|---|
| `LogLevel` | Enum | DEBUG / INFO / WARN / ERROR (ordered by ordinal) |
| `LogEntry` | Class | Snapshot of one log event: level, message, timestamp, loggerName |
| `LogFormatter` | Interface | Strategy for formatting a LogEntry into a String |
| `SimpleFormatter` | Class | `[TIME] [LEVEL] [LOGGER] message` |
| `JsonFormatter` | Class | `{"time":"...","level":"...","message":"..."}` |
| `LogAppender` | **Abstract Class** | Template for all appenders: holds minLevel + formatter, runs level check |
| `ConsoleAppender` | Class | Extends LogAppender → writes to stdout |
| `FileAppender` | Class | Extends LogAppender → simulates writing to a named file |
| `DatabaseAppender` | Class | Extends LogAppender → simulates persisting to a DB table |
| `Logger` | Class | Per-name Singleton, holds list of appenders, exposes debug/info/warn/error |

---

## Step 3 — Class Design

### Relationships

```
Logger (Singleton per name)
  └── HAS-A List<LogAppender>
           ├── ConsoleAppender  IS-A LogAppender (abstract)
           ├── FileAppender     IS-A LogAppender (abstract)
           └── DatabaseAppender IS-A LogAppender (abstract)
                  └── each HAS-A LogFormatter (Strategy, swappable)
```

### Attributes and Methods

**`LogEntry`**
- `id`, `loggerName`, `level: LogLevel`, `message`, `timestamp: Date`

**`LogFormatter` (interface)**
- `String format(LogEntry entry)`

**`LogAppender` (abstract class)**
- `protected LogLevel minLevel`
- `protected LogFormatter formatter`
- `public void log(LogEntry entry)` — Template Method: checks level, calls `append()`
- `protected abstract void append(String formattedMessage)`
- `setMinLevel()`, `setFormatter()` — runtime mutability

**`Logger`**
- `private static Map<String, Logger> instances` — Singleton registry
- `private List<LogAppender> appenders`
- `static getLogger(name)`, `addAppender()`, `log()`, `debug()`, `info()`, `warn()`, `error()`

---

## Step 4 — Design Patterns

### 1. Singleton (per name) — `Logger`
`Logger.getLogger("PaymentService")` always returns the same instance.  
Unlike the parking lot Singleton (one global), here we have one instance **per name** — same principle, stored in a `Map<String, Logger>`.

### 2. Strategy — `LogFormatter`
`LogAppender` holds a `LogFormatter` reference. Swap at runtime:
```java
appender.setFormatter(new JsonFormatter()); // no other code changes
```
Any new format (XML, CSV) = new class implementing `LogFormatter`. Nothing else changes.

### 3. Abstract Class + Template Method — `LogAppender`

**This is the key difference from all previous problems.**

```
LogAppender (abstract)
  log(entry) {                          ← fixed skeleton, same for everyone
      if (entry.level >= minLevel)      ← shared logic (would be duplicated otherwise)
          append(formatter.format(entry)) ← delegates to subclass
  }
  abstract void append(formatted)       ← only this part differs per appender
```

**Why abstract class and not interface here?**

| | Interface | Abstract Class |
|---|---|---|
| Can hold fields | ✗ (only constants) | ✓ (`minLevel`, `formatter`) |
| Can have implemented methods | ✓ (Java 8+ default) | ✓ |
| Forces single inheritance | No | Yes |
| Use when | Pure contract, no shared state | Shared state + shared logic + forced override |

All appenders share `minLevel` and `formatter` as **state**, and share the level-checking **logic** in `log()`. Interface cannot hold mutable instance state. Abstract class is the correct choice.

---

## Step 5 — Extensibility

| Change | What to do |
|---|---|
| Add new appender (Slack, Email) | Extend `LogAppender`, implement `append()` only |
| Add new format (XML, CSV) | Implement `LogFormatter`. One line change to pass it to appender |
| Change formatter at runtime | `appender.setFormatter(new XmlFormatter())` |
| Raise log level at runtime | `appender.setMinLevel(LogLevel.ERROR)` |
| Add new log level (TRACE) | Add to enum. `ordinal()` comparison handles ordering automatically |
| Multiple loggers per component | `Logger.getLogger("name")` returns new or existing — Singleton per name handles it |

---

## Key Interview Points

- **Why per-name Singleton?** Same as Log4j/SLF4J. Different components need separate loggers so you can control their levels independently (UserService at DEBUG, PaymentService at WARN).
- **Why abstract class for LogAppender?** Because the level-check is shared code + `minLevel`/`formatter` are shared state — duplicating them in every appender violates DRY.
- **Why interface for LogFormatter?** Formatters have no shared state. They are pure transformation functions. Interface is sufficient.
- **Template Method pattern:** `log()` is the template — it defines the algorithm skeleton. `append()` is the hook — subclasses fill in the one step that differs.
