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
    [co.multiply.tropical.action :as action]
    [co.multiply.tropical.cookie :as cookie]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.resource :as resource]
    [s-exp.oda :as json])
  (:import
    [java.nio.charset StandardCharsets]
    [java.security MessageDigest]
    [java.util HexFormat]))


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


;; Declaring --------------------------------------------------------------------


;; Signal name -> State, for every state declared.
(defonce ^:private !states (atom {}))


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
        state (->State id (cookie/signal-name id) default valid? encode decode version
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
    (swap! !states (fn [states]
                     (when-some [other (get states (:signal state))]
                       (when (not= id (:id other))
                         (throw (IllegalArgumentException.
                                  (str what " would share its signal with " (:id other) "; rename one.")))))
                     (assoc states (:signal state) state)))
    state))


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
  (let [digest (MessageDigest/.digest (MessageDigest/getInstance "SHA-256")
                 (String/.getBytes (str "tropical-storage/" uid) StandardCharsets/UTF_8))]
    (subs (HexFormat/.formatHex (HexFormat/of) digest) 0 16)))


;; The owner of the page being rendered, `owner`'s. Bound by
;; `co.multiply.tropical.ring` while it renders a page. Without a root, so that
;; `ask` throws where nothing binds it.
#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:dynamic ^:no-doc *owner*)


(def ^:private global
  "Where the page keeps what the handler needs between patches: the default
  each entry was declared with, by key, and the timers of `observe`'s sends."
  "globalThis.__tropicalStorage ??= {defaults: {}, timers: {}}")


(defn- key-js
  "The expression of the browser's key for entry `slot` of the state named
  `signal`, of owner `o`: the browser writes it, as the handler does."
  [o signal slot]
  (str (island/json-js prefix) " + JSON.stringify(" (island/json-js [o signal slot]) ")"))


;; Using ------------------------------------------------------------------------


(def ^:private single
  "The slot of a state used without an item."
  "_")


(defn- claim-shape!
  "Records that `state` is used with an item, or without, if `item` is nil;
  throws if it was used the other way."
  [state item]
  (let [shape (if item :items :single)]
    (when-not (= shape (swap! (:shape state) #(or % shape)))
      (throw (IllegalArgumentException.
               (str "Storage state " (:id state) " is used " (if item "with an item and without" "without an item and with")
                 "; use it one way."))))))


(defn- load-js
  "The expression that gives an entry's value as the page starts: the
  browser's, if it has one under `state`'s version and young enough, else
  `default-json`. It records the default, for the handler. It has no `;`, so
  no string in the default can make Datastar split it into statements."
  [k-js {:keys [version max-age-ms]} default-json]
  (str "((k, d) => ((" global ").defaults[k] = JSON.stringify(d), "
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
   (let [item    (some-> item cookie/item-name)
         slot    (or item single)
         default (if (contains? opts :default) (:default opts) (:default state))
         encoded ((:encode state) default)
         {:keys [uid]} (island/use-session)]
     (claim-shape! state item)
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
        [:p (if (= pending v) \"…\" (str (word-count (get v \"text\")) \" words\"))])

  The browser sends it once the page has the island, so the first frame
  never has it, and doesn't wait for it, then each change, a moment after the
  user stops typing, for as long as the island reads it. Where the page shows
  the value through bindings, it needs no reading: read it for what only the
  server can render from it, such as a row per item, or a count."
  [handle]
  (let [{:keys [state slot default]} handle
        {:keys [uid tab]} (island/use-session)
        {:keys [token !value !handler]}
        (island/use-hold [::observe (:signal state) slot]
          (fn []
            (let [{:keys [value release]} (action/mint uid tab)]
              {:value   (assoc value :!value (atom ::unsent))
               :release release}))
          {:idempotent true})]
    (reset! !handler (fn [body]
                       (let [v (get body "value")]
                         (reset! !value (if (nil? v) default (or (decode state v) default)))
                         nil)))
    (island/declare-signal ["_storageWatch" token] [(:signal state) slot])
    (let [v (island/use-watch !value)]
      (if (= ::unsent v) pending v))))


;; Setting ----------------------------------------------------------------------


(defn- entry-path
  "The path under `_storage` of the entry of `state`, or its `item`."
  [state item]
  (let [item (some-> item cookie/item-name)]
    (claim-shape! state item)
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


(defn- states-js
  "Each declared state's version and age, by signal, as the page needs them."
  []
  (island/json-js (into {} (map (fn [[signal {:keys [version max-age-ms]}]] [signal [version max-age-ms]])) @!states)))


(defn- page-owner
  []
  (ask *owner* (throw (IllegalStateException.
                        (str "ui/page-attrs renders a page's body, whose user co.multiply.tropical.ring knows "
                          "while it renders the page.")))))


(defn handler-js
  "The statements of the body's signal-patch handler (`ui/page-attrs`) that
  keep the `_storage` signals of `patch` in `localStorage`, drop the entries
  `forget` names, and send `observe`'s values."
  {:no-doc true}
  []
  (str
    "try {"
    "const g = " global ";"
    "const o = " (island/json-js (page-owner)) ", v = " (states-js) ", p = " (island/json-js prefix) ";"
    "const key = (s, i) => p + JSON.stringify([o, s, i]);"
    "const has = (x, k) => x !== null && typeof x === 'object' && k in x;"
    ;; Reads a signal without creating it, as reading `$` would.
    "const get = (...ks) => { let x = $; for (const k of ks) { if (!has(x, k)) return undefined; x = x[k] } return x };"
    "const put = (k, x) => {"
    "try { localStorage.setItem(k, x); return } catch (_) {}"
    ;; Full: the oldest entries go first.
    "const ours = [];"
    "for (let n = 0; n < localStorage.length; n++) {"
    "const q = localStorage.key(n);"
    "if (q && q !== k && q.startsWith(p)) { let t = 0; try { t = JSON.parse(localStorage.getItem(q)).at } catch (_) {} ours.push([t, q]) }"
    "}"
    "ours.sort((a, b) => a[0] - b[0]);"
    "for (const [, q] of ours) { localStorage.removeItem(q); try { localStorage.setItem(k, x); return } catch (_) {} }"
    "};"
    "const keep = (s, i) => {"
    "const st = v[s];"
    "if (!st) return;"
    "const k = key(s, i), x = get('_storage', s, i);"
    "if (x === undefined || x === null || JSON.stringify(x) === g.defaults[k]) localStorage.removeItem(k);"
    "else put(k, JSON.stringify({version: st[0], at: Date.now(), value: x}));"
    "};"
    "const send = t => {"
    "clearTimeout(g.timers[t]);"
    "delete g.timers[t];"
    "const w = get('_storageWatch', t);"
    "if (!w) return;"
    "const x = get('_storage', w[0], w[1]);"
    "fetch(location.href, {method: 'POST', headers: {'Tropical-Action': t, 'Content-Type': 'application/json'},"
    " body: JSON.stringify({value: x === undefined ? null : x})})"
    ".then(r => {"
    " if (r.headers.has('Tropical-Reopen')) dispatchEvent(new CustomEvent('tropical:reopen'));"
    " else if (r.status === 403 || r.status === 404) $._storageWatch[t] = null })"
    ".catch(() => {});"
    "};"
    "const later = (t, ms) => { clearTimeout(g.timers[t]); g.timers[t] = setTimeout(() => send(t), ms) };"
    "const watching = (s, i) => Object.entries(get('_storageWatch') || {})"
    ".filter(([, w]) => w && w[0] === s && w[1] === i).map(([t]) => t);"
    "for (const [s, items] of Object.entries(patch._storage || {})) {"
    "if (items === null || typeof items !== 'object') continue;"
    "for (const i of Object.keys(items)) { keep(s, i); for (const t of watching(s, i)) later(t, 300) }"
    "}"
    "for (const [t, w] of Object.entries(patch._storageWatch || {})) if (w) later(t, 0);"
    "const f = patch._storageForget;"
    "if (f) {"
    "for (const [s, items] of Object.entries(f)) {"
    "if (items === null || typeof items !== 'object') continue;"
    "for (const i of Object.keys(items)) {"
    "const k = key(s, i), d = g.defaults[k];"
    "localStorage.removeItem(k);"
    "if (get('_storage', s, i) !== undefined) $._storage[s][i] = d === undefined ? null : JSON.parse(d);"
    "for (const t of watching(s, i)) later(t, 0);"
    "}"
    "}"
    "$._storageForget = null;"
    "}"
    "} catch (e) { console.error(e) }"))


(defn prune-js
  "The statements, run once as the page starts (`ui/page-attrs`), that drop
  the entries the page won't use: another user's, older than their state's
  `:max-age-ms`, stored under another `:version`, or of a state no longer
  declared."
  {:no-doc true}
  []
  (str
    "try {"
    "const o = " (island/json-js (page-owner)) ", v = " (states-js) ", p = " (island/json-js prefix) ", now = Date.now();"
    "for (let n = localStorage.length - 1; n >= 0; n--) {"
    "const k = localStorage.key(n);"
    "if (!k || !k.startsWith(p)) continue;"
    "let kept = false;"
    "try {"
    " const [ko, s] = JSON.parse(k.slice(p.length)), e = JSON.parse(localStorage.getItem(k)), st = v[s];"
    " kept = ko === o && !!st && e.version === st[0] && now - e.at <= st[1]"
    " } catch (_) {}"
    "if (!kept) localStorage.removeItem(k);"
    "}"
    "} catch (_) {}"))
