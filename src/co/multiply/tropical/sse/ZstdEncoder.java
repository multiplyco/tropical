package co.multiply.tropical.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * A zstd encoder over an output stream, as {@link EventStream} takes one.
 * What is written is compressed into one zstd frame; a flush makes what was
 * written so far decodable, and closing ends the frame.
 * <p>
 * It calls libzstd through the Foreign Function and Memory API: the library
 * tropical ships for the platform (macOS, or Linux on x86-64 or aarch64), or
 * the one the system property {@value #LIBRARY} names, 1.4 or later. The
 * library is loaded once, when the first encoder is made. Calling it needs
 * native access granted to the code that loaded this class, such as
 * {@code --enable-native-access=ALL-UNNAMED}; without it, the JDK warns.
 * <p>
 * An encoder holds native memory, its compression context and buffers, until
 * it is closed, which frees it whether or not ending the frame succeeded. Its
 * methods are synchronized. Once it is closed, writing and flushing throw.
 */
@SuppressWarnings("restricted")
public final class ZstdEncoder extends OutputStream {

    /**
     * The system property naming a libzstd to load instead of tropical's: for
     * a platform tropical ships none for.
     */
    public static final String LIBRARY = "co.multiply.tropical.zstd.library";

    private static final int CHUNK = 8192;

    // ZSTD_cParameter
    private static final int COMPRESSION_LEVEL = 100;
    private static final int WINDOW_LOG = 101;
    private static final int HASH_LOG = 102;
    private static final int CHAIN_LOG = 103;

    // ZSTD_EndDirective
    private static final int CONTINUE = 0;
    private static final int FLUSH = 1;
    private static final int END = 2;

    private final OutputStream out;
    private final Arena arena;
    private final MemorySegment cctx;
    private final MemorySegment src;
    private final MemorySegment dst;
    private final MemorySegment input;
    private final MemorySegment output;
    private final MemorySegment inputAt;
    private final MemorySegment outputAt;
    private final byte[] bytes = new byte[CHUNK];
    private boolean closed;

    /**
     * An encoder writing to {@code out}, at compression {@code level}, with a
     * window of 2^{@code windowLog} bytes, and tables of 2^{@code hashLog}
     * and 2^{@code chainLog} entries.
     */
    public ZstdEncoder(OutputStream out, int level, int windowLog, int hashLog, int chainLog) throws IOException {
        this.out = out;
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ctx = Lib.createCCtx();
            if (ctx.equals(MemorySegment.NULL)) {
                throw new IOException("zstd couldn't allocate a compression context.");
            }
            // Freed when the arena closes. The calls take plain addresses,
            // which the arena doesn't guard, as passing its segments would at
            // a cost on each call: the lock does, since closing marks the
            // encoder closed, under it, before freeing.
            ctx.reinterpret(arena, Lib::freeCCtx);
            cctx = ctx;
            Lib.setParameter(cctx, COMPRESSION_LEVEL, level);
            Lib.setParameter(cctx, WINDOW_LOG, windowLog);
            Lib.setParameter(cctx, HASH_LOG, hashLog);
            Lib.setParameter(cctx, CHAIN_LOG, chainLog);
            src = arena.allocate(CHUNK);
            dst = arena.allocate(CHUNK);
            input = arena.allocate(Lib.BUFFER);
            output = arena.allocate(Lib.BUFFER);
            Lib.PTR.set(input, 0L, src);
            Lib.PTR.set(output, 0L, dst);
            Lib.SIZE.set(output, 0L, (long) CHUNK);
            inputAt = MemorySegment.ofAddress(input.address());
            outputAt = MemorySegment.ofAddress(output.address());
        } catch (IOException | RuntimeException | Error e) {
            arena.close();
            throw e;
        }
        this.arena = arena;
    }

    /**
     * The version of the libzstd loaded, such as {@code "1.5.7"}. Loads it if
     * no encoder did.
     */
    public static String libraryVersion() {
        int v = Lib.VERSION;
        return (v / 10000) + "." + (v / 100 % 100) + "." + (v % 100);
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
            Lib.SIZE.set(input, 0L, (long) n);
            Lib.POS.set(input, 0L, 0L);
            do {
                compress(CONTINUE);
            } while ((long) Lib.POS.get(input, 0L) < n);
            off += n;
            len -= n;
        }
    }

    /**
     * Ends the block, so that what was written decodes once it arrives, and
     * flushes the stream under it.
     */
    @Override
    public synchronized void flush() throws IOException {
        ensureOpen();
        drain(FLUSH);
        out.flush();
    }

    /**
     * Ends the frame, frees the encoder's native memory, and closes the stream
     * under it, each even if the one before failed. Does nothing if already
     * closed.
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (out) {
            try {
                drain(END);
            } finally {
                arena.close();
            }
        }
    }

    /**
     * The native memory the encoder holds, in bytes: its compression context
     * and its buffers. Zero once closed.
     */
    public synchronized long nativeSize() {
        return closed ? 0 : Lib.sizeofCCtx(cctx) + 2L * CHUNK + 2 * Lib.BUFFER.byteSize();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("The encoder is closed.");
        }
    }

    /**
     * Compresses with no more input, as {@code directive} says, until zstd
     * holds nothing back.
     */
    private void drain(int directive) throws IOException {
        Lib.SIZE.set(input, 0L, 0L);
        Lib.POS.set(input, 0L, 0L);
        while (compress(directive) != 0) {
            // More to write out.
        }
    }

    /**
     * One call to compress, writing what it output to the stream. Returns what
     * zstd returned: for a flush or an end, how much it still holds.
     */
    private long compress(int directive) throws IOException {
        Lib.POS.set(output, 0L, 0L);
        long remaining = Lib.compressStream2(cctx, outputAt, inputAt, directive);
        int n = (int) (long) Lib.POS.get(output, 0L);
        if (n > 0) {
            MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0, bytes, 0, n);
            out.write(bytes, 0, n);
        }
        return remaining;
    }

    /**
     * The library, loaded when first used, and its functions.
     */
    private static final class Lib {

        // ZSTD_inBuffer and ZSTD_outBuffer: a pointer, a size and a position.
        static final StructLayout BUFFER = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("ptr"),
            ValueLayout.JAVA_LONG.withName("size"),
            ValueLayout.JAVA_LONG.withName("pos"));
        static final VarHandle PTR = BUFFER.varHandle(MemoryLayout.PathElement.groupElement("ptr"));
        static final VarHandle SIZE = BUFFER.varHandle(MemoryLayout.PathElement.groupElement("size"));
        static final VarHandle POS = BUFFER.varHandle(MemoryLayout.PathElement.groupElement("pos"));

        static final MethodHandle CREATE_CCTX;
        static final MethodHandle FREE_CCTX;
        static final MethodHandle SET_PARAMETER;
        static final MethodHandle COMPRESS_STREAM2;
        static final MethodHandle SIZEOF_CCTX;
        static final MethodHandle IS_ERROR;
        static final MethodHandle GET_ERROR_NAME;
        static final int VERSION;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lib = NativeLibrary.load("libzstd", LIBRARY, "a libzstd, 1.4 or later");
            ValueLayout size = ValueLayout.JAVA_LONG;
            ValueLayout ptr = ValueLayout.ADDRESS;
            ValueLayout i32 = ValueLayout.JAVA_INT;
            CREATE_CCTX = linker.downcallHandle(lib.findOrThrow("ZSTD_createCCtx"), FunctionDescriptor.of(ptr));
            FREE_CCTX = linker.downcallHandle(lib.findOrThrow("ZSTD_freeCCtx"), FunctionDescriptor.of(size, ptr));
            SET_PARAMETER = linker.downcallHandle(lib.findOrThrow("ZSTD_CCtx_setParameter"),
                FunctionDescriptor.of(size, ptr, i32, i32));
            COMPRESS_STREAM2 = linker.downcallHandle(lib.findOrThrow("ZSTD_compressStream2"),
                FunctionDescriptor.of(size, ptr, ptr, ptr, i32));
            SIZEOF_CCTX = linker.downcallHandle(lib.findOrThrow("ZSTD_sizeof_CCtx"), FunctionDescriptor.of(size, ptr));
            // A comparison: critical, so the call skips the transition to native.
            IS_ERROR = linker.downcallHandle(lib.findOrThrow("ZSTD_isError"), FunctionDescriptor.of(i32, size),
                Linker.Option.critical(false));
            GET_ERROR_NAME = linker.downcallHandle(lib.findOrThrow("ZSTD_getErrorName"), FunctionDescriptor.of(ptr, size));
            MethodHandle versionNumber = linker.downcallHandle(lib.findOrThrow("ZSTD_versionNumber"),
                FunctionDescriptor.of(i32));
            try {
                VERSION = (int) versionNumber.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            if (VERSION < 10400) {
                throw new IllegalStateException("libzstd " + VERSION + " is older than 1.4.0, which the encoder needs.");
            }
        }

        private Lib() {}

        static MemorySegment createCCtx() {
            try {
                return (MemorySegment) CREATE_CCTX.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static void freeCCtx(MemorySegment cctx) {
            try {
                long ignored = (long) FREE_CCTX.invokeExact(cctx);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        static void setParameter(MemorySegment cctx, int param, int value) throws IOException {
            long code;
            try {
                code = (long) SET_PARAMETER.invokeExact(cctx, param, value);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            check(code);
        }

        static long compressStream2(MemorySegment cctx, MemorySegment output, MemorySegment input, int directive)
            throws IOException {
            long code;
            try {
                code = (long) COMPRESS_STREAM2.invokeExact(cctx, output, input, directive);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            return check(code);
        }

        static long sizeofCCtx(MemorySegment cctx) {
            try {
                return (long) SIZEOF_CCTX.invokeExact(cctx);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /**
         * {@code code}, unless it is an error, which is thrown.
         */
        private static long check(long code) throws IOException {
            int error;
            MemorySegment name;
            try {
                error = (int) IS_ERROR.invokeExact(code);
                if (error == 0) {
                    return code;
                }
                name = (MemorySegment) GET_ERROR_NAME.invokeExact(code);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            throw new IOException("zstd: " + name.reinterpret(Long.MAX_VALUE).getString(0));
        }
    }
}
