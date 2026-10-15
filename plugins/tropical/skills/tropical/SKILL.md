---
name: tropical
description: >-
  Use when writing, reviewing or testing code that uses Tropical
  (co.multiply.tropical): server-rendered islands for Datastar. Covers
  defisland, the hooks (use-watch, use-state, use-hold, use-session,
  use-patch-signals), observe, use-action and navigate, defcookie, defstorage,
  defshared, defscript, use-uploads and drop areas, the ui helpers, mounting
  ring/handler, and testing islands with co.multiply.tropical.test.
---

# Tropical

Server-rendered islands for [Datastar](https://data-star.dev). Each browser tab has a session on the server that renders
its islands, functions returning hiccup, and sends what changed over one SSE stream, which Datastar morphs into the page
by element id. Writes come back as actions: closures rendered into the page as tokens, posted with Datastar's signals.

**Repository**: https://github.com/multiplyco/tropical —
`co.multiply/tropical {:mvn/version "0.6.0"}`, JDK 25+, Clojure 1.12+, Datastar 1.0.4 in the browser.

This file covers what you can't infer: where a reasonable guess from React, Electric, htmx or a Datastar server SDK is
wrong. It is not an API reference. The app-facing vars and namespaces carry thorough docstrings, so for signatures and
options, read `(clojure.repl/doc co.multiply.tropical.hooks/observe)` rather than guessing. The guides have the long
form of everything here, one per subject, at `https://github.com/multiplyco/tropical/blob/main/doc/<name>.md`:
`islands`, `reading`, `actions`, `browser-state`, `shared-state`, `scripts`, `uploads`, `testing`, `serving`,
`security` and `how-it-works`.

Resources and background work are [Quiescent](https://github.com/multiplyco/quiescent) tasks. If the `quiescent`
skill is available, load it for those.

## The model

- **Everything renders on the server.** There is no client component model and no client-side Clojure. The browser
  holds HTML, Datastar's signals, and the scripts the app chose to load. Anything interactive without a round trip is
  a Datastar expression, a JavaScript string in an attribute.
- **One session per tab, not per connection.** A GET of the page creates the session and renders the page from its
  first frame. The page then opens its stream. Datastar drops it routinely (a hidden tab closes it), and the session
  keeps its islands for 15 s (`session/grace-ms`), so a reconnect resyncs without reopening anything.
- **One pump per session.** A session's renders run on one virtual thread, never on the thread that made a change. A
  change marks the sessions that read it, and each renders the latest value when it gets to it: changes coalesce, and
  a slow client gets fewer frames, never a backlog.
- **The view never comes back in an action's response.** An action changes state. The islands that read that state
  render again and arrive over the stream. The response is 204, signals as JSON, or a navigation.

## Islands

```clojure
(defisland message {:key :id}            ; a key fn, for siblings of one kind
  [{:keys [text]}]
  [:li text])

(defisland conversation
  [id]
  (let [msgs (use-watch !messages #(get % id))]
    [:ul (map message msgs)]))           ; calling an island places it
```

- **Calling an island places it; it doesn't render it.** It returns a `Call`, as a React element. The runtime renders
  an island when it is new, when its arguments changed (`=`), or when something it read changed, and otherwise reuses
  its last output, and everything below it, without visiting them.
- **Arguments are compared with `=`.** A fresh fn or a collection built differently each render makes the child render
  every time. Pass data and stable references (vars, def'd fns, atoms created once).
- **An island's id is the path of slots from the root.** Its slot is its name, plus `.key` with a `:key` fn:
  `app/conversation/message.17`. Two siblings of one island need a `:key`, or the parent fails: they'd share a slot.
  The page sees an opaque digest of the path as the element id.
- **Tropical owns the root element's `id`.** Setting one fails the island. A root that isn't an element is wrapped in
  a `:div`. An element the app needs to find from a script gets an id of its own, inside.
- **A failure stays in its island.** A render that throws, an `assert` included, renders an error view in its place:
  its children unmount, the rest of the page goes on. The app map's `:error-view`, `(fn [failure] hiccup)`, replaces
  the default; `defisland`'s `{:error-view f}` replaces the app's for one island. `failure` has `:ref`, a reference
  also in the log line, `:error`, `:island`, `:path`, `:element-id`, `:args` and `:session`. A view's output reaches
  the browser: show the reference, not the message. Not React's error boundaries: a child's failure never reaches its
  parent, so a parent can't catch it.
- **A key is identity.** An island under a new key starts over: its state, holds and actions fresh. To start a dialog
  over each time it opens, key it by when it opened.
- **Hooks are keyed by name, not by call order.** Conditionals and loops around hooks are fine. A render asks for each
  key once: two `use-action :go` in one island fail; key them `[:remove id]`.
- **The gate is `if`.** A branch not rendered holds nothing, reads nothing, and exposes no actions. That is the access
  control model: decide in the render whether the user may see a subtree.

### When to make an island

An island is the unit that renders again, the unit sent to the browser, and the owner of what it holds. Make one for a
component that reads (`use-watch`, `observe`), holds state or renders actions; for a list row that changes on its own;
and for a section that is expensive to render and whose arguments rarely change. Everything presentational stays a
plain function returning hiccup, called inside islands.

- **Hooks in a plain function belong to the island that calls it.** A read there renders that whole island again.
  The same helper called twice in one island repeats its hook keys: a second `use-action` fails, a second `use-state`
  shares the first's state. A component with hooks is an island.
- **Each island costs memory per session**, about 1.3 KB, and a little time on the first render. An island per card or
  section that reads data, keyed islands for rows, plain functions for the rest. Not every button.
- **Slice so that what changes is small.** An island holding a text input must read nothing that ticks, or a render
  morphs away what the user is typing. A list sends only the rows it gains or loses when the rows are the islands' own
  root elements (`[:ul (map message msgs)]`, not `[:li (message m)]`) and nothing else in the list's markup changes
  with them: put a count in a sibling island. A streaming item reads its own content; the list reads only the ids.

## Reading state

- **`(use-watch ref)` or `(use-watch ref select)`** reads an atom, or anything watchable. The island renders again
  when the selected value changes, so `select` narrows what it reacts to. A bare `@atom` in a render isn't tracked.
- **`(use-state :k init)`** is `[value set-value!]`, island-local, kept while the island is mounted, settable from any
  thread, such as an action's.
- **`(use-session)`** is the session's `{:tab :uid :request}`. The request is the one that created the session,
  the page's, or a rebuilt tab's stream to the same URL: read its path and query, `(-> (use-session) :request :uri)`,
  rather than binding the page's path yourself. Its headers depend on which request it was.
- **A render is a plain synchronous call on the session's thread.** Blocking in it stalls that tab. Slow work goes in
  `observe`. A task started in a render belongs to the session, not the island, and isn't cancelled when the island
  unmounts: use `observe` or `use-hold` for what should live as long as the island.

### `observe`: reading from outside the page

```clojure
(observe topic                          ; args first
  (fn [emit! topic]                     ; then f, called (f emit! & args)
    (let [sub (subscribe-feed topic emit!)]
      #(unsubscribe sub))))             ; returns a fn, a Quiescent task, or nil
```

- **One resource per call site and `args`, across sessions.** Every reader of one call site with equal `args` shares
  one resource, opened by the first and closed after the last leaves and a linger passes (`resource/linger-ms`, 5 s),
  which absorbs remounts and navigation. Everything `f` depends on must be among `args`, and data that differs per user
  needs the user among them.
- **`f` can't close over the render.** A local of the render used inside `f` fails to compile. Pass it as an arg.
- **`f` runs on a virtual thread of its own, in an empty scope.** It may block while it connects. Scoped values bound
  by middleware aren't there: read them in the render and pass them, `(observe (ask *account*) query-account)`.
- **`f` returns what undoes it**: a fn, called once when the resource closes; or a Quiescent task, such as a poll
  loop's, cancelled when it closes, whose failure fails the resource; or nil.
- **`emit!` keeps only the latest value.** A reader may never see values in between, so diffs can't go through it:
  emit the state. It works from any thread, and does nothing once the resource has closed.
- **Pending, then values, and failures throw.** `observe` returns `hooks/pending` until the first `emit!`
  (`hooks/pending?`). When `f` throws or its task fails, `observe` throws in the render: `try` handles it, and
  uncaught, the island renders the error view. The next reader opens a new resource.
- **A read that depends on a read waits for it.** `pending` is a value, not a suspension as in Electric or an
  `await`: the render goes on with it. `observe` given `pending` itself returns `pending` and opens nothing, but a
  field of it is nil, so `(observe (:id user) …)` opens with nil, and `(use-shared s (:id user))` reads everyone's
  instance. Branch on `(hooks/pending? user)` and read the rest inside, rendering what has loaded at each step, or in
  a child island given `user` once it has: a read of ids renders an island per id, keyed, each reading its own item.
  Where reads wait together, `(if-loaded [user (observe …) orders (observe (:id user) …)] then else)` evaluates a
  binding once those it refers to have loaded, and starts the rest in the same render; without an else it returns
  `pending`. The first frame's wait, and `render!`'s in a test, hide the bug on a cold load, but no later frame waits.
- **Wrap it with one call site.** An app's own read is a function over `observe`, whose call site is shared by every
  caller, so its resources are keyed by `args` alone. A macro gets a call site per expansion. The same call made twice
  in one render, with equal `args`, returns one value from one resource, so several helpers may ask questions of the
  same read.

## Actions

```clojure
(let [save (use-action [:save id]
             (fn [{:strs [title]}]                 ; the client's signals, string keys
               (when (valid-title? title)
                 (save-title! uid id title))
               nil))]                             ; nil, signals to patch, or (action/navigate path)
  [:button {:data-on:click save} "Save"])
```

- **The token is the authority.** `use-action` mints an unguessable token, bound to the session's user, the first time
  the island renders it, and revokes it once the island stops. Another user's token gets a 403, a revoked one a 404.
  The access check is the island's decision to render the action; the closure carries what it decided.
- **Key an action by what it acts on.** The key is the action's identity across renders, and its handler is the
  latest render's. A positional key such as `[:pick i]` on a card paging through questions lets a click on the old
  render run the new render's handler. `[:pick question-id i]` doesn't. An action per rendered row, `[:remove id]`,
  needs no check that the id is one the user may remove: its token exists only for rows the server rendered.
- **Signals are untrusted, and their keys are strings.** Validate them. Signals whose names start with `_` stay in the
  browser and aren't sent.
- **What the handler returns:** nil (204), a map of signals for the page to patch, or `(action/navigate "/items/42")`,
  which opens that page as a page load. `navigate` takes only a path on the page's origin, so no handler becomes an
  open redirect; a page elsewhere, such as a sign-in provider's, takes `{:external true}` and an absolute URL the
  handler built. `{:signals ...}` on `navigate` carries the signals to take before the page loads (cookie and storage
  patches).
- **A value only the browser knows**, such as `evt.detail` from a web component or the id of what was clicked in a
  block of HTML rendered whole, goes in `{:value "js expression"}`, and the handler is `(handler signals value)`. It is
  untrusted: check it against what the island rendered.
- **The handler runs on the action's own request**, in the scope the route's middleware bound for it, and
  `(action/request)` returns that request. It throws anywhere else, a render included.
- **A token outlives its island's last render by a moment.** A request can land just after the island stopped
  rendering the action. Writes that matter check again where they write.
- **Work that outlives the action** answers at once and goes on in a compelled task: `(q/compel (q/task ...))`. It
  reports back through `use-state`, or through `(use-patch-signals)`'s `patch!` when the result belongs in signals,
  such as a form's fields. A patch overwrites what the user typed into a field bound to that signal.
- **An island whose page belongs elsewhere now**, such as an item that moved, renders
  `(action/navigate-element path {:replace true})`: every tab showing it follows.

## Where state lives

| Kind                                   | Use                              | Lives                                  | Server sees it                 |
|----------------------------------------|----------------------------------|----------------------------------------|--------------------------------|
| An island's own, server-side           | `use-state`                      | while the island is mounted            | yes                            |
| The page's own, browser-side           | a Datastar signal (`data-signals`) | until the page unloads               | when an action sends it        |
| The browser's, rendered in first frame | `defcookie` + `use-cookie`       | across page loads, in a cookie         | as the session starts          |
| The browser's, any size                | `defstorage` + `use-storage`     | across page loads, in `localStorage`   | when an action passes it       |
| Shared across sessions                 | `defshared` + `use-shared`       | while read, then a linger; in memory   | yes                            |
| Durable, or across servers             | a database, read with `observe`  | the database's                         | yes                            |

- **Cookie states** (`co.multiply.tropical.cookie`) are for what the first frame must render right, such as whether a
  section is open: booleans, numbers and strings, about 3.5 KB in all. `ui/shown`, `ui/aria-expanded` and `ui/toggle`
  take the handle. An action sets one with `(cookie/patch [[state item value]])`.
- **Storage states** (`co.multiply.tropical.storage`) are for drafts: JSON, kept per user, dropped by age. The server
  stores none of it and the first frame renders the default; bindings show the browser's value. An action takes it as
  its `:value` (`(ui/signal handle)`) and reads it with `storage/decode`. `storage/observe` reads it for what only the
  server can render.
- **Shared states** (`co.multiply.tropical.shared`) are keyed: a user's id makes one the user's, a document's id
  everyone's who has it open. Build the key from what the session vouches for, such as `use-session`'s `:uid`, never
  from signals. `shared/swap!` changes one from anywhere. A restart loses it, and each server holds its own.
- **The page mounts the app with `(ring/mount :body attrs)`**, whose attributes keep cookie and storage states and
  guard drops. A page that builds its own body instead carries `ui/page-attrs` there; without it, cookie and storage
  changes are never written.
- **The browser sees digests of the app's names**: island ids, state signals and script URLs. Never write one by hand
  in CSS, a script or an expression. `ui/signal` names a handle's signal in an expression and `ui/path` in a
  `data-bind`.
- **An attribute that follows an expression** from the first paint is `ui/attr`. Thread several onto one element,
  `(-> attrs (ui/attr :aria-selected ...) (ui/attr :tabindex ...))`; a `merge` loses all but one. `aria-*` states get
  the words `true` and `false`. Text that follows an expression is `[:span {:data-text expr} first-paint-text]`.
- **A value of the server's in an expression** goes in through `ui/json`: `(str "$tab = " (ui/json tab))`. An
  expression is code, and a value spliced in with `str` as it is written can become code.

## The browser side

- **Datastar 1.0 syntax:** `data-on:click`, `data-bind`, `data-text`, `data-attr:name`, `data-signals`; signals are
  `$name` in expressions. An action is the expression `use-action` returns, placed in an event attribute.
- **Islands own their DOM.** A render morphs an island's elements back to the server's, undoing what a script put
  there. Draw into a web component's shadow root, or keep the morph off an element with `data-ignore-morph`.
- **Scripts** are ES modules declared with `defscript` and taken up with `(script/use s)` in the islands that need
  them. The page loads each once, from the first frame that has it. `customElements.define` won't redefine a name,
  so a page reloads when its stream connects to a server on which a script has changed, as after a deploy.
- **Rocket components** (Datastar's `rocket(...)`) are scripts like any other, and elements whose props the island
  renders as attributes and whose events it takes with `use-action`'s `{:value "evt.detail"}`. The page loads
  `datastar-rocket.js` in place of `datastar.js`, never both, and components import `rocket` from that copy: name it
  in an import map, before the first module, with the nonce. Keep the default shadow-root mode: in `light` mode, the
  island's morph removes what the component drew, and the component undoes the island's children. A prop the
  component sets is reset by the island's next render; state it owns goes in its `$$` signals, which a patch leaves,
  but which start over when the element is moved or replaced. Rocket routes every `@action(...)` in the island's
  children through the component, which passes on those it doesn't define. Never put a server value in a `js`-codec
  prop: it becomes code. The guide: `doc/rocket.md`.
- **Uploads** (`co.multiply.tropical.upload`) send a file's bytes from the browser straight to the app's storage,
  such as presigned PUTs: `use-uploads` asks the app where each file goes and keeps the list for the island.
  `(upload/drop-area attrs start)` makes an element a drop area, the innermost under the pointer taking a drag.

## Mounting

```clojure
(def app
  {:root           (fn [{:keys [uid request]}] (page-root uid (-> request :path-params :id)))
   :page           (fn [{:keys [request]}]
                     (let [nonce (csp-nonce request)]       ; the app's CSP middleware's, or nil
                       (h/html [h/doctype-html5
                                [:html {:data-nonce nonce}
                                 [:head datastar-script (ring/scripts nonce)]
                                 ;; Holds the first frame, opens the stream, keeps the browser's state.
                                 (ring/mount :body {:class [:min-h-screen]})]])))
   :uid            current-user        ; (fn [request] uid-or-nil): authentication is the app's
   :first-frame-ms 150
   :compression    [:zstd :br :gzip]})

;; One route per page, GET and POST, the stream and the actions at the page's URL.
["/items/:id" {:handler (ring/handler app)}]
```

- **One handler per page, at the page's route.** The page, its stream and its actions are requests to the same URL,
  so they pass the same middleware. Wrap the whole app in `ring/wrap-refused-streams`, outside anything that may
  refuse a page, and in `ring/wrap-scripts`, outside the routes.
- **Many pages share one app map** with `:page` and the rest, and each route adds its root:
  `(defn page [root] (ring/handler (assoc app :root (fn [{:keys [request]}] (root request)))))`, then
  `["/items/:id" {:handler (page #(item-page (-> % :path-params :id)))}]`. Make each handler once, with the routes.
- **`ring/mount` is the page's part.** `(ring/mount :body attrs & children)` renders the element the app mounts in:
  the session's first frame, or without one a placeholder with the root's id, which the stream fills; the stream's
  `data-init`; and what keeps the browser's state. `children` go after the frame, outside the islands, `attrs` can't
  have a `data-init`, and a page mounts once. `(ring/scripts nonce)` is the head's part, the islands' script tags. Both
  throw outside the app's `:page`. A page that opens no stream throws as it is served; one without `ui/page-attrs`
  is logged.
- **`:open-when-hidden true`** in the app keeps streams open while pages are hidden, for browser automation in
  development.
- **Body parsers and filters must let actions through.** `(= :action (ring/request-kind req))` holds for exactly the
  requests the handler takes as actions. A body read before the handler gets a 500.
- **Renders see the scope of the request that created the session**, fixed for its life. An action sees its own
  request's scope.
- **An open stream isn't checked again.** Middleware that checks access runs when the stream connects. A user who
  loses access while the page is open keeps its live updates until the stream reconnects. Read access in the islands
  (`observe`), or close the user's sessions when it changes.
- **Signing out:** end the sign-in, then `(session/close-user! uid)`. The user's tabs reconnect, the middleware
  refuses them, and each page reloads into the app's answer. It closes only this JVM's sessions: on several servers,
  tell each, or have the root island read the user's standing from the database with `observe` and render
  `(action/navigate-element "/sign-in")` once it changes, which every server's sessions follow, late ones included.
- **`:first-frame-ms`** lets a cold page wait that long for its reads, so it arrives rendered instead of pending.
  100 to 200 ms. Later frames never wait.
- **The CSP**: Datastar needs `'unsafe-eval'` unless the page puts its nonce on `<html data-nonce=...>`. Leave the
  attribute off when there is no nonce; an empty one breaks Datastar.
- **Reloading code in development:** make `(session/close-all!)` the last step of the reload. Open sessions keep
  rendering with the code they started with until then.
- **Sticky sessions** behind a balancer: a tab's stream and its actions must reach the server holding its session. An
  action that reaches another server makes the page reopen its stream there, which rebuilds the tab; the page keeps
  its signals, and the click is dropped. Tell the user: `data-on:tropical:reopen__window` fires for a dropped action
  only; show a notice by a signal. A rebuild keeps the browser's state, not the first server's memory (`use-state`,
  in-memory shared state, the app's own). Stickiness with a fixed life lapses under an idle tab: make it last hours,
  or set `:stream-max-ms` under it. The second works only if each request extends the cookie's life; a balancer that
  counts it from when it first set the cookie, even setting it again on each response, lapses under a busy tab too. A
  script posting with `use-token` dispatches `tropical:reopen` on `window` when the answer has a `Tropical-Reopen`
  header.
- **A deploy that changes the page's head.** A tab rebuilt after a deploy keeps the head its page loaded: the engine,
  the stylesheet. Give the app `:revision`, `(fn [request] revision)`, naming what the head loads, such as a digest of
  its fingerprinted URLs, and a page whose stream reaches a server that names another reloads. Tropical covers its own
  code and every script itself. Name it alike on every server of a release: not a start time, nor anything random.

## Testing islands

`co.multiply.tropical.test` renders islands as a session does, on the test's thread, without a server or a browser.

```clojure
(require '[co.multiply.tropical.test :as test])

(deftest saving-a-title
  (with-open [h (test/harness #(item-page uid id) {:uid uid})]
    (is (str/includes? (test/render! h) "Untitled"))
    ((test/action h "item-page/title" [:save id]) {:title "Plans"})
    (is (str/includes? (test/render! h) "Plans"))
    (is (empty? (test/failures h)))))
```

- `render!` renders what changed and returns the whole tree's HTML. It waits for pending reads, up to `:wait-ms`
  (1000); `{:wait-ms 0}` shows the pending state.
- `action` finds an action by its island's path and its key, as a fn of the signals (and a value), or nil if the
  island doesn't render it: that is how to test a gate. Signals pass through JSON, so the handler gets string keys.
- `mounted` lists the islands' paths; `failures` the renders that threw, each the failure its view got (`:path`,
  `:error`, ...).
- Closing the harness unmounts everything: its resources linger, its tokens are revoked.
- Renders run in the caller's scope: bind with `scoping` what the route's middleware would.
- `:request` in the harness's options is the request that created the session, which `use-session` returns, and the
  one `action/request` returns in a handler.
- A source the test shouldn't reach, such as a database read through the app's own function over `observe`: redefine
  that function with `with-redefs`.

## Debugging

- `-Dco.multiply.tropical.readable-names=true` shows the app's names in the page instead of digests.
  `(island/element-id path)` maps a path to its digest.
- `(resource/summary)` lists the open resources, their readers and state.
- A failed island is logged with its path, its element id and its reference, which the default view shows the user.

## Traps

- A bare `@atom` in a render → not tracked. Use `use-watch`.
- A fresh fn or collection in an island's args or `observe`'s args → renders, or opens a resource, every time.
- A render local inside `observe`'s `f` → compile error. Pass it as an arg.
- `ask` inside `observe`'s `f` → nothing bound: it runs in an empty scope. Read it in the render, pass it.
- Slow work in a render → stalls the tab. Put it in `observe`.
- Expecting every value from `emit!` → it keeps the latest. Emit state, not diffs.
- An `id` on an island's root → the island fails.
- Two siblings of one island without `:key` → the parent fails.
- One hook key twice in a render, as two buttons with `use-action :go` → fails. Key each.
- A helper with hooks called twice in one island → repeated keys. Make it an island.
- Every small component an island → memory per session. Presentational pieces stay functions.
- A text input in an island that reads something that ticks → typing is morphed away. Split the island.
- A list's rows wrapped in elements, or a count in the list's island → every change resends the list.
- A script drawing into an island's elements → undone by the next render.
- A positional action key (`[:pick i]`) → a stale click runs the new handler. Key by the subject.
- Action signals trusted, or read with keyword keys → unvalidated input, and nil.
- Expecting the view in the action's response → it comes over the stream.
- A task started in a render → belongs to the session, not the island, and outlives it. Use `observe` or
  `use-hold`.
- A signal name, island id or script URL written by hand → breaks when names are digests. Use the helpers.
- A server value spliced into an expression with `str` → can become code. Use `(ui/json v)`.
- A placeholder id written by hand, or a binding of the app's own for the page's path → `ring/mount` places the
  root, and `(:request (use-session))` has the path.
- Access checked only in middleware → an open stream keeps updating. Check in the islands too.
- Body parsers in front of actions → 400s or 500s. Let `:action` requests through.
