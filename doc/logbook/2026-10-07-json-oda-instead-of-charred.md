# 2026-10-07 — JSON: Oda instead of Charred

`signals!` cost 2 µs and 17.9 KB, nearly all of it Charred's `write-json-str`, whose `io/writer` wraps the output in a
fresh `BufferedWriter`: a 16 KB buffer per call. Charred's `JSONWriter` used directly avoids it. Oda was faster still.
Measured in a separate JVM (JDK 27), on the main thread after a warm-up of the same length, with the thread's allocation
counter; both libraries' output parses back to the same data.

| Per call                            | `write-json-str` | Charred's `JSONWriter` | Oda            |
|-------------------------------------|------------------|------------------------|----------------|
| Write the footer's signals (120 B)  | 985 ns, 17.5 KB  | 612 ns, 1.0 KB         | 154 ns, 168 B  |
| Write 1.3 KB, some of it non-ASCII  | 4.3 µs, 26 KB    | 4.0 µs, 11 KB          | 1.8 µs, 2.8 KB |
| Parse an action's signals (`parse`) | 372 ns, 2.0 KB   | (as `read-json`)       | 202 ns, 784 B  |

Tropical now uses Oda (`com.s-exp/oda`, zero dependencies, on Clojars) for both directions. Oda's classes are Java 25,
so tropical's floor is JDK 25, and its own Java compiles with `--release 25`. Its Vector API path is optional: without
the incubator module it scans scalar. Charred escaped non-ASCII and `/` by default; Oda writes UTF-8 as is. Both are
valid JSON, and the wire test's output was unchanged.

Live, after a restart: `bench.sse`'s signals event, encoding included, went from 2.0 µs and 17.9 KB to 324 ns and
512 B. In the browser, a post whose signals carried non-ASCII text arrived intact, and its returned signals cleared the
input.
