# 2026-10-10 — The compressed stream and `:open-when-hidden`, in Chrome

The demo, with `:compression [:zstd :br :gzip]`, in Chrome over `http://localhost:8080`, with `compression/choose`
wrapped from the REPL to record its arguments:

- Chrome sent `Accept-Encoding: gzip, deflate, br, zstd` over plain HTTP to localhost, and the stream was zstd. The
  page updated live over it: the ticker's reading advanced three in 3 s.
- Without `:open-when-hidden`, the page made to read hidden (`document.hidden` redefined, then a
  `visibilitychange` dispatched) closed its stream: the session read `:attached? false`.
- With `stream-init` wrapped from the REPL to pass `:open-when-hidden`, the page's `@get` carried
  `openWhenHidden: true`. Made to read hidden the same way, the session stayed attached and the readings kept
  arriving, three in 3 s.

The hiding was simulated: the automation couldn't select another tab of the window. Datastar decides from
`document.hidden` on `visibilitychange`, which is what the simulation gives it. Both vars were restored after.
