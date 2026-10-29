package co.multiply.tropical.channel;

import java.util.ArrayDeque;

/** Bounded, ordered admission and resume accounting for one channel generation.
 * Incoming credits are returned on take. Sealing never discards accepted
 * input. Outgoing entries survive writes and are removed only by peer receipt.
 * Cumulative credits permit bounded flow control without tying acknowledgements
 * to application processing or relying on the socket's buffers.
 */
public final class Buffers {
    private Buffers() {}
    public static final long MAX = 9007199254740991L;
    public record Entry(long sequence, Object value, long bytes, long totalBytes) {}

    public static final class Incoming {
        private final long maxBytes, maxMessages;
        private final ArrayDeque<Entry> queue = new ArrayDeque<>();
        private long received, processed, bytes, consumedBytes;
        private boolean sealed;

        public Incoming(long maxBytes, long maxMessages) {
            if (maxBytes <= 0 || maxMessages <= 0 || maxBytes > MAX || maxMessages > MAX)
                throw new IllegalArgumentException("Invalid channel capacity");
            this.maxBytes = maxBytes;
            this.maxMessages = maxMessages;
        }

        /** 0 accepted, 1 duplicate, 2 full, 3 sealed; a gap is a protocol error. */
        public synchronized int offer(long sequence, Object value, long size) {
            if (sequence <= 0 || sequence > MAX || size < 0)
                throw new IllegalArgumentException("Invalid channel message");
            if (sequence <= received) return 1;
            if (sequence != received + 1) throw new IllegalArgumentException("Channel sequence gap");
            if (sealed) return 3;
            if (received - processed >= maxMessages || size > maxBytes - bytes) return 2;
            if (size > MAX - maxBytes - consumedBytes - bytes || sequence > MAX - maxMessages)
                throw new IllegalStateException("Channel counters exhausted");
            received = sequence;
            bytes += size;
            queue.addLast(new Entry(sequence, value, size, 0));
            return 0;
        }

        public synchronized Entry peek() { return queue.peekFirst(); }

        public synchronized Entry poll() {
            Entry entry = queue.pollFirst();
            if (entry != null) {
                processed = entry.sequence();
                bytes -= entry.bytes();
                consumedBytes += entry.bytes();
            }
            return entry;
        }

        /** Receipt position and cumulative message/byte grants, as one snapshot. */
        public synchronized long[] receipt() {
            return new long[] {received, maxMessages + processed, maxBytes + consumedBytes};
        }

        public synchronized void seal() { sealed = true; }
        public synchronized boolean drained() { return sealed && queue.isEmpty(); }
    }

    public static final class Outgoing {
        private final long maxBytes, maxMessages;
        private final ArrayDeque<Entry> queue = new ArrayDeque<>();
        private long lastSequence, acknowledged, bytes, totalBytes;
        private boolean sealed;

        public Outgoing(long maxBytes, long maxMessages) {
            if (maxBytes <= 0 || maxMessages <= 0 || maxBytes > MAX || maxMessages > MAX)
                throw new IllegalArgumentException("Invalid channel capacity");
            this.maxBytes = maxBytes;
            this.maxMessages = maxMessages;
        }

        public synchronized boolean canOffer(long size) {
            if (size < 0) throw new IllegalArgumentException("Negative message size");
            if (lastSequence == MAX || size > MAX - totalBytes)
                throw new IllegalStateException("Channel counters exhausted");
            return !sealed && queue.size() < maxMessages && size <= maxBytes - bytes;
        }

        /** Synchronous admission; 0 means full, -1 means sealed. */
        public synchronized long offer(Object value, long size) {
            if (sealed) return -1;
            if (!canOffer(size)) return 0;
            totalBytes += size;
            bytes += size;
            queue.addLast(new Entry(++lastSequence, value, size, totalBytes));
            return lastSequence;
        }

        public synchronized boolean canResume(long receipt) {
            return receipt >= acknowledged && receipt <= lastSequence;
        }

        public synchronized void acknowledge(long receipt) {
            if (!canResume(receipt)) throw new IllegalArgumentException("Invalid channel acknowledgement");
            while (!queue.isEmpty() && queue.peekFirst().sequence() <= receipt)
                bytes -= queue.removeFirst().bytes();
            acknowledged = receipt;
        }

        /** Inspect the next message before applying transient peer credits. */
        public synchronized Entry next(long cursor) {
            for (Entry entry : queue) {
                if (entry.sequence() > cursor) return entry;
            }
            return null;
        }

        public synchronized long lastSequence() { return lastSequence; }
        public synchronized long acknowledged() { return acknowledged; }
        public synchronized boolean empty() { return queue.isEmpty(); }
        public synchronized void seal() { sealed = true; }
        public synchronized void discard() { sealed = true; queue.clear(); bytes = 0; }
    }
}
