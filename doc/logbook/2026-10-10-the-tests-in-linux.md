# 2026-10-10 — The tests in Linux

`bb test:linux` runs the suite in Docker, under JDK 25 (Temurin 25.0.4.1, the official Clojure images): Debian 13 (glibc) or Alpine 3.24 (musl), each for amd64 and arm64. The repository is mounted read-only, with the classes
compiled on the host; the dependencies are kept in a volume. On an Apple-silicon Mac, arm64 runs natively, in about
20 s, and amd64 under emulation, in about a minute.

- glibc, amd64 and arm64: 127 tests, 707 assertions, no failures.
- musl, amd64 and arm64: brotli's five loading tests errored. brotli4j's Linux library needs glibc's loader (`ld-linux-aarch64.so.1` on arm64), and brotli4j builds none for musl. tropical's libzstd ran, as it did in the
  checks before. With brotli left out (`TROPICAL_TEST_SKIP_ENCODINGS=br`, which the task sets for musl): 127 tests,
  695 assertions.
- One run on arm64 with musl had one failure, whose output wasn't kept. Ten runs since, on arm64 with glibc and with
  musl, had none. Likely the race in `sse_test` known before; not established.

A Docker Desktop that quit during a run left the next runs failing to reach its socket, which reads as each target
failing at once.
