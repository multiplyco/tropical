# 2026-10-10 — brotli through a libbrotlienc of tropical's own

brotli was the app's dependency, brotli4j, reached by reflection. That left it open to what zstd-jni ran into, a
brotli4j of another version among the app's dependencies, and brotli4j builds no library for musl. So tropical builds
brotli's encoder and calls it as it does libzstd (`BrotliEncoder`).

The source: brotli 1.2.0, the latest release (October 2025). brotli signs no release: the tag is a plain one, on an
unsigned commit. GitHub's archive of the tag, SHA-256 `816c96e8…dfec`, names the release commit, `028fb5a2…`, in its
header, and its `c/common`, `c/enc` and `c/include` hash to that commit's git trees, less `c/common/dictionary.bin*`,
which brotli's `.gitattributes` keeps out of archives (the dictionary is compiled in from `dictionary.c`). brotli4j
1.23.0 carries the same release, file for file: its `brotli/` trees hash the same, less those two files too.

The build (`native/build-brotli.sh`): `c/common` and `c/enc` in one library, with the flags brotli's CMake gives a
release's shared libraries (`-O3 -DNDEBUG`, `BROTLI_SHARED_COMPILATION`), and hidden visibility. The compilers,
images, targets and linking are libzstd's. glibc 2.29 gave libm's `log2` a new symbol version, so a build on Debian
12 would have needed glibc 2.29; an `.symver` binds the first, `GLIBC_2.2.5` on x86_64 and `GLIBC_2.17` on aarch64.

| Library                         | Bytes     | SHA-256       |
|---------------------------------|-----------|---------------|
| `darwin/libbrotlienc.dylib`     | 1,762,640 | `4d7119fc…94` |
| `linux-x86_64/libbrotlienc.so`  | 866,872   | `daf72ae6…06` |
| `linux-aarch64/libbrotlienc.so` | 854,520   | `0aee778e…5e` |

Each exports brotli's 24 symbols: the encoder's API, the shared dictionary's, and the default allocator's. The Linux
ones import `malloc`, `calloc`, `free`, `memcpy`, `memmove`, `memset`, `exit` and `log2`, with symbol versions up to
GLIBC_2.14 (x86_64) and 2.17 (aarch64), as libzstd's do. All three came out the same, byte for byte, from two builds.
The jar went from 903 KB to 2,491,100 bytes; the three libraries are 1.58 MB of it, compressed.

`exit` is brotli's answer to a failed malloc: by default (`BROTLI_ENCODER_EXIT_ON_OOM`) the encoder ends the process.
`BROTLI_ENCODER_CLEANUP_ON_OOM` returns an error instead, but no build of brotli's own uses it, nor brotli4j's, and it
tracks the encoder's live allocations in a table of 128 that release builds don't bound-check. Left at the default.

brotli 1.2.0's `BROTLI_STATIC_INIT_EARLY` computes the encoder's dictionary tables as the library loads instead of
carrying them: macOS arm64 alone went from 861,512 bytes to 580,920 (395 KB to 258 KB gzipped), about 0.55 MB off the
jar over the four slices. No build of brotli's own sets it either; not taken.

The encoder takes brotli's output from brotli's own buffer (`BrotliEncoderTakeOutput`, which brotli's header suggests
for bindings) rather than giving it one to copy into, and copies its input into an 8 KiB native buffer, in text mode.
Against brotli4j 1.23.0 (macOS arm64), the stream's bytes were the same at quality 4 with a 2^17 window, 5 with 2^18
and 11 with 2^22, on the demo's captured stream and on the large-island workload, flushing once per pass. At quality 1
they differ, both decoding: brotli's fast path compresses each call's input as given, and brotli4j hands it 4 KiB at a
time where tropical's encoder hands 8 KiB. Tropical's setting is quality 4.

Measured as for libzstd: the demo's captured stream and the large-island workload of 2026-10-09, one fresh JVM per
run, JDK 27, macOS arm64, timing after 400 warm-up runs, memory over 500 streams, quality 4, window 2^17. The machine
was loaded (load average 8–12), so the runs were interleaved, four rounds:

| brotli 4, window 2^17   | Wire, demo | µs a pass, demo | µs a pass, large | Memory, demo | Memory, large |
|-------------------------|------------|-----------------|------------------|--------------|---------------|
| brotli4j 1.23.0         | 14,283     | 12.9–13.3       | 36.2–36.7        | 781 KB       | 925–926 KB    |
| tropical's, through FFM | 14,283     | 13.5–13.8       | 36.8–38.3        | 796–802 KB   | 928–931 KB    |

Memory is a stream's, resident, of which 5–9 KB is on the heap. The encoder's own buffers are an 8 KiB native input
buffer and an 8 KiB heap array, where brotli4j has a 4 KiB input buffer; the rest of the difference wasn't traced. A
variant that gave brotli an 8 KiB native buffer to copy its output into timed the same as taking it, within the noise,
interleaved with these. In an earlier round, less loaded, that variant was level with brotli4j or ahead: 12.4–12.5 µs a
pass against 12.7–12.8 on the demo's stream, 35.0–35.2 against 35.3–36.7 on the large island.

The tests decode brotli with Google's pure-Java decoder, `org.brotli:dec` 0.1.2, last published in 2017, as brotli4j's
doesn't load on musl. It decodes a whole stream only: given a stream so far, it returns nothing. A flushed brotli stream
ends with a whole metablock, at a byte's boundary, so the test appends one byte, 0x03, an empty last metablock, and
decodes that; bytes cut anywhere else fail to decode, as a stream short 5 bytes did. brotli4j is gone from the
dependencies, the dev alias's too.

In Chrome, under `bb dev` without brotli4j on the classpath and the demo's encodings put `[:br :zstd :gzip]` for the
check, the stream came as `br`, from `BrotliEncoder`, and the page updated live: five frames in 3 s, the ticker's among
them three times. With curl, 4 s of the stream were 2,032 bytes, decoding to 19,903, 21 events.

`bb test`: 127 tests, 713 assertions. `bb test:linux all`, twice: the same on each of the four, brotli included on musl,
but for one failure in the second run, on amd64, outside compression. `resource_test`'s `closing-undoes-the-subject`
found a closed resource's entry still registered: `close-if-idle!` cancels the subject's task, compelled, before it
removes the entry, so the task's `finally`, which the test waits on, can run first. amd64 again: no failures. Perhaps
the failure on arm64 of the entry before, whose output wasn't kept.
