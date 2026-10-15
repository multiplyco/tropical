# Logbook

Dated records: measurements, smoke-test findings, trials. Oldest first. Append; don't rewrite history.

Measurements are on JDK 27, warmed up, on a developer laptop with other work running, so compare numbers taken in the
same sitting rather than across entries.

## 2026-10-06 — Chrome keeps a left page's stream open (proof of concept)

Navigating away fires `pagehide` with `persisted: true` and `freeze`: Chrome puts the page in the back/forward cache
even though the page is served `Cache-Control: no-store`. The frozen page's SSE fetch stays open, server writes keep
succeeding, and the session reads as attached for about 61 s. Then Chrome drops the connection, and the session
detaches and closes after the grace period. Going back did not restore the frozen page: Chrome loaded a new tab id, and
the old session detached within ~11 ms. Closing a tab detaches within 1 s.

So a session, with its subscriptions and action tokens, is held for about 75 s after the user leaves instead of 15 s,
and the attached count overstates live viewers. Accepted: the connection does drop, and leaving and coming back is
normal browsing. Not a bug; relevant only to detach detection, or to anything that needs the attached count exact.

## 2026-10-07 — Costs of the demo page (proof of concept)

One session, one change to the alpha ticker: render, patches and HTML, no pump or SSE.

|             | Full re-render, every tick | Missionary DAG version | Tropical            |
|-------------|----------------------------|------------------------|---------------------|
| Time        | 0.2–0.4 ms                 | ~8.9 µs per change     | ~9.2 µs per change  |
| Allocated   | 0.25–0.9 MB                | ~8.1 KB per change     | ~15.9 KB per change |
| Sent        | ~21 KB; ~400 B gzipped     | ~180 chars per change  | ~190 chars per change |

- The full re-render's range runs from serializing just the page's static table to rendering every island from scratch.
  Its cost scales with sessions × ticks: at 0.4 ms a session, 1,000 sessions take 0.4 s of every 1 s tick.
- Tropical allocates about twice what the DAG version did, all short-lived: at 1,000 tabs receiving 2.5 frames per
  second, about 40 MB/s of young-generation garbage. About 11 KB of a frame is reconciling the path to the changed
  island: building and serializing its hiccup, and the persistent maps that record each instance. Mutable bookkeeping
  could reduce it if it ever matters.
- Building the ticker's hiccup: 0.04 µs. Serializing a small island with Chassis: ~1.5 µs; `cc/compile` halves it.

## 2026-10-07 — Signals in Java, in place of a watch and a queue per session (proof of concept)

`dev/bench/signal.clj`. 1,000 sessions read one value; cost per change and reading session, on the publishing side.

|                                    | Watch + queue per session | `Cell`        | Atom through `RefSignal` |
|------------------------------------|---------------------------|---------------|--------------------------|
| Session keeps up (1 change/drain)  | ~152 ns, 506 B            | ~52 ns, 2 B   | ~54 ns, 2 B              |
| Session behind (10 changes/drain)  | ~95 ns, 303 B             | ~8 ns, 0.2 B  | ~8 ns, 0.3 B             |

Per frame (`dev/bench/frame.clj`, the table above), before and after, three alternating runs each: time unchanged
within noise (10.4–11.8 µs against 10.6–11.1 µs that day); allocation down ~200 B, 16.0 to 15.8 KB; chars sent
unchanged. The benchmark feeds changes through a test harness that was always cheaper than the real session's mailbox;
a live session also saves the event vector, the queue node and the drain set, nearer 0.5 KB. The gain that matters is on
the publishing thread, times the number of readers, and the bound on a stalled session's inbox.

Live smoke test, two tabs: one watch per atom read; each subscription value cell held one subscription per session. A
flood of 85M publishes in 3 s reached the client at ~20 frames per second (`frame-ms` 50) with at most 2 entries in an
inbox, and both pumps parked in `Inbox.await` (thread dump). Closing one tab dropped the atom's signal count by one and
the subscriptions to one; closing all left no sessions, signals, watches or registry entries (opened 2, closed 2).

While testing by hand through tools, a subscription seemed to reopen after a revoke and grant: the tool latency between
the two exceeded the 5 s linger. Revoking and granting 2 s apart kept the subscription. Not a bug.

## 2026-10-07 — Extracted into a library

Moved from the proof of concept into `co.multiply/tropical`: the library in `src`, the demo page in `dev/demo`. The
session now runs an app map (`:root`, `:uid`, `:patch-signals`); the stream and action handlers moved into
`co.multiply.tropical.ring`. 32 tests, 140 assertions. The demo, driven by curl: page `no-store` with scoped ids, 11
patches in 4 s each carrying the footer's signals, actions 204 / 403 (another user) / 404 (unknown), stream 403 for a
bad tab.

Benchmarks rerun in the library: signals ~51–53 ns and 2 B per session while keeping up, ~7 ns behind (watch baseline
151 and 90 ns); a frame 8.7 µs, 15.9 KB, 191 chars.

## 2026-10-07 — The demo on a production-shaped server

The demo moved to the stack a production app is likely to run: Jetty 12 through `ring-jetty9-adapter` 0.40.4, sync
handlers on virtual threads, a `GzipHandler`; Ring 1.15.5, reitit 0.10.1; cookies, sessions, params and Muuntaja; a
nonce-based CSP with `'strict-dynamic'`. Each finding below was reproduced in the browser, then fixed.

- **A sync `stream` closed at once.** Datastar's Ring adapter calls `on-open` from `write-body-to-stream`, and a sync
  server completes the response when that returns; `retry: 'always'` would have made it a reconnect loop. `stream` now
  holds the thread until the stream closes (test: `a-stream-stays-open-until-its-session-closes`, which fails without
  the hold). On Jetty 12, sync, a tab's stream stayed open 70 s and counting, readings arriving each second.
- **Muuntaja ate the signals.** Its `wrap-format` decodes a JSON body eagerly, and Datastar posts signals as one, so
  `act` read an empty body and the post action got `{}`. The demo now gives Muuntaja to the app's routes only.
- **CSP.** Datastar 1.0.2 compiles every expression with `Function()`: without `'unsafe-eval'` the page's `Function()`
  threw `EvalError` and nothing evaluated. Datastar 1.0.3 added a CSP mode, opted into with `data-nonce` on `<html>`:
  with 1.0.4, the policy without `'unsafe-eval'` (the page's own `Function()` still blocked), the stream, a click
  action and a post with signals all worked. The Clojure SDK's `CDN-url` (1.0.0-RC11) still points at 1.0.2.
- Jetty 12.1.13's `GzipHandler` excludes `text/event-stream` by default, so the stream goes out uncompressed.

Things that looked like bugs and weren't, or were elsewhere:

- `user/reload!` kept serving the handler from before the reload: `user` held an alias into the namespace clj-reload
  replaced. It now resolves the handler by name.
- In a browser driven through an extension, an inline script without a nonce ran despite the CSP: scripts the
  extension injects are exempt. A probe from the page's own nonce'd script showed the policy enforced.
- In that browser, the first stream ended after 15 s with status 0 (a client-side abort), its replacement holding.
  Most likely the tab was hidden while the extension worked, and Datastar closes a hidden tab's stream; not confirmed.

## 2026-10-07 — The wire in-house

The Datastar Clojure SDK (1.0.0-RC11, the API and its Ring adapter) is replaced by Datastar's SDK specification
implemented here: `EventStream` (Java) for the transport, `sse` for the events, `ring` for the response body and for
reading signals. The only new dependency is `ring-core-protocols`. The wire is pinned to the specification by tests.

Per event, from `bench.sse`: a platform thread after a warm-up, the thread's allocation counter, events written to a
null output stream. The SDK was measured the same way, in the same JVM, before it was removed; signals as a pre-encoded
JSON string for both.

| Event                 | SDK                    | In-house                       |
|-----------------------|------------------------|--------------------------------|
| Patch, one island     | 2.2–2.6 µs, 10.4 KB    | 0.25 µs, 0.33 KB               |
| Patch, 27 KB resync   | 39–43 µs, 250 KB       | 25.6 µs, 27.3 KB (presized)    |
| Signals (pre-encoded) | 3.1 µs, 10.0 KB        | 0.23 µs, 0.34 KB               |

Unpresized, the resync allocated 81 KB: the event's builder regrowing. `signals!` with Charred encoding the map takes
2.0 µs and 17.9 KB, nearly all of it Charred's, which the SDK path paid too. Next to a frame (about 10 µs and 16 KB in
`bench.frame`), a patch saves about 2 µs and 10 KB per session.

Live, Jetty 12 with sync handlers: the stream, a click action and a post with signals under the CSP without
`'unsafe-eval'`. With curl, a second page load of one tab got the reload event exactly as the specification has it, and
its stream closed; the first kept streaming. The heartbeat is now an SSE comment: Datastar's client skips comment
lines, and ignores a message whose event type doesn't start with `datastar` (both read in the 1.0.4 bundle). Not seen
live, since the demo is never quiet; the wire test covers it. Cancelling a Quiescent task blocked in
`EventStream.await` interrupts it, which closes the stream (test).

`act` now refuses signals it can't parse (400) and a body something read before it (500), where it read `{}` before.

Looked like a bug, wasn't: no `Connection: keep-alive` on the wire. The response sets it for HTTP/1.1; Jetty 12 drops
it, keep-alive being HTTP/1.1's default.

Read in `ring-jetty9-adapter`'s source, not run: its async handler completes the response as soon as
`write-body-to-stream` returns, though Ring allows an async body to keep writing. There, a stream needs the sync
handler.

## 2026-10-07 — JSON: Oda instead of Charred

`signals!` cost 2 µs and 17.9 KB, nearly all of it Charred's `write-json-str`, whose `io/writer` wraps the output in a
fresh `BufferedWriter`: a 16 KB buffer per call. Charred's `JSONWriter` used directly avoids it. Oda was faster still.
Measured in a separate JVM (JDK 27), on the main thread after a warm-up of the same length, with the thread's allocation
counter; both libraries' output parses back to the same data.

| Per call                            | `write-json-str` | Charred's `JSONWriter` | Oda            |
|-------------------------------------|------------------|------------------------|----------------|
| Write the footer's signals (120 B)  | 985 ns, 17.5 KB  | 612 ns, 1.0 KB         | 154 ns, 168 B  |
| Write 1.3 KB, some of it non-ASCII  | 4.3 µs, 26 KB    | 4.0 µs, 11 KB          | 1.8 µs, 2.8 KB |
| Parse an action's signals (`parse`) | 372 ns, 2.0 KB   | (as `read-json`)       | 202 ns, 784 B  |

Tropical now uses Oda (`com.s-exp/oda`, zero dependencies, on Clojars) for both directions. Oda's classes are Java 25,
so tropical's floor is JDK 25, and its own Java compiles with `--release 25`. Its Vector API path is optional: without
the incubator module it scans scalar. Charred escaped non-ASCII and `/` by default; Oda writes UTF-8 as is. Both are
valid JSON, and the wire test's output was unchanged.

Live, after a restart: `bench.sse`'s signals event, encoding included, went from 2.0 µs and 17.9 KB to 324 ns and
512 B. In the browser, a post whose signals carried non-ASCII text arrived intact, and its returned signals cleared the
input.

## 2026-10-07 — `observe` in place of `<-` and `?`

`(observe & args f)` replaces both read hooks: a resource keyed by its call site and `args`, opened by
`(f emit! & args)`, which returns a cleanup fn, a task or nil. `f` runs on a virtual thread of its own, in an empty
Scoped scope; a session keeps the scope of the request that created it. 49 tests, 201 assertions; ten consecutive suite
runs clean.

- **A closed resource could keep running.** The registry cancelled a closing resource's task from the linger's callback,
  and dropped the task `q/cancel` returned. Made inside a task, a cancel is that task's child, and the callback settles
  at once, cancelling the cancel. Closing 60 resources whose task slept, from inside a task, left 8 running (14 when
  opened from a platform thread); with the cancel compelled, none of 60. The resource was gone from the registry, so
  nothing would ever close it: since extraction, a closed subscription or poll loop could keep running. Every cancel the
  registry makes is now compelled. Found through a test that failed in 2 of 8 suite runs; the regression test
  `every-close-stops-its-resource` closes 30 at once and failed 3 of 3 runs on the old cancel.
- **The old `:open` ran under the registry's lock.** Harmless while it returned a task at once; one that blocked to
  connect would have stalled every session opening any resource. Checked by moving `f` back onto the render thread: the
  test opening a second resource while the first connects then waited on the lock.
- **The compile-time check caught a test of its own**: a fn closing over a promise the test held, which now goes in
  as an argument.
- **A Scoped value the fn reads falls back to its var's root.** `ask` returns the default only for an unbound var, so a
  test of the empty scope uses one.
- Live, the demo's lookup and feed are `observe` calls keyed by topic. Switching topics and back within a second reused
  both lingering resources: the lookup showed at once, with its first timestamp. With the tools' delay between the two
  clicks over the 5 s linger, both reopened, as they should.

## 2026-10-07 — One URL per page; the error view, logging, a failed pump

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

## 2026-10-07 — `request-kind`, and a cap on action bodies

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

## 2026-10-07 — What an island costs

1,000 small list items, `[:li [:span "item " i] [:span label]]`, in one session. They were rendered either as keyed islands placed by a root island or as plain functions called inside it. The runtime was driven directly, as the pump drives it, in the dev JVM, averaged over 50 to 200 runs after warm-up. Memory is the heap used after two GCs, before and after building 40 sessions and rendering each once, divided per session.

| | Islands | Plain functions |
|---|---|---|
| First render | 4.9 ms | 0.6 ms |
| Root re-renders, items unchanged | 1.2 ms | 0.5 ms |
| One item changes, read by the item itself | 0.59 ms, 71 chars sent | 0.55 ms, 55,909 chars sent |
| Retained per session | ~1.4 MB | ~0.14 MB |

- **An island costs per instance, per session:** about 1.3 KB kept, and a first render about seven times a plain function's.
- **It pays back in the patch.** One changed item went out as 71 chars as an island, and as the whole 55,909-char list inside the root. With the root reading every label and re-rendering, the patch was still 70 chars: the root's template was unchanged, so patching went down into the one island that changed.
- **Server time for one changed item was a wash.** The root is on the path to the changed island, so it checks all 1,000 children either way.
- So islands are boundaries, for what reads, holds or changes on its own, and presentational pieces stay plain functions.

## 2026-10-07 — An action that navigates, and a refused stream

Read in Datastar 1.0.4's bundle, not run:

- A `text/javascript` response to an action is run as a script, with attributes from a `datastar-script-attributes`
  header. In CSP mode, Datastar gives that script, and every `<script>` it patches in, the page's nonce.
- Under `retry: 'always'`, the fetch retries every status but 200, 204 and 3xx, with backoff, up to `retryMaxCount`
  (10). A 200 stream that ends is retried too, after `retryInterval` (1 s) at first. This is why the reload guard spares
  the page load it told to reload: a reload slower than a second lets that load retry first.
- `data-ignore-morph` skips an element only when both the element in the page and the incoming one carry it. A patch
  can't keep an existing child by sending a placeholder for it, so a list sends added children with Datastar's
  `after`/`before` modes instead.

Live, in Chrome, under the demo's CSP without `'unsafe-eval'`:

- "Another topic" opened another topic's page, pushed onto the tab's history.
- On `/topic/gamma`, with gamma removed from the topics and the tab's session closed, the stream reconnected, the
  topic route refused it with a 404, and the tab reloaded (`performance` navigation type `reload`) into the not-found
  page.
- With the handler swapped in the REPL for one refusing only `/topic/alpha`'s stream, the page reloaded once, then the
  guard held. Direct fetches confirmed it: the load told to reload got the reload stream again, another load a 204.

Looked like a bug, wasn't: the extension's network list reported the guard's 204 as 503, as it did with actions in the
`request-kind` entry. A direct fetch got the 204.

## 2026-10-07 — A first frame that waits for its reads

Page latency, before and after `:first-frame-ms`. A `ring/handler` page whose root is one island reading a source
that emits once, a set time after it opens, under a fresh key each time so every read is cold. Timed around the sync
handler call in the dev JVM, 15 runs each after 20 to warm up.

| Wait | Read lands after | Median | Max | In the page |
|---|---|---|---|---|
| 0 | 20 ms | 0.5 ms | 3.7 ms | pending, 15 of 15 |
| 150 ms | 20 ms | 23.5 ms | 25.9 ms | the value, 15 of 15 |
| 0 | 2 s | 0.3 ms | 3.5 ms | pending, 15 of 15 |
| 150 ms | 2 s | 152.5 ms | 155.7 ms | pending, 15 of 15 |

- A read that lands within the wait costs its own time and about 3 ms more, the wake-up of the pump after the value is
  emitted, and the render.
- One that doesn't costs the wait and about 2.5 ms more.

The demo waits 150 ms, and alpha's feed now connects in 50 ms. With no session holding anything, `/topic/alpha` took
156 ms with curl and arrived with the feed's first reading and the 700 ms summary still pending. With gamma's connect
time set to 50 ms in the REPL, `/topic/gamma` did the same in 154 ms. In Chrome, a cold `/topic/alpha` had its first
byte at 155 ms; the summary followed over the stream, and the ticker went on ticking.

Reads in one frame load side by side. A render is synchronous, but `observe` returns `pending` at once and opens its
resource on a virtual thread of its own, so one render starts every read in the frame. Measured the same way, with a
1 s wait and reads of 50 ms, the median of 10 cold pages:

| Page | Median | Landed |
|---|---|---|
| One island, one read | 55 ms | 1 of 1 |
| Five keyed islands side by side, a read each | 54 ms | 5 of 5 |
| An island placed only once another's read has landed | 109 ms | 1 of 1 |

Reads are sequential only where the structure makes them so: a child placed once its parent's read lands starts its
read in the render after. A child whose read doesn't need the parent's data can be placed regardless, showing its own
pending state, and its read starts with the first render.

## 2026-10-08 — Lists that grow send what they gain

A conversation island with one keyed child island per message, each message about 430 chars, and one message added.
Before, the conversation's template gained a hole, so the conversation was the topmost change and went out whole. Now
the new message is inserted after the last. Measured in a fresh JVM on the test classpath: the runtime driven as the
pump drives it, `island/patches` timed over 2,000 runs after ten warm-up rounds, the list's HTML over 200, and one
frame once, for scale.

| Rows | Sent before | Sent now | Patches, a row added | Patches, a row changed | HTML of the list | Frame |
|---|---|---|---|---|---|---|
| 10 | 4,805 chars | 434 chars | 0.9 µs | 1.4 µs | 1 µs | 107 µs |
| 100 | 43,866 chars | 435 chars | 5.2 µs | 3.9 µs | 15 µs | 308 µs |
| 1,000 | 435,367 chars | 436 chars | 60.7 µs | 41.0 µs | 160 µs | 1,490 µs |

Deciding the splice at 1,000 rows, in the dev JVM, as it went:

- 950 µs, first in Clojure. Most of it went to merging every kept child's empty patches, which also slowed the common
  case, a wide island with one child changed.
- 446 µs, skipping children that are the same frame.
- 254 µs, one pass over each slot map in place of sets built from the templates.
- 134 µs, the comparison in Java (`Splice`), one pass over both templates.
- 61 µs, taking a child at the same place in both as kept without asking either slot map, which leaves two lookups
  per kept child, to tell whether it changed (fresh JVM, the table above). The two JVMs agree on what this didn't
  touch: the list's HTML took 162 µs in the dev JVM and 160 µs in the fresh one, a changed row's patches 40 and
  41 µs.

The test that applies patches to a model of the client, a DOM parsed from the frame it shows, runs 400 random edits.
Breaking the slot check in `Splice` at first went unnoticed: no edit both reordered and added, and a reorder alone is no
splice. Edits that do both now catch it.

Live, in Chrome: five posts to the demo's messages, newest first and capped at five, each sent one insert of about
140 chars before the newest, and the fifth also a removal of the oldest by its id. The list island was never resent,
and the page's order matched after each post.

## 2026-10-08 — UI state the browser owns: a prototype

`demo.ui`: signals under `_ui` declared on the page's body from a cookie, an effect on the body writing them back,
and islands rendering the plain attribute for the first paint while binding it to the signal (`data-attr`). The
demo's Server resources table and the vault panel's messages fold away. Tried in Chrome, under the demo's CSP:

- The effect writes the cookie, `JSON.stringify($_ui)` reading through Datastar's signals, and the next page load
  was server-rendered folded, with `_ui` declared from the cookie.
- Folded against the session's own copy, the resources table stayed folded through every patch of its card (49
  mutations in the card over two topic switches), its button reading "Show" with `aria-expanded` false.
- The case `data-preserve-attr` alone can't hold: folded, then access revoked and granted again, the messages came
  back as a new element, folded, though the session's copy said open.
- What keeps an attribute right is `data-attr`: Datastar's attribute binding watches the attribute it sets and sets
  it again when something else changes it. Without `data-preserve-attr`, seven patches set the stale `hidden` on the
  table 14 times, each undone before the page settled; with it, none. So `data-preserve-attr` only spares the churn.
- Validation: a value that isn't valid for its key reads as the default, and a key not declared is dropped.

## 2026-10-08 — UI state in the library

`co.multiply.tropical.ui` replaces the prototype: `defstate` for a state, `use` in any island, which declares the
state's signal on the island's root with `data-signals__ifmissing`, and `page-attrs`, a `data-on-signal-patch` handler
on the body that keeps the cookie. Read in Datastar 1.0.4's bundle first:

- `data-signals__ifmissing` merges per leaf: it sets only the signals missing, at any depth.
- `$a.b` in an expression becomes `$['a']['b']`, a lookup on the reactive root, and a following `["x"]` stays member
  access on it. Item keys go in brackets as JSON strings, which keeps any id safe: `$a.b-c` would split on the `-`.
- `data-on-signal-patch` runs with `patch`, the changed signals that pass `data-on-signal-patch-filter`. Declarations
  patch signals too, which is why the handler drops values equal to their state's default. An expression starting with
  an arrow function is taken for a function, so the handler is `void (() => {...})()`.

Live, in Chrome, under the demo's CSP, no console messages:

- The resources card declared its state on its root, and the table stayed folded through every patch (67 mutations of
  the card, none of them to `hidden`).
- After a reload, the card declared `false`, read from the cookie, and the table was server-rendered folded.
- Folded, then access revoked and granted again: the vault panel came back as a new element declaring the session's
  stale `true`, and its messages stayed folded.
- Item signals, on elements put in the page by hand: `$_ui.t["a-b/c"]` toggled the bound attribute, and an element put
  back declaring the stale value kept the browser's.
- The cookie the browser then sent, captured on the server, held only `{"demo_page__resources_open":false}`: the
  messages, back at their default, were dropped, and so were the prototype's keys, no longer declared.

## 2026-10-08 — A wide island's frame, made cheaper

A list island of 1,000 keyed rows, re-rendering with no row changed (its own attribute changed): the costliest frame
left. Measured in the dev JVM with one script after every step (`frame!` timed over 300 runs after 300 warm-up runs;
garbage from the thread's allocation counter, on a platform thread since evals run on virtual threads, over 100 runs).
Keys were longs, or UUID strings, which an app's ids likely are.

| | UUID keys | longs |
|---|---|---|
| Before | 2,119 µs, 3,374 KB | 1,075 µs, 2,191 KB |
| `key-string` in Java (`Slot/key`) | 1,074 µs, 2,164 KB | 986 µs, 2,023 KB |
| A template compiled into mutable lists | 976 µs, 1,448 KB | 834 µs, 1,301 KB |
| Fixed arities for an island's calls | 821 µs, 929 KB | 678 µs, 840 KB |
| Kept children reconciled in place | 461 µs, 335 KB | 331 µs, 343 KB |

- `key-string` checked each key a character at a time through a seq over the string: 1.2 MB of the frame with UUID
  keys. `Slot/key` scans the chars and returns the string itself when it needs no escaping, the usual case.
- `compile-island` built a pair of vectors per child and a set at the end to check slots: 882 KB. It now walks into an
  `ArrayList` and a `HashSet`, checking slots as it goes, and makes the parts a vector once: 160 KB. No Java class was
  needed.
- An island's fn took rest args, `apply`'d its key and `vec`'d its args, and joined its slot with `str`. Arities for
  zero to three args, and `String.concat`, took the calls from about 700 KB to about 225 KB.

- Reconciling the parent's children rebuilt, on every render of it, a new id string per child, the map of child
  frames, and a set of the placed children to find the unmounted: about 570 µs and 550 KB with UUID keys, though the
  children were the same. A render that places the same children in the same slots, in the same order, as the last
  now keeps their ids, starts from the last frame's map of child frames, changing it only where a child's frame
  changed, and skips the search for the unmounted. A render whose children changed takes the path it did before: a
  frame that adds a row to 1,000 measured 982 µs without this step, and 967, 1,056 and 992 µs with it, in three runs.

Against where it started, the frame takes 78% less time and leaves 90% less garbage with UUID keys. What remains is
creating the calls and compiling the parent's template, about 250 µs together, and one instance lookup per child.

## 2026-10-08 — Squint, spiked

Could Datastar expressions be written as Clojure forms, and a page's script as a Squint file? Tried in the dev JVM with
Squint 0.14.211 (from Clojars, added at runtime), on a page served next to the demo under its CSP (nonce,
`'strict-dynamic'`, no `'unsafe-eval'`) and Datastar 1.0.4, in Chrome.

- A `js` macro read its forms as data, as the JVM reads any quoted form, and compiled them with
  `squint.compiler/compile*` (`:context :expr`, imports elided) when it expanded: about 85 µs a form, once. Several
  forms were joined as statements; `~x` became a hole filled with `x` as JSON at render, or as it was if marked raw.
- What ran in the page: `(set! $n (inc $n))`; `str`, as a template literal; `if`, through Squint's core;
  `and`, `let` and `do`, each a function called in place, which Datastar's statement splitter keeps whole;
  `data-show` and `data-attr`; `el` and `evt`; a server value through `~`, following the server when it changed; and
  `(set! $busy true) ~(raw post)`, whose button stayed disabled until the reply set `$busy` back.
- Squint's core (`squint/core.js` in its jar, 128 KB, 33 KB gzipped) was served from the page's origin, and a module
  ahead of Datastar's imported it and set the global, `squint_core`, that compiled expressions refer to. It was there
  for the first `data-init`.
- A web component in Squint (`defclass` extending `HTMLElement`, a scroll listener throttled by animation frames)
  compiled in 0.8 ms to an ES module. `:import-maps` points its import of core at the served URL, the same one the
  global came from, so the page held one copy of core, and needed no import map.
- `#js` doesn't read on the JVM, and wasn't needed: Squint's maps are JS objects. `if`, `when`, `and` and `or` follow
  Clojure's truthiness, so a signal holding 0 or `""` is true to them, where it is false to Datastar's own JS.

Delivering the component's module, for the script support to follow:

- A `<script type="module">` in a patched island got the page's nonce from Datastar and ran. Three tags for one URL
  ran the module once, and removing the islands and patching them back in ran it no more: the browser keeps a module
  per URL for the life of the page. A module that first arrived by a patch also upgraded the element already in the
  page.
- The same tag in the first frame, without the nonce, was blocked, and so was every later tag for its URL, though
  those carried the nonce: a module that failed to load stays failed for the page. The first page's scripts need the
  nonce, from the page itself.
- A re-render of the island holding the component emptied it: the morph matched its children to the server's empty
  element. Drawing into a shadow root, which the morph doesn't reach, kept it through two re-renders, the element
  itself kept.
- Editing the component gave its module a new URL, which loaded, but a name stays defined as it first was
  (`customElements.define` throws on a second definition), so a change shows on the next page load.

## 2026-10-08 — Reloading code under open tabs

`user/reload!` closed every session, then reloaded and restarted the server. Datastar retries an ended stream after a
second, so a reload slower than that would let a tab reconnect to the old handler, into a session of the old code that
would outlive the restart. Not seen, but nothing prevented it. It now closes the sessions last.

With two tabs of the demo open, in Chrome, and `user/reload!` run from the REPL:

- The reload took 145 to 343 ms, and each tab was attached to a fresh session 1.2 to 1.4 s after `reload!` began.
- A changed heading in the `header` island showed in the open page, and changing it back showed too. The page kept
  its tab id, a global set from the console before the reload, and the navigation type `navigate`: it was updated in
  place, not loaded again.

## 2026-10-08 — A value from the click

`use-action`'s `:value` puts the value in the action's body with the signals, through Datastar's `payload`, which
replaces the signals. The expression puts back the signals `@post` would have sent, filtering out keys starting with
`_` in a `JSON.stringify` replacer, read inside `@peek`.

- On the demo page, the replacer's output equalled Datastar's own `filtered({exclude: /(^|\.)_/})` (its export, from
  the module the page loaded), which leaves out the footer's `_wire` and the `_ui` states. Where they would differ,
  an object holding only local signals (`{}` from the replacer, nothing from Datastar) and objects inside arrays
  (filtered by the replacer, a leaf to Datastar), no signal of the demo's is.
- In Chrome, under the demo's CSP, with `window.fetch` wrapped to record what the page posted: a click on a term in
  the raw-HTML card posted `{"signals":{"note":""},"value":{"term":"signal"}}`, and the card showed the definition. A
  term changed to one the passage doesn't have, in the DOM, was posted and ignored. A topic button posted
  `{"note":""}`, as before, and the page's signals were the same after the clicks as before them.

## 2026-10-08 — Uploads straight to a bucket

The demo's uploads card, in Chrome, under its CSP, against `demo.bucket`, which takes a presigned PUT and reads at
8 MB/s so progress shows on a local connection. Files were given to the input and dropped on the body from the
console, as `File`s in a `DataTransfer`, with `window.fetch` and `XMLHttpRequest` wrapped to record and tamper.

- A pick of a 40 MB and a 5-byte file posted one description of both, and no bytes. The small file arrived at once;
  the large one was at 27% after 1.5 s, and arrived with the size the bucket recorded, which `:arrived` checks.
- The expression imported the module by its URL, under the CSP's `'strict-dynamic'`, as the page had loaded it.
- A drop on the body was taken by the window's handler, its default prevented. Of four files, one whose PUT carried
  a forged signature got a 403 and was reported failed with that status; two arrived; the fourth was refused, the
  card's limit of five counting the 40 MB file, the small one and the one uploading.
- Send took the four that arrived and left the failed and the refused; removing one dropped its row.
- A second pick during a 40 MB upload re-rendered the island. The bar was the same element, and its width went on
  from 27.0% to 32.8% over 400 ms, never back: Datastar's `style` plugin re-applies its value when a morph changes the
  attribute.
- A fresh page that uploaded four 3 MB files logged no errors: no binding read a missing signal.

## 2026-10-08 — A page that follows its subject

The demo's topic page renders `navigate-element` with `{:replace true}` once its topic's page has moved
(`demo.state/move!`), and the topic route redirects a page loaded at a moved topic's old URL. In Chrome, under the
demo's CSP, with two tabs on `/topic/alpha`, each marked with a global from the console:

- `(move! "alpha" "alpha-2")` from the REPL took both tabs to `/topic/alpha-2`, as page loads (the mark gone), with
  their history lengths unchanged: the entry was replaced. The new pages had no navigating element.
- Moving it back took both tabs home, each navigating 3 and 9 ms after `move!` returned, by
  `performance.timeOrigin`, so as soon as the frame arrived.
- Loading `/topic/alpha` while it was at `alpha-2` arrived at `/topic/alpha-2`, with one redirect.
- The element's script for `/topic/ö#x`, run on a page at `/topic/%C3%B6`, found the page already there and stayed:
  the mark survived.
- That a later render of the same element doesn't run it again is from reading Datastar 1.0.4, not observed: it
  applies an element's plugins when the element is added and when one of its `data-*` attributes changes, and its
  morph sets only attributes that differ.

## 2026-10-08 — A state for the page an action opens, and a pick taken whole

`action/navigate`'s `:ui` writes UI states into the cookie before the page it opens loads, with the code the body's
handler runs, factored out to share it; `ui/patch` returns them as signals. `use-uploads`'s `:settled` gets a pick
once nothing is on its way. In Chrome, under the demo's CSP, against `demo.bucket`:

- The first shared version broke the body's handler, and every test passed. It had become `((patch) => {...})(patch)`,
  and Datastar 1.0.4, for an attribute whose expression returns a value, splits the expression into statements at
  each `;` outside strings, regex literals and an arrow function taking no arguments, called with none, then puts
  `return (` before the last. So it cut the function apart, and put `return (` inside the `while` that keeps the
  cookie under its cap. In CSP mode a function that fails to compile is reported as `GenerateExpression`, "Blocked by
  CSP.": the page compiles expressions in a script with its nonce, and the script didn't parse. The handler wrote no
  cookie, for the browser's own toggles as for patches. Restored to `void (() => {...})()`, reading the handler's
  `patch`; the navigation's script wraps the same statements in a function of its own. A test ports Datastar's
  statement regex and checks the handler is one statement.
- "…with its summary hidden" on `/`, with gamma picked, opened `/topic/gamma`, pushed onto the history (6 to 7). The
  cookie held `gamma` hidden and nothing else, and the page's HTML, fetched again with that cookie, rendered the
  summary hidden and its toggle reading "Show", as did the page itself.
- On a topic's page, Hide (every topic's, `ui/patch`) hid its summary within the action's response, and the cookie
  then held all three topics hidden; Show emptied it, every value its default; the page's own toggle then changed
  only its topic. `/topic/beta`, loaded after Hide, rendered its summary hidden.
- Three files dropped at once were taken in as one batch. Of three more, one whose PUT carried a forged signature got
  a 403 and stayed in the list, failed; the other two were taken in together. A 2 MB file dropped while a 12 MB one
  was uploading arrived first and waited for it: the two were one batch.
- Removing a 40 MB upload while a 2 MB one had arrived took the 2 MB one in at once. The list was the same 5 s later,
  time enough at the bucket's 8 MB/s for the 40 MB file to land and its report to be refused.

## 2026-10-08 — State shared on the server

`co.multiply.tropical.shared`: a state declared with `defshared`, an instance per key held in the resource registry,
opening with its `:init`. The demo shows a user's toasts with it, on both pages. In Chrome, two tabs on `/` as one
user:

- "Notify my tabs" in one showed the toast in both, and the Server resources table listed the instance, live, with
  three subscribers: the two tabs, and the session one of them had before it loaded the page again, still in its
  grace period. The toast was gone from both 6 s later, its timer started where it was posted.
- "Another topic" on `/topic/alpha` posts a toast, then opens a random topic's page. The page it opened,
  `/topic/beta`, showed it, as did the other tab: the instance outlived the page that posted it.
- Dismissing a toast in one tab removed it from both. The console showed no errors.

## 2026-10-09 — What a morph leaves to Datastar's bindings

Asked for: attributes and text that follow the browser from the first paint, on the premise that a render puts back
the server's text under `data-text`, which an app had worked around with a preserved attribute shown through CSS
`content: attr(...)`. Measured in Chrome against Datastar 1.0.4, on the demo page, by dispatching the
`datastar-fetch` event its patch watcher listens for, with an element as the server would send it:

- `data-text` and `data-attr` each watch what they set, and set it again when a morph changes it. A note bound with
  `data-text` read "Copied" after the browser set its signal, and still did after a morph sending the server's empty
  span, and after one restructuring the elements around it. `hidden` bound with `data-attr` was the browser's after
  the morph too. So the premise was wrong: text needs no helper.
- Without `data-preserve-attr`, the morph does write the server's value: `hidden` was set right after the patch, and
  the binding's observer removed it again before anything painted. A field focused inside the panel stayed focused.
  With it, the morph wrote nothing at all, so preserving stays, for an element no other observer sees flip.
- `data-attr:aria-selected` with an expression that's `true` writes `aria-selected=""`, which counts as no state, so
  `aria-selected:` styles don't apply: the trap the app fell into. `ui/attr`'s expression turned `true`, `false`, a
  string and `null` into `"true"`, `"false"`, the string, and no attribute, and a morph changed none of them.
- A signal set to `null` is deleted, and an expression reading only it stops following it: later values reached a
  `data-text` that also read another signal, but not a `data-attr` reading that one alone. So nil is for an
  expression to return, not for a signal to hold.

The demo, after a restart for the Java change (`Splice`), in a tab of its own:

- The resources card's first paint, fetched: Registry selected (`aria-selected="true"`, `tabindex="0"`), Counts not
  (`"false"`, `-1`), the counts' list hidden and the table not, each preserving both attributes; Post disabled.
- Counts, clicked: selected, styled by `aria-selected:` (the indigo background), its list shown. The card, then
  patched twice from the REPL, changed 8 text nodes and no attribute: the tabs stayed as clicked.
- Post was enabled by "hello", disabled again by spaces and by an empty message, all in the browser.
- Suggest showed "Suggesting…", disabled, with what was typed kept through that render; the suggestion then replaced
  it, and Post was enabled. In a tab in the foreground, over three runs, "Suggesting…" showed 12 to 34 ms after the
  click and the suggestion landed 1,562 to 1,592 ms after it, against the server's 1,500 ms wait. In a tab in the
  background, Chrome holds timers to about a second, so the page's own timings there say nothing: a first try read
  the suggestion as landing within 350 ms.
- The definition in the Raw HTML card, an island keyed by its term: the first term sent the card once, since
  "Click a term." became an island; "An example?" sent the definition alone, 283 chars; another term was inserted
  after the old definition, which was then removed, starting over with its example hidden. The card wasn't sent, and
  the passage was the same node throughout.

## 2026-10-09 — The same read twice in one render

`use-hold`'s `:idempotent`, set by `observe` and `use-shared`: a read made again in one render returns the first one's
value. The demo's ticker now reads its topic's feed through two hooks of its own, the reading and its label, both
through one function over `observe`, so one call site with the same args. In Chrome, after a reload, it rendered both
("908", "reading #11 at 04:00:00.393"), where before the second read would have thrown and rendered the error view.
The Server resources table listed the feed's resource, `page.clj:184 ["alpha"]`, live, with 2 subscribers: the two
tabs open on `/`, one each, so the two reads are one hold.

## 2026-10-09 — State the browser keeps in its storage

`co.multiply.tropical.storage`, beside the cookie's state, now `co.multiply.tropical.cookie`. Read from the Datastar
1.0.4 bundle before building it, and checked where noted:

- A server patch setting a signal to null deletes it through the store's proxy, whose delete makes no patch event, so
  `data-on-signal-patch` never hears it. An expression setting one to null does make one. So `forget` patches a
  signal of its own, `_storageForget`, and the handler clears it once done: patched to `true` again while still
  `true`, it would change nothing, and make no event.
- Reading a missing path through `$` creates it, as an empty string, with a patch event. The handler steps into
  `_storage` with `in` before reading.
- The patch a handler gets carries the current value at each changed path, not the patch's own: `data-on-signal-patch`
  filters the event's paths, then reads each from the store.
- A `data-bind` write patches the store as any write does, so the handler hears every keystroke.
- On load, Datastar applies `document.documentElement`, then every element in document order, so the body's handler
  listens before the first frame's islands declare their signals.
- The statement pattern Datastar uses before returning an expression's last statement takes `\"` for an escaped quote
  wherever it is, so the end of `"a\\"` doesn't end the string, and a `;` in a later string becomes a split. JSON in
  expressions now writes a backslash as `\`, and the entry's declaration has no `;` at all. A run of the port,
  in Node, of a declaration whose default held `"})(); x\"`: one statement.

The handler's code ran in Node first, over plain objects for the store and a map for `localStorage`: a value equal to
its declared default kept no entry, an edit did; a watch sent at once, and a change 300 ms after the last; forget
dropped the entry, set the value back to its default and told the watch, which a 404 then dropped; the start-up pass
kept only the user's live entries; and with storage full, a write dropped the oldest of Tropical's entries until it
fit.

The demo's Drafts card, in Chrome, in a tab in the background:

- Each row counted its draft on the server, "Not reworked yet", from the value the browser sent once the page had the
  row: the server rendered it, and nothing was stored.
- Rework opened the dialog with the defaults. A section typed into, and a chip picked, which added its line to the
  note and pressed itself: the row read "A draft of 21 words", and the entry held the edit.
- Closed and opened again, a reload, and a restart of the server with every session closed, the tab reconnecting:
  the fields were as typed and the chip pressed each time, and the rows counted 21 words in the new session.
- After the restart, the page held four watches: the closed session's two, and the new one's. Typing again sent to
  both on that entry, and the old one's 404 dropped it.
- Start posted the draft as its action's value: "Started “Plan the launch”, 24 words." The entry was gone, the signal
  back to its default, and `_storageForget` cleared.
- A second proposal, edited and closed, then dismissed from its row: the entry was gone. Brought back, it read "Not
  reworked yet".
- Entries planted by hand, then a reload: another user's, one older than 30 days, one of version 0, and one of a
  state never declared were dropped; the user's own, and a key not Tropical's, were kept, and the kept draft loaded.
- Folding the vault's messages wrote `{"demo_page__messages_open": false}` to the cookie, from `_cookie`, and
  unfolding left it empty.
- Post's request carried `note` alone: no `_storage` or `_cookie` signal goes with an action.
- No console errors.

## 2026-10-09 — Signals ahead of a navigation

`navigate`'s `:signals`: an action that opens a page answers with events, `datastar-patch-signals` with the signals,
then a script appended to the body, which opens the page and removes itself, in place of a `text/javascript` answer.
Datastar handles an answer's events in order, and the body's signal-patch handler runs as the patch lands, so what it
writes is written before the script runs. In Chrome, in the demo:

- The Drafts dialog's "Start, then open alpha", with a section edited and its entry stored: the tab was on
  `/topic/alpha` with the toast "Started “Plan the launch”, 12 words.", and `localStorage` held no entry. Back on `/`,
  the row read "Not reworked yet", and the dialog showed the proposal's own text.
- "…with its summary hidden", its cookie state now in `:signals` (`cookie/patch`) rather than `:cookie`: alpha's page
  showed "Show the summary", and its HTML, fetched again with the tab's cookie, had the summary hidden in the first
  frame. So the cookie was written before the page loaded.

## 2026-10-09 — Digests of the app's names

`co.multiply.tropical.names`: the page sees a SHA-256 digest of an island's id, a cookie or storage state's signal
name, and a script's name, unless the JVM property `co.multiply.tropical.readable-names` is `true`. The suite passed
both ways: as `bb test` runs it, with digests, its assertions on ids taken through `island/element-id`, and with
`-J-Dco.multiply.tropical.readable-names=true`.

The demo, on the reloaded JVM with its state registries cleared, fetched: every element id a digest
(`t27d5211dc43eef66`), the script at `/tropical/t46b7d22850.<hash>.js`, and no namespace or var name in anything
Tropical wrote. What still reads `demo.page/toasts` is the Server resources table, which shows the registry's keys on
purpose. In Chrome:

- The ticker ticked, morphed by its element's digest. A posted message was inserted as a keyed row, with its
  `<time-ago>` defined from the digest's URL and reading "18 s ago". The definition in the Raw HTML card was replaced
  in place when another term was picked.
- Folding the vault's messages wrote the cookie under `t835c0c72ab`; a draft was stored under
  `tropical:["<user>","tc2c239d902","launch"]`, and its row counted it from the server.

## 2026-10-09 — Compressing the stream

**Jetty can't do it.** Jetty 12.1 compresses with zstd and brotli (`jetty-compression-zstandard`, `-brotli`, behind
its `CompressionHandler`, over zstd-jni and brotli4j). Read from their source on the 12.1 branch, the zstd encoder
compresses a write that isn't the response's last with `EndDirective.CONTINUE`, and flushes only on the last; the
brotli one's says "A non-last write simply waits for more content". Only its gzip has a sync flush. A stream never
writes its last, so its frames would sit in the encoder.

**What it saves.** The demo's stream, recorded for 60 s in-process: a new tab's stream request through `demo.core`'s
handler, written to an output stream that kept each flush. 101 passes of the pump, 203 events, 104,931 bytes: the
first pass the scripts, the root (34.5 KB) and the footer's signals, then the ticker's morph and the signals, about
700 bytes a pass. Replayed in a separate JVM through each encoder, flushing once per pass, on an M-series Mac:

| Encoder                                | Whole stream    | First pass | Later passes | Per pass |
|----------------------------------------|-----------------|------------|--------------|----------|
| none                                   | 104,931         | 35,208     | 697          |          |
| gzip, the JDK's default level (6)      | 13,795 (13.1 %) | 9,482      | 43           | 11–13 µs |
| zstd 3, window 2^18, hash 15, chain 14 | 14,905 (14.2 %) | 9,780      | 51           | 2–7 µs   |
| brotli 4, window 2^17                  | 14,283 (13.6 %) | 9,102      | 52           | 13 µs    |
| brotli 5, window 2^17                  | 12,812 (12.2 %) | 8,459      | 44           | 16 µs    |
| brotli 11, window 2^22                 | 10,925 (10.4 %) | 6,967      | 40           | 1,630 µs |

A flush per event instead of per pass sent 6–8 % more (gzip 14,633, zstd 15,871, brotli 4 15,378). The window made
no difference here: the ticker's morph repeats the one before it, well within any window.

It does for a large island. Synthetic: a 300-row table of 32.5 KB, its rows distinct, re-sent whole 60 times with
one row changed each time:

| Encoder                                | Later passes | Per pass |
|----------------------------------------|--------------|----------|
| gzip 6                                 | 3,441        | 360 µs   |
| zstd 3, window 2^15                    | 3,980        | 42 µs    |
| zstd 3, window 2^16                    | 1,209        | 19 µs    |
| zstd 3, window 2^17, hash 15, chain 14 | 542          | 10 µs    |
| zstd 3, window 2^18, hash 15, chain 14 | 139          | 6 µs     |
| zstd 5, window 2^18, hash 16, chain 15 | 118          | 9 µs     |
| brotli 3, window 2^16                  | 282          | 39 µs    |
| brotli 4, window 2^17                  | 40           | 36 µs    |
| brotli 5, window 2^18                  | 32           | 47 µs    |

**What it holds.** Each open stream holds its encoder. Measured as the growth of the process's resident memory with
500 open streams, each having encoded the large island's 60 passes, in a fresh JVM per setting, after a GC; the heap
part is the growth of the used heap:

| Encoder                                | Per stream | Of it, heap |
|----------------------------------------|------------|-------------|
| gzip 6                                 | 227 KB     | 4 KB        |
| zstd 3, window 2^17, hash 15, chain 14 | 596 KB     | 131 KB      |
| zstd 3, window 2^18, hash 15, chain 14 | 725 KB     | 131 KB      |
| zstd 3, window 2^18, hash 17, chain 16 | 1,300 KB   | 131 KB      |
| brotli 4, window 2^17                  | 926 KB     | 5 KB        |
| brotli 4, window 2^18                  | 1,179 KB   | 5 KB        |
| brotli 5, window 2^17                  | 1,728 KB   | 4 KB        |

zstd-jni's stream keeps a 128 KiB output buffer on the heap, whatever its settings. Smaller windows hold less (zstd
3 at 2^15: about 370 KB) but can't refer back across a large island, and one budget for all three, gzip's, left
zstd no better than gzip there. Chosen: zstd 3 with a 2^18 window and small tables, brotli 4 with a 2^17 window, and
gzip as the JDK has it. Brotli 5 compresses a little better at twice the memory.

`bb test`: 125 tests, 693 assertions. Among them, each encoding decodes the stream so far after every event, as a
browser decodes what arrived; a frame repeating an earlier one of 20 KB costs under a tenth of it; a frame is one
flush of the response, compressed or not; and a client gone mid-frame closes the stream and its response's output
stream. The flush test fails with `sse/together` stubbed to flush per event.

## 2026-10-09 — A click soon after a load, in a hidden tab

Looked like a lost click, and wasn't. Reported from browser automation: in a tab reading `visibilityState` `hidden`,
a click on a button whose action opens an island's dialog, about a second after the page loaded, showed no dialog
1.5 s later and no error; a second click opened it. The page's resource timings had a fetch to its own URL about
every 10 s.

Datastar 1.0.4's `@get` takes `openWhenHidden`, false by default, and `stream-init` doesn't pass it. It starts the
fetch at once, hidden or not; on `visibilitychange` to hidden it aborts it, and it fetches again once the page is
shown. In the demo, in Chrome:

- Loaded and kept hidden, the page held its stream: no `visibilitychange` came. A click 1.6 s after the load opened
  the dialog.
- Hidden after the load: the stream closed. The click's action ran on the server, and the dialog appeared when the
  tab was shown and the stream resynced. Shown again after more than the session's grace (15 s), the session had
  closed, and the tab reconnected into a new one without the dialog open.
- So the report fits a page hidden and shown again by the automation, each hide closing the stream. The 10 s fetches
  are likely those reopenings; it wasn't reproduced that far.

Chrome hides a window behind others, and throttles its timers to about once a second. Launched with
`--disable-backgrounding-occluded-windows --disable-renderer-backgrounding --disable-background-timer-throttling`,
a window behind others, unfocused, read `visible`, and ten 100 ms timeouts took 1,008 ms. The flags don't reach a
minimized window or a tab that isn't its window's selected one: the automation's window had opened minimized, its
outer size 0×0, and read `hidden` until it was restored and the tab selected. Then a click 880 ms after a load
showed the dialog 26 ms later.

`stream-init`'s `:open-when-hidden` passes `openWhenHidden: true`, for automation in development. Production keeps
Datastar's default (design notes, Sessions).

## 2026-10-10 — The compressed stream and `:open-when-hidden`, in Chrome

The demo, with `:compression [:zstd :br :gzip]`, in Chrome over `http://localhost:8080`, with `compression/choose`
wrapped from the REPL to record its arguments:

- Chrome sent `Accept-Encoding: gzip, deflate, br, zstd` over plain HTTP to localhost, and the stream was zstd. The
  page updated live over it: the ticker's reading advanced three in 3 s.
- Without `:open-when-hidden`, the page made to read hidden (`document.hidden` redefined, then a
  `visibilitychange` dispatched) closed its stream: the session read `:attached? false`.
- With `stream-init` wrapped from the REPL to pass `:open-when-hidden`, the page's `@get` carried
  `openWhenHidden: true`. Made to read hidden the same way, the session stayed attached and the readings kept
  arriving, three in 3 s.

The hiding was simulated: the automation couldn't select another tab of the window. Datastar decides from
`document.hidden` on `visibilitychange`, which is what the simulation gives it. Both vars were restored after.

## 2026-10-10 — zstd through a libzstd of tropical's own

zstd-jni couldn't serve an app whose dependencies pin an older zstd-jni, from about 2020: the classpath holds one
version. Shading it doesn't work. Its 1.5.7-22 macOS library exports 149 functions named
`Java_com_github_luben_zstd_…`, the names the JVM binds its native methods by, has no `JNI_OnLoad` or
`RegisterNatives` that would let a relocated class bind them, and names `com/github/luben/zstd/ZstdFrameProgression`
itself. aircompressor's pure-Java `ZstdOutputStream` (0.27) doesn't override `flush`. So tropical builds libzstd and
calls it through the foreign function API (`ZstdEncoder`).

The source: zstd 1.5.7, the latest release (February 2025; the development branch reads 1.6.0, unreleased). The
tarball's SHA-256, `eb33e51f…6fa3`, matches the one published with it, and its signature verifies against "Zstandard
Release Signing Key <signing@zstd.net>", `4EF4 AC63 455F C9F4 545D 9B7D EF8F E995 28B5 2FFD`, from
keyserver.ubuntu.com. zstd's release workflow signs as that address.

The build (`native/build-zstd.sh`): zstd's `libzstd-nomt` target, with `ZSTD_LIB_DECOMPRESSION=0`,
`ZSTD_LIB_DICTBUILDER=0` and `-DZSTD_TRACE=0`. macOS with Apple clang 21, one universal file for arm64 and x86_64,
macOS 11 and later; Linux with gcc 12.2 in Debian 12 images pinned by digest, linked `-Bsymbolic`, with a soname of its
own.

| Library                     | Bytes   | SHA-256       |
|-----------------------------|---------|---------------|
| `darwin/libzstd.dylib`      | 978,000 | `982423ca…fa` |
| `linux-x86_64/libzstd.so`   | 595,984 | `c86f281e…9e` |
| `linux-aarch64/libzstd.so`  | 526,400 | `8e061648…1b` |

Each exports zstd's 104 functions and nothing else. The Linux ones import `malloc`, `calloc`, `free`, `memcpy`,
`memmove` and `memset`, with symbol versions up to GLIBC_2.14 (x86_64) and 2.17 (aarch64). Without
`-DZSTD_TRACE=0` they also imported zstd's weak tracing hooks, which any library in the process defining them would
have received. The macOS file came out the same, byte for byte, from two builds.

200 frames of patches, each written and flushed, as one stream, at level 3 with a 2^18 window: 4,153 bytes, the same
bytes from tropical's encoder on macOS (arm64), on Ubuntu 26.04 and Alpine 3.24 (musl, no glibc shim) for amd64 and
arm64 under Temurin 25 in Docker, and from zstd-jni 1.5.7-22. Ubuntu's own libzstd.so.1 (1.5.7), named by
`co.multiply.tropical.zstd.library`, wrote the same bytes too. musl's loader takes `libc.so.6` as itself, so the glibc
build runs there unchanged. A wrong path, or a missing library, fails with what to set.

On the demo's captured stream and the large-island workload of 2026-10-09, one fresh JVM per configuration (JDK 27,
macOS arm64), timing after 400 warm-up runs, memory over 500 streams:

| zstd 3, window 2^18     | Wire, demo | µs a pass, demo | µs a pass, large | Memory, demo | Memory, large |
|-------------------------|------------|-----------------|------------------|--------------|---------------|
| zstd-jni 1.5.7-22       | 14,905     | 1.9–2.0         | 6.0–6.6          | 532 + 131 KB | 712 + 131 KB  |
| tropical's, through FFM | 14,905     | 2.1–2.3         | 6.8–7.6          | 474 + 9 KB   | 669 + 9 KB    |

Memory is a stream's, resident and on the heap.

zstd-jni keeps a 128 KiB output buffer on the heap; tropical's encoder has 8 KiB buffers, native. zstd's own
allocation, `ZSTD_sizeof_CCtx`, is 1.25 MB a stream, of which a stream touches what its window fills. The first
version passed the arena's segments to each call, and took 3.1–3.4 µs a pass on the demo's stream: a shared arena's
segments are acquired and released, atomically, around every call that takes them. Passing plain addresses, guarded by
the encoder's lock instead, took that back. What remains is the copy of each write into native memory, where zstd-jni
pins the Java array for the call, holding off the garbage collector while it compresses.

In Chrome, under `bb dev` without zstd-jni on the classpath, the demo's stream was zstd, from `ZstdEncoder`, and the
page updated live: the ticker's reading advanced three in 3 s.

`bb test`: 127 tests, 707 assertions.

## 2026-10-10 — The tests in Linux

`bb test:linux` runs the suite in Docker, under JDK 25 (Temurin 25.0.4.1, the official Clojure images): Debian 13
(glibc) or Alpine 3.24 (musl), each for amd64 and arm64. The repository is mounted read-only, with the classes
compiled on the host; the dependencies are kept in a volume. On an Apple-silicon Mac, arm64 runs natively, in about
20 s, and amd64 under emulation, in about a minute.

- glibc, amd64 and arm64: 127 tests, 707 assertions, no failures.
- musl, amd64 and arm64: brotli's five loading tests errored. brotli4j's Linux library needs glibc's loader
  (`ld-linux-aarch64.so.1` on arm64), and brotli4j builds none for musl. tropical's libzstd ran, as it did in the
  checks before. With brotli left out (`TROPICAL_TEST_SKIP_ENCODINGS=br`, which the task sets for musl): 127 tests,
  695 assertions.
- One run on arm64 with musl had one failure, whose output wasn't kept. Ten runs since, on arm64 with glibc and with
  musl, had none. Likely the race in `sse_test` known before; not established.

A Docker Desktop that quit during a run left the next runs failing to reach its socket, which reads as each target
failing at once.

## 2026-10-10 — brotli through a libbrotlienc of tropical's own

brotli was the app's dependency, brotli4j, reached by reflection. That left it open to what zstd-jni ran into, a
brotli4j of another version among the app's dependencies, and brotli4j builds no library for musl. So tropical builds
brotli's encoder and calls it as it does libzstd (`BrotliEncoder`).

The source: brotli 1.2.0, the latest release (October 2025). brotli signs no release: the tag is a plain one, on an
unsigned commit. GitHub's archive of the tag, SHA-256 `816c96e8…dfec`, names the release commit, `028fb5a2…`, in its
header, and its `c/common`, `c/enc` and `c/include` hash to that commit's git trees, less `c/common/dictionary.bin*`,
which brotli's `.gitattributes` keeps out of archives (the dictionary is compiled in from `dictionary.c`). brotli4j
1.23.0 carries the same release, file for file: its `brotli/` trees hash the same, less those two files too.

The build (`native/build-brotli.sh`): `c/common` and `c/enc` in one library, with the flags brotli's CMake gives a
release's shared libraries (`-O3 -DNDEBUG`, `BROTLI_SHARED_COMPILATION`), and hidden visibility. The compilers,
images, targets and linking are libzstd's. glibc 2.29 gave libm's `log2` a new symbol version, so a build on Debian
12 would have needed glibc 2.29; an `.symver` binds the first, `GLIBC_2.2.5` on x86_64 and `GLIBC_2.17` on aarch64.

| Library                         | Bytes     | SHA-256       |
|---------------------------------|-----------|---------------|
| `darwin/libbrotlienc.dylib`     | 1,762,640 | `4d7119fc…94` |
| `linux-x86_64/libbrotlienc.so`  | 866,872   | `daf72ae6…06` |
| `linux-aarch64/libbrotlienc.so` | 854,520   | `0aee778e…5e` |

Each exports brotli's 24 symbols: the encoder's API, the shared dictionary's, and the default allocator's. The Linux
ones import `malloc`, `calloc`, `free`, `memcpy`, `memmove`, `memset`, `exit` and `log2`, with symbol versions up to
GLIBC_2.14 (x86_64) and 2.17 (aarch64), as libzstd's do. All three came out the same, byte for byte, from two builds.
The jar went from 903 KB to 2,491,100 bytes; the three libraries are 1.58 MB of it, compressed.

`exit` is brotli's answer to a failed malloc: by default (`BROTLI_ENCODER_EXIT_ON_OOM`) the encoder ends the process.
`BROTLI_ENCODER_CLEANUP_ON_OOM` returns an error instead, but no build of brotli's own uses it, nor brotli4j's, and it
tracks the encoder's live allocations in a table of 128 that release builds don't bound-check. Left at the default.

brotli 1.2.0's `BROTLI_STATIC_INIT_EARLY` computes the encoder's dictionary tables as the library loads instead of
carrying them: macOS arm64 alone went from 861,512 bytes to 580,920 (395 KB to 258 KB gzipped), about 0.55 MB off the
jar over the four slices. No build of brotli's own sets it either; not taken.

The encoder takes brotli's output from brotli's own buffer (`BrotliEncoderTakeOutput`, which brotli's header suggests
for bindings) rather than giving it one to copy into, and copies its input into an 8 KiB native buffer, in text mode.
Against brotli4j 1.23.0 (macOS arm64), the stream's bytes were the same at quality 4 with a 2^17 window, 5 with 2^18
and 11 with 2^22, on the demo's captured stream and on the large-island workload, flushing once per pass. At quality 1
they differ, both decoding: brotli's fast path compresses each call's input as given, and brotli4j hands it 4 KiB at a
time where tropical's encoder hands 8 KiB. Tropical's setting is quality 4.

Measured as for libzstd: the demo's captured stream and the large-island workload of 2026-10-09, one fresh JVM per
run, JDK 27, macOS arm64, timing after 400 warm-up runs, memory over 500 streams, quality 4, window 2^17. The machine
was loaded (load average 8–12), so the runs were interleaved, four rounds:

| brotli 4, window 2^17   | Wire, demo | µs a pass, demo | µs a pass, large | Memory, demo | Memory, large |
|-------------------------|------------|-----------------|------------------|--------------|---------------|
| brotli4j 1.23.0         | 14,283     | 12.9–13.3       | 36.2–36.7        | 781 KB       | 925–926 KB    |
| tropical's, through FFM | 14,283     | 13.5–13.8       | 36.8–38.3        | 796–802 KB   | 928–931 KB    |

Memory is a stream's, resident, of which 5–9 KB is on the heap. The encoder's own buffers are an 8 KiB native input
buffer and an 8 KiB heap array, where brotli4j has a 4 KiB input buffer; the rest of the difference wasn't traced. A
variant that gave brotli an 8 KiB native buffer to copy its output into timed the same as taking it, within the noise,
interleaved with these. In an earlier round, less loaded, that variant was level with brotli4j or ahead: 12.4–12.5 µs a
pass against 12.7–12.8 on the demo's stream, 35.0–35.2 against 35.3–36.7 on the large island.

The tests decode brotli with Google's pure-Java decoder, `org.brotli:dec` 0.1.2, last published in 2017, as brotli4j's
doesn't load on musl. It decodes a whole stream only: given a stream so far, it returns nothing. A flushed brotli stream
ends with a whole metablock, at a byte's boundary, so the test appends one byte, 0x03, an empty last metablock, and
decodes that; bytes cut anywhere else fail to decode, as a stream short 5 bytes did. brotli4j is gone from the
dependencies, the dev alias's too.

In Chrome, under `bb dev` without brotli4j on the classpath and the demo's encodings put `[:br :zstd :gzip]` for the
check, the stream came as `br`, from `BrotliEncoder`, and the page updated live: five frames in 3 s, the ticker's among
them three times. With curl, 4 s of the stream were 2,032 bytes, decoding to 19,903, 21 events.

`bb test`: 127 tests, 713 assertions. `bb test:linux all`, twice: the same on each of the four, brotli included on musl,
but for one failure in the second run, on amd64, outside compression. `resource_test`'s `closing-undoes-the-subject`
found a closed resource's entry still registered: `close-if-idle!` cancels the subject's task, compelled, before it
removes the entry, so the task's `finally`, which the test waits on, can run first. amd64 again: no failures. Perhaps
the failure on arm64 of the entry before, whose output wasn't kept.

## 2026-10-10 — A closing resource is forgotten before it tears down

`close-if-idle!` cancelled a resource's task, then removed its entry. Cancelling starts the teardown on other threads:
the subject's thread is interrupted and runs its `finally`, and the `q/finally` in `start` calls the cleanup fn. So a
teardown could find the entry still registered. `resource_test`'s `closing-undoes-the-subject`, which waits on its
subject's `finally` and then looks, failed that way once on amd64 under emulation (the entry above). `acquire!`,
`release!` and closing exclude each other under the registry's lock; only what reads without it could see the entry:
`summary`, `cell` and through it `shared/swap!`, and the teardown itself.

Now the entry goes first, then the cancel. Removing it only once torn down, in a `q/finally` of the cancel, would
leave a cancelled resource registered meanwhile, for an `acquire!` to find and subscribe to.

A new test, `a-resource-is-forgotten-before-it-tears-down`, makes closing wait for the teardown, with `cancel!`
redefined to deref `q/cancel`'s task, which settles once its target has torn down. The old order then shows every
time: against it, the subject's `finally` found its entry as `:lingering` in three runs of three; against the new, in
none of three.

Closing still doesn't wait for the teardown, so a resource opened again soon after can run beside the last as it tears
down (backlog).

`bb test`: 128 tests, 714 assertions.

## 2026-10-10 — A page left behind, with `:open-when-hidden`

Item 23 of the app's requirements. In Chrome, on the demo over HTTP/1.1, with `:open-when-hidden` on: loading
`/topic/alpha`, `/topic/beta`, `/topic/gamma` and `/` in turn, about a second apart, each a full load in one tab. The
first five pages had their first byte in 7–159 ms; the sixth hadn't loaded 45 s later, and the server had six
sessions attached. Chrome had frozen each page left in its back/forward cache, as on 2026-10-06, its stream open, and
HTTP/1.1 allows six connections to a host.

With the option, `stream-init` now opens the stream under an `AbortController` (Datastar 1.0.4's
`requestCancellation`), aborted on `pagehide`, and opens it again, as the same page load, on a `pageshow` from the
cache. Without the option, Datastar does as much itself: leaving a page hides it, which closes its stream, and showing
it reopens it. Datastar runs `data-init`'s expression as one function body and rewrites each `@get(` where it stands,
so a closure can call it again.

The same ten loads after the change: each at once, 5–160 ms to the first byte, and the server had one session
attached and ten detached. Going back loaded the page afresh: Chrome gives `response-cache-control-no-store` as why it
didn't restore it, so the reopening didn't run there. Fired on a page by hand, a `pagehide` stopped its stream, with
no frames in 3 s and its session detached, and a `pageshow` with `persisted` opened it again: the same session
reattached, eleven frames in 3 s, and no reload.

## 2026-10-10 — Drop areas

Item 24 of the app's requirements: `upload/drop-area`, `upload/dropping`, and `upload/drop-guard` in
`ui/page-attrs`. In the demo, the root island is a drop area with a list of its own, holding the uploads card's.

In Chrome, synthetic drag events, each a `DragEvent` with a `DataTransfer` holding a `File`:

- On the page outside the card, a drag of files showed the page's overlay alone, and over the card, the card's alone.
  Leaving the page's element for the card's didn't clear it; leaving the window, `relatedTarget` null, did.
- A drag of text over the card wasn't taken: not prevented, the overlay as it was.
- A drop on the card uploaded its file into the card's list; one on the page outside it, into the page's list, and
  not the card's.
- On a topic page, with no areas, the body refused a drag and a drop of files, and let a drag of text be. A file
  input added to the page took files, not refused.
- Two areas side by side, added to that page: each showed its own overlay; over the gap between them, neither did, and
  a drop there was refused and reached neither. A drop on one reached that one alone.

Not shown by synthetic events: the drop effect, which a constructed `DataTransfer` keeps at `none`, and the browser
opening a file dropped where nothing takes it. By hand, screenshots dragged from the Finder into Chrome on macOS: one
dropped on the page outside the card went to the page's list, one dropped on the card to the card's, and both overlays
were cleared after. On a topic page, with no areas, a drop left the tab on its page. No cursor showed the refusal there:
macOS has none for a drop refused, only a badge on one taken.

`bb test`: 129 tests, 727 assertions.

## 2026-10-10 — A placeholder that never matched its root

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

## 2026-10-10 — The first frame's scripts, twice

An app found each page's head with its island script twice: once from the page, and once appended over the stream after
it attached. By design: the session sent the first frame's scripts over the stream too, in case the page had left them
out, and a module runs once per URL, so nothing ran twice. Now `script/tags`, which `ring/scripts` calls, records that
the page has the first frame's scripts, and the session counts them as sent; a page without them still gets them over
the stream.

A test triggers a patch once the stream has attached, and checks whether the script came over the stream before it.
With the record disabled, the page with the scripts in its head got them again, and the test failed. In the demo, the
stream sent 9 element patches in 3 s, and not the head's script. In the app: one copy per page, its streams live, and
no errors in the console.

## 2026-10-10 — A formatter dropped imports in use

IntelliJ's formatter, run over the tree, removed imports still in use: `BrotliEncoder` and `ZstdEncoder` from
`compression.clj`, and `ZstdInputStreamNoFinalizer` from `ring_test.clj`. Each class was used only as a constructor,
`(ZstdEncoder. ...)`, or through a method value, `ZstdEncoder/.nativeSize`, which it took for no use. Nothing that
loads `compression` compiled, so the first test namespace failed to load, and `bb lint` reported each as an unresolved
symbol. After formatting, `bb lint` and `bb test` find it.

The tree after these, `bb test` and `bb test:linux all`, on amd64 and arm64, with glibc and with musl: 145 tests, 782
assertions, no failures.

## 2026-10-13 — A Rocket component in the demo

Datastar 1.0.4 moved Rocket, its web components, out of Datastar Pro, into a bundle of its own with Datastar,
`datastar-rocket.js` ("Datastar v1.0.4 + Rocket beta.2"). The demo now loads it in place of `datastar.js`, named
`datastar` in an import map, and has a Rocket component, `<star-rating>`, in a card of its own: props from the island,
local signals for the star under the pointer and an open breakdown, a `rate` event the island takes as an action's
value, and the island's label, with an action, in its children. Checked in Chrome, with the page's CSP (a nonce,
`'strict-dynamic'`, no `'unsafe-eval'`), from a tab kept streaming while hidden (`:open-when-hidden`, set from the
REPL):

- The component rendered in CSP mode, with no console errors. A click posted the action with `evt.detail` as its value,
  `{"stars": 4}`, and the island's patch changed the props, the summary and the label, while the breakdown stayed
  open. A rating from another user, set from the REPL, changed every prop the same way.
- A full resync, after `session/close-all!`, left the shadow root and the open breakdown as they were.
- The island's action in the component's children, `@post(...)`, arrived rewritten by Rocket to
  `@dispatchRocket("post", ...)`, and worked: the component defines no `post`, so it passed the call on to Datastar.
  It worked in the first frame, and patched in after a rating.
- Moving the element, with `moveBefore` as the morph moves one, or with `insertBefore`, closed the breakdown: the
  component defines no `connectedMoveCallback`, so it is disconnected, which drops its signals, and set up again.
- The morph removed `data-rocket-host`, which Rocket sets on the element as it connects, since the island doesn't
  render it; it keeps `data-scope-children`, which it knows. Rocket reads the marker to tell a component's children
  from those of one nested in them. The component's own children, and their actions, worked without it.
- The same component in `light` mode, for the test only: a rating changed the props, and the component rendered again
  with the label it had taken in when it started, "Rate the demo.", over the island's "You rated it 3". A resync, its
  props unchanged, removed what it drew, and left only the island's label.

Not exercised: a Rocket module first loaded over the stream, when an island using it is patched in, and a component
inside another's children.

## 2026-10-13 — An action that reaches another server, and streams ended at an age

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

## 2026-10-13 — A dropped action's notice, and a reconnect that sends only what changed

The demo's footer now shows a notice on `tropical:reopen`, by a signal. Checked in Chrome with the same stand-in for
another server as in the entry before: the click on the rating card was dropped, the notice showed, the page didn't
reload, Dismiss hid it, and the next click rated.

A stream ended for its age now keeps what the page was sent, unless a patch went out within a second of the end
(`session/recycle-quiet-ms`). With `:stream-max-ms` 5000 on `/topic/gamma`, whose feed ticks every 2.5 s, the
footer's wire counts over 23 s and four reconnects:

- Two reconnects came more than a second after a patch, and sent only what changed while the stream was down,
  4205 to 4784 chars: the resources card, which counts sessions attached and detached, and once a tick that fell in
  the gap.
- Two came within about a second of a tick, and resent the root, the whole page, 8161 chars.

On the home page, whose feed ticks every second, a stream is never a second quiet, so every reconnect resyncs, as
before. Behind a balancer the quiet case is the one `:stream-max-ms` is for: an idle tab.

## 2026-10-13 — Event ids say what a reconnecting page has

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

## 2026-10-15 — A page's revision

Datastar 1.0.4 copies a request's headers once, as `@get` starts it. In the bundle the demo loads, a retry rebuilds
the URL, with the signals in its query, and sends the headers as they were, `last-event-id` added, as does a hidden
page shown again. So what a page sends with its stream can't change after it opens it: a list of the scripts in its
head, read as the stream opens, would lack one patched in later on the reconnect after a restart. The revision
covers every script instead.

Cost of `ring/revision-of`, from the REPL in the dev JVM, warm, over 20000 calls:

- 15 µs a call while it rendered tropical's code in the page on each: about 10 µs rendering it, 2 µs joining the
  parts, 3 µs the SHA-256 of their 6 KB. Joined with `pr-str` before that, 430 µs, as it prints a string a character
  at a time.
- 2 µs with that code's digest kept until the cookie and storage states declared change.

Checked in Chrome on the home page. With what the demo's head loads changed from the REPL, Tailwind's URL given a
query, `session/close-all!` reloaded the tab once, into a page of the new revision, whose stream attached. With
nothing changed, `close-all!` rebuilt it in place, and a value set on `window` before stayed.

`sse-test/await-returns-once-closed` failed once in a full run: the stream still open after the task in `await` was
cancelled (line 125). It passed in three runs of its namespace after. It checks as soon as `cancel` settles, while
the interrupted thread closes the stream: a race in the test, not in the stream.

Then an action that reaches a server of a new release, made from the REPL as in the 2026-10-13 entries: for the test
tab only, `action/invoke!` answered `[:gone]` and `session/held?` false, and the head's URL changed. A click was
answered with the reopen, the reopened stream with the reload, and the page loaded into the new revision, whose
stream attached: four requests, the click dropped, as a reopen drops it.

The extension's network log showed the two POSTs a page of the demo makes as it loads as 503. The server answered
both with 204, by a wrapper over the handler, and the page's resource timing has 204 for both: the log mislabels
them.

## 2026-10-15 — Internal boundaries and frame ownership

`39ebd25` separated frame compilation and patch planning, session delivery, and signal-patch composition into
internal namespaces. Browser-state naming and validation share helpers, as does the hashing used for names and
revisions. Cookie and storage persistence moved into JavaScript resources, still embedded and run synchronously,
with direct Node.js tests. The internal `Call`, `Hole`, `Template` and `Frame` types moved to `internal.model`;
taking up that change in this repository's running development JVM required a restart.

A review found an ownership gap above `abandon-render!`: when a child aborted a frame, its ancestors had already
acquired their new holds and released their old ones, but the runtime still held their old instances. Disposal
leaked the new holds and released the old ones twice. The regression covers two ancestors, both on a first frame
and after a successful frame, with a separate reader's claim left intact. Reconciliation now records each rendered
instance before visiting its children, so disposal sees the holds that instance actually owns.

The storage loader and persistence handler now take their global's name from one definition. The JavaScript tests
use a different configured name, so a literal name in the handler cannot silently bypass their defaults. Both
`bb test` and `bb test:linux` run these tests on the host; the Linux JVM matrix remains in its containers.

The review's Chrome smoke test found cookie persistence, storage observation, reload and dismiss working, with no
console errors. Its signal-patch attribute retained its newlines, so the earlier explanation about HTML folding
them was wrong. The resources keep block comments and explicit statement endings as an embedding convention.

## 2026-10-15 — Profiling the update path

Baseline: `4d918cd`, with no production-code changes during the measurements. Local macOS arm64, ten available
processors, Corretto 27+33-FR, Clojure 1.12.6, a fixed 512 MiB heap. These are local measurements with other desktop
work running, not capacity numbers or comparisons with earlier logbook entries.

`dev/bench/profile.clj` adds repeatable workloads and a JFR summarizer; the commands are in `development.md`.
Each timing case warms for two seconds, then reports the median of seven batches, with batch size chosen from the
warm-up throughput. Allocation comes from the calling platform thread's allocation counter. Separate ten-second
JFR recordings use 1 ms execution sampling and weighted allocation sampling at up to 1,000 events/s, after another
warm-up. CPU percentages below refer to the benchmark thread's execution samples; inclusive percentages overlap.
Allocation attribution is sampled, while the allocation totals in the tables are measured directly.

One step publishes through a Cell, drains an Inbox, reconciles the tree, prepares patches and writes a batch to a
null output stream. The sparse workloads have two ancestors above the list, with each row reading its own source.
The broad workloads have every row read the same source. The filtered workload has 1,000 readers of one source,
all selecting an unchanged value. The list-change workload alternates adding and removing its last row. Row HTML
is deliberately small. These exclude the session pump, its 50 ms pacing, HTTP, compression, browser work and real
resource I/O. The filtered case also prepares an empty batch, which a real session can skip.

First sweep (decimal KB and MB):

| Update | Median time | Allocated per update |
|---|---:|---:|
| One row among 10 | 11.7 µs | 22.1 KB |
| One row among 100 | 26.6 µs | 22.6 KB |
| One row among 1,000 | 198 µs | 23.5 KB |
| One row among 10,000 | 4.89 ms | 24.0 KB |
| One row below 20 wrapper islands | 27.4 µs | 75.3 KB |
| All 100 rows | 569 µs | 1.28 MB |
| All 1,000 rows | 7.95 ms | 13.33 MB |
| 1,000 selectors reject the update | 297 µs | 403 KB |
| Add/remove one row in a 1,000-row list | 799 µs | 690 KB |

Every sparse update sends the same 85 characters, irrespective of list width. Subsequent warmed baseline runs put
one changed row among 1,000 at 167–198 µs, among 10,000 at 2.97–4.89 ms, and a broad 1,000-row update around
6.7–8.0 ms. Allocation varied less, though compilation and escape analysis moved the broad case to about 12.7 MB.
Timing differences smaller than these spreads need paired measurements.

Findings and priority:

1. **Visit fewer unchanged siblings.** In the 1,000-row sparse recording, `reconcile` accounts for 81% of samples,
   while `render-island` accounts for only 3.6%. `changed-slots` in patch planning accounts for another 14%.
   Reconciliation checks every child of an ancestor, including its instance, render function and arguments; patch
   planning then scans the child frames. The cost grows with the width even though just one row renders.

   An isolated experiment skipped child reconciliation outside `on-path` when the parent had not rendered. It
   still loops over siblings and retains the existing patch scan, but avoids their instance and argument checks:

   | Sparse update | Original, two paired rounds | Early skip, same rounds |
   |---|---:|---:|
   | 1,000 rows | 167 / 177 µs | 60 / 62 µs |
   | 10,000 rows | 4.29 / 2.97 ms | 0.59 / 0.71 ms |

   Allocation was effectively unchanged. This is a useful first change in Clojure. A later index from a parent to
   its affected children could avoid the sibling walk altogether. Any similar shortcut in patch planning must
   handle comparisons against an older sent frame, after coalescing or reconnecting, rather than assuming the client
   has the immediately preceding frame.

2. **Remove temporary collections in invalidation and patch aggregation.** `dirty-islands` builds lazy sequences,
   `[sub id]` pairs and a predicate per reader. It accounts for 98.6% of samples and almost all allocation in the
   filtered workload. In a small sparse update, `prepare-frame` accounts for about 46% of sampled allocation;
   merging child patch maps is about 24% by itself. Each ancestor currently combines a fresh patch result even when
   there is only one changed child.

   A second isolated experiment used direct iteration for invalidation, returned the first child's immutable patch
   result directly, and appended only nonempty patch categories thereafter. Two paired rounds, reversing the order
   in the second round:

   | Update | Original time | Experiment time | Original allocation | Experiment allocation |
   |---|---:|---:|---:|---:|
   | One row among 10 | 10.6–10.7 µs | 8.6–9.1 µs | 21.5–21.6 KB | 15.6–15.7 KB |
   | One row below 20 wrappers | 25.2–25.3 µs | 20.0–20.1 µs | 75.1–75.2 KB | 39.0 KB |
   | 1,000 selectors reject the update | 235–241 µs | 143–174 µs | 403 KB | 23.6 KB |

   The 1,000-row sparse update remained dominated by sibling traversal. Broad updates allocated about 13% less,
   but their timings were inconsistent, so no broad-update speedup is established. These changes need no new Java.

3. **Preserve list bookkeeping across small structural changes.** Adding or removing one child loses the
   `:kept?` path: all child ids, the slots map and the placed-id set are rebuilt. In the list-change recording,
   child-id construction accounts for about 35% of sampled allocation. Reusing surviving ids and updating the
   previous slots map is a better candidate than further optimizing the existing Java splice detector, which is
   only about 8% of CPU samples here.

4. **Consider a typed, mutable instance only after the traversal changes.** Broad updates spend about 57% of samples
   in `render-island`, including 18% in compilation. Instance updates, read bookkeeping and persistent-map operations
   contribute substantial allocation. A session-owned Java instance with direct fields and reusable read storage
   could express its single-threaded ownership and reduce allocation. Clojure `deftype` is another option. Keep the
   frames used for sent/latest comparison immutable, and preserve installation of current hold ownership before
   descending into children, including when a render aborts.

   A smaller experiment batched `render-island`'s instance-map updates through a transient. It saved only about
   0.54 MB of a 12.7 MB broad update (4%), with little timing benefit. That alone does not justify a representation
   rewrite. Repeated element-id hashing is secondary too: about 4% of broad-update CPU samples, though caching it
   per mounted instance could save allocation without an unbounded global cache.

The existing primitive benchmarks were also run in an isolated JVM. Signals cost 56 ns per publishing change and
reading session through Cell, 61 ns through RefSignal, against 190 ns for a watch and queue; the signal paths allocate
about 2.1 bytes per session/change. When ten publications coalesce before a drain, the signal paths cost about 8 ns
per publication/session. A small SSE event costs 0.278 µs and 440 bytes; a 26.7 KB event costs 27.8 µs and 27.3 KB;
a signals event costs 0.305 µs and 512 bytes. The demo's render/patch/HTML benchmark gives 15.2 µs, 20.5 KB and
188 characters per ticker change. The signal and SSE primitives are not the first optimization targets.

A registry-only exercise kept one subscriber per resource, repeatedly acquired/released an additional subscriber,
and verified that the retained subscriptions survived. An uncontended pair cost about 0.42 µs and 496 bytes;
a warmed virtual-thread worker reached roughly 3.5–4.4 million pairs/s. Eight workers on distinct keys together
reached 1.6–1.7 million pairs/s, consistent with the registry's single lock. This exposes a ceiling for heavy resource
churn, not a cost paid by every render: existing holds reuse their acquisitions. No registry rewrite is indicated
without a workload dominated by simultaneous mounts and unmounts. All exercise resources were released afterwards.

The prototypes were confined to throwaway files under `target/perf` and separate JVMs. Invalidation and patch
results were compared with the original functions on the same inputs across all nine workloads. With the early
skip and collection changes installed together in an isolated JVM, the runtime, island and session suites passed:
41 tests, 180 assertions. These are promising prototypes, not a completed production optimization. Recordings,
summary EDN, timing samples and prototype scripts remain under `target/perf`; no optimization was installed in
`src`. Network latency, browser rendering, compression, retained heap and multi-session end-to-end throughput were
not measured in this pass.

## 2026-10-15 — Applying the measured update-path changes

Implemented the three targeted changes from the profiling pass, in Clojure:

- When a parent has not rendered, reconciliation skips children outside the changed path before looking up their
  instances and comparing arguments. A parent that did render still checks every call it placed.
- Invalidation iterates a subscription's readers and each reader's selected values directly. Patch aggregation
  reuses its first child's immutable result and appends only nonempty categories, also when combining splices.
- When a list changes shape, surviving children reuse the ids already in its frame. Reconciliation edits a transient
  copy of the previous slots map, updating changed entries and removing dropped children, instead of rebuilding the
  map. It unmounts removed children after visiting the new calls, in the previous document order. The old frame stays
  immutable, and a rendered instance still records its current hold ownership before any child can abort.

Comparison: four fresh JVMs, baseline / optimized / optimized / baseline. The baseline prepended copies of
`runtime.clj` and `internal/frame.clj` from `4d918cd` to the classpath; each process recorded the resolved source URL
so the selected implementation was checked. Both versions used the same current benchmark file, JDK 27, Clojure
1.12.6 and 512 MiB heap. The second pair reversed the workload order. Each entry below is the range of the two
seven-batch medians, with the same two-second warm-up as the profiling pass. These are wall times in the local
synthetic workload, not HTTP latency or production capacity. No other benchmark or test ran concurrently.

| Update | Before | After | Allocation before → after |
|---|---:|---:|---:|
| One row among 10 | 9.5–9.7 µs | 7.4–7.5 µs | 21.7–22.1 → 15.6–15.8 KB |
| One row among 100 | 22.6–24.3 µs | 11.2–11.6 µs | 22.3–22.7 → 16.2–16.4 KB |
| One row among 1,000 | 166–174 µs | 57–59 µs | 23.1–23.5 → 17.1–17.2 KB |
| One row among 10,000 | 3.78–3.94 ms | 0.587–0.599 ms | 23.9–24.0 → 17.6 KB |
| One row below 20 wrappers | 24.7–27.2 µs | 20.1–22.2 µs | 75.3–75.4 → 39.0–39.2 KB |
| All 100 rows | 515–538 µs | 464–479 µs | 1.24–1.29 → 1.06–1.07 MB |
| All 1,000 rows | 6.57–7.07 ms | 5.95–6.52 ms | 12.95–13.38 → 11.16–11.27 MB |
| 1,000 selectors reject the update | 219–298 µs | 137–142 µs | 403 → 23.5 KB |
| Add/remove one row in a 1,000-row list | 775–799 µs | 534–592 µs | 689 → 361 KB |

The strongest time gains are about threefold for a sparse 1,000-row update and 6.4–6.6-fold at 10,000 rows. List
changes allocate about 48% less; rejected updates about 94% less. Broad-update allocation falls by 13–17%, while its
time improvement ranges from negligible to 16% across the paired rounds, so the allocation reduction is the clearer
result there. Output character counts and patch-category counts matched between versions for every workload.

The existing demo benchmark was also run in separate baseline and optimized JVMs, three times each, with 10,000
warm-up and 50,000 measured updates per run. Baseline: 13.19, 10.73, 10.91 µs and about 20.4 KB per update. After:
11.99, 9.94, 8.23 µs and 16.1–16.2 KB. Every run sent 188 characters. Timing overlaps; the approximately 21% lower
allocation is consistent. This benchmark renders and serializes patches, without the pump or SSE.

Two fresh ten-second JFR recordings locate the remaining costs. For one changed row among 1,000, reconciliation
accounts for about 48% of samples and `changed-slots` for 46%; the latter still scans siblings when comparing the
sent frame with the latest one. For list changes, rendering the parent, creating its calls and holes, and checking
its children dominate; child-id construction is no longer a major allocation site. A future change that avoids
these scans should preserve comparisons with older client frames, rather than assume every intermediate frame was
sent. This pass adds no Java classes or mutable frame representation.

Validation: `bb test` passes all eight Node tests and 169 JVM tests with 941 assertions; lint, formatting and
reflection checks pass. New regressions cover multiple selections of one source, simultaneous list edits and child
invalidations, surviving holds and remounts, disposal after a partially reconciled list aborts, and mixed sibling
patches against an older client frame. The existing randomized list-patching and render-failure tests also pass.
Raw paired measurements, source copies, demo results and the new recordings are in `target/perf/incisions`.
