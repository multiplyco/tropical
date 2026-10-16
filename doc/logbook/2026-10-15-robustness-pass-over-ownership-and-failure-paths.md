# 2026-10-15 — Robustness pass over ownership and failure paths

Reviewed the resource registry, signal inbox and subscriptions, island ownership and frame reconciliation, session
delivery and reconnect handling, Ring page and stream setup, browser state, uploads and compression cleanup. The
existing ownership boundaries remain useful: the session pump owns mutable island state, frames are immutable
snapshots, and the registry owns shared resource lifetimes. No architectural rewrite was needed.

Fixed the following failure paths:

- A resource's `emit!` checked that it was open and then published in separate steps. Closing could pass between
  them, publish `Failed`, and have an already-running emission overwrite it. An isolated probe with six emitters
  reproduced two lost failures in 300 rounds before the fix, and none in 300 rounds after. Emission and closing the
  emission gate now share a per-resource monitor. Failure publication follows gate closure. Registry updates and
  cleanup callbacks stay outside that monitor, avoiding lock-order inversion with the registry.
- A hold release throwing an `AssertionError` interrupted reconciliation, unmounting or disposal, and could replace
  the original exception when abandoning a render. Releases now log any throwable and continue, as resource cleanup
  already did. Tests cover dropping holds, unmounting, disposal and an aborted first render, with every release
  attempted once and every watch removed.
- An exception while consuming an attach event escaped before the pump's normal failure boundary. The event was
  already out of the inbox, so neither its incoming stream nor the previously attached stream was necessarily closed.
  Each event now fails against the last owned state and answers the consumed event as well. A failing stream close
  also cannot interrupt the remaining cleanup or prevent later queued requests from being answered.
- An exception from the page or its revision left the new session alive until its grace period expired. Page setup
  now closes the session on any exception, preserving the original throwable.
- The browser upload queue reported XHR error events, but not exceptions from opening the request, setting a header
  or sending. Those rejected jobs left uploads marked as uploading. They now report failure and release their queue
  slot; Node tests exercise all three cases and continued processing within the three-transfer limit.
- Upload arrival and settlement callbacks throwing an assertion left inconsistent state or could run again on a
  repeated report. Their failure bookkeeping now covers throwables while rethrowing the original. If both callbacks
  throw the same exception object, it is no longer added to itself as a suppressed exception.

The new cleanup, page, attach and upload regressions failed against the previous code. Initial validation passed
189 JVM tests with 1,469 assertions and 11 Node tests, plus lint, formatting and reflection checks. Browser upload
tests are included in `bb test:browser`, and therefore in `bb test` and the host portion of `bb test:linux`. This pass
did not run a live-browser smoke test or the Linux containers. No Java source changed.

The new resource monitor was also checked locally against `8cbbd4f` in four fresh JVMs, before/after/after/before,
using JDK 27, Clojure 1.12.6 and a 512 MiB heap. Each case warmed for two seconds and measured seven batches of
500,000 alternating emissions. Subscriptions stayed dirty, modeling repeated emissions coalesced before a pump
drains them. Ranges are the medians from the two JVMs, in nanoseconds per emission:

| Subscribers |      Before |       After |
|-------------|------------:|------------:|
| 0           |     8.3–8.6 |   13.0–13.1 |
| 1           |   12.1–12.4 |   16.3–16.5 |
| 100         | 360.9–363.6 | 333.1–335.9 |

The small cases show about 4–5 ns of added uncontended cost. The 100-subscriber result is a JVM microbenchmark
observation, not evidence that synchronization improves delivery. This excludes contending emitters, rendering,
HTTP and browser work; concurrent publishers to the same resource now serialize intentionally. Independent
resources do not share this monitor. Probe scripts, the baseline source and measurements are in `target/robustness`.
