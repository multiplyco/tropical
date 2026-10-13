# Changelog

## 0.2.0 - 2026-10-13

- Add `session/close-user!`, which closes a user's sessions, as when they sign out or are removed: their islands
  unmount, their actions are revoked, and their tabs reconnect through the route's middleware, which refuses a user no
  longer signed in, so each page reloads into the app's own answer. It reaches the sessions of its own JVM. The
  Security guide covers signing out, on one server and on several, where a root island following the user's standing
  covers every server.
- Add a guide to web components made with Datastar's Rocket, which the demo's `<star-rating>` follows, and the skill's
  points on them. No change to the library.

## 0.1.0 - 2026-10-10

- Islands as functions (`defisland`), rendered per browser tab on the server and patched into the page by Datastar:
  hooks keyed by name (`use-watch`, `use-state`, `use-hold`, and `use-session`, with the request that created the
  session), ids scoped by parent, and frames that
  send only the topmost islands that changed. A list that gains or loses children sends only those, inserted next to
  a kept sibling or removed by id, and a child whose key changed, starting over, goes in the old one's place without
  its parent.
- Tab sessions that outlive their SSE connection, each run by one pump, with a grace period, coalescing backpressure
  and heartbeats. A session whose pump fails closes its stream, so its client reconnects. A session's first frame can
  wait briefly for reads still pending (`:first-frame-ms`), so a cold page arrives with what lands in time.
- One Ring handler per page, serving the page, its stream and its actions at the page's URL, so all three pass the
  route's middleware, and a tab the server no longer knows is rebuilt from its route. `request-kind` tells its requests
  apart for middleware that runs before routing, and an action's body is capped. The page mounts the app with
  `ring/mount`, an element of its choosing that holds the first frame, or a placeholder the stream fills, and opens
  the stream, and its head takes the islands' scripts with `ring/scripts`. A page that opens no stream throws, and
  one without `ui/page-attrs` is logged.
- The stream compressed, opted into with `:compression`, such as `[:zstd :br :gzip]`: with the first encoding the
  request accepts, one encoder for the stream's life, and each frame flushed through it at once
  (`co.multiply.tropical.compression`). gzip is the JDK's. zstd and brotli are tropical's: libzstd and libbrotlienc,
  built from their release source for macOS and Linux and shipped in the jar, called through the JDK's foreign function
  API (`co.multiply.tropical.sse.ZstdEncoder`, `BrotliEncoder`), or the libraries the system properties
  `co.multiply.tropical.zstd.library` and `co.multiply.tropical.brotli.library` name.
- A frame's events go out in one flush (`sse/together`).
- `:open-when-hidden` in the app, keeping the stream open while the page is hidden, for browser automation in
  development. A page left behind still lets go of its stream, and one restored from the back/forward cache opens it
  again.
- `session/close-all!` as the last step of a reload in development: every open tab reconnects into a session of the
  new code, and is updated in place.
- A refused stream reloads its page, so the page request gets the app's own answer: `wrap-refused-streams` for refusals
  by the app's middleware, and the handler's own. A page whose stream is refused again is left as it is.
- Shared, reference-counted resources with a linger, read through `observe`: keyed by call site and arguments, and
  opened by a fn of `emit!`, as Missionary's `observe` is. The same read made again in one render, as by two hooks of
  an app's own over one call site, returns the same value from the one resource (`use-hold`'s `:idempotent`).
- State the server shares across sessions (`co.multiply.tropical.shared`): declared with `defshared`, read by key with
  `use-shared`, created by its first reader and held while read, lingering after so a page load keeps it, and changed
  from anywhere with `shared/swap!`. The key decides how shared it is: a user's, a document's, everyone's.
- State the browser owns, in two stores, each a namespace, kept in Datastar signals and never undone by a patch or a
  remount, with `ui/page-attrs` on the element the app mounts in keeping both:
  - In a cookie (`co.multiply.tropical.cookie`), such as whether a section is open: declared with `defcookie`, taken
    up by any island with `use-cookie`, and right in the first frame. An action sets one with `cookie/patch`.
  - In the browser's storage (`co.multiply.tropical.storage`), such as a draft the user is writing: JSON of any size,
    declared with `defstorage` with its encoding, taken up with `use-storage`, which a reload and a server restart
    leave, and which the server stores none of. An action takes one as its value, sets it with `storage/patch` and
    drops it with `storage/forget`; `storage/observe` reads it, for what only the server can render from it. Entries
    are the user's, kept only while they differ from their default, and dropped by age and version.

  `ui/attr`, under `ui/shown` and `ui/aria-expanded`, has any attribute follow any expression from the first paint,
  with `aria-*` states as the words `true` and `false`, `ui/path` names a signal for `data-bind`, and `ui/json` writes
  a value of the server's into an expression.
- Scripts the page needs, such as a web component (`co.multiply.tropical.script`): declared with `defscript` and taken
  up with `use` by the islands that need them, served by `ring/wrap-scripts` at URLs named by their content, and loaded
  once per page: in its head when the first frame needs them, or over the stream when an island needing one is patched
  in.
- Actions as capabilities: tokens bound to the user, revoked when their island stops rendering them. A handler opens
  another page on the page's origin by returning `(action/navigate path)`, and with `:signals`, the signals it would
  otherwise return are taken first, so what they write to the cookie or the browser's storage, such as a new item's
  state or the draft the action used up, is written before that page loads. An island whose page belongs elsewhere
  now, such as one showing an item that moved, renders `(action/navigate-element path)`, which takes every tab
  showing it there. An action can take a value only the browser knows when it is invoked (`use-action`'s `:value`),
  such as the clicked element's or a custom event's, passed to its handler beside the signals, in its own body.
  `use-token` gives a script that posts to the page itself an action's token. A handler reads the request it came on
  with `action/request`, and opens a page on another origin, such as a sign-in provider's, with `navigate`'s
  `:external`. Work that outlives its action patches the page's signals when it lands, through the island's
  `use-patch-signals`.
- Files the browser sends straight to the app's storage, such as a bucket taking presigned PUTs
  (`co.multiply.tropical.upload`): `use-uploads` asks the app where each file goes, keeps the list for the island to
  render, with each file's progress in a signal, and takes a report on an upload only for one of the island's own, named
  by the id it gave. `:settled` takes a pick in whole, once nothing is on its way. `drop-area` makes any element a drop
  area, the innermost under the pointer taking a drag, with `dropping` for the app's own overlay, and `ui/page-attrs`
  refuses files dropped outside every area.
- The page sees opaque, deterministic digests of the app's names: an island's element id, a cookie or storage state's
  signal, a script's URL (`co.multiply.tropical.names`). The JVM property `co.multiply.tropical.readable-names` shows
  them as written, for debugging. The default error view no longer names the island.
- Islands in an app's tests (`co.multiply.tropical.test`), rendered as a session renders them, without a server or a
  browser: `render!` waits briefly for pending reads, `action` invokes an action an island renders, found by its
  island's path and its key, and `failures` lists the renders that threw.
- A Claude Code plugin in the repository, whose `tropical` skill gives Claude what it can't infer from the API when it
  writes islands.
- A clj-kondo config in the jar, for apps to import, that lints `defisland` as `defn`, and `defcookie`, `defstorage`,
  `defscript` and `defshared` as `def`.
- An app-defined error view for islands that fail, which by default shows no exception message, and logging through
  `clojure.tools.logging`.
- Signals in Java: `Cell` and `RefSignal` mark a session's subscription at most once until it catches up, without
  allocating.
