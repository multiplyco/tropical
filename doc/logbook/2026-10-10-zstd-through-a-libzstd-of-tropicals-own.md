# 2026-10-10 — zstd through a libzstd of tropical's own

zstd-jni couldn't serve an app whose dependencies pin an older zstd-jni, from about 2020: the classpath holds one
version. Shading it doesn't work. Its 1.5.7-22 macOS library exports 149 functions named
`Java_com_github_luben_zstd_…`, the names the JVM binds its native methods by, has no `JNI_OnLoad` or
`RegisterNatives` that would let a relocated class bind them, and names `com/github/luben/zstd/ZstdFrameProgression`
itself. aircompressor's pure-Java `ZstdOutputStream` (0.27) doesn't override `flush`. So tropical builds libzstd and
calls it through the foreign function API (`ZstdEncoder`).

The source: zstd 1.5.7, the latest release (February 2025; the development branch reads 1.6.0, unreleased). The
tarball's SHA-256, `eb33e51f…6fa3`, matches the one published with it, and its signature verifies against "Zstandard
Release Signing Key <signing@zstd.net>", `4EF4 AC63 455F C9F4 545D 9B7D EF8F E995 28B5 2FFD`, from
keyserver.ubuntu.com. zstd's release workflow signs as that address.

The build (`native/build-zstd.sh`): zstd's `libzstd-nomt` target, with `ZSTD_LIB_DECOMPRESSION=0`,
`ZSTD_LIB_DICTBUILDER=0` and `-DZSTD_TRACE=0`. macOS with Apple clang 21, one universal file for arm64 and x86_64,
macOS 11 and later; Linux with gcc 12.2 in Debian 12 images pinned by digest, linked `-Bsymbolic`, with a soname of its
own.

| Library                    | Bytes   | SHA-256       |
|----------------------------|---------|---------------|
| `darwin/libzstd.dylib`     | 978,000 | `982423ca…fa` |
| `linux-x86_64/libzstd.so`  | 595,984 | `c86f281e…9e` |
| `linux-aarch64/libzstd.so` | 526,400 | `8e061648…1b` |

Each exports zstd's 104 functions and nothing else. The Linux ones import `malloc`, `calloc`, `free`, `memcpy`,
`memmove` and `memset`, with symbol versions up to GLIBC_2.14 (x86_64) and 2.17 (aarch64). Without
`-DZSTD_TRACE=0` they also imported zstd's weak tracing hooks, which any library in the process defining them would
have received. The macOS file came out the same, byte for byte, from two builds.

200 frames of patches, each written and flushed, as one stream, at level 3 with a 2^18 window: 4,153 bytes, the same
bytes from tropical's encoder on macOS (arm64), on Ubuntu 26.04 and Alpine 3.24 (musl, no glibc shim) for amd64 and
arm64 under Temurin 25 in Docker, and from zstd-jni 1.5.7-22. Ubuntu's own libzstd.so.1 (1.5.7), named by
`co.multiply.tropical.zstd.library`, wrote the same bytes too. musl's loader takes `libc.so.6` as itself, so the glibc
build runs there unchanged. A wrong path, or a missing library, fails with what to set.

On the demo's captured stream and the large-island workload of 2026-10-09, one fresh JVM per configuration (JDK 27,
macOS arm64), timing after 400 warm-up runs, memory over 500 streams:

| zstd 3, window 2^18     | Wire, demo | µs a pass, demo | µs a pass, large | Memory, demo | Memory, large |
|-------------------------|------------|-----------------|------------------|--------------|---------------|
| zstd-jni 1.5.7-22       | 14,905     | 1.9–2.0         | 6.0–6.6          | 532 + 131 KB | 712 + 131 KB  |
| tropical's, through FFM | 14,905     | 2.1–2.3         | 6.8–7.6          | 474 + 9 KB   | 669 + 9 KB    |

Memory is a stream's, resident and on the heap.

zstd-jni keeps a 128 KiB output buffer on the heap; tropical's encoder has 8 KiB buffers, native. zstd's own
allocation, `ZSTD_sizeof_CCtx`, is 1.25 MB a stream, of which a stream touches what its window fills. The first
version passed the arena's segments to each call, and took 3.1–3.4 µs a pass on the demo's stream: a shared arena's
segments are acquired and released, atomically, around every call that takes them. Passing plain addresses, guarded by
the encoder's lock instead, took that back. What remains is the copy of each write into native memory, where zstd-jni
pins the Java array for the call, holding off the garbage collector while it compresses.

In Chrome, under `bb dev` without zstd-jni on the classpath, the demo's stream was zstd, from `ZstdEncoder`, and the
page updated live: the ticker's reading advanced three in 3 s.

`bb test`: 127 tests, 707 assertions.
