package co.multiply.tropical.signal;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IRef;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A signal over a Clojure ref (an atom, or anything supporting
 * {@code add-watch}), so page code can keep using atoms.
 * <p>
 * There is one per ref, with one watch on it, however many sessions read it. It
 * is created by the first subscription and removed with the last one. Reading
 * it reads the ref, not the value the watch was called with, so watches that
 * run out of order can't leave it stale.
 */
public final class RefSignal extends Signal {

    private static final ConcurrentHashMap<IRef, RefSignal> SIGNALS = new ConcurrentHashMap<>();

    private static final IFn WATCH = new AFn() {
        @Override
        public Object invoke(Object key, Object ref, Object old, Object curr) {
            if (old != curr) {
                ((RefSignal) key).invalidate();
            }
            return null;
        }
    };

    private final IRef ref;

    private RefSignal(IRef ref) {
        this.ref = ref;
        ref.addWatch(this, WATCH);
    }

    @Override
    public Object deref() {
        return ref.deref();
    }

    /**
     * Subscribes {@code inbox} to {@code ref}. Creating the signal and adding the
     * subscription happen under the map's lock for {@code ref}, so they can't
     * interleave with the last subscription leaving.
     */
    static Sub watch(IRef ref, Inbox inbox) {
        Sub[] sub = new Sub[1];
        SIGNALS.compute(ref, (r, signal) -> {
            RefSignal s = signal != null ? signal : new RefSignal(r);
            sub[0] = s.subscribe(inbox, r);
            return s;
        });
        return sub[0];
    }

    @Override
    void unsubscribe(Sub sub) {
        subs.remove(sub);
        SIGNALS.computeIfPresent(ref, (r, signal) -> {
            if (signal != this || !subs.isEmpty()) {
                return signal;
            }
            r.removeWatch(this);
            return null;
        });
    }

    /**
     * How many refs have a signal: refs that some session reads.
     */
    public static int count() {
        return SIGNALS.size();
    }
}
