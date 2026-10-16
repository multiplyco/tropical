# 2026-10-13 — An action that reaches another server, and streams ended at an age

Checked in Chrome against the demo, on one server, with the other server made from the REPL: for the test tab only,
`action/invoke!` answered `[:gone]` and `session/held?` false, as a server without the tab's session would. A field
bound to a signal held text typed before each click.

- A click on the rating card was answered with the reopen event. The click didn't run: no rating. The page didn't
  reload, the typed text stayed, and the page opened one new stream, with the page load's id it had. Landing on the
  server that held the tab, the stream attached to the same session, and the next click rated.
- The same, with the session closed as the action was answered, so the reopened stream found no session, as on
  another server: it rebuilt the tab, a new session under the same tab id, with the page as it was, the typed text
  included. The next click rated, through the new session's tokens.
- `:stream-max-ms` 5000, set on the demo from the REPL: the stream ended about 5 s after it opened, and the next opened
  about a second later, Datastar's retry interval, as the same page load, attached to the same session. Over 18 s the
  session stayed the one the page was served with, and a click then rated without a reload. Datastar resets its retry
  count on each answer of 200, so ending streams doesn't use up its retries.

Not exercised: two servers behind a balancer, which takes a deployment; and the upload module and the browser-storage
script reopening the stream on a `Tropical-Reopen` answer.
