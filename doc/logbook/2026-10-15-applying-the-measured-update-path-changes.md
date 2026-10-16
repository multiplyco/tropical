# 2026-10-15 — Applying the measured update-path changes

Implemented the three targeted changes from the profiling pass, in Clojure:

- When a parent has not rendered, reconciliation skips children outside the changed path before looking up their
  instances and comparing arguments. A parent that did render still checks every call it placed.
- Invalidation iterates a subscription's readers and each reader's selected values directly. Patch aggregation
  reuses its first child's immutable result and appends only nonempty categories, also when combining splices.
- When a list changes shape, surviving children reuse the ids already in its frame. Reconciliation edits a transient
  copy of the previous slots map, updating changed entries and removing dropped children, instead of rebuilding the
  map. It unmounts removed children after visiting the new calls, in the previous document order. The old frame stays
  immutable, and a rendered instance still records its current hold ownership before any child can abort.

Comparison: four fresh JVMs, baseline / optimized / optimized / baseline. The baseline prepended copies of
`runtime.clj` and `internal/frame.clj` from `4d918cd` to the classpath; each process recorded the resolved source URL
so the selected implementation was checked. Both versions used the same current benchmark file, JDK 27, Clojure
1.12.6 and 512 MiB heap. The second pair reversed the workload order. Each entry below is the range of the two
seven-batch medians, with the same two-second warm-up as the profiling pass. These are wall times in the local
synthetic workload, not HTTP latency or production capacity. No other benchmark or test ran concurrently.

| Update                                 |       Before |          After |    Allocation before → after |
|----------------------------------------|-------------:|---------------:|-----------------------------:|
| One row among 10                       |   9.5–9.7 µs |     7.4–7.5 µs |     21.7–22.1 → 15.6–15.8 KB |
| One row among 100                      | 22.6–24.3 µs |   11.2–11.6 µs |     22.3–22.7 → 16.2–16.4 KB |
| One row among 1,000                    |   166–174 µs |       57–59 µs |     23.1–23.5 → 17.1–17.2 KB |
| One row among 10,000                   | 3.78–3.94 ms | 0.587–0.599 ms |          23.9–24.0 → 17.6 KB |
| One row below 20 wrappers              | 24.7–27.2 µs |   20.1–22.2 µs |     75.3–75.4 → 39.0–39.2 KB |
| All 100 rows                           |   515–538 µs |     464–479 µs |     1.24–1.29 → 1.06–1.07 MB |
| All 1,000 rows                         | 6.57–7.07 ms |   5.95–6.52 ms | 12.95–13.38 → 11.16–11.27 MB |
| 1,000 selectors reject the update      |   219–298 µs |     137–142 µs |                403 → 23.5 KB |
| Add/remove one row in a 1,000-row list |   775–799 µs |     534–592 µs |                 689 → 361 KB |

The strongest time gains are about threefold for a sparse 1,000-row update and 6.4–6.6-fold at 10,000 rows. List
changes allocate about 48% less; rejected updates about 94% less. Broad-update allocation falls by 13–17%, while its
time improvement ranges from negligible to 16% across the paired rounds, so the allocation reduction is the clearer
result there. Output character counts and patch-category counts matched between versions for every workload.

The existing demo benchmark was also run in separate baseline and optimized JVMs, three times each, with 10,000
warm-up and 50,000 measured updates per run. Baseline: 13.19, 10.73, 10.91 µs and about 20.4 KB per update. After:
11.99, 9.94, 8.23 µs and 16.1–16.2 KB. Every run sent 188 characters. Timing overlaps; the approximately 21% lower
allocation is consistent. This benchmark renders and serializes patches, without the pump or SSE.

Two fresh ten-second JFR recordings locate the remaining costs. For one changed row among 1,000, reconciliation
accounts for about 48% of samples and `changed-slots` for 46%; the latter still scans siblings when comparing the
sent frame with the latest one. For list changes, rendering the parent, creating its calls and holes, and checking
its children dominate; child-id construction is no longer a major allocation site. A future change that avoids
these scans should preserve comparisons with older client frames, rather than assume every intermediate frame was
sent. This pass adds no Java classes or mutable frame representation.

Validation: `bb test` passes all eight Node tests and 169 JVM tests with 941 assertions; lint, formatting and
reflection checks pass. New regressions cover multiple selections of one source, simultaneous list edits and child
invalidations, surviving holds and remounts, disposal after a partially reconciled list aborts, and mixed sibling
patches against an older client frame. The existing randomized list-patching and render-failure tests also pass.
Raw paired measurements, source copies, demo results and the new recordings are in `target/perf/incisions`.
