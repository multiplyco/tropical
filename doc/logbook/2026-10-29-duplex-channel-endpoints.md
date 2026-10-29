# Duplex channel endpoints

Application integration exposed lifetime bookkeeping around the receive/cleanup callback API. The JVM API now
provides a core.async duplex endpoint for each logical generation. The callback receives `:channel`, `:reason` and
`:generation`, and runs the peer's lifetime or returns its task/completion channel. It can use a serial take loop
and finally cleanup. Tropical protects that lifetime from unmount and transport cancellation, and awaits it before
providing a replacement endpoint after continuity loss. The browser retains its JavaScript callback API.

Ordinary reconnects retain the identical endpoint. Permanent retirement closes both admissions, preserves accepted
input through EOF, and discards outgoing messages. Pending puts complete as they are consumed into the discard
sink; new puts return false. This avoids stranding parked puts when the remote peer is gone. Put completion still
establishes local admission only. The public `channel/flush!` waits for receipt of committed puts; callers must
await put completion first if they want a pending put included.

The port implementation commits core.async handlers under their locks and invokes callbacks outside those locks.
Cancelled alts operations neither consume messages nor enter the wire sequence. Pending puts have a separate
byte/message budget equal to the retained outbox's limits. Incoming credits now return on take; application-owned
work is outside the queue budget. The old JVM receive timeout and outgoing drain deadline no longer apply:
processing belongs to the application's task, and retired output is discarded immediately.

Added `org.clojure/core.async` 1.9.865. The demo room uses a port and a receive loop; the guide, design notes,
security guide, changelog and application-agent skill describe the new ownership and acknowledgement boundaries.

Local verification: `bb test` passed 232 JVM tests with 6,588 assertions and 43 JavaScript tests. Tests cover
core.async blocking, callback, go and alts operations; losing alternatives; pending-put ordering, copied binary
bytes and budgets; EOF; retirement with blocked producers; unchanged endpoints on resume; and fresh endpoints
only after processing and asynchronous finally cleanup. They also cover unmount during resource acquisition,
an application returning a go completion channel, terminal task failures and the existing transport regressions.
Lint, source/test/demo formatting, version synchronization and whitespace checks passed.

The development JVM was restarted with the new dependency and compiled Java buffers. Chrome and the in-process
REPL confirmed text and binary delivery with the room's render count remaining one. A forced socket interruption
preserved the identical endpoint and one peer initialization. Explicit generation retirement produced a distinct
endpoint and a second initialization marked continuity-lost; puts to the old endpoint returned false and its input
returned nil. Unmount closed the endpoint and completed the application's cleanup. Remount restored a healthy room.
