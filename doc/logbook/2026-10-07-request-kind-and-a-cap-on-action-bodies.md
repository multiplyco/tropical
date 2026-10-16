# 2026-10-07 — `request-kind`, and a cap on action bodies

Read ahead of integrating into a production-shaped stack: a body filter and a body parser (Muuntaja) both run
globally, before routing. The filter refuses a body on any path it doesn't list, and every action POST carries one; the
parser consumes the JSON body, leaving the handler a 500. Either breaks every action, and global middleware has no
per-route opt-out, so the app's middleware has to recognize actions itself. `request-kind` is the handler's own
dispatch, public. 56 tests, 226 assertions.

- **The header alone would have opened the filter.** It is the client's to send: a filter admitting any body with a
  `Tropical-Action` header would pass a multipart or form body to the parsers behind it, on any path. An action now
  needs a JSON body too, which those parsers ignore, and the handler answers 415 to an action without one.
- **The handler read the whole body.** It `slurp`ed it, after the user check, so a signed-in client could make the
  server buffer any amount. It now reads at most `:max-signals-bytes` + 1 (1 MiB by default), refusing an announced
  length over the cap before reading, and answers 413.
- **Jetty's `GzipHandler` doesn't hold back the stream.** Such a stack compresses every response, and with sync flush
  off by default, a compressed stream could sit in the deflater. But Jetty 12's default excluded mime types include
  `text/event-stream`, checked on a default instance, so the stream is sent as written and the pages are compressed.
- Live, after a reload: Datastar's actions ran as before, with the JSON content type it sends on a POST. The browser
  automation's network list reported their POSTs as 503, which looked like a regression and wasn't: Datastar's own
  `datastar-fetch` events, which signal an error for any status of 400 or more, showed each request start and finish
  without one, and the same request sent from the page's console got a 204.
