# WebSocket recovery review

Application review found two recovery faults in the initial WebSocket implementation. Both were reproduced locally
and corrected on 2026-10-28.

## Transport failures were classified as application failures

The writer's exception handler closed with `1011`, which the browser correctly treats as terminal. Failed writes,
write timeouts and thrown control-ping errors now use retryable transport code `4000`. Initialization and receive
handler failures retain `1011`.

The close must be established before failed send tasks wake their callers. A receive handler or constructor may
return a send task, so failing that task first could let the application's exception handler win the race and close
with `1011`. Regression tests cover callback failures, timeouts and synchronous write exceptions returned through
both kinds of handler, as well as thrown pings and genuinely terminal application failures.

The review's server probes now report `4000` for all three transport failures and settled send tasks. Browser tests
keep the distinction explicit: `4000` reconnects; `1011` does not.

## Recovery depended on a changed descriptor

`tropical:reopen` suspended sockets until a replacement descriptor arrived. SSE can reconnect to its original
session with the original capability still valid, without patching the DOM at all. An unchanged descriptor passed
to `update` was also ignored.

Recovery now revalidates the current capability with bounded backoff, while accepting descriptor updates. A valid
unchanged descriptor can resume immediately through `update`, or through revalidation without a DOM mutation.
`recoveryTimeout`, 10 seconds by default, bounds the whole recovery cycle, including connection initialization.
Repeated reopen events and replacement descriptors do not reset that deadline. Expiry aborts pending work and
reports terminal failure. Stable connections reset the budget for later independent recovery cycles.

The review's unchanged-descriptor browser probe passes. Additional tests cover no DOM mutation, a revoked old
descriptor awaiting replacement, authentication refusal, stalled checks and initialization, late callbacks,
disposal, page suspension and the recovery budgets.

## Receive admission

The serial receive contract is unchanged and clarified in the guide. A returned task delays the next dispatch;
disconnect discards frames still in Tropical's queue. An application with its own bounded document queue can admit
synchronously and return after admission, leaving processing to that queue's worker. This transfers only messages
whose handlers ran; it does not promise immediate application acceptance of every arriving frame. Ordering,
durability and recovery of unaccepted messages remain the document protocol's responsibilities.

## Validation

`bb test`: 218 JVM tests, 6,395 assertions, no failures; 44 browser JavaScript tests passed. The WebSocket subset has
21 JVM tests with 136 assertions and 29 JavaScript tests. Lint, formatting of the changed Clojure files and
`git diff --check` passed. The repository-wide formatting check separately flagged an already modified SCI import;
that unrelated file was left untouched.
