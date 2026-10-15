(ns co.multiply.tropical.island
  "Islands: the unit of rendering, caching and patching, written as functions.

  `defisland` defines a function returning hiccup, as a React component does.
  Calling an island inside another island's hiccup does not render it: it places
  the island there, with its arguments, for the runtime
  (`co.multiply.tropical.runtime`) to reconcile. An island renders again only
  when its arguments change (`=`), or when something it read through a hook
  changed. Otherwise its last output is reused, and so is everything it holds.

  Hooks run only during a render. Each takes a key, unique within the island,
  instead of relying on call order:

  - `use-watch`   read a ref (atom, etc.) or a signal; re-render when the selected value changes.
  - `use-state`   island-local state, kept while the island is mounted.
  - `use-hold`    hold something (a subscription, a token) while renders keep asking for it.
  - `use-session` the session's `{:tab :uid :request}`.
  - `use-patch-signals` a fn patching the page's signals from any thread.

  `co.multiply.tropical.hooks/observe` and
  `co.multiply.tropical.action/use-action` build on these.

  Ids are scoped by parent, as React keys are. An island's slot is its name,
  plus `.key` for a keyed island, and is unique among its siblings. Its id is
  the path of slots from the root, `app/vault-card/vault-panel`, so it is
  unique on the page wherever the island is placed. An island placed by a
  different parent is a different instance, and so is one under a new key: it
  starts over, which is how to reset an island, such as a dialog opened again.

  The page sees an opaque digest of the id on the island's element
  (`element-id`), unless names are readable (`co.multiply.tropical.names`)."
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.internal.frame :as frame]
    [co.multiply.tropical.internal.model :as model]
    [s-exp.oda :as json])
  (:import
    [co.multiply.tropical.frame Slot]
    [co.multiply.tropical.internal.model Call]))


;; The render in progress: the runtime's hook implementations for this island.
(def ^:dynamic ^:no-doc *render* nil)


;; Calls ------------------------------------------------------------------------


(defn ^:no-doc call-slot
  "The island's slot in its parent: its name, plus `.key` if it has a key."
  [call]
  (model/call-slot call))


(defn- key-string
  "The key `k` as it appears in a slot (`Slot/key`)."
  [k]
  (Slot/key (if (keyword? k) (str (symbol k)) (str k))))


(defn element-id
  "The id of island `id`'s element in the page: an opaque digest of it, or the
  id itself while names are readable (`co.multiply.tropical.names`)."
  [id]
  (model/element-id id))


(defn ^:no-doc island-fn
  "The function `defisland` defines: it returns a `Call` for the island named
  `island-name`, a symbol (qualified, from `defisland`) or a string, with
  `render` and the given arguments. The name is a letter followed by letters,
  digits, `-` and `_`, so that it can't run into the `.` and `/` of ids."
  [island-name {:keys [key error-view]} render]
  (let [slot-name (name island-name)
        island    {:name (symbol island-name) :error-view error-view}]
    (when-not (re-matches #"[A-Za-z][A-Za-z0-9_-]*" slot-name)
      (throw (IllegalArgumentException.
               (str "Island name " (pr-str slot-name) ": use a letter followed by letters, digits, - and _."))))
    (when-not (or (nil? error-view) (ifn? error-view))
      (throw (IllegalArgumentException.
               (str "Island " slot-name ": :error-view is a fn of the failure, not " (pr-str error-view) "."))))
    ;; Called once per island placed, per render of its parent: a list places a
    ;; call per row. Fixed arities spare the common case the rest args and `apply`.
    (if key
      (let [prefix (str slot-name ".")
            slot   #(String/.concat prefix (key-string %))]
        (fn
          ([] (Call. (slot (key)) render [] island))
          ([a] (Call. (slot (key a)) render [a] island))
          ([a b] (Call. (slot (key a b)) render [a b] island))
          ([a b c] (Call. (slot (key a b c)) render [a b c] island))
          ([a b c & more]
           (let [args (into [a b c] more)]
             (Call. (slot (apply key args)) render args island)))))
      (fn
        ([] (Call. slot-name render [] island))
        ([a] (Call. slot-name render [a] island))
        ([a b] (Call. slot-name render [a b] island))
        ([a b c] (Call. slot-name render [a b c] island))
        ([a b c & more] (Call. slot-name render (into [a b c] more) island))))))


(defmacro defisland
  "Defines an island: a function of its arguments returning hiccup, rendered and
  patched on its own.

  The island's element id is the path of slots from the root (see the
  namespace docstring). It is set on the root element the render returns, so
  the render must not set an id of its own; a root that isn't an element is
  wrapped in a `:div`. Siblings need distinct slots: to place several
  instances under one parent, give a `:key` fn of the arguments in an options
  map, and the slot becomes `name.<key>`:

      (defisland message {:key :n} [msg]
        [:li (:text msg)])

  Calling the island by name in its own body places a child island, as for a
  tree view. Each child has its own state, reads and holds; siblings need keys
  just as when calling another island.

  The options map's `:error-view`, `(fn [failure] hiccup)`, renders in place of
  this island when its render throws, instead of the app's (see `error-view`)."
  [island-name & decl]
  (let [[doc decl] (if (string? (first decl)) [(first decl) (next decl)] [nil decl])
        [opts decl] (if (map? (first decl)) [(first decl) (next decl)] [nil decl])
        [params & body] decl
        ;; Leave the island var available for self-calls, without capturing an
        ;; app helper that happens to be named <island>-render either.
        render-name (gensym (str (name island-name) "-render-"))
        island-name (cond-> (vary-meta island-name assoc :arglists (list 'quote (list params)))
                      doc (vary-meta assoc :doc doc))]
    `(def ~island-name
       (island-fn '~(symbol (str *ns*) (str island-name)) ~opts (fn ~render-name ~params ~@body)))))


;; Hooks ------------------------------------------------------------------------


(defn- current
  [hook]
  (or *render*
    (throw (IllegalStateException. (str hook " called outside an island render.")))))


(defn use-session
  "The session's context: `{:tab :uid :request}`, its tab, its user, and the
  Ring request that created it, as its root got them.

  The request is the page's, or, for a tab the server rebuilt, as after a
  restart, its stream's, to the page's own URL: so its path and query are the
  page's either way, and fixed for the session's life, while its headers
  depend on which request it was. An action's handler reads its own request
  with `co.multiply.tropical.action/request`."
  []
  (:session (current "use-session")))


(defn use-watch
  "The current value of `ref` (anything supporting `add-watch`, or a
  `co.multiply.tropical.signal.Signal`), through `select` if given. The island
  renders again when the selected value changes, compared with `=`; a change
  that `select` maps to an equal value renders nothing.

  `select` runs on the session's thread whenever `ref` changes, so keep it cheap."
  ([ref]
   (use-watch ref identity))
  ([ref select]
   ((:read! (current "use-watch")) ref select)))


(defn use-hold
  "Holds a value under `k` for as long as the island's renders keep calling
  `(use-hold k ...)`. `acquire` runs when the island first asks for `k` and
  returns `{:value v, :release f}`. `release` runs after the first render that no
  longer asks for `k`, or when the island unmounts. Returns `v`.

  A render asks for each `k` at most once: asking twice throws, since two call
  sites sharing one hold (two buttons sharing one action token) is a bug.
  With `{:idempotent true}`, asking again returns what the first ask got, for
  a hold whose key is what it holds, such as a read: two hooks reading the
  same thing in one render share one hold, as `observe` and
  `co.multiply.tropical.shared/use-shared` do."
  ([k acquire]
   (use-hold k acquire nil))
  ([k acquire {:keys [idempotent]}]
   ((:hold! (current "use-hold")) k acquire idempotent)))


(defn json-js
  "`v` as JSON, for a Datastar expression. A backslash is written `\\u005c`:
  Datastar 1.0.4 finds an expression's statements with a pattern that reads
  the end of `\"a\\\\\"` as an escaped quote, and would go on to split the
  expression at a `;` in a later string."
  {:no-doc true}
  [v]
  (str/replace (json/write-str v) "\\\\" "\\u005c"))


(deftype Js [expr])


(defn js
  "A JavaScript expression, as the value `declare-signal` declares a signal
  with, for a value only the browser knows. The browser evaluates it each time
  it applies the declaration, and keeps it only where the signal is missing."
  [expr]
  (->Js expr))


(defn declare-signal
  "Declares the Datastar signal at `path`, a vector of names, as `value`, unless
  the client already has it. The island's root carries the declaration, as
  `data-signals__ifmissing`, so the signal exists wherever the island is in the
  page, and neither a later render, a patch, nor the island mounting again
  resets what the client has made of it. `value` is data, sent as JSON, or
  `(js expr)`. Returns nil.

  A map is declared per leaf: a key missing from the client's copy is added,
  and the others are left as they are.

  The primitive under `co.multiply.tropical.cookie` and
  `co.multiply.tropical.storage`, for state the browser owns."
  [path value]
  ((:declare! (current "declare-signal")) path value))


(defn use-patch-signals
  "A fn that patches `signals`, a map, into the page's Datastar signals, as an
  action's answer does, for work that lands after its action answered: a
  background task's result filling a form's fields. Callable from any thread
  while the island is mounted, and returns nil:

      (let [patch! (use-patch-signals)
            import (use-action :import
                     (fn [{text \"text\"}]
                       (q/compel (q/task (patch! {:draft (convert text)})))
                       nil))]
        ...)

  The session sends them after the frame rendering what changed with them,
  so elements bound to them are in the page by then, or once its tab is
  connected, if it isn't. Patches sent together are merged where one patch
  does what they do in turn. A patch from an island since unmounted is
  dropped, as are those a session closes with.

  Signals are the page's, not the island's: a patch overwrites what the user
  typed into a field bound to one."
  []
  (:signals! (current "use-patch-signals")))


(defn declare-script
  "Declares the ES module at `url`, which the island's elements need. The
  session sends it to the page once, from the first frame in which an island
  declares it, as a `<script type=\"module\">` appended to the page's head, and
  gives it to the page for its own head when it is in the first frame. Returns
  nil.

  The primitive under `co.multiply.tropical.script`."
  [url]
  ((:script! (current "declare-script")) url))


(defn use-state
  "Island-local state under `k`, starting at `init`. Returns `[value set-value!]`.
  `set-value!` may be called from any thread, typically from an action, and
  returns nil. The state lives as long as the island is mounted."
  [k init]
  (let [!state (:state (current "use-state"))]
    [(use-watch !state #(get % k init))
     (fn [v]
       (swap! !state assoc k v)
       nil)]))


(defn error-view
  "What renders in place of an island whose render throws, unless the island or
  the app gives an `:error-view` of its own. It says only that the island
  failed, with the failure's reference: an exception's message can carry what
  the user shouldn't see, and the exception is logged with the reference.

  An error view is a fn of the failure, a map:

  - `:ref`        a reference made for this failure, such as `4KF7-Q2XM`, also
                  in the line logging it: something for the user to report.
  - `:error`      the exception.
  - `:island`     the island's qualified name, as `app.page/rating-card`.
  - `:path`       the island's id, the path of slots from the root.
  - `:element-id` its element's id in the page (`element-id`).
  - `:args`       the island's arguments.
  - `:session`    the session's `{:tab :uid :request}`, as `use-session`.

  What it returns renders in the island's place, and reaches the client. One
  that throws gives way to the next: an island's to the app's, and the app's to
  this one."
  [{:keys [ref]}]
  [:div {:class [:rounded :border :border-red-400 :p-3 :text-sm :text-red-600]}
   "This part of the page failed to render. Reference " ref "."])


(defn ^:no-doc html
  "The full HTML of `frame`, children included."
  [frame]
  (model/html frame))


(defn ^:no-doc patches
  "The morphs, inserts and removals bringing `sent` up to `frame`."
  [sent frame]
  (frame/patches sent frame))


(defn frame-chars
  "Length of the HTML `frame` emits, computed from cached template sizes."
  [frame]
  (model/frame-chars frame))
