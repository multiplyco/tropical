# 2026-10-09 — Compressing the stream

**Jetty can't do it.** Jetty 12.1 compresses with zstd and brotli (`jetty-compression-zstandard`, `-brotli`, behind
its `CompressionHandler`, over zstd-jni and brotli4j). Read from their source on the 12.1 branch, the zstd encoder
compresses a write that isn't the response's last with `EndDirective.CONTINUE`, and flushes only on the last; the
brotli one's says "A non-last write simply waits for more content". Only its gzip has a sync flush. A stream never
writes its last, so its frames would sit in the encoder.

**What it saves.** The demo's stream, recorded for 60 s in-process: a new tab's stream request through `demo.core`'s
handler, written to an output stream that kept each flush. 101 passes of the pump, 203 events, 104,931 bytes: the
first pass the scripts, the root (34.5 KB) and the footer's signals, then the ticker's morph and the signals, about
700 bytes a pass. Replayed in a separate JVM through each encoder, flushing once per pass, on an M-series Mac:

| Encoder                                | Whole stream    | First pass | Later passes | Per pass |
|----------------------------------------|-----------------|------------|--------------|----------|
| none                                   | 104,931         | 35,208     | 697          |          |
| gzip, the JDK's default level (6)      | 13,795 (13.1 %) | 9,482      | 43           | 11–13 µs |
| zstd 3, window 2^18, hash 15, chain 14 | 14,905 (14.2 %) | 9,780      | 51           | 2–7 µs   |
| brotli 4, window 2^17                  | 14,283 (13.6 %) | 9,102      | 52           | 13 µs    |
| brotli 5, window 2^17                  | 12,812 (12.2 %) | 8,459      | 44           | 16 µs    |
| brotli 11, window 2^22                 | 10,925 (10.4 %) | 6,967      | 40           | 1,630 µs |

A flush per event instead of per pass sent 6–8 % more (gzip 14,633, zstd 15,871, brotli 4 15,378). The window made
no difference here: the ticker's morph repeats the one before it, well within any window.

It does for a large island. Synthetic: a 300-row table of 32.5 KB, its rows distinct, re-sent whole 60 times with
one row changed each time:

| Encoder                                | Later passes | Per pass |
|----------------------------------------|--------------|----------|
| gzip 6                                 | 3,441        | 360 µs   |
| zstd 3, window 2^15                    | 3,980        | 42 µs    |
| zstd 3, window 2^16                    | 1,209        | 19 µs    |
| zstd 3, window 2^17, hash 15, chain 14 | 542          | 10 µs    |
| zstd 3, window 2^18, hash 15, chain 14 | 139          | 6 µs     |
| zstd 5, window 2^18, hash 16, chain 15 | 118          | 9 µs     |
| brotli 3, window 2^16                  | 282          | 39 µs    |
| brotli 4, window 2^17                  | 40           | 36 µs    |
| brotli 5, window 2^18                  | 32           | 47 µs    |

**What it holds.** Each open stream holds its encoder. Measured as the growth of the process's resident memory with
500 open streams, each having encoded the large island's 60 passes, in a fresh JVM per setting, after a GC; the heap
part is the growth of the used heap:

| Encoder                                | Per stream | Of it, heap |
|----------------------------------------|------------|-------------|
| gzip 6                                 | 227 KB     | 4 KB        |
| zstd 3, window 2^17, hash 15, chain 14 | 596 KB     | 131 KB      |
| zstd 3, window 2^18, hash 15, chain 14 | 725 KB     | 131 KB      |
| zstd 3, window 2^18, hash 17, chain 16 | 1,300 KB   | 131 KB      |
| brotli 4, window 2^17                  | 926 KB     | 5 KB        |
| brotli 4, window 2^18                  | 1,179 KB   | 5 KB        |
| brotli 5, window 2^17                  | 1,728 KB   | 4 KB        |

zstd-jni's stream keeps a 128 KiB output buffer on the heap, whatever its settings. Smaller windows hold less (zstd
3 at 2^15: about 370 KB) but can't refer back across a large island, and one budget for all three, gzip's, left
zstd no better than gzip there. Chosen: zstd 3 with a 2^18 window and small tables, brotli 4 with a 2^17 window, and
gzip as the JDK has it. Brotli 5 compresses a little better at twice the memory.

`bb test`: 125 tests, 693 assertions. Among them, each encoding decodes the stream so far after every event, as a
browser decodes what arrived; a frame repeating an earlier one of 20 KB costs under a tenth of it; a frame is one
flush of the response, compressed or not; and a client gone mid-frame closes the stream and its response's output
stream. The flush test fails with `sse/together` stubbed to flush per event.
