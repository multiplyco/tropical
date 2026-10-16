# 2026-10-07 — The wire in-house

The Datastar Clojure SDK (1.0.0-RC11, the API and its Ring adapter) is replaced by Datastar's SDK specification
implemented here: `EventStream` (Java) for the transport, `sse` for the events, `ring` for the response body and for
reading signals. The only new dependency is `ring-core-protocols`. The wire is pinned to the specification by tests.

Per event, from `bench.sse`: a platform thread after a warm-up, the thread's allocation counter, events written to a
null output stream. The SDK was measured the same way, in the same JVM, before it was removed; signals as a pre-encoded
JSON string for both.

| Event                 | SDK                 | In-house                    |
|-----------------------|---------------------|-----------------------------|
| Patch, one island     | 2.2–2.6 µs, 10.4 KB | 0.25 µs, 0.33 KB            |
| Patch, 27 KB resync   | 39–43 µs, 250 KB    | 25.6 µs, 27.3 KB (presized) |
| Signals (pre-encoded) | 3.1 µs, 10.0 KB     | 0.23 µs, 0.34 KB            |

Unpresized, the resync allocated 81 KB: the event's builder regrowing. `signals!` with Charred encoding the map takes
2.0 µs and 17.9 KB, nearly all of it Charred's, which the SDK path paid too. Next to a frame (about 10 µs and 16 KB in
`bench.frame`), a patch saves about 2 µs and 10 KB per session.

Live, Jetty 12 with sync handlers: the stream, a click action and a post with signals under the CSP without
`'unsafe-eval'`. With curl, a second page load of one tab got the reload event exactly as the specification has it, and
its stream closed; the first kept streaming. The heartbeat is now an SSE comment: Datastar's client skips comment
lines, and ignores a message whose event type doesn't start with `datastar` (both read in the 1.0.4 bundle). Not seen
live, since the demo is never quiet; the wire test covers it. Cancelling a Quiescent task blocked in
`EventStream.await` interrupts it, which closes the stream (test).

`act` now refuses signals it can't parse (400) and a body something read before it (500), where it read `{}` before.

Looked like a bug, wasn't: no `Connection: keep-alive` on the wire. The response sets it for HTTP/1.1; Jetty 12 drops
it, keep-alive being HTTP/1.1's default.

Read in `ring-jetty9-adapter`'s source, not run: its async handler completes the response as soon as
`write-body-to-stream` returns, though Ring allows an async body to keep writing. There, a stream needs the sync
handler.
