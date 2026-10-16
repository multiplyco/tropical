(ns co.multiply.tropical.cookie
  "State the browser owns and the server renders, kept in a cookie: whether a
  section is open, a sidebar folded, a pane's width. It changes in the browser
  without a round trip, survives page loads, is right in the first frame, and
  neither a later patch nor an island mounting again undoes what the user did.

  `defcookie` declares a state, with its default and what a value must be. The
  var is its identity, so it lives wherever the code that uses it does: next to
  one island, or in a namespace several share. Any island anywhere in the page
  takes it up with `use-cookie`, which returns a handle for `value` and for the
  helpers in `co.multiply.tropical.ui`:

      (defcookie group-open
        \"Whether a sidebar group is open, per group.\"
        {:default true :valid? boolean?})

      (defisland sidebar-group
        {:key :id}
        [group]
        (let [open (use-cookie group-open (:id group))]
          [:section
           [:button (ui/aria-expanded {:data-on:click (ui/toggle open)} open) (:name group)]
           [:ul (ui/shown open) ...]]))

  How it holds:

  - The browser keeps each state in a Datastar signal under `_cookie`. An
    island that uses a state declares its signal on its root, only if missing
    (`island/declare-signal`), so the first island in the page to use it
    declares it, and any later declaration, a remount or a patch carrying the
    server's stale copy changes nothing. Islands using one state follow one
    signal.
  - The element the app mounts in carries `ui/page-attrs` (`ring/mount` adds
    it): a handler that writes each change of a `_cookie` signal into the
    cookie, merged with what the cookie holds, so tabs don't undo one
    another's changes. It keeps only values other than
    their state's default, drops states no longer declared, and drops the
    oldest values past `max-cookie-chars`.
  - The server reads the cookie of the request that created the session
    (`co.multiply.tropical.ring` binds it), so its value is the state as the
    session began. The cookie is the client's to write: a value that isn't
    valid for its state reads as the default.
  - An island that renders from the page's value, such as one placing a
    panel's content only while it is open, reads it with `observe`: the
    session's in the first frame, then the page's, as it changes.

  The server sets a state from an action, as it would any signal: its handler
  returns `patch`'s signals, which the islands using the state follow and the
  body's handler keeps. For the page an action opens, such as a new item's
  with a choice made before the item existed, it passes them to
  `action/navigate`'s `:signals`: the cookie has them before the page loads.

  A state's values are booleans, numbers or strings, since every request
  carries the cookie. Text, or a value of any size, that the server needn't
  render in the first frame is `co.multiply.tropical.storage`'s. A state is
  used either with an item, as one per item, or without, never both."
  (:require
    [co.multiply.scoped :refer [ask]]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.names :as names]
    [co.multiply.tropical.internal.browser-code :as browser-code]
    [co.multiply.tropical.internal.browser-state :as browser-state]
    [s-exp.oda :as json])
  (:import
    [java.net URLDecoder]
    [java.nio.charset StandardCharsets]))


(def cookie-name
  "The cookie the browser keeps its state in."
  "tropical-ui")


(def max-cookie-chars
  "The most the cookie holds, encoded. A browser keeps about 4 KB per cookie,
  and every request carries it."
  3500)


;; Declaring --------------------------------------------------------------------


;; Signal name -> State, for every state declared.
(defonce ^:private !states (atom {}))


(defn declared
  "Every cookie state declared, by signal name: the same map until a state is
  declared, or declared again."
  {:no-doc true}
  []
  @!states)


(defrecord State [id signal default valid? shape])


(defn signal-name
  "The name of the signal of the state `id`, a qualified symbol: an opaque
  digest of it, unless names are readable (`co.multiply.tropical.names`)."
  {:no-doc true}
  [id]
  (browser-state/signal-name id))


(defn state*
  "What `defcookie` expands to: the state `id`, a qualified symbol."
  {:no-doc true}
  [id {:keys [default valid?] :or {valid? some?}}]
  (when-not (or (boolean? default) (number? default) (string? default))
    (throw (IllegalArgumentException. (str "Cookie state " id ": its default is a boolean, a number or a string."))))
  (when-not (valid? default)
    (throw (IllegalArgumentException. (str "Cookie state " id ": its default " (pr-str default) " isn't valid."))))
  (let [state (->State id (signal-name id) default valid? (atom nil))]
    (names/register! !states (:signal state) state "Cookie state" "signal")))


(defmacro defcookie
  "Defines a state kept in the cookie: `(defcookie name doc? {:default v, :valid? pred})`.
  `:default` is a boolean, a number or a string, what the state is until the
  user changes it. `:valid?` decides what the cookie may hold for it, and
  defaults to `some?`."
  [state-name & decl]
  (let [[doc decl] (if (string? (first decl)) [(first decl) (next decl)] [nil decl])
        state-name (cond-> state-name doc (vary-meta assoc :doc doc))]
    `(def ~state-name
       (state* '~(symbol (str (ns-name *ns*)) (str state-name)) ~(first decl)))))


;; Reading ----------------------------------------------------------------------


;; A delay of what the cookie held (`parse-cookie`), as the request that created
;; the session carried it. Bound by `co.multiply.tropical.ring`. Without a root,
;; so that `ask` gets its default where nothing binds it.
#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:dynamic ^:no-doc *cookie*)


(defn ^:no-doc parse-cookie
  "The state in the cookie of the `Cookie` header `header`: a map from
  `\"<signal>\"`, or `\"<signal>/<item>\"`, to its value. Nil if there is
  none, or it isn't a JSON object."
  [header]
  (when-some [[_ v] (some->> header (re-find (re-pattern (str "(?:^|;)\\s*" cookie-name "=([^;]*)"))))]
    (try
      (let [m (json/parse (URLDecoder/decode ^String v StandardCharsets/UTF_8))]
        (when (map? m) m))
      (catch Exception _ nil))))


(defn item-name
  "`item` as the name of a signal: a keyword's name with its namespace, or the
  string of anything else. Throws for one holding a `.`, which Datastar reads
  as a step into the signal."
  {:no-doc true}
  [item]
  (browser-state/item-name item))


(defn- valid
  "`v`, a value from the browser, if it is a valid value of `state`; its
  default if not."
  [{:keys [default valid?]} v]
  (if (and (or (boolean? v) (number? v) (string? v)) (try (valid? v) (catch Exception _ false)))
    v
    default))


(defn- kept
  "The value of `state`, or of its `item`, as the session's request carried it,
  if valid; its default if not."
  [state item]
  (valid state (get (some-> (ask *cookie* nil) deref) (cond-> (:signal state) item (str "/" item)))))


(defn use-cookie
  "Takes up `state` in this island, or the one for `item`, such as a group's id:
  declares its signal on the island's root, unless the browser has it, and
  returns a handle for `value` and the helpers in `co.multiply.tropical.ui`. A
  hook: call it during the render."
  ([state]
   (use-cookie state nil))
  ([state item]
   (let [item (some-> item item-name)
         path (cond-> ["_cookie" (:signal state)] item (conj item))]
     (browser-state/claim-shape! "Cookie" state item)
     (let [v (kept state item)]
       (island/declare-signal path v)
       {:state state
        :value v
        :path  path
        :expr  (str "$_cookie." (:signal state) (when item (str "[" (island/json-js item) "]")))}))))


(defn value
  "The value of `handle`'s state as the session began: what to render for the
  first paint. The browser's may have moved on since: `observe` follows it."
  [handle]
  (:value handle))


(defn observe
  "The page's value of `handle`'s state, as `use-cookie` gives the handle, for
  the server to render from: the session's at first, as `value` gives it, so
  the first frame has it, then the page's, each time it changes, for as long
  as the island reads it. The island renders again when it does. A value
  that isn't valid reads as the default.

      (let [open (use-cookie resources-open)]
        [:section
         [:button (ui/aria-expanded {:data-on:click (ui/toggle open)} open) \"Resources\"]
         [:div (ui/shown open)
          (when (observe open) (resource-list))]])

  What `ui/shown` hides, the server goes on rendering, and the islands in it
  go on reading. Placed by `observe`, it is rendered only while the page shows
  it: the element shows at once, and what it holds a round trip later. Hide
  with `ui/shown` alone what is cheap to keep live.

  It follows the page, not the cookie, which the page's tabs share: each
  session renders what its own tab shows."
  [handle]
  (let [{:keys [state value path]} handle]
    (browser-state/observe [::observe path] path
      {:ms     0
       :read   #(valid state %)
       :value  value
       :known? true})))


;; Setting ----------------------------------------------------------------------


(defn- entry
  "The path under `_cookie` and the value of `e`, an entry of `patch`'s, checked."
  [e]
  (let [n (when (sequential? e) (count e))
        [state item v] (cond
                         (= 2 n) [(first e) nil (second e)]
                         (= 3 n) e)]
    (when-not (instance? State state)
      (throw (IllegalArgumentException. (str "A cookie state's entry is [state item value], or [state value]; not "
                                          (pr-str e) "."))))
    (let [item (some-> item item-name)]
      (browser-state/claim-shape! "Cookie" state item)
      (when-not (and (or (boolean? v) (number? v) (string? v))
                  (try ((:valid? state) v) (catch Exception _ false)))
        (throw (IllegalArgumentException. (str "Cookie state " (:id state) ": " (pr-str v) " isn't a valid value."))))
      [(cond-> [(:signal state)] item (conj item)) v])))


(defn patch
  "The signals that set cookie states, for an action's handler to return,
  merged with its own. Each of `entries` is `[state item value]`, or
  `[state value]` for a state used without an item:

      (fn [_] (cookie/patch (for [g groups] [group-open (:id g) false])))

  Patched in, they change the islands in the page that use those states, and
  the body's handler (`ui/page-attrs`) writes them into the cookie, so a page
  loaded later starts with them, one showing an item this page doesn't too.
  For the page an action opens, pass them to `action/navigate`'s `:signals`,
  and the cookie has them before that page loads.

  A value that isn't a boolean, a number or a string, or isn't valid for its
  state, throws, as does a state used the other way, with an item or without."
  [entries]
  {:_cookie (reduce (fn [signals e]
                      (let [[path v] (entry e)]
                        (assoc-in signals path v)))
              {}
              entries)})


;; The page ---------------------------------------------------------------------


(def ^:private keep-program
  (browser-code/program "cookie-keep.js"))


(defn handler-js
  "The statements of the body's signal-patch handler (`ui/page-attrs`) that
  keep the `_cookie` signals of `patch` in the cookie."
  {:no-doc true}
  []
  (browser-code/call keep-program
    ["patch" (island/json-js
               {:defaults (into (sorted-map) (map (fn [[signal state]] [signal (:default state)])) @!states)
                :name cookie-name
                :maxChars max-cookie-chars})]))
