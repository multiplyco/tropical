# 2026-10-07 — Extracted into a library

Moved from the proof of concept into `co.multiply/tropical`: the library in `src`, the demo page in `dev/demo`. The
session now runs an app map (`:root`, `:uid`, `:patch-signals`); the stream and action handlers moved into
`co.multiply.tropical.ring`. 32 tests, 140 assertions. The demo, driven by curl: page `no-store` with scoped ids, 11
patches in 4 s each carrying the footer's signals, actions 204 / 403 (another user) / 404 (unknown), stream 403 for a
bad tab.

Benchmarks rerun in the library: signals ~51–53 ns and 2 B per session while keeping up, ~7 ns behind (watch baseline
151 and 90 ns); a frame 8.7 µs, 15.9 KB, 191 chars.
