# 2026-10-09 — The same read twice in one render

`use-hold`'s `:idempotent`, set by `observe` and `use-shared`: a read made again in one render returns the first one's
value. The demo's ticker now reads its topic's feed through two hooks of its own, the reading and its label, both
through one function over `observe`, so one call site with the same args. In Chrome, after a reload, it rendered both (
"908", "reading #11 at 04:00:00.393"), where before the second read would have thrown and rendered the error view.
The Server resources table listed the feed's resource, `page.clj:184 ["alpha"]`, live, with 2 subscribers: the two
tabs open on `/`, one each, so the two reads are one hold.
