# 2026-10-13 — A dropped action's notice, and a reconnect that sends only what changed

The demo's footer now shows a notice on `tropical:reopen`, by a signal. Checked in Chrome with the same stand-in for
another server as in the entry before: the click on the rating card was dropped, the notice showed, the page didn't
reload, Dismiss hid it, and the next click rated.

A stream ended for its age now keeps what the page was sent, unless a patch went out within a second of the end (`session/recycle-quiet-ms`). With `:stream-max-ms` 5000 on `/topic/gamma`, whose feed ticks every 2.5 s, the
footer's wire counts over 23 s and four reconnects:

- Two reconnects came more than a second after a patch, and sent only what changed while the stream was down,
  4205 to 4784 chars: the resources card, which counts sessions attached and detached, and once a tick that fell in
  the gap.
- Two came within about a second of a tick, and resent the root, the whole page, 8161 chars.

On the home page, whose feed ticks every second, a stream is never a second quiet, so every reconnect resyncs, as
before. Behind a balancer the quiet case is the one `:stream-max-ms` is for: an idle tab.
