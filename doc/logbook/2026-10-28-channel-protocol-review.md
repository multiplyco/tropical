# Channel protocol review

Review of the resumable channel implementation found two protocol failures, reproduced in JVM and browser
regression tests before fixing them.

JSON text frames accepted arbitrary tags, including the internal `data` representation produced by decoding a
binary envelope. A peer could therefore supply an object or array to an application handler, or forge a payload's
byte count. Both decoders now accept only the handshake and acknowledgement control tags in JSON frames.
Application data always passes through binary envelope decoding, which establishes its type, size and sequence.

Outgoing queues filtered by available credit before checking the peer's permanent message-size limit. A message
larger than the peer's entire receive capacity could wait forever. Both senders now inspect the next message's
size before waiting for message or byte credit. An oversized message ends the channel and fails pending delivery
waits. Temporary credit exhaustion still pauses valid messages without removing them.

Both endpoints also reject configuration where the maximum message size exceeds the queued-byte limit, including
defaults. This keeps the advertised maximum within the receive queue's capacity. The guide documents this
constraint and the need to retain refused application messages for retry when generation advances protocol state.

Local verification: `bb test` passed 222 JVM tests with 6,494 assertions and 43 JavaScript tests. The targeted
channel suite has 25 JVM tests with 235 assertions and 28 JavaScript tests. New regressions cover forged object,
text and array payloads, oversized Unicode and binary output with insufficient credits, failed delivery waits,
and inconsistent limits. Existing tests still cover waiting for replenished credits and normal binary/text
delivery. Lint, source/test/demo formatting and whitespace checks passed. The Java buffers were recompiled and
the demo JVM restarted.
