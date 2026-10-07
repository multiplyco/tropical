# Serving pages

A page is one Ring handler, `ring/handler`, given the app: a map of what the sessions render and how the page around
them looks.

```clojure
(def app
  {;; The session's root island, from the request that created it.
   :root           (fn [{:keys [uid request]}] (project-page uid (-> request :path-params :id)))
   ;; The page, rendered from the session's first frame.
   :page           (fn [{:keys [request]}]
                     (let [nonce (csp-nonce request)]
                       (h/html
                         [h/doctype-html5
                          [:html {:data-nonce nonce}
                           [:head
                            [:script {:type "module" :nonce nonce :src datastar-url}]
                            (ring/scripts nonce)]
                           (ring/mount :body {:class [:min-h-screen]})]])))
   ;; Your authentication: the user a request is made on behalf of, or nil.
   :uid            current-user
   :error-view     (fn [id e] [:div.error "This part of the page failed to load."])
   :first-frame-ms 150
   :compression    [:zstd :br :gzip]})


(def routes
  [["/projects/:id" {:handler (ring/handler app)}]])


;; Innermost first: the router, then what may refuse a page, then tropical's.
(def handler
  (-> (router routes)
    wrap-login
    wrap-session
    ring/wrap-refused-streams
    ring/wrap-scripts))
```

The keys, with the details in the docstrings of `co.multiply.tropical.ring` and `co.multiply.tropical.session`:

| Key                  | What it is                                                                                     |
|----------------------|------------------------------------------------------------------------------------------------|
| `:root`              | `(fn [{:keys [tab uid request]}] call)`: the session's root island                             |
| `:page`              | `(fn [{:keys [tab frame scripts request]}] html)`: the page, from the session's first frame   |
| `:open-when-hidden`  | true keeps each tab's stream open while its page is hidden, for browser automation; optional  |
| `:uid`               | `(fn [request] uid)`: the user, or nil                                                         |
| `:error-view`        | `(fn [id e] hiccup)`: what renders in place of an island that throws; optional                 |
| `:first-frame-ms`    | how long a session's first frame may wait for reads still pending; 0 by default               |
| `:compression`       | the encodings to compress the stream with, in order of preference; none by default             |
| `:max-signals-bytes` | the largest action body read; 1 MiB by default                                                 |

## Mounting it

- **One handler per page, at the page's route.** `ring/handler` serves the page (a GET), the stream the page opens (a
  GET with a `Tropical-Tab` header) and the actions its islands render (POSTs with a `Tropical-Action` header), all at
  the page's own URL. Mount it for GET and POST. The app's `:root` gets the `:request` that created the session, so a
  root can come from the route: `(fn [{:keys [request]}] (project (-> request :path-params :id)))`.
- **The route's middleware covers all three.** Whatever it binds, such as the account from the login or a workspace
  from the path, the page, its stream and its actions see alike. A session renders in the
  [Scoped](https://github.com/multiplyco/scoped) scope of the request that created it, fixed for its life: the page
  load, or the stream request that resyncs a tab the server no longer knows, as after a restart. That request is to the
  page's URL too, so the tab is rebuilt as the page it was. An action runs in its own request's scope, and
  `(action/request)` returns that request.
- **Authentication is the app's.** `:uid` returns the user a request is made on behalf of, and a page or action without
  one gets a 403, a stream a reload, as a refused one does. Sign-in happens before the handler, or `:uid` returns an
  anonymous id the app issues.
- **A refused stream reloads its page.** A tab's stream passes the route's middleware each time it connects, and the
  middleware may refuse it: the login expired, or what the page shows is gone. Datastar would retry, then give up, and
  the tab would stay stale. Wrap the app in `ring/wrap-refused-streams`, outside everything that may refuse a page, and
  a stream answered with a redirect or a 4xx (other than 408 and 429) gets a stream that reloads the page instead, so
  the page request gets the app's own answer: the sign-in redirect, the not-found page. A 5xx, as during a deploy, is
  retried. If the page answers but its stream is refused again, the tab is left as it is rather than reloaded in a
  loop. An open stream isn't asked again, so a page's subject going away while it is open is for its islands to show.
- **Sync or async Ring.** Called sync, the handler holds a stream request's thread for as long as the stream is open,
  as any streaming response on a blocking server does. Run sync handlers on virtual threads.
- **Body parsers and filters let actions past.** The handler reads an action's Datastar signals from the request body
  itself. Middleware that parses or refuses bodies, on the route or before routing, such as Muuntaja's, lets actions
  through: `(= :action (ring/request-kind req))` holds for exactly the requests the handler takes as actions, POSTs with
  a `Tropical-Action` header and a JSON body, which Ring's form and multipart parsers leave alone. A body read before
  the handler gets a 500. The handler reads at most `:max-signals-bytes` (1 MiB by default), and answers 413 to more.
- **Scripts are served outside the routes.** `ring/wrap-scripts` serves the scripts islands declare, under
  `/tropical/`: wrap the app in it outside the routes and the session middleware ([Scripts the page needs](scripts.md)).

## Many pages

An app of many pages shares one map, and gives each route its own root:

```clojure
(def ^:private app
  "What every page shares; each route adds its root."
  {:uid            current-user
   :error-view     error-view
   :first-frame-ms 150
   :compression    [:zstd :br :gzip]
   :page           (fn [{:keys [request]}] (document request))})


(defn page
  "The handler for a page whose root island is `(root request)`."
  [root]
  (ring/handler (assoc app :root (fn [{:keys [request]}] (root request)))))


(def routes
  [["/projects/:id" {:handler (page #(project-page (-> % :path-params :id)))}]
   ["/settings" {:handler (page (fn [_] (settings-page)))}]])
```

- **The document is written once.** `:page` renders what every page shares: the head, with Datastar, the CSP nonce
  and the scripts, and the element the app mounts in (`ring/mount`). What differs from page to page is the root.
- **What a page needs from its request reaches its root.** The root gets the request that created the session, so it
  reads the route's parameters, and passes on what its islands need as arguments.
- **What every island may need, the session's context has.** `(use-session)` is `{:tab :uid :request}`, the request
  being the one that created the session, so an island deep in the page reads the page's path, `(-> (use-session)
  :request :uri)`, without a binding of the app's own. For a tab rebuilt after a restart, the request is its stream's,
  to the same URL: the path and query are the page's either way, and the headers are the request's.
- **What a route's middleware binds, every request to the page sees.** The account, or a workspace from the path,
  bound with [Scoped](https://github.com/multiplyco/scoped) in the route's middleware, is in the scope of the session's
  renders and of each action, as the page, its stream and its actions all pass it.
- **Each handler is made once,** as the routes are defined: `ring/handler` checks the app as it is called, such as
  whether it can load its encodings.

## The page

- **The app mounts in an element of the page's.** `(ring/mount :body attrs & children)` renders the element, such as
  the body, with the app's attributes, holding the session's first frame. Its attributes open the tab's stream, kept
  open while the page is hidden if the app says `:open-when-hidden`, keep the state the browser owns
  ([State the browser owns](browser-state.md)), and refuse files dropped outside a drop area. `children` go after the
  first frame, outside the islands, such as a container for dialogs. The element's `data-init` opens the stream, so
  `attrs` can't have one, and a page mounts once.
- **The head takes the islands' scripts.** `(ring/scripts nonce)` gives the script tags of the scripts the first
  frame's islands declared, with the page's CSP nonce ([Scripts the page needs](scripts.md)). With `mount` and
  `scripts`, `:page` needs nothing from its argument but the request, for the nonce and the app's own head.
- **The first frame is the page's content.** Only the islands are patched from then on: what the page renders around
  them is fixed until the page loads again. Without a first frame, because the session closed or took longer than
  5 s, the element holds a placeholder with the root island's id, and the stream, which opens a fresh session,
  patches the root into it.
- **A page that can't update fails.** A page that opens no stream for its tab renders fine and never changes, so the
  handler throws instead, and its session closes. One without `ui/page-attrs` works, but doesn't keep the state the
  browser owns, and lets a file dropped outside a drop area open in the tab: it is logged, once per handler.
- **A page of its own making** puts together what `mount` does: `(ring/stream-init tab)`, the expression opening the
  stream, in the element's `data-init`, with `{:open-when-hidden true}` if wanted; `ui/page-attrs` on it, given its
  other attributes; and the page's `frame`, or without one, an element with the root's id, `(island/element-id "app")`
  for a root named `app`. Its head takes `(script/tags scripts nonce)`.
- **Content Security Policy.** Datastar compiles its expressions with `Function()`, which needs `'unsafe-eval'`, unless
  the page opts into its CSP mode (Datastar 1.0.3 and later): put the page's nonce on the root element,
  `[:html {:data-nonce nonce}]`, and Datastar compiles each expression into a script carrying it. The nonce holds for
  the page's life, so expressions patched in over the stream compile the same way. Without a nonce, leave the
  attribute off: an empty one stops Datastar.
- **A cold first frame waits briefly.** A session's first frame, the page or the root a rebuilt tab resyncs from, often
  reads what nothing holds yet: a new tab, a first visit, every open tab after a restart. With `:first-frame-ms`, it
  waits until no read of its islands is pending, or that many ms pass, and renders as reads land meanwhile, so a page
  whose reads land in 20 ms arrives with them, and one whose read takes seconds arrives pending at the budget. Later
  frames never wait. 100 to 200 ms covers the reads worth waiting for; the default, 0, sends the first frame at once.

## Failures

An island whose render throws renders the app's `:error-view`, `(fn [id e] hiccup)`, in its place. The default says
only that the island failed: what it renders reaches the client, and an exception's message can carry what the user
shouldn't see. Failures and lifecycle events are logged through `clojure.tools.logging`, with the exception. A
failure in a session's own loop, outside the islands' renders, closes the session and its stream, so the client
reconnects into a new one.

## Names in the page

An island's element id, the signal of a cookie or storage state, and a script's URL are opaque digests of the app's
names for them (`co.multiply.tropical.names`), so the page tells nothing of how the app is laid out: its namespaces,
its functions, its states. The server keeps the names as written, in its logs, its REPL and its errors; a failed
island's log line gives its element id too. A digest is deterministic, so every server gives a name the same one, and
what the browser keeps under a state's name is found after a deploy.

For debugging, the JVM property `co.multiply.tropical.readable-names=true` has the page show the names as written, and
`(island/element-id path)` gives an island's digest from the REPL. Never name them by hand in CSS, a script or an
expression: that holds only while they are readable. The helpers name them (`ui/signal`, `ui/path`), and an element
the app needs to find gets an id of its own.

## Compressing the stream

With `:compression [:zstd :br :gzip]` in the app, each tab's stream is compressed with the first of those its request
accepts, or not at all. One encoder lasts the stream's life, so a morph repeating what an earlier frame sent goes as a
reference to it, and each frame is flushed through it at once, so the client decodes the frame as it arrives. A
server's own compression can't do that for a response that never ends: Jetty's zstd and brotli hold what they are given
until the response ends. The page and the actions are left to the server's compression.

- **The encoders.** gzip is the JDK's. zstd and brotli are tropical's: it ships libzstd and brotli's encoder,
  libbrotlienc, for macOS, and for Linux on x86-64 and aarch64, glibc or musl, and calls them through the JDK's foreign
  function API, so they can't conflict with a zstd-jni or brotli4j the app's dependencies bring. Elsewhere, the system
  properties `co.multiply.tropical.zstd.library` and `co.multiply.tropical.brotli.library` name a libzstd, 1.4 or
  later, and a libbrotlienc, 1.0 or later. An encoding the handler can't load fails its creation.
- **Native access.** On JDK 24 and later, `--enable-native-access=ALL-UNNAMED` keeps the JDK from warning as they call
  native code.
- **Memory.** The settings are tropical's: each open stream holds its encoder, about 0.7 MB with zstd, 0.9 MB with
  brotli and 0.25 MB with gzip, whose shorter window can't refer back across a large island.
- **BREACH.** A stream's size can leak a secret it carries: see [the design notes](design.md).

## Running it

- **A tab's requests go to one server.** A session lives in the memory of the server that created it, and a tab's
  stream and its actions must reach that server: behind a load balancer, keep a tab on one server, as sticky sessions
  do. An action that reaches another server gets a 404, and changes nothing. A stream that reaches another server, as
  after a deploy, rebuilds the tab there from its route.
- **Heartbeats.** A dead connection is only noticed on write, so a quiet stream writes a heartbeat every 10 s
  (`session/heartbeat-ms`): an SSE comment, which the client ignores, and which keeps the idle timeouts of proxies
  along the way from closing it.
- **Hidden tabs.** Datastar closes a hidden tab's stream and opens it again when the tab is shown. Its session keeps
  its islands, and what they hold, for 15 s (`session/grace-ms`), then lets go of them; a tab shown after that gets a
  fresh session. `:open-when-hidden true` in the app keeps the stream open while hidden, for browser automation in
  development.
- **Memory per tab.** A session keeps its islands' last output, about 1.3 KB per island, and its stream's encoder, if
  compressed.

## Reloading code in development

An open session goes on rendering with the code it started with, since a reload such as clj-reload's or
tools.namespace's makes new vars. Make `session/close-all!` the last step of the app's reload, once the new code serves
requests. Each tab reconnects about a second later into a fresh session, whose first frame updates the page in place,
leaving its signals, scroll and form input as they are. A tab that reconnects before the new code serves gets a session
of the old code. What only a page load renders, the page's head and edited scripts, shows once the page is reloaded.
