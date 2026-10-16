# 2026-10-10 — A placeholder that never matched its root

Without the session's first frame, because the session closed or took longer than 5 s, the page gets `frame` nil and
renders a stand-in for the root island, which the fresh session its stream opens patches by id. The README's example
and the demo rendered `[:main {:id "app"}]`, and an app passed short names of its own for its roots, not even the
roots' names. Ids are digests of the islands' paths, so none of these matched: the root's patch would have found no
element, and the page stayed on its stand-in. A first frame is rarely missing, which is why it went unseen.

`ring/mount` now renders the element the app mounts in, and without a first frame, a placeholder with the root's id,
taken from `:root`. With it, a page that opens no stream for its tab throws as it is served, and closes its session,
and one without `ui/page-attrs` is logged, once per handler. The check caught two of the library's own tests, whose
pages opened no stream: one rendered the frame alone, to read the cookie state declared on it, and one the scripts'
URLs.

In the demo, after the change, the page's body carried its class and signals, the stream's `data-init` and
`page-attrs`'s attributes, with the root island first and the footer after. A stream opened by curl with the page's tab
sent 7 element patches and 6 signal patches within 3 s.
