package co.multiply.tropical.runtime;

import co.multiply.tropical.signal.Sub;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * Reads collected by one island render, with reusable entries and a distinct
 * subscription list. Only the session pump accesses this buffer.
 * Clearing releases references to sources, selectors and selected values while
 * retaining capacity for similarly sized renders. Large contractions drop spare
 * capacity without allocating during cleanup. A separate buffer preserves the
 * previous render's reads until ownership has been reconciled, including when
 * rendering fails.
 */
public final class ReadBuffer {
    private static final Read[] NO_READS = new Read[0];
    private static final Sub[] NO_SUBSCRIPTIONS = new Sub[0];

    public static final class Read {
        public Sub sub;
        // Object preserves recording of an invalid selector before it is rethrown.
        public Object select;
        public Object seen;
        public boolean failed;

        private void clear() {
            sub = null;
            select = null;
            seen = null;
            failed = false;
        }
    }

    private Read[] reads = NO_READS;
    private int size;
    private Sub[] subscriptions = NO_SUBSCRIPTIONS;
    private int subscriptionCount;
    // Small renders need no index. Large ones must not do a quadratic scan.
    private IdentityHashMap<Sub, Boolean> index;

    public int size() {
        return size;
    }

    public Read get(int i) {
        return reads[i];
    }

    public int subscriptionCount() {
        return subscriptionCount;
    }

    public Sub subscriptionAt(int i) {
        return subscriptions[i];
    }

    public boolean contains(Sub sub) {
        if (index != null) {
            return index.containsKey(sub);
        }
        for (int i = 0; i < subscriptionCount; i++) {
            if (subscriptions[i] == sub) {
                return true;
            }
        }
        return false;
    }

    public void add(Sub sub, Object select, Object seen, boolean failed) {
        if (size == reads.length) {
            reads = Arrays.copyOf(reads, Math.max(4, size * 2));
        }
        Read read = reads[size];
        if (read == null) {
            read = new Read();
            reads[size] = read;
        }
        read.sub = sub;
        read.select = select;
        read.seen = seen;
        read.failed = failed;
        size++;
        if (!contains(sub)) {
            if (subscriptionCount == subscriptions.length) {
                subscriptions = Arrays.copyOf(subscriptions, Math.max(4, subscriptionCount * 2));
            }
            subscriptions[subscriptionCount++] = sub;
            if (index != null) {
                index.put(sub, Boolean.TRUE);
            } else if (subscriptionCount > 8) {
                index = new IdentityHashMap<>();
                for (int i = 0; i < subscriptionCount; i++) {
                    index.put(subscriptions[i], Boolean.TRUE);
                }
            }
        }
    }

    public boolean hasPending(Object pending) {
        for (int i = 0; i < size; i++) {
            if (reads[i].seen == pending) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            reads[i].clear();
        }
        if (reads.length > 16 && size < reads.length / 4) {
            reads = NO_READS;
        }
        boolean shrink = subscriptions.length > 16 && subscriptionCount < subscriptions.length / 4;
        if (shrink) {
            subscriptions = NO_SUBSCRIPTIONS;
        } else {
            Arrays.fill(subscriptions, 0, subscriptionCount, null);
        }
        if (index != null) {
            if (shrink || subscriptionCount <= 8) {
                index = null;
            } else {
                index.clear();
            }
        }
        size = 0;
        subscriptionCount = 0;
    }
}
