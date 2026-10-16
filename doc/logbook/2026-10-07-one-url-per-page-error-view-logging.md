# 2026-10-07 — One URL per page; the error view, logging, a failed pump

`ring/handler` replaces `page-response`, `stream` and `act`: one handler per page route serves the page, its stream (a
GET with `Tropical-Tab`) and its actions (POSTs with `Tropical-Action`) at the page's own URL. With fixed `/stream` and
`/act/<token>` paths, a tab the server no longer knew, as after every restart, could only be rebuilt as the one app the
stream handler had, and middleware binding context from the path had nothing to bind on either. 54 tests, 214
assertions; ten consecutive suite runs clean.

- **Datastar 1.0.4 cancels by method and URL.** Every fetch sends `Datastar-Request: true` and merges the `headers`
  option, so the headers carry tab, load and token. But `requestCancellation: 'auto'` aborts a request in flight when
  another goes out with the same method and URL, kept in one map for the page. Once every action posts to the page's
  URL, a second click would drop the first one's response, so actions turn it off.
- **Logs would have gone nowhere.** The dev and test classpaths both had `slf4j-api` and no provider, so tools.logging
  would have picked SLF4J and dropped everything; `slf4j-simple` is on `:dev` and `:test` now. The
  `SLF4J(I): Connected with provider of type [org.slf4j.nop.NOPServiceProvider]` line `bb test` prints comes from
  `compile:java` (tools.build's resolver), not the test JVM, which logs through slf4j-simple.
- **A `:root` that threw left its session registered with no pump.** It ran in `start!`, after the session was
  registered, so nothing would ever close it, and the page waited out the 5 s render timeout. It runs in the pump now,
  whose failure closes the session: the page gets no frame at once, and the test of it checks under a second.
- **The default error view carried the exception's message to the client.** It now says only that the island failed;
  the demo's own view adds the message.
- Live, after a restart: the demo has a second page, `/topic/:topic`, whose root comes from the route. Actions on `/`
  worked (a topic switch; a post whose returned signals cleared the input). Following the link to `/topic/beta`
  showed the reading at once, with the lookup timestamp from `/`: the same call sites and args, so the same resources.
  Moving between the pages felt instantaneous. `(session/close-all!)`, standing in for a restart: both open tabs
  reconnected within 3 s into new sessions under their tab ids, the topic tab's stream went to
  `/topic/beta?datastar=%7B%7D` and rebuilt that page, and the registry opened nothing new (11 opened, 6 closed,
  before and after).
