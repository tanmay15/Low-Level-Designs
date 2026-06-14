// =============================================================================
// LLD: LOGGING SYSTEM — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Log messages at severity levels: DEBUG, INFO, WARN, ERROR
//   2. Support multiple appenders (output targets): Console, File, Database
//   3. Each appender filters messages below its configured minimum level
//   4. Log format is configurable per appender: Simple text or JSON
//   5. Same named logger is reused across the system (Singleton per name)
//
// Non-Functional:
//   - Adding a new appender must not change Logger (Open/Closed Principle)
//   - Adding a new formatter must not change LogAppender
//   - Level-check logic is common to ALL appenders → Abstract Class (Template Method)
//
// Out of scope: Async logging, log rotation, remote log shipping, log sampling
// =============================================================================

import java.util.*;
import java.text.SimpleDateFormat;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum LogLevel { DEBUG, INFO, WARN, ERROR }
// ordinal() gives 0,1,2,3 — used for level comparison (>= check)


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entity:         LogEntry
// Interface:      LogFormatter        (Strategy pattern)
// Abstract Class: LogAppender         (Template Method pattern)
// Concrete:       ConsoleAppender, FileAppender, DatabaseAppender
// Singleton:      Logger (per-name)
//
// Relationships:
//   Logger       HAS-A (Composition)   List<LogAppender>
//   LogAppender  HAS-A (Aggregation)   LogFormatter     → swappable
//   LogAppender  HAS-A                 LogLevel         → min filter level
//   ConsoleAppender / FileAppender / DatabaseAppender   IS-A LogAppender
//
// Why Abstract Class here (not interface)?
//   All appenders share TWO fields: minLevel + formatter
//   All appenders share ONE behaviour: level-check in log()
//   Only append() differs per appender → extract common code into abstract class
//   Interface cannot hold state (fields) or implemented methods (pre-Java 8 default)
// =============================================================================


// ── LogEntry ──────────────────────────────────────────────────────────────────

class LogEntry {
    public String id;
    public String loggerName;
    public LogLevel level;
    public String message;
    public Date timestamp;

    public LogEntry(String id, String loggerName, LogLevel level, String message) {
        this.id = id;
        this.loggerName = loggerName;
        this.level = level;
        this.message = message;
        this.timestamp = new Date();
    }
}


// ── LogFormatter (Strategy Pattern) ──────────────────────────────────────────
// Each formatter encapsulates a formatting algorithm.
// Swap formatter on any appender at runtime with no other changes.

interface LogFormatter {
    String format(LogEntry entry);
}

class SimpleFormatter implements LogFormatter {
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

    @Override
    public String format(LogEntry entry) {
        return String.format("[%s] [%-5s] [%s] %s",
                sdf.format(entry.timestamp),
                entry.level,
                entry.loggerName,
                entry.message);
    }
}

class JsonFormatter implements LogFormatter {
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

    @Override
    public String format(LogEntry entry) {
        return String.format(
                "{\"time\":\"%s\",\"level\":\"%s\",\"logger\":\"%s\",\"message\":\"%s\"}",
                sdf.format(entry.timestamp),
                entry.level,
                entry.loggerName,
                entry.message);
    }
}


// ── LogAppender (Abstract Class — Template Method Pattern) ───────────────────
// WHY ABSTRACT CLASS HERE?
//   All appenders need: minLevel (field), formatter (field), level-check logic.
//   Duplicating that in ConsoleAppender, FileAppender, DatabaseAppender is wrong.
//   Abstract class lets us define the shared state + the shared template once.
//
// Template Method: log() is the fixed skeleton — calls abstract append().
//   Subclass only overrides append() to deliver to its specific destination.

abstract class LogAppender {
    protected LogLevel minLevel;   // shared field — every appender has a filter level
    protected LogFormatter formatter; // shared field — every appender has a formatter

    public LogAppender(LogLevel minLevel, LogFormatter formatter) {
        this.minLevel = minLevel;
        this.formatter = formatter;
    }

    // Template Method — fixed algorithm skeleton, same for ALL appenders
    public void log(LogEntry entry) {
        if (entry.level.ordinal() >= minLevel.ordinal()) { // filter by level
            append(formatter.format(entry));               // delegate formatting + delivery
        }
    }

    // The one thing each appender does differently — WHERE to write
    protected abstract void append(String formattedMessage);

    public void setMinLevel(LogLevel level) { this.minLevel = level; }
    public void setFormatter(LogFormatter formatter) { this.formatter = formatter; }
}


// ── ConsoleAppender ───────────────────────────────────────────────────────────
// Delivers logs to standard output.

class ConsoleAppender extends LogAppender {
    public ConsoleAppender(LogLevel minLevel, LogFormatter formatter) {
        super(minLevel, formatter);
    }

    @Override
    protected void append(String formattedMessage) {
        System.out.println("[CONSOLE] " + formattedMessage);
    }
}


// ── FileAppender ──────────────────────────────────────────────────────────────
// Simulates writing to a named log file. Stores lines in memory (interview-safe).

class FileAppender extends LogAppender {
    private String fileName;
    private List<String> logLines; // simulates file storage

    public FileAppender(String fileName, LogLevel minLevel, LogFormatter formatter) {
        super(minLevel, formatter);
        this.fileName = fileName;
        this.logLines = new ArrayList<>();
    }

    @Override
    protected void append(String formattedMessage) {
        logLines.add(formattedMessage);
        System.out.println("[FILE:" + fileName + "] " + formattedMessage);
    }

    public void dumpFile() {
        System.out.println("\n=== Contents of " + fileName + " (" + logLines.size() + " lines) ===");
        for (String line : logLines) System.out.println("  " + line);
        System.out.println();
    }
}


// ── DatabaseAppender ──────────────────────────────────────────────────────────
// Simulates persisting LogEntry records to a database table.

class DatabaseAppender extends LogAppender {
    private List<LogEntry> logStore; // simulates DB rows

    public DatabaseAppender(LogLevel minLevel, LogFormatter formatter) {
        super(minLevel, formatter);
        this.logStore = new ArrayList<>();
    }

    @Override
    protected void append(String formattedMessage) {
        System.out.println("[DB] " + formattedMessage);
    }

    public int getStoredCount() { return logStore.size(); }
}


// ── Logger (Singleton per name) ───────────────────────────────────────────────
// Named loggers are reused — Logger.getLogger("PaymentService") always returns
// the same instance. This is how Log4j / SLF4J work in the real world.

class Logger {
    private static Map<String, Logger> instances = new HashMap<>();

    private String name;
    private List<LogAppender> appenders;
    private int entryCounter;

    private Logger(String name) {
        this.name = name;
        this.appenders = new ArrayList<>();
        this.entryCounter = 0;
    }

    public static Logger getLogger(String name) {
        if (!instances.containsKey(name)) {
            instances.put(name, new Logger(name));
        }
        return instances.get(name);
    }

    public void addAppender(LogAppender appender) {
        appenders.add(appender);
    }

    public void log(LogLevel level, String message) {
        LogEntry entry = new LogEntry("LOG-" + (++entryCounter), name, level, message);
        for (LogAppender appender : appenders) {
            appender.log(entry); // each appender applies its own minLevel filter
        }
    }

    public void debug(String message) { log(LogLevel.DEBUG, message); }
    public void info(String message)  { log(LogLevel.INFO,  message); }
    public void warn(String message)  { log(LogLevel.WARN,  message); }
    public void error(String message) { log(LogLevel.ERROR, message); }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: LoggerSolution.java
// =============================================================================

public class LoggerSolution {
    public static void main(String[] args) {
        System.out.println("=== Logging System Demo ===\n");

        // ── Setup ────────────────────────────────────────────────────────────
        // ConsoleAppender: shows INFO and above, simple format
        ConsoleAppender console = new ConsoleAppender(LogLevel.INFO, new SimpleFormatter());

        // FileAppender: captures WARN and above, JSON format (for log parsers)
        FileAppender file = new FileAppender("app.log", LogLevel.WARN, new JsonFormatter());

        // DatabaseAppender: captures ERROR only, simple format (for alerting)
        DatabaseAppender db = new DatabaseAppender(LogLevel.ERROR, new SimpleFormatter());

        // Named logger — reused across the app for "PaymentService"
        Logger paymentLogger = Logger.getLogger("PaymentService");
        paymentLogger.addAppender(console);
        paymentLogger.addAppender(file);
        paymentLogger.addAppender(db);

        System.out.println("── Logging at various levels ──");
        paymentLogger.debug("Entering processPayment()");   // filtered by all (below INFO)
        paymentLogger.info("Processing payment for order #1001"); // console only
        paymentLogger.warn("Payment gateway response slow (2.3s)"); // console + file
        paymentLogger.error("Payment failed: timeout after 5s");    // all three

        System.out.println();

        // ── Same logger instance returned for same name ──
        Logger sameLogger = Logger.getLogger("PaymentService");
        System.out.println("Same logger instance: " + (paymentLogger == sameLogger)); // true

        System.out.println();

        // ── Second logger for a different component ──
        System.out.println("── UserService logger (console only, DEBUG+) ──");
        Logger userLogger = Logger.getLogger("UserService");
        userLogger.addAppender(new ConsoleAppender(LogLevel.DEBUG, new SimpleFormatter()));

        userLogger.debug("Looking up user ID: U42");  // shown (DEBUG >= DEBUG)
        userLogger.info("User U42 found");
        userLogger.warn("User U42 has unverified email");

        System.out.println();

        // ── Dump file contents ──
        file.dumpFile();

        // ── Runtime formatter swap on console appender ──
        System.out.println("── Switching console to JSON format ──");
        console.setFormatter(new JsonFormatter());
        paymentLogger.info("Formatter changed to JSON at runtime");

        System.out.println();

        // ── Raise console minimum level to ERROR only ──
        System.out.println("── Raising console minLevel to ERROR ──");
        console.setMinLevel(LogLevel.ERROR);
        paymentLogger.info("This info will be filtered out");  // filtered
        paymentLogger.error("This error still shows");
    }
}
