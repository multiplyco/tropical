package co.multiply.tropical.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A stream of server-sent events, written to an HTTP response's output stream.
 * <p>
 * An event is written whole before the next begins: senders are serialized,
 * so a client never sees two events interleaved. Closing happens
 * once and is final: from then on, sending returns false and writes nothing. A
 * write that fails closes the stream, so a client that left is noticed by the
 * next send. {@link #await()} returns once the stream is closed, for a server
 * that ends the response when the handler returns.
 * <p>
 * Each event is flushed as it is sent, unless the stream is held
 * ({@link #hold}): then the events sent are flushed together once it is
 * released, so related events, such as a frame's patches and its signals,
 * reach the client at once.
 * <p>
 * The response may be compressed: the events are then written to an encoder
 * over the response's output stream, and each flush pushes what the encoder
 * holds through to the client, which decodes it as it arrives.
 * <p>
 * Writing allocates nothing per event: the event's chars go through a reused
 * buffer into a charset encoder that reuses its own.
 * <p>
 * The stream knows SSE framing, not what events mean. {@link #event} starts
 * one, {@link #data} appends its data lines, and {@link #send} ends and writes
 * it.
 */
public final class EventStream {

    private static final int CHUNK = 4096;

    private final Writer writer;
    private final OutputStream out;
    private final ReentrantLock lock = new ReentrantLock();
    private final CountDownLatch closed = new CountDownLatch(1);

    // Guarded by lock.
    private final char[] chunk = new char[CHUNK];
    private boolean open = true;
    private int holds;
    private boolean unflushed;

    /**
     * A stream of events written to {@code out} as they are.
     */
    public EventStream(OutputStream out) {
        this(out, out);
    }

    /**
     * A stream of events written to {@code encoded}, an encoder over
     * {@code out}, the response's output stream. Flushing {@code encoded}
     * must push what it holds through to {@code out}, and flush that; closing
     * it must end the encoding and close {@code out}.
     */
    public EventStream(OutputStream out, OutputStream encoded) {
        this.out = out;
        this.writer = new OutputStreamWriter(encoded, StandardCharsets.UTF_8);
    }

    /**
     * A new event of type {@code type}, to append data lines to and send.
     */
    public static StringBuilder event(String type) {
        return event(type, 256);
    }

    /**
     * As {@link #event(String)}, with room for {@code capacity} chars, so a large
     * event is built without regrowing.
     */
    public static StringBuilder event(String type, int capacity) {
        return new StringBuilder(capacity).append("event: ").append(type).append('\n');
    }

    /**
     * Appends {@code value} to {@code event} as data lines, one per line of
     * {@code value} (ended by LF, CR or CRLF), each starting with {@code key}
     * and a space. Returns {@code event}.
     */
    public static StringBuilder data(StringBuilder event, String key, String value) {
        int n = value.length();
        int start = 0;
        for (int i = 0; i < n; i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                line(event, key, value, start, i);
                if (c == '\r' && i + 1 < n && value.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < n || n == 0) {
            line(event, key, value, start, n);
        }
        return event;
    }

    private static void line(StringBuilder event, String key, String value, int from, int to) {
        event.append("data: ").append(key).append(' ').append(value, from, to).append('\n');
    }

    /**
     * Ends {@code event} and writes it, flushed unless the stream is held.
     * False if the stream is closed, or the write failed and closed it.
     */
    public boolean send(StringBuilder event) {
        event.append('\n');
        lock.lock();
        try {
            if (!open) {
                return false;
            }
            try {
                for (int i = 0, n = event.length(); i < n; i += CHUNK) {
                    int len = Math.min(CHUNK, n - i);
                    event.getChars(i, i + len, chunk, 0);
                    writer.write(chunk, 0, len);
                }
                if (holds > 0) {
                    unflushed = true;
                } else {
                    writer.flush();
                }
                return true;
            } catch (IOException e) {
                closeLocked();
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Holds the events sent from now on, unflushed, until a matching
     * {@link #release}. Holds nest.
     */
    public void hold() {
        lock.lock();
        try {
            holds++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends a {@link #hold}. Releasing the last one flushes the events sent
     * while held. False if the stream is closed, or the flush failed and
     * closed it.
     */
    public boolean release() {
        lock.lock();
        try {
            if (holds == 0) {
                throw new IllegalStateException("Released more often than held.");
            }
            holds--;
            if (!open) {
                return false;
            }
            if (holds == 0 && unflushed) {
                unflushed = false;
                try {
                    writer.flush();
                } catch (IOException e) {
                    closeLocked();
                    return false;
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes a comment, which a client ignores: it keeps an idle connection
     * alive, and tells whether it still is. False as {@link #send}.
     */
    public boolean comment() {
        return send(new StringBuilder(":\n"));
    }

    public boolean isOpen() {
        return closed.getCount() > 0;
    }

    /**
     * Closes the stream, sending what a hold kept back, ending the encoding if
     * any, and closing the output stream under it. Waits for an event being
     * written to finish. Does nothing if already closed.
     */
    public void close() {
        lock.lock();
        try {
            closeLocked();
        } finally {
            lock.unlock();
        }
    }

    private void closeLocked() {
        if (!open) {
            return;
        }
        open = false;
        try {
            // Closes the encoder, if any, which frees what it holds even when
            // writing its end fails.
            writer.close();
        } catch (IOException e) {
            try {
                out.close();
            } catch (IOException ignored) {
                // Closed as far as it can be.
            }
        } finally {
            closed.countDown();
        }
    }

    /**
     * Blocks until the stream is closed. If interrupted, closes it and returns
     * with the interrupt status set.
     */
    public void await() {
        try {
            closed.await();
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
        }
    }
}
