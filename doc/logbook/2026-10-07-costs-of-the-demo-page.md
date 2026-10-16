# 2026-10-07 — Costs of the demo page (proof of concept)

One session, one change to the alpha ticker: render, patches and HTML, no pump or SSE.

|           | Full re-render, every tick | Missionary DAG version | Tropical              |
|-----------|----------------------------|------------------------|-----------------------|
| Time      | 0.2–0.4 ms                 | ~8.9 µs per change     | ~9.2 µs per change    |
| Allocated | 0.25–0.9 MB                | ~8.1 KB per change     | ~15.9 KB per change   |
| Sent      | ~21 KB; ~400 B gzipped     | ~180 chars per change  | ~190 chars per change |

- The full re-render's range runs from serializing just the page's static table to rendering every island from scratch.
  Its cost scales with sessions × ticks: at 0.4 ms a session, 1,000 sessions take 0.4 s of every 1 s tick.
- Tropical allocates about twice what the DAG version did, all short-lived: at 1,000 tabs receiving 2.5 frames per
  second, about 40 MB/s of young-generation garbage. About 11 KB of a frame is reconciling the path to the changed
  island: building and serializing its hiccup, and the persistent maps that record each instance. Mutable bookkeeping
  could reduce it if it ever matters.
- Building the ticker's hiccup: 0.04 µs. Serializing a small island with Chassis: ~1.5 µs; `cc/compile` halves it.
