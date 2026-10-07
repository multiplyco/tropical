package co.multiply.tropical.signal;

import clojure.lang.IDeref;
import co.multiply.conc.Deque;
import co.multiply.conc.Link;

/**
 * A value that tells its subscribers when it changes, without sending them the
 * value.
 * <p>
 * A change marks each subscription dirty ({@link Sub#fire()}). Each session reads
 * the current value when it gets to it, so a session that falls behind has
 * nothing to catch up on: it reads the latest value once. This is the protocol of
 * Missionary's continuous flows: notify once, read on transfer.
 * <p>
 * Subscriptions are links in a lock-free deque, so subscribing and unsubscribing
 * are O(1) and walking them allocates nothing.
 */
public abstract class Signal implements IDeref {

    final Deque subs = new Deque();

    /**
     * Marks every subscription dirty. Call it after the change is visible to
     * {@link #deref()}: a session that is marked will read the value after.
     */
    public final void invalidate() {
        for (Link l = subs.head.getNext(); l != null; l = l.getNext()) {
            ((Sub) l).fire();
        }
    }

    /**
     * Adds a subscription for {@code inbox}, subscribed with {@code source}.
     */
    Sub subscribe(Inbox inbox, Object source) {
        Sub sub = new Sub(inbox, this, source);
        subs.add(sub);
        return sub;
    }

    /**
     * Removes {@code sub}. Called once per subscription, by {@link Sub#cancel()}.
     */
    void unsubscribe(Sub sub) {
        subs.remove(sub);
    }

    public int subscriberCount() {
        return subs.size();
    }
}
