# 2026-10-15 — Waiting for SSE interruption cleanup in the test

`sse-test/await-returns-once-closed` asserted that the stream was closed as soon as cancelling its task settled.
That does not order the assertion after the interrupted thread finishes `EventStream.await`, which closes the
stream and restores the interrupt flag. The race noted earlier in this logbook was in the test.

The test now uses a startup promise instead of a fixed sleep before cancellation, and a separate promise completed
after the Java method returns. It waits for that completion before checking closure, and also checks the restored
interrupt flag. Both test branches use bounded waits and close their streams in `finally`. Library code is unchanged.

In separate local JVMs, repeating the original test 300 times produced seven failures; the revised test passed all
300 repetitions, with 2,100 assertions. The full SSE namespace passes six tests with 32 assertions, and all 11 Node
tests, lint and formatting checks pass. Linux containers were not run for this test-only change.
