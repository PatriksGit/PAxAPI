package hu.patriksgit.paxapi.text;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory SLF4J logger for tests. Captures every logging call (level, message
 * pattern, argument array) so tests can assert on emitted WARNs without a real
 * logging backend. All levels are enabled so the production code's
 * {@code isWarnEnabled()} guards never short-circuit the capture.
 *
 * <p>Mockito is not on the test classpath, so this extends {@link AbstractLogger}
 * (which reduces the SLF4J surface to two abstract methods) instead of mocking.
 */
final class CapturingLogger extends AbstractLogger {

    /** A single captured logging call. */
    static final class Event {
        final Level level;
        final String pattern;
        final Object[] args;
        final Throwable throwable;

        Event(Level level, String pattern, Object[] args, Throwable throwable) {
            this.level = level;
            this.pattern = pattern;
            this.args = args;
            this.throwable = throwable;
        }
    }

    final List<Event> events = new ArrayList<>();

    CapturingLogger() {
        this.name = "CapturingLogger";
    }

    /** All captured events whose level is WARN. */
    List<Event> warns() {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.level == Level.WARN) out.add(e);
        }
        return out;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                               Object[] arguments, Throwable throwable) {
        events.add(new Event(level, messagePattern, arguments, throwable));
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return CapturingLogger.class.getName();
    }

    @Override public boolean isTraceEnabled() { return true; }
    @Override public boolean isTraceEnabled(Marker marker) { return true; }
    @Override public boolean isDebugEnabled() { return true; }
    @Override public boolean isDebugEnabled(Marker marker) { return true; }
    @Override public boolean isInfoEnabled() { return true; }
    @Override public boolean isInfoEnabled(Marker marker) { return true; }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public boolean isWarnEnabled(Marker marker) { return true; }
    @Override public boolean isErrorEnabled() { return true; }
    @Override public boolean isErrorEnabled(Marker marker) { return true; }
}
