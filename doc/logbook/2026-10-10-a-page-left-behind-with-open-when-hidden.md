# 2026-10-10 — A page left behind, with `:open-when-hidden`

Item 23 of the app's requirements. In Chrome, on the demo over HTTP/1.1, with `:open-when-hidden` on: loading
`/topic/alpha`, `/topic/beta`, `/topic/gamma` and `/` in turn, about a second apart, each a full load in one tab. The
first five pages had their first byte in 7–159 ms; the sixth hadn't loaded 45 s later, and the server had six
sessions attached. Chrome had frozen each page left in its back/forward cache, as on 2026-10-06, its stream open, and
HTTP/1.1 allows six connections to a host.

With the option, `stream-init` now opens the stream under an `AbortController` (Datastar 1.0.4's
`requestCancellation`), aborted on `pagehide`, and opens it again, as the same page load, on a `pageshow` from the
cache. Without the option, Datastar does as much itself: leaving a page hides it, which closes its stream, and showing
it reopens it. Datastar runs `data-init`'s expression as one function body and rewrites each `@get(` where it stands,
so a closure can call it again.

The same ten loads after the change: each at once, 5–160 ms to the first byte, and the server had one session
attached and ten detached. Going back loaded the page afresh: Chrome gives `response-cache-control-no-store` as why it
didn't restore it, so the reopening didn't run there. Fired on a page by hand, a `pagehide` stopped its stream, with
no frames in 3 s and its session detached, and a `pageshow` with `persisted` opened it again: the same session
reattached, eleven frames in 3 s, and no reload.
