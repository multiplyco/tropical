package co.multiply.tropical.runtime;

import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/**
 * Recent stack traces for one island definition, shared by its session pumps.
 * Claiming a reference, expiry and eviction are one synchronized operation;
 * no logging or application code runs under the monitor. Ordinary repeats do
 * not consult this cache. Entries retain neither throwables nor sessions.
 */
public final class TraceCache {
    private record Key(boolean errorView, Class<?> type, StackTraceElement site) {}
    private record Trace(String reference, long at) {}

    private final int capacity;
    private final long windowNanos;
    private final LongSupplier clock;
    private final LinkedHashMap<Key, Trace> traces = new LinkedHashMap<>();

    public TraceCache() {
        this(64, 60_000_000_000L, System::nanoTime);
    }

    /** Explicit bounds and a monotonic clock, also used by deterministic tests. */
    public TraceCache(int capacity, long windowNanos, LongSupplier clock) {
        if (capacity < 1 || windowNanos < 0) {
            throw new IllegalArgumentException("Positive capacity and nonnegative trace window required");
        }
        this.capacity = capacity;
        this.windowNanos = windowNanos;
        this.clock = clock;
    }

    /**
     * The recent reference for this trace, or null when the caller must log it.
     * A missing stack cannot stand for a later, available trace.
     */
    public synchronized String claim(boolean errorView, Class<?> type, StackTraceElement site, String reference) {
        if (site == null) {
            return null;
        }
        long now = clock.getAsLong();
        // In insertion order, expired entries precede all still-recent ones.
        while (!traces.isEmpty() && now - traces.firstEntry().getValue().at >= windowNanos) {
            traces.pollFirstEntry();
        }
        Key key = new Key(errorView, type, site);
        Trace prior = traces.get(key);
        if (prior != null) {
            return prior.reference;
        }
        if (traces.size() == capacity) {
            traces.pollFirstEntry();
        }
        traces.put(key, new Trace(reference, now));
        return null;
    }
}
