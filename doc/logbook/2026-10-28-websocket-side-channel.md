# WebSocket side channel

The first implementation of `co.multiply.tropical.websocket`, checked locally on 2026-10-28 with JDK 27,
Ring's Jetty adapter 1.15.5 (Jetty 12.1.8), Node.js and Chrome. These are correctness and browser smoke checks;
no performance comparison was taken.

## Automated checks

`bb test`: 215 JVM tests, 6,370 assertions, no failures; 33 browser JavaScript tests passed. `clj-kondo --lint src test
dev`, `cljstyle check`, `git diff --check` and `bb version:check` passed. Java was compiled and the development JVM
restarted before the final browser checks.

Coverage includes independent connections over a shared resource, stable declarations through rerenders, binary
buffer ownership, asynchronous initialization and serial receives, FIFO writes including cancellation of a caller's
wait, message/queue limits, failed and timed-out sends, heartbeat failure, late initialization cleanup, session
retention without SSE, explicit revocation, and attachment racing revocation. HTTP tests cover user, route and
Origin checks and missing-session recovery. A real Jetty test roundtrips repeated binary messages and Unicode text,
then leaves the connection quiet for 450 ms against a 150 ms adapter idle timeout with control pings every 40 ms,
before sending again.

Browser-helper tests use controlled sockets, fetches and timers. They cover retry exhaustion, terminal refusals,
bounded recovery across descriptors, preservation of application state, no replay, stale callbacks, bounded
initialization queues, disposal and page suspension.

## Chrome

The demo's shared room was exercised through its normal nonce-based CSP:

- Text and binary messages arrived, with the room's server render count unchanged.
- An explicit server rerender preserved the component's draft and message history.
- Two tabs used one shared room resource and both received a binary message.
- A hidden tab remained connected well beyond the session's 15-second grace period. The REPL confirmed its SSE
  attachment was false. Closing only its physical socket with the retryable transport code reconnected it; showing
  the tab retained its draft, received history and server render count.
- Closing that tab's server session let SSE rebuild it and replace its descriptor. The existing component retained
  its draft and message history and returned to `open`.
- Unmounting the room removed the component; the server registry then contained zero capabilities for that tab.

The live checks caught a session-identity mistake: attaching SSE replaces the session registry's map to update its
display metadata. Capability checks must compare the stable inbox identity, not the map object. A regression test
attaches SSE before checking and upgrading a capability.

## Adapter finding

Both `info.sunng/ring-jetty9-adapter` 0.40.4 and 0.40.5 ignore the callback passed to `onWebSocketBinary` in their
published source. The generic transport cannot complete a callback the adapter does not expose. The demo and
integration tests therefore use `ring/ring-jetty-adapter` 1.15.5, whose binary receive path works. Tropical depends
only on Ring's protocol artifacts, so an app can use any working implementation of `Socket` and `AsyncSocket`.

App-specific document bootstrap, peer synchronization, persistence and recovery have not been migrated by these
checks; application review and adoption remain separate.
