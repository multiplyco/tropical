package co.multiply.tropical.signal;

import clojure.lang.IDeref;
import co.multiply.conc.Link;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * One session's subscription to one signal: a link in the signal's deque, and,
 * while dirty, an entry in the session's {@link Inbox}.
 * <p>
 * The dirty flag is what bounds the inbox. Only the change that sets it pushes
 * the subscription, so a subscription is in its inbox at most once, however
 * fast its signal changes. The inbox clears the flag when it hands the
 * subscription to the session, before the session reads the value. A change
 * after the clear marks it again, so none is missed.
 * <p>
 * A link can't rejoin a deque, so a subscription is used once: cancelled, it is
 * done.
 */
public final class Sub extends Link implements IDeref {

    final Inbox inbox;
    final Signal signal;

    /**
     * What the session subscribed with: the signal, or the ref it wraps.
     */
    public final Object source;

    /**
     * The session's own data for this subscription: which islands read it.
     * Only the session's thread reads or writes it.
     */
    public Object readers;

    private volatile int dirty;

    /**
     * The next entry in the inbox while this one is dirty. Written before the CAS
     * that pushes this subscription, read after the swap that takes it.
     */
    Sub nextDirty;

    private static final VarHandle DIRTY;

    static {
        try {
            DIRTY = MethodHandles.lookup().findVarHandle(Sub.class, "dirty", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    Sub(Inbox inbox, Signal signal, Object source) {
        this.inbox = inbox;
        this.signal = signal;
        this.source = source;
    }

    /**
     * Marks this subscription dirty, pushing it to its inbox unless it already
     * was. The plain read first means repeated changes cost a load, not a CAS.
     */
    void fire() {
        if (dirty == 0 && DIRTY.compareAndSet(this, 0, 1)) {
            inbox.push(this);
        }
    }

    /**
     * Clears the mark. A volatile write, so it is ordered before the session's
     * read of the value.
     */
    void clear() {
        DIRTY.setVolatile(this, 0);
    }

    /**
     * The signal's current value.
     */
    @Override
    public Object deref() {
        return signal.deref();
    }

    /**
     * Unsubscribes. A change already under way may still mark it once.
     */
    public void cancel() {
        signal.unsubscribe(this);
    }
}
