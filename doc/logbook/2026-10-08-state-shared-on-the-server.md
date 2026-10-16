# 2026-10-08 — State shared on the server

`co.multiply.tropical.shared`: a state declared with `defshared`, an instance per key held in the resource registry,
opening with its `:init`. The demo shows a user's toasts with it, on both pages. In Chrome, two tabs on `/` as one
user:

- "Notify my tabs" in one showed the toast in both, and the Server resources table listed the instance, live, with
  three subscribers: the two tabs, and the session one of them had before it loaded the page again, still in its
  grace period. The toast was gone from both 6 s later, its timer started where it was posted.
- "Another topic" on `/topic/alpha` posts a toast, then opens a random topic's page. The page it opened,
  `/topic/beta`, showed it, as did the other tab: the instance outlived the page that posted it.
- Dismissing a toast in one tab removed it from both. The console showed no errors.
