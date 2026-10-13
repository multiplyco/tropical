(ns co.multiply.tropical.action
  "Actions as capabilities: server-side closures reachable from the client only
  while the island rendering them is mounted.

  `use-action` mints an unguessable token bound to the session's user the first
  time an island asks for it, and revokes it once the island stops asking or
  unmounts. So a POST can only reach closures currently rendered for that user,
  which is the property Electric gets from `(e/server (when allowed (e/client ...)))`.
  The authority part of the check happened when the island decided to render
  the action. The arguments (Datastar signals, and a value if the action takes
  one) are untrusted input, as `e/client` -> `e/server` values always were."
  (:require
    [clojure.string :as str]
    [co.multiply.scoped :refer [ask]]
    [co.multiply.tropical.island :as island]
    [s-exp.oda :as json]))


;; token -> {:uid, :!handler}, the handler a fn of the action request's body
(defonce ^{:doc "The action tokens minted and not yet revoked, for counting and
  watching: an atom of a map keyed by token."}
  !actions
  (atom {}))


(defn mint
  "A token bound to `uid`, minted by the session of `tab`, as
  `{:value {:token :!handler}, :release f}` for a hold: `release` revokes it.
  Its handler is `!handler`'s, a fn of the request's JSON body.

  The token names its tab, before a random part (`token-tab`), so a server
  that doesn't know a token can tell whether it holds the tab's session. They
  are joined by a `_`, which neither has, and not a `.`, since a token is also
  a key in a signal's path."
  {:no-doc true}
  [uid tab]
  (let [token    (str tab "_" (random-uuid))
        !handler (atom nil)]
    (swap! !actions assoc token {:uid uid :!handler !handler})
    {:value   {:token token :!handler !handler}
     :release #(swap! !actions dissoc token)}))


(defn use-token
  "The token of an action held under `k`, unique within the island, whose
  `handler` is a fn of the action request's body, its JSON. Minted bound to
  the session's user when the island first asks for `k`, revoked once it
  stops, and its handler replaced by each render's, as `use-action`'s.

  The primitive under `use-action`, for a script that posts to the page's URL
  itself, with the token in a `Tropical-Action` header, and reads the answer:
  a map the handler returns, as JSON. `co.multiply.tropical.upload` is one.

  An answer with a `Tropical-Reopen` header, a 409, says the request reached a
  server that doesn't hold the tab's session (see `co.multiply.tropical.ring`):
  the script dispatches a `tropical:reopen` event on `window`, which reopens
  the page's stream, and the request is dropped."
  [k handler]
  (let [{:keys [uid tab]} (island/use-session)
        {:keys [token !handler]} (island/use-hold k #(mint uid tab))]
    (reset! !handler handler)
    token))


(defn ^:no-doc token-tab
  "The tab whose session minted `token`, or nil if it names none."
  [token]
  (let [i (str/last-index-of (str token) "_")]
    (when (and i (pos? (long i)))
      (subs token 0 i))))


;; Bound by `co.multiply.tropical.ring` while an action's handler runs. Without
;; a root, so that `ask` throws, or gets its default, where nothing binds it.
#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^{:dynamic true :doc "The Ring request of the action being invoked, unbound elsewhere: see `request`."} *request*)


(defn request
  "The Ring request of the action being invoked, for its handler and what the
  handler calls, a `use-token` handler's included. It is the action's own,
  not the session's, so what the page's response set, such as a cookie, is
  on it. Its body has been read: the handler has the signals.

  Throws anywhere else, such as in a render, whose request is the one that
  created its session: the root's `:request`. Code that runs both in an
  action and outside reads `(ask *request* nil)`, nil outside."
  []
  (ask *request* (throw (IllegalStateException.
                          (str "action/request is the request of an action being invoked, in its handler. "
                            "A render has the :request that created its session.")))))


(def ^:private signals-js
  "The signals `@post` sends by itself: all but Datastar's local ones, those
  under a key starting with `_`, read without the expression depending on them."
  "@peek(() => JSON.parse(JSON.stringify($, (k, v) => k[0] === '_' ? undefined : v)))")


(defn use-action
  "A Datastar `@post(...)` expression that invokes `(handler signals)` on behalf
  of the session's user. `k` names the action within the island; the token
  stays the same across renders, and `handler` is replaced by each render's.

  `handler` returns a map of signals to patch into the client, nil, or
  `(navigate path)` to open another page once it is done. It runs on the
  action's own request, in the scope the route's middleware binds for it, and
  `request` returns that request.

  With `{:value js}`, the action also takes a value only the browser knows when
  it is invoked, and the handler is called `(handler signals value)`. `js` is a
  JavaScript expression, evaluated in the expression's scope at that moment,
  such as `evt.detail`, or an attribute of the element clicked in a block of
  HTML the island doesn't render element by element. The value arrives as JSON
  decodes it, and is untrusted, as the signals are: a value naming one of
  several things must be checked against those the island rendered. It travels
  in this action's body with the signals, so no other action posts it, and no
  signal holds it.

  It posts to the page's own URL, with the token in a `Tropical-Action` header
  (see `co.multiply.tropical.ring`). Every action on the page shares that URL,
  and Datastar cancels a request in flight when another goes to the same one,
  so its cancellation is off: each click gets its response."
  ([k handler]
   (use-action k handler nil))
  ([k handler {:keys [value]}]
   (let [token (use-token [::action k]
                 (if value
                   (fn [body] (handler (get body "signals" {}) (get body "value")))
                   handler))]
     ;; Datastar's `payload` replaces the signals in the body, so the
     ;; expression puts back the ones `@post` would have sent.
     (str "@post(location.href, {headers: {'Tropical-Action': '" token "'}, requestCancellation: 'disabled'"
       (when value
         (str ", payload: {signals: " signals-js ", value: (" value ")}"))
       "})"))))


(deftype Navigation [script signals])


(defn- navigation-script
  "The script that opens `path` as a page load, checked as `navigate` describes;
  `f` names the fn checking it, for the message."
  [f path {:keys [replace external]}]
  (when-not (and (string? path)
              (re-matches (if external #"(?i)https?://[^\x00-\x20\x7f]+" #"/(?![/\\])[^\x00-\x20\x7f]*") path))
    (throw (IllegalArgumentException.
             (str f (if external
                      " with :external takes an absolute http or https URL, such as \"https://example.com/authorize\", not "
                      " takes a path on the page's origin, such as \"/items/42\", not ")
               (pr-str path) "."))))
  (str "location." (if replace "replace" "assign") "(" (json/write-str path) ")"))


(defn navigate
  "What an action's handler returns to open `path` in the page's tab once the
  action is done, as a page load: `(navigate (str \"/items/\" id))`. It is
  pushed onto the tab's history, or replaces the current entry with
  `{:replace true}`, as when the page's own item moved.

  With `{:signals m}`, the page first takes the signals `m`, the map a handler
  that doesn't navigate returns, and only then opens `path`. So what the
  page's body keeps from them (`ui/page-attrs`) is kept before the page it
  opens loads: cookie states `cookie/patch` sets, such as a new item's,
  chosen before the item existed, and storage entries `storage/patch` sets
  and `storage/forget` drops, such as the draft the action used up.

      (navigate (str \"/projects/\" id)
        {:signals (merge (cookie/patch [[picked id group]])
                         (storage/forget [[edits draft-id]]))})

  `path` is on the page's own origin: it starts with a single `/` and holds no
  whitespace or control characters (percent-encode them). Browsers drop tabs
  and newlines from a URL before parsing it, so `/<tab>/host` would open
  `//host`, another origin. Any other string throws, since an action that
  opened whatever URL it was handed would be an open redirect.

  With `{:external true}`, `path` is an absolute `http` or `https` URL on any
  origin instead, such as a sign-in provider's:

      (navigate authorize-url {:external true})

  The option is the handler's word that it built the URL, or checked it
  against those it means to open: never pass on one the client sent. Another
  scheme throws, `javascript:` among them, as does whitespace. So does an
  option other than these."
  ([path]
   (navigate path nil))
  ([path {:keys [signals] :as opts}]
   (when-some [other (seq (remove #{:replace :external :signals} (keys opts)))]
     (throw (IllegalArgumentException.
              (str "navigate takes :replace, :external and :signals, not " (str/join ", " other) "."))))
   (when-not (or (nil? signals) (map? signals))
     (throw (IllegalArgumentException. (str "navigate's :signals is a map of signals, not " (pr-str signals) "."))))
   (Navigation. (navigation-script "navigate" path opts) (not-empty signals))))


(defn navigate-element
  "An element that opens `path` in the page's tab, as a page load, once it is
  in the page: for an island whose page belongs elsewhere now, such as one
  showing an item that moved, in every tab showing it.

      (when (not= (:path item) page-path)
        (navigate-element (:path item) {:replace true}))

  `path`, `:replace` and `:external` are `navigate`'s. An item that moved wants
  `{:replace true}`, so Back doesn't return to where it was.

  It runs as it arrives, in the page's first frame or patched in, and the
  same element in a later render doesn't run it again. In a page already at
  `path`, fragment aside, it does nothing: the browser compares the two as it
  resolves them, so a path the island compared in another encoding, as `/ö`
  is `/%C3%B6`, doesn't reload the page in a loop. Compare canonical paths all
  the same.

  For a page loaded at the old path, the route's own redirect is better: one
  load, nothing of the old page shown."
  ([path]
   (navigate-element path nil))
  ([path opts]
   (let [script (navigation-script "navigate-element" path opts)
         target (str "new URL(" (json/write-str path) ", location.href).href.split('#')[0]")]
     [:div {:hidden    true
            :data-init (str target " === location.href.split('#')[0] || " script)}])))


(defn navigation
  "The script that opens the page an action's handler `result` navigates to,
  or nil if it doesn't navigate."
  [result]
  (when (instance? Navigation result)
    (.-script ^Navigation result)))


(defn navigation-signals
  "The signals the page takes before it opens the page an action's handler
  `result` navigates to, or nil."
  {:no-doc true}
  [result]
  (when (instance? Navigation result)
    (.-signals ^Navigation result)))


(defn ^:no-doc invoke!
  "Runs the action behind `token` on behalf of `uid` with `body`, its request's
  JSON: the signals, or for an action taking a value, the signals and the value.
  Returns `[:ok result]`, `[:forbidden]` (another user's token) or `[:gone]`
  (never minted, or revoked because its island no longer renders it)."
  [token uid body]
  (if-let [{owner :uid !handler :!handler} (get @!actions token)]
    (if (= owner uid)
      [:ok (@!handler body)]
      [:forbidden])
    [:gone]))


(comment
  (count @!actions))
