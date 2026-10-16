# 2026-10-07 — Signals in Java, in place of a watch and a queue per session (proof of concept)

`dev/bench/signal.clj`. 1,000 sessions read one value; cost per change and reading session, on the publishing side.

|                                   | Watch + queue per session | `Cell`       | Atom through `RefSignal` |
|-----------------------------------|---------------------------|--------------|--------------------------|
| Session keeps up (1 change/drain) | ~152 ns, 506 B            | ~52 ns, 2 B  | ~54 ns, 2 B              |
| Session behind (10 changes/drain) | ~95 ns, 303 B             | ~8 ns, 0.2 B | ~8 ns, 0.3 B             |

Per frame (`dev/bench/frame.clj`, the table above), before and after, three alternating runs each: time unchanged
within noise (10.4–11.8 µs against 10.6–11.1 µs that day); allocation down ~200 B, 16.0 to 15.8 KB; chars sent
unchanged. The benchmark feeds changes through a test harness that was always cheaper than the real session's mailbox;
a live session also saves the event vector, the queue node and the drain set, nearer 0.5 KB. The gain that matters is on
the publishing thread, times the number of readers, and the bound on a stalled session's inbox.

Live smoke test, two tabs: one watch per atom read; each subscription value cell held one subscription per session. A
flood of 85M publishes in 3 s reached the client at ~20 frames per second (`frame-ms` 50) with at most 2 entries in an
inbox, and both pumps parked in `Inbox.await` (thread dump). Closing one tab dropped the atom's signal count by one and
the subscriptions to one; closing all left no sessions, signals, watches or registry entries (opened 2, closed 2).

While testing by hand through tools, a subscription seemed to reopen after a revoke and grant: the tool latency between
the two exceeded the 5 s linger. Revoking and granting 2 s apart kept the subscription. Not a bug.
