# 2026-10-15 — Profiling the update path

Baseline: `4d918cd`, with no production-code changes during the measurements. Local macOS arm64, ten available
processors, Corretto 27+33-FR, Clojure 1.12.6, a fixed 512 MiB heap. These are local measurements with other desktop
work running, not capacity numbers or comparisons with earlier logbook entries.

`dev/bench/profile.clj` adds repeatable workloads and a JFR summarizer; the commands are in `development.md`.
Each timing case warms for two seconds, then reports the median of seven batches, with batch size chosen from the
warm-up throughput. Allocation comes from the calling platform thread's allocation counter. Separate ten-second
JFR recordings use 1 ms execution sampling and weighted allocation sampling at up to 1,000 events/s, after another
warm-up. CPU percentages below refer to the benchmark thread's execution samples; inclusive percentages overlap.
Allocation attribution is sampled, while the allocation totals in the tables are measured directly.

One step publishes through a Cell, drains an Inbox, reconciles the tree, prepares patches and writes a batch to a
null output stream. The sparse workloads have two ancestors above the list, with each row reading its own source.
The broad workloads have every row read the same source. The filtered workload has 1,000 readers of one source,
all selecting an unchanged value. The list-change workload alternates adding and removing its last row. Row HTML
is deliberately small. These exclude the session pump, its 50 ms pacing, HTTP, compression, browser work and real
resource I/O. The filtered case also prepares an empty batch, which a real session can skip.

First sweep (decimal KB and MB):

| Update                                 | Median time | Allocated per update |
|----------------------------------------|------------:|---------------------:|
| One row among 10                       |     11.7 µs |              22.1 KB |
| One row among 100                      |     26.6 µs |              22.6 KB |
| One row among 1,000                    |      198 µs |              23.5 KB |
| One row among 10,000                   |     4.89 ms |              24.0 KB |
| One row below 20 wrapper islands       |     27.4 µs |              75.3 KB |
| All 100 rows                           |      569 µs |              1.28 MB |
| All 1,000 rows                         |     7.95 ms |             13.33 MB |
| 1,000 selectors reject the update      |      297 µs |               403 KB |
| Add/remove one row in a 1,000-row list |      799 µs |               690 KB |

Every sparse update sends the same 85 characters, irrespective of list width. Subsequent warmed baseline runs put
one changed row among 1,000 at 167–198 µs, among 10,000 at 2.97–4.89 ms, and a broad 1,000-row update around
6.7–8.0 ms. Allocation varied less, though compilation and escape analysis moved the broad case to about 12.7 MB.
Timing differences smaller than these spreads need paired measurements.

Findings and priority:

1. **Visit fewer unchanged siblings.** In the 1,000-row sparse recording, `reconcile` accounts for 81% of samples,
   while `render-island` accounts for only 3.6%. `changed-slots` in patch planning accounts for another 14%.
   Reconciliation checks every child of an ancestor, including its instance, render function and arguments; patch
   planning then scans the child frames. The cost grows with the width even though just one row renders.

   An isolated experiment skipped child reconciliation outside `on-path` when the parent had not rendered. It
   still loops over siblings and retains the existing patch scan, but avoids their instance and argument checks:

   | Sparse update | Original, two paired rounds | Early skip, same rounds |
         |---|---:|---:|
   | 1,000 rows | 167 / 177 µs | 60 / 62 µs |
   | 10,000 rows | 4.29 / 2.97 ms | 0.59 / 0.71 ms |

   Allocation was effectively unchanged. This is a useful first change in Clojure. A later index from a parent to
   its affected children could avoid the sibling walk altogether. Any similar shortcut in patch planning must
   handle comparisons against an older sent frame, after coalescing or reconnecting, rather than assuming the client
   has the immediately preceding frame.

2. **Remove temporary collections in invalidation and patch aggregation.** `dirty-islands` builds lazy sequences,
   `[sub id]` pairs and a predicate per reader. It accounts for 98.6% of samples and almost all allocation in the
   filtered workload. In a small sparse update, `prepare-frame` accounts for about 46% of sampled allocation;
   merging child patch maps is about 24% by itself. Each ancestor currently combines a fresh patch result even when
   there is only one changed child.

   A second isolated experiment used direct iteration for invalidation, returned the first child's immutable patch
   result directly, and appended only nonempty patch categories thereafter. Two paired rounds, reversing the order
   in the second round:

   | Update | Original time | Experiment time | Original allocation | Experiment allocation |
         |---|---:|---:|---:|---:|
   | One row among 10 | 10.6–10.7 µs | 8.6–9.1 µs | 21.5–21.6 KB | 15.6–15.7 KB |
   | One row below 20 wrappers | 25.2–25.3 µs | 20.0–20.1 µs | 75.1–75.2 KB | 39.0 KB |
   | 1,000 selectors reject the update | 235–241 µs | 143–174 µs | 403 KB | 23.6 KB |

   The 1,000-row sparse update remained dominated by sibling traversal. Broad updates allocated about 13% less,
   but their timings were inconsistent, so no broad-update speedup is established. These changes need no new Java.

3. **Preserve list bookkeeping across small structural changes.** Adding or removing one child loses the
   `:kept?` path: all child ids, the slots map and the placed-id set are rebuilt. In the list-change recording,
   child-id construction accounts for about 35% of sampled allocation. Reusing surviving ids and updating the
   previous slots map is a better candidate than further optimizing the existing Java splice detector, which is
   only about 8% of CPU samples here.

4. **Consider a typed, mutable instance only after the traversal changes.** Broad updates spend about 57% of samples
   in `render-island`, including 18% in compilation. Instance updates, read bookkeeping and persistent-map operations
   contribute substantial allocation. A session-owned Java instance with direct fields and reusable read storage
   could express its single-threaded ownership and reduce allocation. Clojure `deftype` is another option. Keep the
   frames used for sent/latest comparison immutable, and preserve installation of current hold ownership before
   descending into children, including when a render aborts.

   A smaller experiment batched `render-island`'s instance-map updates through a transient. It saved only about
   0.54 MB of a 12.7 MB broad update (4%), with little timing benefit. That alone does not justify a representation
   rewrite. Repeated element-id hashing is secondary too: about 4% of broad-update CPU samples, though caching it
   per mounted instance could save allocation without an unbounded global cache.

The existing primitive benchmarks were also run in an isolated JVM. Signals cost 56 ns per publishing change and
reading session through Cell, 61 ns through RefSignal, against 190 ns for a watch and queue; the signal paths allocate
about 2.1 bytes per session/change. When ten publications coalesce before a drain, the signal paths cost about 8 ns
per publication/session. A small SSE event costs 0.278 µs and 440 bytes; a 26.7 KB event costs 27.8 µs and 27.3 KB;
a signals event costs 0.305 µs and 512 bytes. The demo's render/patch/HTML benchmark gives 15.2 µs, 20.5 KB and
188 characters per ticker change. The signal and SSE primitives are not the first optimization targets.

A registry-only exercise kept one subscriber per resource, repeatedly acquired/released an additional subscriber,
and verified that the retained subscriptions survived. An uncontended pair cost about 0.42 µs and 496 bytes;
a warmed virtual-thread worker reached roughly 3.5–4.4 million pairs/s. Eight workers on distinct keys together
reached 1.6–1.7 million pairs/s, consistent with the registry's single lock. This exposes a ceiling for heavy resource
churn, not a cost paid by every render: existing holds reuse their acquisitions. No registry rewrite is indicated
without a workload dominated by simultaneous mounts and unmounts. All exercise resources were released afterwards.

The prototypes were confined to throwaway files under `target/perf` and separate JVMs. Invalidation and patch
results were compared with the original functions on the same inputs across all nine workloads. With the early
skip and collection changes installed together in an isolated JVM, the runtime, island and session suites passed:
41 tests, 180 assertions. These are promising prototypes, not a completed production optimization. Recordings,
summary EDN, timing samples and prototype scripts remain under `target/perf`; no optimization was installed in
`src`. Network latency, browser rendering, compression, retained heap and multi-session end-to-end throughput were
not measured in this pass.
