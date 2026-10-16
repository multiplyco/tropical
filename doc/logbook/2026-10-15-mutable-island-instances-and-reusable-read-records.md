# 2026-10-15 — Mutable island instances and reusable read records

Compared a Java bookkeeping prototype with `1211508`, then kept it after the tests and measurements below.
`runtime.Instance` holds the mutable fields of one mounted island, owned by its session pump. Rendering updates
those fields directly; the instance table changes only when an island mounts or unmounts. Frames, templates, and
frame slot maps remain immutable snapshots, including across a failed render and a reconnect.

`ReadBuffer` keeps each selection separately, alongside distinct subscriptions for ownership. Two buffers let a
render collect its reads without overwriting the committed ones. Success reconciles ownership and swaps them;
an escaping failure releases only new acquisitions and clears the tentative reads. Clearing releases references
and reuses records and arrays. Large contractions discard spare capacity without allocating during cleanup. Small
read sets need no index; larger ones use a reusable identity index, avoiding quadratic ownership comparisons.
Selectors are stored as objects so even an invalid, non-callable selector is recorded before its failure escapes.

There are no new locks or atomics: only the pump accesses instances and read buffers. The state atom used by island
callbacks remains an atom. Resource sharing, subscription delivery, rendering functions and the patch algorithm
are unchanged. The existing instance table also remains a persistent map; this comparison does not combine the
representation change with a new traversal algorithm or a mutable lookup table.

Final comparison: baseline / Java / Java / baseline in four fresh JVMs, reversing the workload order in the second
pair. The baseline uses the runtime source from `1211508` ahead of `src` on the classpath. Each process records its
resolved runtime URL. Both use the same benchmark, Corretto 27+33-FR, Clojure 1.12.6, macOS arm64 and a fixed
512 MiB heap. Two seconds warm-up per case, then seven timed batches without a profiler; ranges below are the two
batch medians, not confidence intervals. Thread allocation is measured separately from elapsed time within each
batch. Tests and other benchmark processes did not run concurrently. Compare these pairs with one another, not
with the earlier logbook timings from a different sitting.

| Update                            |         Before | Java bookkeeping |  Allocation before → after |
|-----------------------------------|---------------:|-----------------:|---------------------------:|
| One row among 10                  |   6.95–7.03 µs |     4.38–4.55 µs |   15.8–16.1 → 10.8–10.9 KB |
| One row among 100                 | 10.45–10.81 µs |     7.89–8.28 µs |   16.4–16.7 → 10.9–11.0 KB |
| One row among 1,000               | 53.26–53.56 µs |   48.73–52.46 µs |   17.2–17.5 → 11.0–11.1 KB |
| One row among 10,000              |     510–515 µs |       462–525 µs |   17.7–18.0 → 11.2–11.3 KB |
| One row below 20 wrappers         | 18.03–19.31 µs |   10.86–11.13 µs |   39.3–39.4 → 24.0–24.2 KB |
| All 100 rows                      |     450–451 µs |       250–254 µs | 1.08–1.11 → 0.695–0.698 MB |
| All 1,000 rows                    |   5.51–5.53 ms |     2.98–3.07 ms | 11.35–11.62 → 7.14–7.22 MB |
| 1,000 selectors reject the update |     128–138 µs |         59–60 µs |   23.5–23.6 → 23.5–23.6 KB |
| One island reads 128 sources      | 53.95–55.11 µs |   19.71–20.34 µs |   85.6–86.6 → 14.1–14.2 KB |
| Add/remove one row among 1,000    |     411–469 µs |       364–423 µs |               361 → 353 KB |

The broad-update case takes about 44–46% less time and allocates 36–39% less. The many-read case allocates about
84% less. Filtered updates get faster without an allocation reduction: direct field access helps even when no
island renders. Wide sparse updates remain dominated by sibling scans, with no consistent time improvement at
10,000 rows. List changes save little allocation, since constructing the parent's calls and template still dominates.
The benchmark's final instance counts, payload character counts and patch-category counts match in every case.
These timings include publication, reconciliation, patch preparation and uncompressed writes to a null stream,
excluding HTTP, browser work and the session's pacing.

The demo's existing render/patch/HTML benchmark was run three times per implementation in fresh JVMs, with 10,000
warm-up and 50,000 measured updates per run. Before: 10.28, 8.34, 7.71 µs and 16.2–16.3 KB per ticker update.
After: 7.46, 5.65, 4.79 µs and 11.2–11.3 KB. Every run sent 188 characters. The approximately 31% allocation
reduction is consistent; these short timing runs also show improvement, with substantial warm-up variation.

Retained heap was checked separately, with three batches of 20 sessions, each containing 1,000 rows with independent
sources. Every row was updated before measuring, so both read buffers had been populated; the benchmark's retained
prepared payload was cleared. Heap usage was read after two explicit collections before and after each batch.
Before: approximately 1.820–1.821 MB per session. After: 1.557 MB, about 14.5% less. This includes the fixtures'
source cells, calls, frames and null-output connection, not just instances, and excludes compression/native memory.
An earlier sparse-only check gave about 20% less; the fully populated buffer comparison is the more conservative one.

Validation: all eight Node tests and 171 JVM tests with 1,070 assertions pass, as do lint, formatting and reflection
checks. New regressions grow, shrink, reorder and empty a large read set, exercise multiple selectors of one source,
and verify ownership after a non-callable selector fails. Existing tests cover failed-read recovery, aborted first
and later frames, ancestor and child hold ownership, retained frames, and patches against older client frames.
Raw results, baseline/prototype source copies, and comparison scripts are under `target/perf/java-instance`;
`*-release-*` contains the final timing comparison. The development JVM needs a restart to load the new Java classes.

After restarting the development JVM, an isolated REPL smoke check confirmed that mounted instances use
`co.multiply.tropical.runtime.Instance`: a source update rendered twice in total, the earlier frame stayed unchanged,
and disposal removed every watch from the source.
