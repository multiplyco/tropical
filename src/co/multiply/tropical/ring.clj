(ns co.multiply.tropical.ring
  "A Ring handler for a page: the page itself, the stream it opens and the
  actions its islands render, all at the page's URL.

  All three are requests to the page's route, so they pass through the same
  middleware: what it binds from the request, such as the account from the
  login or a workspace from the path, renders and actions see alike, and a tab
  the server no longer knows is rebuilt from the route it was on. The handler
  tells them apart by method, and by the header the page's expressions send:

  - `GET`                          the page: a new tab's session, server-side
                                   rendered from its first frame.
  - `GET`  with `Tropical-Tab`     the tab's stream, which the page opens with
                                   `mount`, or `stream-init`.
  - `POST` with `Tropical-Action`  an action `use-action` rendered, its signals,
                                   and a value if it takes one, a JSON body; or
                                   a script's request to a token of its island
                                   (`action/use-token`), as an upload's.

  `request-kind` tells them apart, for middleware that runs before routing and
  must let them through, such as one that refuses or parses request bodies.

  An action can reach a server that doesn't hold its tab's session: a
  balancer's stickiness lapsed while the tab sat open, or the server
  restarted. An action's token names its tab, so the handler tells that apart
  from a token since revoked, which gets a 404: the page reopens its stream,
  which rebuilds the tab where the action went, and the action is dropped.
  The session's `:stream-max-ms` keeps such stickiness from lapsing.

  A stream passes the route's middleware each time it connects, and a refusal,
  because the login expired or what the page shows is gone, would leave the
  tab stale: Datastar retries it, then gives up. `wrap-refused-streams` answers
  such a refusal with a reload of the page, so the page request gets the app's
  own answer, and the handler answers its own refusals of a stream that way.
  An open stream isn't asked again: what a page shows going away while it is
  open is for its islands to render.

  A tab rebuilt from its route, as after a restart or a deploy, is patched
  into the page it has, which keeps what the browser holds. What the page
  loaded outside its islands stays as it was: tropical's own code in it, the
  scripts its islands declared, and what the app's `:page` put in its head,
  such as the engine and the stylesheet. So a page has a revision, a digest of
  them, which its stream sends each time it connects, and a stream for a page
  of another revision than the server's reloads it. The head is the app's to
  name, with `:revision`.

  The app the sessions run (`co.multiply.tropical.session`) takes more keys
  here, and its `:root` also gets the `:request` that created the session:

  - `:uid`               `(fn [request] uid)`: the user the request is made on
                         behalf of, or nil. Authentication is the app's; the
                         session and its actions belong to that user. A page or
                         action without one gets a 403, a stream a reload.
  - `:page`              `(fn [{:keys [tab frame scripts request]}] html)`: the
                         page, server-side rendered from the session's first
                         `frame`. It mounts the app with `mount`, which holds
                         the frame, opens the stream and keeps the state the
                         browser owns, and puts the scripts the frame's islands
                         declared in its head with `scripts`. A page that opens
                         no stream for its tab, which could never update,
                         throws; one without `ui/page-attrs` is logged, once.
  - `:revision`          optional: `(fn [request] revision)`, a string naming
                         what the app's `:page` loads, such as a digest of
                         the URLs in its head. A page whose stream connects
                         to a server that names another reloads, and takes
                         up what changed. Without it, a page's revision is
                         tropical's part alone.
  - `:open-when-hidden`  optional: true keeps each tab's stream open while its
                         page is hidden, for browser automation in
                         development (see `stream-init`).
  - `:max-signals-bytes` optional: the largest action body read, in bytes;
                         larger gets a 413. Defaults to `max-signals-bytes`.
  - `:compression`       optional: the encodings to compress the stream with,
                         in order of preference, such as `[:zstd :br :gzip]`
                         (`co.multiply.tropical.compression`). A stream is
                         compressed with the one its request accepts, or not
                         at all. The page and the actions are left to the
                         server's own compression.

  The scripts islands declare (`co.multiply.tropical.script`) are served by
  `wrap-scripts`, outside the routes.

  A session is created in the scope of the browser's state its request carries
  (`co.multiply.tropical.cookie`), so its islands render that state in the
  first frame. The page is rendered for its user, whose entries in the
  browser's storage `ui/page-attrs` keeps (`co.multiply.tropical.storage`).

  A session renders in the scope of the request that created it, fixed for its
  life. An action's handler runs in the scope of its own request, and
  `action/request` returns it: what may differ from the session's, such as a
  cookie the page's own response set.

  The handler is sync or async. Called sync, it holds the stream request's
  thread for as long as the stream is open.

  What goes over the stream, and how the signals are read, follow Datastar's
  SDK specification, as `co.multiply.tropical.sse` describes."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [co.multiply.scoped :refer [scoping]]
    [co.multiply.tropical.action :as action]
    [co.multiply.tropical.compression :as compression]
    [co.multiply.tropical.cookie :as cookie]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.names :as names]
    [co.multiply.tropical.page :as page]
    [co.multiply.tropical.script :as script]
    [co.multiply.tropical.session :as session]
    [co.multiply.tropical.sse :as sse]
    [co.multiply.tropical.storage :as storage]
    [co.multiply.tropical.ui :as ui]
    [ring.core.protocols :as protocols]
    [s-exp.oda :as json])
  (:import
    [co.multiply.tropical.sse EventStream]
    [java.io ByteArrayOutputStream InputStream IOException OutputStream]
    [java.nio.charset StandardCharsets]))


(def max-signals-bytes
  "The default cap on an action's body, its signals and any value: 1 MiB."
  (* 1024 1024))


(defn- json?
  [req]
  (some-> (get-in req [:headers "content-type"]) (str/split #";" 2) first str/trim str/lower-case
    (= "application/json")))


(defn request-kind
  "What `req` is to tropical's handler: `:page`, `:stream`, `:action`, or nil
  for a request it refuses. The handler dispatches on it, so middleware that
  must tell tropical's requests apart before routing agrees with the handler.

  An action is a POST with a `Tropical-Action` header and a JSON body, which
  only the handler reads: a body parser that runs first leaves it nothing to
  read (see `handler`). Ring's form and multipart parsers ignore JSON."
  [req]
  (let [{:strs [tropical-tab tropical-action]} (:headers req)]
    (case (:request-method req)
      :get (if tropical-tab :stream :page)
      :post (when (and tropical-action (json? req)) :action)
      nil)))


(defn- init-js
  "`stream-init`'s expression, its stream carrying the page's `revision`, if
  known."
  [tab revision open-when-hidden]
  ;; One statement, as Datastar splits them. The page load's id is made once,
  ;; so a stream opened again is this load's: one opened as another load
  ;; would be taken for a copy of the page, and reloaded.
  (str "(() => {let load = Math.random().toString(36).slice(2), stream, open = () => {"
    "stream && stream.abort(); stream = new AbortController(); "
    "@get(location.href, {headers: {'Tropical-Tab': '" tab "', 'Tropical-Load': load"
    (when revision (str ", 'Tropical-Revision': '" revision "'")) "}, "
    "retry: 'always', requestCancellation: stream" (when open-when-hidden ", openWhenHidden: true") "})}; "
    "addEventListener('tropical:reopen', open); "
    ;; Leaving a page hides it, where Datastar closes the stream, unless it
    ;; is kept open; showing it again, as the back/forward cache restores it,
    ;; reopens it. Kept open, the stream is cancelled as the page is left
    ;; instead, and opened again, as the same page load, as it is restored.
    (when open-when-hidden
      "addEventListener('pagehide', () => stream.abort()); addEventListener('pageshow', (evt) => evt.persisted && open()); ")
    "open()})()"))


(defn stream-init
  "The Datastar expression that opens `tab`'s stream, for the page's `data-init`.

  The stream is requested from the page's own URL. It carries the tab, and an
  id for this page load, made in the browser, so the session can tell its own
  page from a copy with the same tab id, such as a duplicated tab. And it
  carries the page's revision, so that a stream that connects to a server on
  which what the page loaded has changed reloads it (see `handler`).
  `retry: 'always'` reconnects a stream the server ended too, not just one that
  failed: a closed session ends its connection so the client retries into a
  new one.

  Datastar closes the stream while the page is hidden, and reopens it when the
  page is shown, which brings it up to date. An action taken meanwhile runs, and its
  result shows once the page is shown again, within the session's grace
  (`session/grace-ms`).

  A `tropical:reopen` event on `window` reopens the stream, as the same page
  load: the answer to an action that reached a server without the tab's
  session dispatches it, so the stream follows the action there. Nothing else
  does, so an app listens for it to say that an action was dropped, as
  `data-on:tropical:reopen__window`.

  `mount` renders it into the element the app mounts in, with the app's own
  `:open-when-hidden`. For a page of its own making, `opts`:

  - `:open-when-hidden` true keeps the stream open while the page is hidden,
                        for development: browser automation can drive a page
                        its browser keeps hidden, and see each change as it
                        happens. Every hidden tab then holds its connection,
                        its session and, compressed, its encoder. A page that
                        is left still lets go of its stream, as it does
                        without the option, and one the browser restores
                        from its back/forward cache opens it again."
  ([tab]
   (stream-init tab nil))
  ([tab {:keys [open-when-hidden] :as opts}]
   (when-some [unknown (seq (remove #{:open-when-hidden} (keys opts)))]
     (throw (ex-info (str "Unknown stream-init options: " (vec unknown)) {:options opts})))
   (let [page (page/current)
         own? (= tab (:tab page))]
     (when own?
       (page/mark! :stream))
     (init-js tab (when own? (:revision page)) open-when-hidden))))


(defn- rendering
  "The page being rendered, for `helper`, which throws outside the app's
  `:page`."
  [helper]
  (or (page/current)
    (throw (IllegalStateException.
             (str "ring/" helper " renders part of the page, in the app's :page, which ring/handler renders.")))))


(defn mount
  "The element the app mounts in, such as the page's body: `tag` with `attrs`,
  holding the session's first frame, which the islands are patched into from
  then on. Its attributes open the tab's stream (`stream-init`, kept open
  while hidden if the app says `:open-when-hidden`) and keep the state the
  browser owns (`ui/page-attrs`), so the page needs nothing else of tropical's
  but its scripts (`scripts`):

      [:html
       [:head ... (ring/scripts nonce)]
       (ring/mount :body {:class [:min-h-screen]})]

  `children` go after the first frame, outside the islands, such as a
  container for dialogs. Without a first frame, because the session closed or
  took too long, it holds a placeholder with the root island's id, which the
  stream fills in.

  The stream needs the element's `data-init`, so `attrs` can't have one, nor
  the attributes `page-attrs` adds, and a page mounts once: a second element
  would open a second stream for the tab. Call it in the app's `:page`, which
  `handler` renders; anywhere else it throws."
  ([tag]
   (mount tag nil))
  ([tag attrs & children]
   (let [{:keys [tab frame placeholder open-when-hidden]} (rendering "mount")]
     (when (contains? attrs :data-init)
       (throw (IllegalArgumentException.
                (str "The element ring/mount renders opens the stream with its data-init, so it can't take one. "
                  "Put it on an element inside."))))
     (when (page/mark! :mount)
       (throw (IllegalStateException.
                "ring/mount renders the page's one mount point: a second would open a second stream for the tab.")))
     (into [tag (ui/page-attrs (assoc attrs :data-init (stream-init tab (when open-when-hidden {:open-when-hidden true}))))
            (or frame @placeholder)]
       children))))


(defn scripts
  "The script tags for the page's head: the scripts its first frame's islands
  declared (`co.multiply.tropical.script`), each carrying `nonce`, the page's
  CSP nonce, or none without one. Scripts islands declare later go over the
  stream. Call it in the app's `:page`, which `handler` renders; anywhere else
  it throws."
  [nonce]
  (script/tags (:scripts (rendering "scripts")) nonce))


(def ^:no-doc reload-guard-s
  "How long, in seconds, a page that reloaded because its stream was refused, or
  its revision was another, is no longer reloaded for the same reason, instead
  of reloading in a loop."
  10)


(def ^:private reload-event
  "A stream that reloads its page and ends, as `sse/reload!` writes it."
  (let [out (ByteArrayOutputStream.)]
    (sse/reload! (EventStream. out))
    (String. (ByteArrayOutputStream/.toByteArray out) StandardCharsets/UTF_8)))


(def ^:private reloaded-cookie "tropical-reloaded")


(defn- page-mark
  "Which page `req` is for, as a cookie value."
  [req]
  (Integer/toHexString (hash (:uri req))))


(defn- load-id
  "The page load a stream request comes from (`stream-init`), or nil."
  [req]
  (re-matches #"[0-9a-z]{1,32}" (str (get-in req [:headers "tropical-load"]))))


(defn- reloaded?
  "Whether the page `req` is for reloaded within `reload-guard-s` because its
  stream was turned away (`reload`): the cookie that set names this page, and
  another load of it. The load told to reload may itself retry before it
  unloads."
  [req]
  (when-some [[_ page load] (some->> (get-in req [:headers "cookie"])
                              (re-find (re-pattern (str "(?:^|;)\\s*" reloaded-cookie
                                                     "=([0-9a-f]+)\\.([0-9a-z]*)\\s*(?:;|$)"))))]
    (and (= page (page-mark req)) (not= load (str (load-id req))))))


(defn- set-cookies
  "The `Set-Cookie` values of the response `resp`."
  [resp]
  (into [] (mapcat (fn [[k v]] (when (= "set-cookie" (str/lower-case (str k))) (if (string? v) [v] v))))
    (:headers resp)))


(defn- reload
  "The answer to the stream request `req`: a stream that reloads the page, with
  the `Set-Cookie` values `cookies`, and a cookie naming the page and the load
  told to reload (`reloaded?`)."
  [req cookies]
  {:status  200
   :headers {"Content-Type"  "text/event-stream"
             "Cache-Control" "no-cache"
             "Set-Cookie"    (conj (vec cookies) (str reloaded-cookie "=" (page-mark req) "." (load-id req)
                                                   "; Path=/; Max-Age=" reload-guard-s "; HttpOnly; SameSite=Lax"))}
   :body    reload-event})


(defn- refused-stream
  "The answer to the stream request `req`, refused with `resp` (nil for the
  handler's own refusals): a stream that reloads the page, so the page request
  gets the app's own answer, such as a sign-in redirect or a not-found page.
  The refusal's cookies go with it.

  A page that answers while its stream is refused would reload forever. So a
  stream refused again, from a load of the page after the one told to reload,
  within `reload-guard-s`, gets a 204, which ends Datastar's retries, and the
  tab is left as it is."
  [req resp]
  (let [cookies (set-cookies resp)]
    (if (reloaded? req)
      (do (log/warn "The stream of" (:uri req) "was refused again after its page reloaded. Leaving the tab as it is.")
        (cond-> {:status 204}
          (seq cookies) (assoc :headers {"Set-Cookie" cookies})))
      (reload req cookies))))


(defn- refusal?
  "Whether `resp`, the answer to a stream request, refuses it in a way a page
  load resolves: a redirect, or a 4xx other than a timeout or a rate limit,
  which retrying resolves."
  [{:keys [status]}]
  (or (contains? #{301 302 303 307 308} status)
    (and (int? status) (<= 400 status 499) (not (contains? #{408 429} status)))))


(defn- reload-refused
  "`resp`, the answer to `req`, or a reload if it refuses a stream."
  [req resp]
  (if (and (= :stream (request-kind req)) (refusal? resp))
    (refused-stream req resp)
    resp))


(defn wrap-refused-streams
  "Middleware that answers a refused stream with a reload of its page.

  A tab's stream passes the page route's middleware each time it connects, and
  that middleware may refuse it: the login expired, or what the page shows is
  gone. Datastar retries a refused stream with backoff, then gives up, and
  follows a redirect to a page it can't use as a stream; either way the tab is
  left stale. So a stream request answered with a redirect, or a 4xx other than
  408 and 429, gets a stream that reloads the page instead, and the page
  request gets the app's own answer. A 5xx, as during a deploy, stays as it is,
  and the client retries it.

  Put it outside everything that may refuse a page's requests, before routing
  if the router's own not-found should count. A page whose stream is refused
  again within `reload-guard-s` of reloading is left as it is."
  [handler]
  (fn
    ([req]
     (reload-refused req (handler req)))
    ([req respond raise]
     (handler req #(respond (reload-refused req %)) raise))))


(defn- script-response
  "The script `req` asks for, if it is a GET under `script/path` naming one.
  Its current version is kept for good; another version of it, asked for by a
  page from before it changed, gets the current one, revalidated."
  [req]
  (when (= :get (:request-method req))
    (let [^String uri (:uri req)]
      (when (str/starts-with? uri script/path)
        (when-some [{:keys [js current?]} (script/lookup (subs uri (count script/path)))]
          {:status  200
           :headers {"Content-Type"  "text/javascript; charset=utf-8"
                     "Cache-Control" (if current? "public, max-age=31536000, immutable" "no-cache")}
           :body    js})))))


(defn wrap-scripts
  "Middleware that serves the scripts islands declare
  (`co.multiply.tropical.script`), at their URLs under `script/path`. Other
  requests pass to `handler`.

  Put it outside the routes and the session's middleware: a script is the same
  for everyone, so the browser and shared caches keep it."
  [handler]
  (fn
    ([req]
     (or (script-response req) (handler req)))
    ([req respond raise]
     (if-some [resp (script-response req)]
       (respond resp)
       (handler req respond raise)))))


(defn- with-request
  "`app`, whose sessions have the request `req` in their context: their root
  gets it, and their islands, through `use-session`."
  [app req]
  (assoc app ::session/request req))


(defmacro ^:private kept-by
  "Runs `body`, which creates a session, in the scope of the browser's state as
  `req` carries it (`cookie/*cookie*`): a session renders in the scope that
  created it."
  [req & body]
  `(scoping [cookie/*cookie* (delay (cookie/parse-cookie (get-in ~req [:headers "cookie"])))]
     ~@body))


(defn- client-code
  "What tropical puts in the page outside its islands, with fixed arguments:
  the expression that opens the stream, and the attributes that keep the state
  the browser owns, which follow the cookie and storage states declared."
  []
  (scoping [storage/*owner* "owner"]
    (str/join "\n" (concat [(init-js "tab" "revision" false) (init-js "tab" "revision" true)]
                     (map (fn [[k v]] (str k " " v)) (into (sorted-map) (ui/page-attrs)))))))


(def ^:private !client
  "`client-code`'s digest, with the cookie and storage states it was made for."
  (atom nil))


(defn- client-digest
  "`client-code`'s digest, made again only once the states it follows change."
  []
  (let [states          [(cookie/declared) (storage/declared)]
        [made-for made] @!client]
    (if (= made-for states)
      made
      (let [made (names/digest (client-code) 16)]
        (reset! !client [states made])
        made))))


(defn ^:no-doc revision-of
  "The revision of the page `req` is for, under `app`: a digest of what the page
  loads outside its islands, which a tab rebuilt in place keeps. Tropical's
  code in the page, the version of every script, and the app's `:revision`."
  [app req]
  (names/digest (str/join "\n" (concat [(client-digest)] (script/urls) [(when-some [f (:revision app)] (f req))])) 16))


(defn- page-response
  "A new tab, owned by `uid`: the page, server-side rendered from the first frame
  of its new session. A frame that took too long is nil, and the page should
  still open the stream: the tab is then unknown, and its stream resyncs a fresh
  session from scratch.

  The response is kept out of the HTTP cache, so a duplicated or restored tab
  fetches a page, and a tab id, of its own."
  [app req uid]
  (let [tab     (str (random-uuid))
        session (kept-by req (session/create! tab uid (with-request app req)))]
    (try
      (let [{:keys [frame scripts] :as page} (session/render-page! session)
            ;; Without a frame, an element with the root island's id, for the
            ;; fresh session the stream resyncs to patch it into.
            placeholder (delay
                          (when-some [root (try
                                             (kept-by req ((:root app) {:tab tab :uid uid :request req}))
                                             (catch Exception e
                                               (log/warn e "The root island's placeholder: :root threw.")
                                               nil))]
                            [:div {:id (island/element-id (island/call-slot root))}]))
            !marks      (atom #{})
            ;; Outside the page: it renders the page's attributes itself.
            revision    (revision-of app req)
            body        (scoping [storage/*owner* (storage/owner uid)
                                  page/*page* {:tab              tab
                                               :frame            frame
                                               :scripts          scripts
                                               :placeholder      placeholder
                                               :open-when-hidden (:open-when-hidden app)
                                               :revision         revision
                                               :!marks           !marks}]
                          ((:page app) {:tab tab :frame frame :scripts scripts :request req}))]
        (when (or (not page) (not (contains? @!marks :stream)))
          (session/close! session))
        (when (and page (contains? @!marks :scripts))
          (session/scripts-in-page! session))
        ;; A page that opens no stream renders fine and never updates: fail it.
        (when-not (contains? @!marks :stream)
          (throw (IllegalStateException.
                   (str "The app's :page opened no stream for its tab, so the page would never update: "
                     "render the app's element with ring/mount, or put (ring/stream-init tab) in an element's data-init."))))
        (when (and (not (contains? @!marks :state)) (compare-and-set! (::unkept app) false true))
          (log/warn (str "The app's :page has no ui/page-attrs, which ring/mount adds: the state the browser owns isn't "
                      "kept, and files dropped outside a drop area open in the tab. Logged once.")))
        {:status  200
         :headers {"Content-Type"  "text/html; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body    body})
      (catch Throwable t
        ;; No page can attach to this session. Revoke its actions and release
        ;; its resources now, including when the page or its revision throws.
        (session/close! session)
        (throw t)))))


(deftype StreamBody [session load last-id hold? encoding]
  protocols/StreamableResponseBody
  (write-body-to-stream
    [_ _ out]
    ;; The headers go out first: the client is waiting on them.
    (OutputStream/.flush out)
    (when-some [stream (if encoding
                         (try
                           (EventStream. out (compression/encoder encoding out))
                           ;; An encoder that writes as it opens, to a client
                           ;; that already left.
                           (catch IOException _
                             (OutputStream/.close out)
                             nil))
                         (EventStream. out))]
      (session/attach! session stream load last-id)
      ;; A sync server ends the response once this returns. An async one may
      ;; keep writing after it, and ends the response when the stream closes.
      (when hold?
        (EventStream/.await stream)))))


(defn- stream-response
  "Attaches the stream of the tab in the request's `Tropical-Tab` header to its
  session. A tab the server doesn't know, because its session expired or the
  server restarted, gets a fresh session, built from this request. Another
  user's tab is refused, with a reload (`refused-stream`). The stream is
  compressed with the app's encoding the request accepts, if any.

  A page of another revision than the server's, or of none, from before
  revisions, is reloaded first, with no session built: what it loaded has
  changed since. If it is of another again just after it reloaded, the
  servers disagree on its revision, and the tab is rebuilt in place."
  [app req uid hold?]
  (let [{:strs [tropical-tab tropical-load tropical-revision last-event-id accept-encoding]} (:headers req)
        stale? (not= tropical-revision (revision-of app req))]
    (if (and stale? (not (reloaded? req)))
      (do (log/debug "The page" (:uri req) "is of another revision than this server's: reloading it.")
        (reload req nil))
      (let [session  (when (parse-uuid (str tropical-tab))
                       (kept-by req (session/obtain! tropical-tab uid (with-request app req))))
            encoding (compression/choose (:compression app) accept-encoding)]
        (when stale?
          (log/warn "The page" (:uri req) "is of another revision than this server's, just after it reloaded for"
            "that: rebuilding the tab in place. Servers whose :revision differs do this."))
        (if session
          {:status  200
           :headers (cond-> {"Content-Type"  "text/event-stream"
                             "Cache-Control" "no-cache"}
                      ;; A connection-specific header, which HTTP/2 forbids.
                      (= "HTTP/1.1" (:protocol req)) (assoc "Connection" "keep-alive")
                      (seq (:compression app)) (assoc "Vary" "Accept-Encoding")
                      encoding (assoc "Content-Encoding" (name encoding)))
           ;; The last event id the client read, which says what it has (`sse/mark!`).
           :body    (->StreamBody session tropical-load (re-matches #"[0-9a-z.]{1,40}" (str last-event-id)) hold? encoding)}
          (refused-stream req nil))))))


(def ^:private too-large
  {:status 413 :body "The action's body is too large."})


(defn- read-body
  "The action request's JSON body, its Datastar signals and any value, as
  `[body nil]`; `{}` if it is empty. Or `[nil response]` refusing the request:
  413 for a body over `max` bytes, announced or read, 400 for JSON that doesn't
  parse, and 500 for a body that something read before the handler."
  [req max]
  (let [announced (some-> (get-in req [:headers "content-length"]) str/trim parse-long)]
    (if (and announced (> announced max))
      [nil too-large]
      (let [^bytes body (if-some [in (:body req)]
                          (InputStream/.readNBytes in (int (min (inc max) Integer/MAX_VALUE)))
                          (byte-array 0))]
        (cond
          (> (alength body) max)
          [nil too-large]

          (pos? (alength body))
          (try
            [(json/parse body) nil]
            (catch Exception _
              [nil {:status 400 :body "An action's body is JSON."}]))

          ;; Announced, but gone: middleware such as a body parser read it first.
          (and announced (pos? announced))
          [nil {:status 500 :body "The request body was read before tropical's handler: let actions past body parsers (`request-kind`)."}]

          :else
          [{} nil])))))


(defn- navigation-events
  "The events answering an action that navigates: the signals `signals`, if
  any, then `script`, which opens the page. Datastar handles them in order, so
  the body's handler keeps what the signals set before the page loads."
  [signals script]
  (let [out    (ByteArrayOutputStream.)
        stream (EventStream. out)]
    (when signals
      (sse/signals! stream signals))
    (sse/script! stream script)
    (sse/close! stream)
    (String. (ByteArrayOutputStream/.toByteArray out) StandardCharsets/UTF_8)))


(def ^:private reopen-event
  "Events that reopen the page's stream (`stream-init`)."
  (let [out    (ByteArrayOutputStream.)
        stream (EventStream. out)]
    (sse/script! stream "dispatchEvent(new CustomEvent('tropical:reopen'))")
    (sse/close! stream)
    (String. (ByteArrayOutputStream/.toByteArray out) StandardCharsets/UTF_8)))


(defn- elsewhere
  "The tab the action token `token` names, if this process holds no session
  for it."
  [token]
  (when-some [tab (action/token-tab token)]
    (when (and (parse-uuid tab) (not (session/held? tab)))
      tab)))


(defn- reopen
  "The answer to an action for `tab`, whose session this process doesn't hold:
  it is on another server, as when a balancer's stickiness lapsed while the tab
  sat open, or it ended here, as when the server restarted. The action can't
  run here, so its page reopens its stream, which rebuilds the tab where it
  lands, and the action is dropped. The balancer sends the stream where it
  sent the action, so the two meet again. The old stream ends with its
  request.

  Datastar's own request gets events that dispatch `tropical:reopen`. A
  script's gets a 409 with a `Tropical-Reopen` header, on which it dispatches
  the event itself (`action/use-token`)."
  [req tab]
  (log/warn "An action for tab" tab "reached a server that doesn't hold its session: its page reopens its stream."
    "A balancer's stickiness that lapses while a tab is open does this (see :stream-max-ms), as does a restart.")
  (if (get-in req [:headers "datastar-request"])
    {:status  200
     :headers {"Content-Type"  "text/event-stream; charset=utf-8"
               "Cache-Control" "no-store"}
     :body    reopen-event}
    {:status  409
     :headers {"Tropical-Reopen" "true"}
     :body    "This tab's session is on another server: reopen the page's stream."}))


(defn- act-response
  "Invokes the action whose token is in the request's `Tropical-Action` header,
  on behalf of `uid`, with the request's body, and `action/*request*` bound to
  the request. Answers 204; the handler's map as JSON, signals Datastar
  patches or a script's answer; or, for `action/navigate`, events: its
  signals, then a script opening the page, which Datastar runs, with the
  page's nonce in its CSP mode. 403 for another user's action, and 404 for one
  never minted or since revoked. One whose tab's session isn't here is
  answered by reopening the page's stream (`reopen`). View updates arrive over
  the stream."
  [app req uid]
  (let [[body refused] (read-body req (:max-signals-bytes app max-signals-bytes))]
    (or refused
      (let [[status result] (scoping [action/*request* req]
                              (action/invoke! (get-in req [:headers "tropical-action"]) uid body))]
        (case status
          :ok (if-some [script (action/navigation result)]
                {:status  200
                 :headers {"Content-Type"  "text/event-stream; charset=utf-8"
                           "Cache-Control" "no-store"}
                 :body    (navigation-events (action/navigation-signals result) script)}
                (if (map? result)
                  {:status  200
                   :headers {"Content-Type" "application/json"}
                   :body    (json/write-str result)}
                  {:status 204}))
          :forbidden {:status 403 :body "Not your action."}
          :gone (let [token (get-in req [:headers "tropical-action"])]
                  (if-some [tab (elsewhere token)]
                    (reopen req tab)
                    {:status 404 :body "No such action: never minted, or its island has unmounted."})))))))


(defn- refusal
  "The answer to a request that is none of the handler's (`request-kind` nil)."
  [req]
  (cond
    (not (#{:get :post} (:request-method req)))
    {:status 405 :headers {"Allow" "GET, POST"} :body "Method not allowed."}

    (get-in req [:headers "tropical-action"])
    {:status 415 :body "An action's body is JSON."}

    :else
    {:status 400 :body "A POST to a page is an action, with a Tropical-Action header."}))


(defn- response
  [app req hold?]
  (if-some [kind (request-kind req)]
    (if-some [uid ((:uid app) req)]
      (case kind
        :page (page-response app req uid)
        :stream (stream-response app req uid hold?)
        :action (act-response app req uid))
      (if (= :stream kind)
        (refused-stream req nil)
        {:status 403 :body "Not signed in."}))
    (refusal req)))


(defn handler
  "A Ring handler, sync or async, for a page's route, serving the page, its
  stream and its actions for `app` (see the namespace docstring). Mount it for
  GET and POST. It reads an action's body, its signals and any value, itself, and
  refuses a body read before it, with a 500: middleware that parses or refuses
  bodies, on the route or before routing, lets actions past
  (`(= :action (request-kind req))`).

  Called sync, it holds the stream request's thread for as long as the stream
  is open: run sync handlers on virtual threads.

  Throws for an encoding in `:compression` it can't load, for a
  `:stream-max-ms` that isn't a positive number of ms, and for a `:revision`
  that isn't a fn."
  [app]
  (some-> (:compression app) compression/check!)
  (when-not (or (nil? (:stream-max-ms app)) (pos-int? (:stream-max-ms app)))
    (throw (IllegalArgumentException.
             (str ":stream-max-ms is a positive number of ms, not " (pr-str (:stream-max-ms app)) "."))))
  (when-not (or (nil? (:revision app)) (fn? (:revision app)) (var? (:revision app)))
    (throw (IllegalArgumentException.
             (str ":revision is a fn of the request, as (fn [request] revision), not " (pr-str (:revision app)) "."))))
  ;; Whether a page without `ui/page-attrs` has been logged yet.
  (let [app (assoc app ::unkept (atom false))]
    (fn
      ([req]
       (response app req true))
      ([req respond raise]
       (when-some [resp (try
                          (response app req false)
                          (catch Throwable t
                            (raise t)
                            nil))]
         (respond resp))))))
