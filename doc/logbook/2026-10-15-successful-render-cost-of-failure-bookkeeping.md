# 2026-10-15 — Successful-render cost of failure bookkeeping

Compared the pending failure bookkeeping with `e526a99`, using independently compiled source snapshots and matching
Java classes. The local baseline remains JDK 27 (`27+33-FR`), Clojure 1.12.6, macOS arm64, with a fixed 512 MiB heap.
No profiler or server was started. All ten `bench.profile` workloads ran in fresh JVMs in baseline/current/current/
baseline order, reversing workload order in the second pair. Each workload used the usual two-second warm-up and
seven timing/allocation samples. Prepared character counts, patch counts and mounted instance counts matched.

Ranges below are the medians from the two JVMs for each variant, in microseconds per update:

| Workload                |      Before |     Pending |
|-------------------------|------------:|------------:|
| Sparse, 10 rows         |   4.19–4.36 |   4.18–4.48 |
| Sparse, 100 rows        |   7.73–8.03 |   7.86–7.99 |
| Sparse, 1,000 rows      | 50.41–51.01 | 49.33–50.66 |
| Sparse, 10,000 rows     |     457–491 |     505–508 |
| Deep, 20 levels         | 10.13–10.87 | 10.51–10.96 |
| Fanout, 100 rows        |     236–249 |     242–247 |
| Fanout, 1,000 rows      | 2,790–2,885 | 2,895–2,928 |
| Filtered, 1,000 readers | 58.26–61.65 | 59.38–62.03 |
| 128 reads in one island | 19.05–19.17 | 19.35–19.59 |
| List churn, 1,000 rows  |     363–407 |     367–425 |

The wide sparse and large fanout cases warranted longer checks. Each was measured alone in four more fresh JVMs,
again baseline/current/current/baseline, with a five-second warm-up and nine samples. Sparse 10,000: before
447–508 µs, pending 463–498 µs. Fanout 1,000: before 2.71–2.87 ms, pending 2.76–2.77 ms. The apparent slowdowns in
the first pair did not persist consistently. These measurements do not establish a zero CPU cost; several small
workloads were a few percent slower, and JVM warm-up/order differences remain visible.

The reverse-order suite's warmed allocation counts differed by eight bytes per rendered island: sparse 1,000 went
from 11,016 to 11,024 bytes per update, 128 reads from 14,112 to 14,120, and fanout 1,000 from 7,111,976 to 7,119,976.
The filtered workload, which does not render, stayed at 23,568 bytes. Other runs showed larger allocation variation
from JVM optimization, including the longer isolated runs. The small matched difference is about 0.07% for sparse
1,000 and 0.11% for fanout 1,000. No failure objects are created on these paths, and the shared trace cache is never
consulted. There is a cache allocated once per island definition. An independent Instrumentation.getObjectSize
check found the Java Instance remained 72 bytes in both variants on this JVM; its new field fit the existing layout.

No production code changed for this check. Results, source snapshots, scripts and the separate size probe are under
`target/perf/failure-overhead`. Timing excludes HTTP, browser work, compression and session pacing. The observed
successful-render cost is small; these runs do not support further complexity to remove it.
