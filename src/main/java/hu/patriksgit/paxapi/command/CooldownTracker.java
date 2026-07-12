package hu.patriksgit.paxapi.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key cooldown state, keyed by a caller-supplied {@code String} (typically a sender identity
 * from {@link SenderAdapter#identity}). One tracker instance is meant to back one gated command —
 * see {@link CommandSpec.Builder#cooldown}.
 *
 * <p>Never spawns threads: {@link #evictOlderThan} must be called by the owner on its own schedule.
 */
public final class CooldownTracker {
    private final ConcurrentHashMap<String, Instant> lastUse = new ConcurrentHashMap<>();
    private final Clock clock;

    public CooldownTracker() { this(Clock.systemUTC()); }

    // package-private: lets tests inject a controllable clock without real sleeps
    CooldownTracker(Clock clock) { this.clock = clock; }

    /**
     * Atomically checks whether {@code key} may act again given {@code cooldown}, and if so
     * records the current instant as its new last-use time. A denied attempt performs no
     * mutation — the window always anchors to the last PERMITTED use.
     *
     * @return {@link Duration#ZERO} if acquired; otherwise the remaining wait time (positive).
     */
    public Duration tryAcquire(String key, Duration cooldown) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(cooldown, "cooldown");
        Instant now = clock.instant();
        Instant[] blockedAnchor = new Instant[1]; // non-null iff blocked; holds the OLD (unchanged) timestamp

        lastUse.compute(key, (k, last) -> {
            if (last == null || !now.isBefore(last.plus(cooldown))) {
                return now;          // allowed: first use, or cooldown fully elapsed — record now
            }
            blockedAnchor[0] = last; // blocked: capture the OLD value for the remaining-time calc...
            return last;             // ...and return it UNCHANGED — no mutation, same reference re-stored
        });

        if (blockedAnchor[0] == null) return Duration.ZERO;
        return Duration.between(now, blockedAnchor[0].plus(cooldown)); // remaining computed from the OLD anchor
    }

    /** Caller-scheduled cleanup — no internal thread (library never spawns threads). */
    public void evictOlderThan(Duration age) {
        Objects.requireNonNull(age, "age");
        Instant cutoff = clock.instant().minus(age);
        lastUse.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }
}
