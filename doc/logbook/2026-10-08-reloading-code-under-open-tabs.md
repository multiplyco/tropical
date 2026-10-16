# 2026-10-08 — Reloading code under open tabs

`user/reload!` closed every session, then reloaded and restarted the server. Datastar retries an ended stream after a
second, so a reload slower than that would let a tab reconnect to the old handler, into a session of the old code that
would outlive the restart. Not seen, but nothing prevented it. It now closes the sessions last.

With two tabs of the demo open, in Chrome, and `user/reload!` run from the REPL:

- The reload took 145 to 343 ms, and each tab was attached to a fresh session 1.2 to 1.4 s after `reload!` began.
- A changed heading in the `header` island showed in the open page, and changing it back showed too. The page kept
  its tab id, a global set from the console before the reload, and the navigation type `navigate`: it was updated in
  place, not loaded again.
