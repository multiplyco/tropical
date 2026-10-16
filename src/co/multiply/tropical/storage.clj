(ns co.multiply.tropical.storage
  "State the browser owns and keeps in `localStorage`, such as a draft the user
  is writing: a value of any size, which outlasts closing what shows it, a page
  load, and the server restarting or being deployed again, in the one browser,
  while the server stores none of it.

  `defstorage` declares a state, with its default and how its values become
  JSON and come back. Any island takes it up with `use-storage`, with an item
  for one per item, such as a suggestion's id, and a default of its own if the
  item has one:

      (defstorage edits
        \"Edits to a suggestion, before the user acts on it.\"
        {:default {} :valid? map?})

      (defisland rework
        [{:keys [id text]}]
        (let [draft (use-storage edits id {:default {\"text\" text}})
              start (use-action :start
                      (fn [_ value]
                        (when-some [{t \"text\"} (decode edits value)]
                          (start! id t))
                        (forget [[edits id]]))
                      {:value (ui/signal draft)})]
          [:form
           [:textarea {:data-bind (ui/path draft \"text\")}]
           [:button {:type \"button\" :data-on:click start} \"Start\"]]))

  How it holds:

  - The browser keeps each value in a Datastar signal under `_storage`, local
    to the page, so no action posts it unless one passes it as its value. An
    island that uses a state declares the signal on its root, only if missing:
    as the browser's entry has it, or as the default. A later render, or the
    island mounting again, leaves what the user did.
  - The element the app mounts in carries `ui/page-attrs` (`ring/mount` adds
    it), whose handler writes each change into the browser's entry, while the
    value differs from the default the island declared it with: only an edit
    is kept, and a default the server revised shows through where there is
    none.
  - The server sees a value only when an action passes it, or through
    `observe`. The first frame renders the default, and the browser fills
    in its own as its script starts, through the elements' bindings
    (`data-bind`, `data-text`, `ui/attr`).
  - An entry is the user's: its key holds a digest of the session's user, and
    a page loaded for another drops it. A page also drops entries older than
    their state's `:max-age-ms`, stored under another `:version`, or of a
    state no longer declared. When the browser's storage is full, the oldest
    entries go first.

  Values are JSON, since the browser changes them itself, in expressions and
  bindings. `:encode` turns a Clojure value into one, and `:decode` turns one
  back; a state checks, as it is declared, that its default comes back as it
  went. A value only ever round-tripped, which the browser never reads, can
  be a string, such as Transit, at the cost of binding to it.

  An action sets entries with `patch`, and drops them with `forget`, in the
  browser that made it, whether or not anything on the page uses them."
  (:require
    [clojure.string :as str]
    [co.multiply.scoped :refer [ask]]
    [co.multiply.tropical.internal.browser-code :as browser-code]
    [co.multiply.tropical.internal.browser-state :as browser-state]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.names :as names]
    [co.multiply.tropical.resource :as resource]
    [s-exp.oda :as json]))


(def ^:no-doc prefix
  "What the browser's keys for entries start with."
  "tropical:")


(def max-age-ms
  "How long an entry is kept since it was last written, unless its state says:
  30 days."
  (* 30 24 60 60 1000))


(def pending
  "What `observe` returns until the browser has sent its value: the same as
  `co.multiply.tropical.hooks/pending`."
  resource/pending)


(defn pending?
  "Whether `x` is `pending`: `observe`'s value until the browser has sent it."
  [x]
  (identical? pending x))


;; Declaring --------------------------------------------------------------------


;; Signal name -> State, for every state declared.
(defonce ^:private !states (atom {}))


(defn declared
  "Every storage state declared, by signal name: the same map until a state is
  declared, or declared again."
  {:no-doc true}
  []
  @!states)


(defrecord State [id signal default valid? encode decode version max-age-ms shape])


(defn- check-json
  "Throws unless `v`, encoded, is a value the browser keeps as it is: no nil,
  which Datastar takes for deleting a signal, and no key holding a `.`, which
  it reads as a step into one. `what` names it, for the message."
  [what v]
  (cond
    (nil? v) (throw (IllegalArgumentException. (str what " holds nil, which Datastar takes for deleting a signal.")))
    (map? v) (doseq [[k x] v]
               (when (str/includes? (if (keyword? k) (str (symbol k)) (str k)) ".")
                 (throw (IllegalArgumentException. (str what " has the key " (pr-str k) ", whose '.' Datastar reads "
                                                     "as a step into the signal."))))
               (check-json what x))
    (coll? v) (run! #(check-json what %) v)))


(defn- trip
  "`v` as the browser gives it back: written as JSON, and parsed."
  [v]
  (json/parse (json/write-str v)))


(defn state*
  "What `defstorage` expands to: the state `id`, a qualified symbol."
  {:no-doc true}
  [id {:keys [default valid? encode decode version] :or {valid? some? encode identity decode identity version 1}
       :as   opts}]
  (let [what  (str "Storage state " id)
        state (->State id (browser-state/signal-name id) default valid? encode decode version
                (:max-age-ms opts max-age-ms) (atom nil))]
    (when-not (contains? opts :default)
      (throw (IllegalArgumentException. (str what ": it takes a :default."))))
    (check-json (str what "'s default") (encode default))
    (when-not (valid? default)
      (throw (IllegalArgumentException. (str what ": its default " (pr-str default) " isn't valid."))))
    (when-not (= default (decode (trip (encode default))))
      (throw (IllegalArgumentException.
               (str what ": its default comes back from the browser as " (pr-str (decode (trip (encode default))))
                 ", not as it went. Give it an :encode and a :decode that keep its values, such as keywords."))))
    (when-not (or (int? version) (string? version))
      (throw (IllegalArgumentException. (str what ": its :version is an integer or a string."))))
    (names/register! !states (:signal state) state "Storage state" "signal")))


(defmacro defstorage
  "Defines a state kept in the browser's `localStorage`:
  `(defstorage name doc? opts)`. `opts`:

  - `:default`    what the state is until the user changes it; an island may
                  give one of its own (`use-storage`).
  - `:valid?`     what a value from the browser must be, once decoded. Defaults
                  to `some?`.
  - `:encode`     a fn of a value to JSON: maps, vectors, strings, numbers and
                  booleans, with no nil. Defaults to `identity`.
  - `:decode`     a fn of JSON, as parsed, with string keys, back to a value.
                  Defaults to `identity`.
  - `:version`    an integer or a string. Change it when the shape of the
                  values changes: entries stored under another are dropped.
                  Defaults to 1.
  - `:max-age-ms` how long an entry is kept since it was last written.
                  Defaults to `max-age-ms`."
  [state-name & decl]
  (let [[doc decl] (if (string? (first decl)) [(first decl) (next decl)] [nil decl])
        state-name (cond-> state-name doc (vary-meta assoc :doc doc))]
    `(def ~state-name
       (state* '~(symbol (str (ns-name *ns*)) (str state-name)) ~(first decl)))))


;; Keys -------------------------------------------------------------------------


(defn owner
  "The browser's name for the user `uid`'s entries: a digest, so the id itself
  isn't kept in the browser."
  {:no-doc true}
  [uid]
  (names/digest (str "tropical-storage/" uid) 16))


;; The owner of the page being rendered, `owner`'s. Bound by
;; `co.multiply.tropical.ring` while it renders a page. Without a root, so that
;; `ask` throws where nothing binds it.
#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:dynamic ^:no-doc *owner*)


(def ^:private global-name
  "Where the page keeps what the handler needs between patches: the default
  each entry was declared with, by key."
  "__tropicalStorage")


(defn- global-js
  "The expression initializing the page's shared storage bookkeeping."
  []
  (str "globalThis[" (island/json-js global-name) "] ??= {defaults: {}}"))


(defn- key-js
  "The expression of the browser's key for entry `slot` of the state named
  `signal`, of owner `o`: the browser writes it, as the handler does."
  [o signal slot]
  (str (island/json-js prefix) " + JSON.stringify(" (island/json-js [o signal slot]) ")"))


;; Using ------------------------------------------------------------------------


(def ^:private single
  "The slot of a state used without an item."
  "_")


(defn- load-js
  "The expression that gives an entry's value as the page starts: the
  browser's, if it has one under `state`'s version and young enough, else
  `default-json`. It records the default, for the handler. It has no `;`, so
  no string in the default can make Datastar split it into statements."
  [k-js {:keys [version max-age-ms]} default-json]
  (str "((k, d) => ((" (global-js) ").defaults[k] = JSON.stringify(d), "
    "(e => e && e.version === " (island/json-js version) " && Date.now() - e.at <= " max-age-ms " ? e.value : d)"
    "((() => { try { return JSON.parse(localStorage.getItem(k)) } catch (_) { return null } })())))"
    "(" k-js ", " default-json ")"))


(defn use-storage
  "Takes up `state` in this island, or the one for `item`, such as a
  suggestion's id: declares its signal on the island's root, unless the page
  has it, from the browser's entry, or the default if there is none. Returns a
  handle for the helpers in `co.multiply.tropical.ui` and for `observe`. A
  hook: call it during the render.

  With `{:default v}`, the island gives a default of its own, for a state whose
  starting point is the item's, such as the text of a suggestion. The browser
  keeps an entry only while its value differs from the default it was
  declared with.

  The server doesn't know the browser's value. What the first frame shows of
  it is the default, which the browser replaces as its script starts: bind
  what shows it (`data-bind`, `data-text`, `ui/attr`). For the server to render
  from it, read it with `observe`."
  ([state]
   (use-storage state nil nil))
  ([state item]
   (use-storage state item nil))
  ([state item opts]
   (let [item    (some-> item browser-state/item-name)
         slot    (or item single)
         default (if (contains? opts :default) (:default opts) (:default state))
         encoded ((:encode state) default)
         {:keys [uid]} (island/use-session)]
     (browser-state/claim-shape! "Storage" state item)
     (check-json (str "Storage state " (:id state) "'s default") encoded)
     (island/declare-signal ["_storage" (:signal state) slot]
       (island/js (load-js (key-js (owner uid) (:signal state) slot) state (island/json-js encoded))))
     {:state   state
      :slot    slot
      :default default
      :value   default
      :path    ["_storage" (:signal state) slot]
      :expr    (str "$_storage." (:signal state) "[" (island/json-js slot) "]")})))


(defn decode
  "`value`, the JSON of one of `state`'s values as an action got it, as the
  state's value: decoded, and nil unless it is valid. It came from the
  browser, so it is untrusted, as signals are."
  [state value]
  (when (some? value)
    (let [v (try ((:decode state) value) (catch Exception _ nil))]
      (when (and (some? v) (try ((:valid? state) v) (catch Exception _ false)))
        v))))


(defn observe
  "The browser's value of `handle`'s entry, as `use-storage` gives the handle,
  for the server to render from: `pending` until the browser has sent it,
  then the latest, decoded. The island renders again when it changes. A
  value that isn't valid reads as the handle's default.

      (let [draft (use-storage edits id {:default {\"text\" text}})
            v     (observe draft)]
        [:p (if (pending? v) \"…\" (str (word-count (get v \"text\")) \" words\"))])

  The browser sends it once the page has the island, so the first frame
  never has it, and doesn't wait for it, then each change, a moment after the
  user stops typing, for as long as the island reads it. Where the page shows
  the value through bindings, it needs no reading: read it for what only the
  server can render from it, such as a row per item, or a count."
  [handle]
  (let [{:keys [state slot default path]} handle
        v (browser-state/observe [::observe (:signal state) slot] path
            {:ms    300
             :read  #(if-some [decoded (decode state %)] decoded default)
             :value ::unsent})]
    (if (= ::unsent v) pending v)))


;; Setting ----------------------------------------------------------------------


(defn- entry-path
  "The path under `_storage` of the entry of `state`, or its `item`."
  [state item]
  (let [item (some-> item browser-state/item-name)]
    (browser-state/claim-shape! "Storage" state item)
    [(:signal state) (or item single)]))


(defn patch
  "The signals that set entries, for an action's handler to return, merged
  with its own. Each of `entries` is `[state item value]`, or `[state value]`
  for a state used without an item. The browser that made the action keeps
  them, whether or not the page uses them, and islands using them follow.

  A map is merged into the browser's value, as any signal patch is: a key the
  patch leaves out stays as it was. A value that isn't valid for its state, or
  can't be kept as JSON, throws."
  [entries]
  {:_storage (reduce (fn [signals e]
                       (let [n (when (sequential? e) (count e))
                             [state item v] (cond
                                              (= 2 n) [(first e) nil (second e)]
                                              (= 3 n) e)]
                         (when-not (instance? State state)
                           (throw (IllegalArgumentException.
                                    (str "A storage entry is [state item value], or [state value]; not " (pr-str e) "."))))
                         (when-not (try ((:valid? state) v) (catch Exception _ false))
                           (throw (IllegalArgumentException.
                                    (str "Storage state " (:id state) ": " (pr-str v) " isn't a valid value."))))
                         (let [encoded ((:encode state) v)]
                           (check-json (str "Storage state " (:id state) "'s value") encoded)
                           (assoc-in signals (entry-path state item) encoded))))
               {}
               entries)})


(defn forget
  "The signals that drop entries, for an action's handler to return, merged
  with its own. Each of `entries` is `[state item]`, or `[state]` for a state
  used without an item:

      (fn [_ value] (start! value) (forget [[edits id]]))

  The browser that made the action drops them, whether or not the page uses
  them, and an island using one goes back to the default it declared."
  [entries]
  {:_storageForget (reduce (fn [signals e]
                             (let [n (when (sequential? e) (count e))
                                   [state item] (when (#{1 2} n) e)]
                               (when-not (instance? State state)
                                 (throw (IllegalArgumentException.
                                          (str "A storage entry to forget is [state item], or [state]; not " (pr-str e) "."))))
                               (assoc-in signals (entry-path state item) true)))
                     {}
                     entries)})


;; The page ---------------------------------------------------------------------


(defn- page-owner
  []
  (ask *owner* (throw (IllegalStateException.
                        (str "ui/page-attrs renders a page's body, whose user co.multiply.tropical.ring knows "
                          "while it renders the page.")))))


(defn- config-js
  "The owner's storage definitions in stable order, also part of the page revision."
  []
  (island/json-js
    {:owner (page-owner)
     :states (into (sorted-map)
               (map (fn [[signal {:keys [version max-age-ms]}]] [signal [version max-age-ms]]))
               @!states)
     :prefix prefix
     :globalName global-name}))


(def ^:private keep-program
  (browser-code/program "storage-keep.js"))


(def ^:private prune-program
  (browser-code/program "storage-prune.js"))


(defn handler-js
  "The statements of the body's signal-patch handler (`ui/page-attrs`) that
  keep the `_storage` signals of `patch` in `localStorage`, and drop the
  entries `forget` names."
  {:no-doc true}
  []
  (browser-code/call keep-program ["$" "patch" (config-js)]))


(defn prune-js
  "The expression run as the page starts, dropping entries for other users,
  expired entries, and definitions or versions no longer in use."
  {:no-doc true}
  []
  (str "void (() => {" (browser-code/call prune-program [(config-js)]) "})()"))
