package co.multiply.tropical.signal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A signal that holds its value: what a resource publishes into, or a one-off
 * task completes into.
 */
public final class Cell extends Signal {

    private volatile Object value;

    private static final VarHandle VALUE;

    static {
        try {
            VALUE = MethodHandles.lookup().findVarHandle(Cell.class, "value", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public Cell(Object init) {
        this.value = init;
    }

    @Override
    public Object deref() {
        return value;
    }

    /**
     * Sets the value to {@code v} and marks the subscribers, unless it already
     * was {@code v} (identical).
     */
    public void reset(Object v) {
        if (VALUE.getAndSet(this, v) != v) {
            invalidate();
        }
    }
}
