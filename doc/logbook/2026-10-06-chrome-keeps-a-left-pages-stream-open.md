# 2026-10-06 — Chrome keeps a left page's stream open (proof of concept)

Navigating away fires `pagehide` with `persisted: true` and `freeze`: Chrome puts the page in the back/forward cache
even though the page is served `Cache-Control: no-store`. The frozen page's SSE fetch stays open, server writes keep
succeeding, and the session reads as attached for about 61 s. Then Chrome drops the connection, and the session
detaches and closes after the grace period. Going back did not restore the frozen page: Chrome loaded a new tab id, and
the old session detached within ~11 ms. Closing a tab detaches within 1 s.

So a session, with its subscriptions and action tokens, is held for about 75 s after the user leaves instead of 15 s,
and the attached count overstates live viewers. Accepted: the connection does drop, and leaving and coming back is
normal browsing. Not a bug; relevant only to detach detection, or to anything that needs the attached count exact.
