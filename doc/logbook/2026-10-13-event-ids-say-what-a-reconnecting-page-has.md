# 2026-10-13 — Event ids say what a reconnecting page has

The one-second quiet window of the entry before is gone. Each send now ends, in its flush, with a mark: an empty
signal patch carrying an SSE id. From the bundle the demo loads, Datastar 1.0.4 records an id as it reads the `id:`
line, before the event is applied, and sends the last one back as `last-event-id` on every reconnect of the request
that read it: a retry, a stream the server ended, and a hidden page shown again, whose `visibilitychange` handler
reopens the request with the same headers. A request opened anew, as the reopen after a dropped action, sends none.

Checked in Chrome on the home page, whose feed ticks every second:

- `:stream-max-ms` 5000: over 16 s and two reconnects, neither resent the page (38066 chars). The largest frame after
  one, 4700 chars, held the islands that had changed while the stream was down: the resources card, the vault feed
  and the ticker. With the quiet window, every reconnect of this page resent it.
- A hidden page, made by `document.hidden` reading true and a `visibilitychange`, shown again 3.5 s later: it got the
  whole page. Datastar sent the last id it read, mark 165, which went out about 100 ms before the page hid. The server
  then wrote marks 166 and 167, about 0.5 and 0.9 s after, into the connection the client had closed, before a write
  failed. So the ids didn't match, as they shouldn't: a page whose islands change every second resyncs when it is
  shown again, as before, and a quiet one gets what changed.
- The same trace showed the next send using a failed flush's mark id again. Fixed: an id is never used twice.

Not done: a page that read an older mark could get what changed since that mark, if the session kept the frames it
sent for a while, with the parts of a step it may have read partly sent again as morphs rather than inserts.
