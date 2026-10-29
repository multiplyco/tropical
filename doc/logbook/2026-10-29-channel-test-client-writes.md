# Serializing the channel test client's writes

Integration review reported intermittent `Send pending` failures in the real-Jetty channel test. The JDK test
client sent handshake and acknowledgement text from listener callbacks without awaiting completion, while the
test thread independently sent binary application envelopes. Those writes could overlap.

The test client now routes handshake, acknowledgement, binary and pong writes through one per-connection lock,
held until the returned future completes, with a two-second timeout. The real-Jetty test also sends a burst of
32 alternating text and binary messages while the listener acknowledges echoes, and checks their arrival order.
The channel implementation and public API are unchanged.

Local verification: all 25 JVM channel tests passed, with 236 assertions. The real-Jetty test then passed 30
consecutive runs in one JVM, with 360 assertions and no failures or errors. Each run exercises the burst,
keepalive beyond the configured idle timeout, socket replacement and retained peer state. Lint, formatting of
the changed test and whitespace checks passed.
