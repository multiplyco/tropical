# 2026-10-15 — Robustness review follow-up

The resource regression now runs 1,000 rounds. Independent review measured this variant in fresh JVMs: it caught
the old race in 19 of 20 runs, while the fixed code failed in none of 22 runs, taking about 390 ms per run. Detection
is probabilistic: a passing run does not establish that the old race is absent. The additional rounds improve its
chance of catching a regression at a small cost to the suite.

Release cleanup remains best-effort even for fatal throwables. Its regression now covers a synthetic
`OutOfMemoryError` as well as an `AssertionError`, through dropping holds, unmounting, disposal and an aborted render,
and checks that cleanup preserves the render's original exception. The ownership reason is recorded in the design:
some holds are already outside the instance table, so rethrowing would leave them beyond the pump's later disposal.

Validation after these follow-ups: 189 JVM tests with 6,190 assertions and all 11 Node tests pass, along with lint
and formatting checks.
