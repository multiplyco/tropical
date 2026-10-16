# 2026-10-07 — The demo on a production-shaped server

The demo moved to the stack a production app is likely to run: Jetty 12 through `ring-jetty9-adapter` 0.40.4, sync
handlers on virtual threads, a `GzipHandler`; Ring 1.15.5, reitit 0.10.1; cookies, sessions, params and Muuntaja; a
nonce-based CSP with `'strict-dynamic'`. Each finding below was reproduced in the browser, then fixed.

- **A sync `stream` closed at once.** Datastar's Ring adapter calls `on-open` from `write-body-to-stream`, and a sync
  server completes the response when that returns; `retry: 'always'` would have made it a reconnect loop. `stream` now
  holds the thread until the stream closes (test: `a-stream-stays-open-until-its-session-closes`, which fails without
  the hold). On Jetty 12, sync, a tab's stream stayed open 70 s and counting, readings arriving each second.
- **Muuntaja ate the signals.** Its `wrap-format` decodes a JSON body eagerly, and Datastar posts signals as one, so
  `act` read an empty body and the post action got `{}`. The demo now gives Muuntaja to the app's routes only.
- **CSP.** Datastar 1.0.2 compiles every expression with `Function()`: without `'unsafe-eval'` the page's `Function()`
  threw `EvalError` and nothing evaluated. Datastar 1.0.3 added a CSP mode, opted into with `data-nonce` on `<html>`:
  with 1.0.4, the policy without `'unsafe-eval'` (the page's own `Function()` still blocked), the stream, a click
  action and a post with signals all worked. The Clojure SDK's `CDN-url` (1.0.0-RC11) still points at 1.0.2.
- Jetty 12.1.13's `GzipHandler` excludes `text/event-stream` by default, so the stream goes out uncompressed.

Things that looked like bugs and weren't, or were elsewhere:

- `user/reload!` kept serving the handler from before the reload: `user` held an alias into the namespace clj-reload
  replaced. It now resolves the handler by name.
- In a browser driven through an extension, an inline script without a nonce ran despite the CSP: scripts the
  extension injects are exempt. A probe from the page's own nonce'd script showed the policy enforced.
- In that browser, the first stream ended after 15 s with status 0 (a client-side abort), its replacement holding.
  Most likely the tab was hidden while the extension worked, and Datastar closes a hidden tab's stream; not confirmed.
