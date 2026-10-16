# 2026-10-16 — Ordering browser observations and counting the current render's holds

Regression tests first reproduced two failures. A cookie watch accepted an older value after a newer one, because
requests could arrive out of order. An island acquiring a shared state before reading its session summary saw no
resource, and stayed that way: the acquisition preceded the registry subscription, and the instance's holds had not
yet been committed.

Browser watches now number sends per token. The server atomically keeps the sequence and value, accepting only a
newer sequence, and the page ignores responses to superseded sends. The tests cover older, repeated and malformed
sends, handler replacement on render, and late HTTP failures, reopen responses and network failures.

The session summary includes the current render's acquisitions, merged with its committed holds so an idempotent
read counts once. Its old holds remain visible until released after the render, which invalidates the registry.
Tests cover both hook orders, the first frame, replacement, release and reacquisition, and settling without extra
renders. No registry bookkeeping was added.

Validation: `bb test` passes 196 JVM tests with 6,241 assertions and 15 Node tests. Lint, formatting and diff whitespace
checks pass. Linux containers and browser smoke tests were not run; Java and native code are unchanged.
