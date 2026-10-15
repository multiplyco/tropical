package co.multiply.tropical.runtime;

import java.util.IdentityHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One mounted island's consecutive failures, owned only by its session pump.
 * Render failures and each error view have independent counters and trace
 * decisions, under the same user-facing reference. No throwable is retained.
 */
public final class Failure {
    private static final String REF_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public final String reference;
    public final Object island;
    public final String id;
    public final String elementId;
    public final Attempt render;
    private final TraceCache traces;
    private IdentityHashMap<Object, Attempt> views;

    /** The log decision for the latest failure in one sequence of attempts. */
    public static final class Attempt {
        private final Class<?> type;
        private StackTraceElement site;
        public long count;
        public boolean log;
        public boolean trace;
        public String tracedAs;

        private Attempt(Throwable error) {
            type = error.getClass();
        }

        private boolean matches(Throwable error, StackTraceElement nextSite) {
            return type == error.getClass() && (site == null || nextSite == null || site.equals(nextSite));
        }

        private void failed(TraceCache traces, boolean errorView, String reference, StackTraceElement nextSite) {
            boolean first = count == 0;
            boolean learnedSite = site == null && nextSite != null;
            if (learnedSite) {
                site = nextSite;
            }
            if (count < Long.MAX_VALUE) {
                count++;
            }
            log = first || learnedSite || (count & (count - 1)) == 0;
            trace = false;
            tracedAs = null;
            if (first || learnedSite) {
                tracedAs = traces.claim(errorView, type, site, reference);
                trace = tracedAs == null;
            }
        }
    }

    private Failure(TraceCache traces, Throwable error, Object island, String id, String elementId) {
        this.traces = traces;
        this.island = island;
        this.id = id;
        this.elementId = elementId;
        this.reference = reference();
        this.render = new Attempt(error);
    }

    /** Keep the reference until recovery, a different failure, or a new definition. */
    public static Failure failed(Failure previous, TraceCache traces, Throwable error,
                                 Object island, String id, String elementId) {
        StackTraceElement site = throwSite(error);
        Failure run = previous != null && previous.traces == traces && previous.render.matches(error, site)
            ? previous : new Failure(traces, error, island, id, elementId);
        run.render.failed(traces, false, run.reference, site);
        return run;
    }

    public Attempt viewFailed(Object view, Throwable error) {
        if (views == null) {
            views = new IdentityHashMap<>();
        }
        StackTraceElement site = throwSite(error);
        Attempt attempt = views.get(view);
        if (attempt == null || !attempt.matches(error, site)) {
            attempt = new Attempt(error);
            views.put(view, attempt);
        }
        attempt.failed(traces, true, reference, site);
        return attempt;
    }

    public void viewRendered(Object view) {
        if (views != null) {
            views.remove(view);
        }
    }

    private static StackTraceElement throwSite(Throwable error) {
        StackTraceElement[] trace = error.getStackTrace();
        return trace.length == 0 ? null : trace[0];
    }

    /** Forty random bits in Crockford's base 32, short enough to read out. */
    private static String reference() {
        char[] chars = new char[9];
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = i == 4 ? '-' : REF_CHARS.charAt(random.nextInt(32));
        }
        return new String(chars);
    }
}
