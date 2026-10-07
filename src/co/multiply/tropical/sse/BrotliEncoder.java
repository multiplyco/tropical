package co.multiply.tropical.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

/**
 * A brotli encoder over an output stream, as {@link EventStream} takes one.
 * What is written is compressed into one brotli stream, in brotli's mode for
 * text; a flush makes what was written so far decodable, and closing ends the
 * stream.
 * <p>
 * It calls libbrotlienc through the Foreign Function and Memory API: the
 * library tropical ships for the platform (macOS, or Linux on x86-64 or
 * aarch64), or the one the system property {@value #LIBRARY} names, 1.0 or
 * later. The library is loaded once, when the first encoder is made. Calling
 * it needs native access granted to the code that loaded this class, such as
 * {@code --enable-native-access=ALL-UNNAMED}; without it, the JDK warns.
 * <p>
 * An encoder holds native memory, brotli's state and an input buffer, until
 * it is closed, which frees it whether or not ending the stream succeeded. Its
 * output is taken from brotli's own buffer. Its methods are synchronized. Once
 * it is closed, writing and flushing throw.
 */
@SuppressWarnings("restricted")
public final class BrotliEncoder extends OutputStream {

    /**
     * The system property naming a libbrotlienc to load instead of
     * tropical's: for a platform tropical ships none for.
     */
    public static final String LIBRARY = "co.multiply.tropical.brotli.library";

    private static final int CHUNK = 8192;

    // BrotliEncoderParameter
    private static final int MODE = 0;
    private static final int QUALITY = 1;
    private static final int LGWIN = 2;

    // BrotliEncoderMode
    private static final int TEXT = 1;

    // BrotliEncoderOperation
    private static final int PROCESS = 0;
    private static final int FLUSH = 1;
    private static final int FINISH = 2;

    // The calls' cursors, each a size_t or a pointer, in one segment. The
    // output's stay zero: what brotli outputs is taken from its buffer.
    private static final long AVAIL_IN = 0;
    private static final long NEXT_IN = 8;
    private static final long AVAIL_OUT = 16;
    private static final long NEXT_OUT = 24;
    private static final long TAKE = 32;

    private final OutputStream out;
    private final Arena arena;
    private final MemorySegment state;
    private final MemorySegment src;
    private final MemorySegment cursors;
    private final MemorySegment availInAt;
    private final MemorySegment nextInAt;
    private final MemorySegment availOutAt;
    private final MemorySegment nextOutAt;
    private final MemorySegment takeAt;
    private final byte[] bytes = new byte[CHUNK];
    private boolean closed;

    /**
     * An encoder writing to {@code out}, at {@code quality}, 0 to 11, with a
     * window of 2^{@code windowLog} bytes, less 16.
     */
    public BrotliEncoder(OutputStream out, int quality, int windowLog) throws IOException {
        this.out = out;
        Arena arena = Arena.ofShared();
        try {
            MemorySegment s = Lib.createInstance();
            if (s.equals(MemorySegment.NULL)) {
                throw new IOException("brotli couldn't allocate an encoder.");
            }
            // Freed when the arena closes. The calls take plain addresses,
            // which the arena doesn't guard, as passing its segments would at
            // a cost on each call: the lock does, since closing marks the
            // encoder closed, under it, before freeing.
            s.reinterpret(arena, Lib::destroyInstance);
            state = s;
            Lib.setParameter(state, MODE, TEXT);
            Lib.setParameter(state, QUALITY, quality);
            Lib.setParameter(state, LGWIN, windowLog);
            src = arena.allocate(CHUNK);
            cursors = arena.allocate(40, 8);
            long at = cursors.address();
            availInAt = MemorySegment.ofAddress(at + AVAIL_IN);
            nextInAt = MemorySegment.ofAddress(at + NEXT_IN);
            availOutAt = MemorySegment.ofAddress(at + AVAIL_OUT);
            nextOutAt = MemorySegment.ofAddress(at + NEXT_OUT);
            takeAt = MemorySegment.ofAddress(at + TAKE);
            cursors.set(ValueLayout.JAVA_LONG, NEXT_IN, src.address());
        } catch (IOException | RuntimeException | Error e) {
            arena.close();
            throw e;
        }
        this.arena = arena;
    }

    /**
     * The version of the libbrotlienc loaded, such as {@code "1.2.0"}. Loads
     * it if no encoder did.
     */
    public static String libraryVersion() {
        int v = Lib.VERSION;
        return (v >>> 24) + "." + ((v >>> 12) & 0xFFF) + "." + (v & 0xFFF);
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        ensureOpen();
        while (len > 0) {
            int n = Math.min(len, CHUNK);
            MemorySegment.copy(b, off, src, ValueLayout.JAVA_BYTE, 0, n);
            cursors.set(ValueLayout.JAVA_LONG, AVAIL_IN, n);
            cursors.set(ValueLayout.JAVA_LONG, NEXT_IN, src.address());
            do {
                compress(PROCESS);
            } while (cursors.get(ValueLayout.JAVA_LONG, AVAIL_IN) > 0);
            off += n;
            len -= n;
        }
    }

    /**
     * Ends the metablock, so that what was written decodes once it arrives,
     * and flushes the stream under it.
     */
    @Override
    public synchronized void flush() throws IOException {
        ensureOpen();
        compress(FLUSH);
        out.flush();
    }

    /**
     * Ends the brotli stream, frees the encoder's native memory, and closes
     * the stream under it, each even if the one before failed. Does nothing if
     * already closed.
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (out) {
            try {
                compress(FINISH);
                if (!Lib.isFinished(state)) {
                    throw new IOException("brotli: the stream didn't end.");
                }
            } finally {
                arena.close();
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("The encoder is closed.");
        }
    }

    /**
     * Calls the encoder, as {@code op} says, on what input is left, until a
     * call leaves no output behind, writing what it output to the stream.
     */
    private void compress(int op) throws IOException {
        while (true) {
            if (!Lib.compressStream(state, op, availInAt, nextInAt, availOutAt, nextOutAt)) {
                throw new IOException("brotli: the encoder failed.");
            }
            if (!Lib.hasMoreOutput(state)) {
                return;
            }
            do {
                cursors.set(ValueLayout.JAVA_LONG, TAKE, CHUNK);
                long at = Lib.takeOutput(state, takeAt);
                int n = (int) cursors.get(ValueLayout.JAVA_LONG, TAKE);
                MemorySegment.copy(Lib.MEMORY, ValueLayout.JAVA_BYTE, at, bytes, 0, n);
                out.write(bytes, 0, n);
            } while (Lib.hasMoreOutput(state));
        }
    }

    /**
     * The library, loaded when first used, and its functions.
     */
    private static final class Lib {

        // All of memory, to copy what brotli outputs from where it is.
        static final MemorySegment MEMORY = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

        static final MethodHandle CREATE_INSTANCE;
        static final MethodHandle DESTROY_INSTANCE;
        static final MethodHandle SET_PARAMETER;
        static final MethodHandle COMPRESS_STREAM;
        static final MethodHandle HAS_MORE_OUTPUT;
        static final MethodHandle IS_FINISHED;
        static final MethodHandle TAKE_OUTPUT;
        static final int VERSION;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lib = NativeLibrary.load("libbrotlienc", LIBRARY, "a libbrotlienc, 1.0 or later");
            ValueLayout ptr = ValueLayout.ADDRESS;
            ValueLayout i32 = ValueLayout.JAVA_INT;
            CREATE_INSTANCE = linker.downcallHandle(lib.findOrThrow("BrotliEncoderCreateInstance"),
                FunctionDescriptor.of(ptr, ptr, ptr, ptr));
            DESTROY_INSTANCE = linker.downcallHandle(lib.findOrThrow("BrotliEncoderDestroyInstance"),
                FunctionDescriptor.ofVoid(ptr));
            SET_PARAMETER = linker.downcallHandle(lib.findOrThrow("BrotliEncoderSetParameter"),
                FunctionDescriptor.of(i32, ptr, i32, i32));
            COMPRESS_STREAM = linker.downcallHandle(lib.findOrThrow("BrotliEncoderCompressStream"),
                FunctionDescriptor.of(i32, ptr, i32, ptr, ptr, ptr, ptr, ptr));
            // Reads of the state, and the output's taking: critical, so the
            // call skips the transition to native.
            HAS_MORE_OUTPUT = linker.downcallHandle(lib.findOrThrow("BrotliEncoderHasMoreOutput"),
                FunctionDescriptor.of(i32, ptr), Linker.Option.critical(false));
            IS_FINISHED = linker.downcallHandle(lib.findOrThrow("BrotliEncoderIsFinished"),
                FunctionDescriptor.of(i32, ptr), Linker.Option.critical(false));
            TAKE_OUTPUT = linker.downcallHandle(lib.findOrThrow("BrotliEncoderTakeOutput"),
                FunctionDescriptor.of(ptr, ptr, ptr), Linker.Option.critical(false));
            MethodHandle version = linker.downcallHandle(lib.findOrThrow("BrotliEncoderVersion"),
                FunctionDescriptor.of(i32));
            try {
                VERSION = (int) version.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            if (VERSION >>> 24 < 1) {
                throw new IllegalStateException("libbrotlienc " + VERSION + " is older than 1.0.0, which the encoder "
                    + "needs.");
            }
        }

        private Lib() {}

        static MemorySegment createInstance() {
            try {
                // brotli's own allocator: malloc and free.
                return (MemorySegment) CREATE_INSTANCE.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static void destroyInstance(MemorySegment state) {
            try {
                DESTROY_INSTANCE.invokeExact(state);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static void setParameter(MemorySegment state, int param, int value) throws IOException {
            int ok;
            try {
                ok = (int) SET_PARAMETER.invokeExact(state, param, value);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            if (ok == 0) {
                throw new IOException("brotli: parameter " + param + " can't be " + value + ".");
            }
        }

        static boolean compressStream(MemorySegment state, int op, MemorySegment availIn, MemorySegment nextIn,
            MemorySegment availOut, MemorySegment nextOut) {
            try {
                return (int) COMPRESS_STREAM.invokeExact(state, op, availIn, nextIn, availOut, nextOut,
                    MemorySegment.NULL) != 0;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static boolean hasMoreOutput(MemorySegment state) {
            try {
                return (int) HAS_MORE_OUTPUT.invokeExact(state) != 0;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static boolean isFinished(MemorySegment state) {
            try {
                return (int) IS_FINISHED.invokeExact(state) != 0;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /**
         * Takes up to {@code *size} bytes of brotli's output, and returns
         * where they are; {@code *size} is set to how many it took.
         */
        static long takeOutput(MemorySegment state, MemorySegment size) {
            try {
                return ((MemorySegment) TAKE_OUTPUT.invokeExact(state, size)).address();
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }
}
