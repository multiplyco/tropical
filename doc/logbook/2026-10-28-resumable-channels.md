# Resumable channels

The socket-owned prototype was replaced by `co.multiply.tropical.channel/use-channel` on 2026-10-28. The public
abstraction is an ordered conversation whose handlers and buffers survive transport replacement. An initializer
receives `:initial` or `:continuity-lost`, with `send!`, `flush!` and the generation identifier. This changes the
prototype API and wire protocol; there is no compatibility layer for that unreleased API.

## Mechanism

Generation identities and cumulative receipts distinguish resume from reset. Outgoing messages remain buffered
until the peer acknowledges admission. Retransmissions are suppressed before application dispatch. Cumulative
message and byte credits bound receiver memory, including its active handler. Text and binary application messages
share a binary transport envelope with a type byte and an eight-byte sequence number; payload interpretation stays
with the application.

The Java buffers encode bounded admission, retention until acknowledgement and input sealing without discarding.
Accepted input is processed by its generation's compelled worker, even after the socket or owning island goes
away. Cleanup follows that drain. Outgoing delivery has a separate deadline. A failing handler is reported and
ends new admission, but remaining accepted messages are still invoked. Resources needed by the handler must be
owned through its cleanup, independent of unrelated outer-resource cleanup.

Sends acknowledge local admission; an explicit flush waits for peer receipt. A backend change or other loss of
generation state can cause a reset. Reset drains old input and cleanup before initializing the new generation,
and never transfers old outgoing wire messages into it. The application resynchronizes its retained document.
These guarantees rely on retained memory; durable acceptance across process loss remains a separate application
commitment.

## Verification

Local `bb test`: 219 JVM tests, 6,426 assertions and 40 JavaScript tests passed. The channel tests include real
Jetty binary/Unicode roundtrips, keepalive past a short idle timeout, resume with the same peer, offline sends,
lost acknowledgements, replay, duplicate suppression, byte/message credits, invalid receipts, explicit reset,
unconfirmed handshakes, initialization barriers and input draining without a client. The browser tests also cover
admission before the first connection, page suspension, descriptor replacement and stale callbacks.

Two retirement races were covered during implementation: neither old cleanup nor an old receive-handler failure
may close a replacement transport that is waiting for the previous generation to drain. Isolated island tests can
also declare and release a channel without an actual session or transport.

The demo JVM was started fresh after compiling the Java buffers. In Chrome:

- Text and binary messages arrived through the channel, without increasing the room's render count.
- Closing only the physical socket reconnected with the peer-initialization count still at one. The component's
  draft and message history remained.
- Closing the server session let SSE rebuild the page's islands and replace the descriptor. The same component
  retained its draft and history, initialized peer state a second time, and reported `continuity-lost`.

Lint, reflection-enabled loading, formatting of source/tests/demo, version synchronization and whitespace checks
passed. Repository-wide formatting still reports the existing SCI import formatting issue; that unrelated file was
left unchanged. No benchmark was taken and no performance improvement is claimed.
