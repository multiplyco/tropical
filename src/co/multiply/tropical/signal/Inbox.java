package co.multiply.tropical.signal;

import clojure.lang.IRef;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A session's inbox: the subscriptions marked dirty since the session last
 * looked, and the session's lifecycle events (attach, detach, close).
 * <p>
 * Dirty subscriptions form a lock-free stack threaded through the subscriptions
 * themselves, so marking one allocates nothing. Each is on it at most once, so
 * the inbox never holds more than the session's subscriptions, whatever the
 * rate of change. Events are rare and kept in order, in a queue.
 * <p>
 * One thread consumes it, the owner, which parks in {@link #await(long)} while
 * the inbox is empty. Marking the first subscription after a drain, or posting
 * an event, unparks it. The owner always checks before parking, so an unpark
 * that comes early is never lost.
 */
public final class Inbox {

    private volatile Sub dirty;
    private volatile Thread owner;
    private final ConcurrentLinkedQueue<Object> events = new ConcurrentLinkedQueue<>();

    private static final VarHandle DIRTY;

    static {
        try {
            DIRTY = MethodHandles.lookup().findVarHandle(Inbox.class, "dirty", Sub.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Makes the calling thread the owner: the thread to wake.
     */
    public void bind() {
        owner = Thread.currentThread();
    }

    /**
     * Subscribes to {@code source}: a {@link Signal}, or a ref supporting
     * {@code add-watch}.
     */
    public Sub subscribe(Object source) {
        if (source instanceof Signal s) {
            return s.subscribe(this, s);
        }
        if (source instanceof IRef r) {
            return RefSignal.watch(r, this);
        }
        throw new IllegalArgumentException("Can't subscribe to " + (source == null ? "nil" : source.getClass().getName()));
    }

    void push(Sub sub) {
        Sub head;
        do {
            head = dirty;
            sub.nextDirty = head;
        } while (!DIRTY.compareAndSet(this, head, sub));
        if (head == null) {
            wake();
        }
    }

    /**
     * Queues {@code event} for the owner, and wakes it.
     */
    public void post(Object event) {
        events.add(event);
        wake();
    }

    private void wake() {
        Thread t = owner;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /**
     * The next event, or null if there is none.
     */
    public Object poll() {
        return events.poll();
    }

    /**
     * Adds the dirty subscriptions to {@code into}, each once, clearing their
     * marks. Returns how many were added.
     */
    public int drain(Collection<Object> into) {
        if (dirty == null) {
            return 0;
        }
        Sub sub = (Sub) DIRTY.getAndSet(this, (Sub) null);
        int n = 0;
        while (sub != null) {
            // Read the link before clearing the mark: once cleared, a change can
            // push the subscription again and overwrite it.
            Sub next = sub.nextDirty;
            sub.nextDirty = null;
            sub.clear();
            into.add(sub);
            n++;
            sub = next;
        }
        return n;
    }

    public boolean isEmpty() {
        return dirty == null && events.isEmpty();
    }

    /**
     * Parks the owner until something arrives or {@code ms} elapse. Returns at
     * once if something is waiting, and may return early.
     */
    public void await(long ms) {
        if (isEmpty()) {
            LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(ms));
        }
    }
}
