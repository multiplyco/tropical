(ns co.multiply.tropical.hooks
  "`observe`, the hook for reading from outside the page, such as a database
  subscription, a poll loop or a query.

  It returns `pending` until the first value arrives, and throws in the render
  when what it reads fails: the island renders the error view unless it catches
  it. A read is a hold keyed by what it reads, so hooks of an app's own that
  read the same thing in one render share it: one resource, one value."
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.resource :as resource]))


(def pending
  "What `observe` returns until its first value arrives."
  resource/pending)


(defn observe*
  "What `observe` expands to: the resource keyed by `site`, the call site, and
  `args`, opened by calling `f` with `emit!` and `args`."
  {:no-doc true}
  [site args f]
  (let [key [site args]
        v   (island/use-watch
              (island/use-hold [::observe key]
                (fn []
                  (let [!value (resource/acquire! key #(apply f % args))]
                    {:value   !value
                     :release #(resource/release! key !value)}))
                {:idempotent true}))]
    (if (resource/failed? v)
      (throw (:error v))
      v)))


;; The check that `f` takes everything it depends on through `args`.


(defn- symbols
  [form]
  (into #{} (filter symbol?) (tree-seq coll? seq form)))


(defn- bound-names
  "The names that a fn's param vector binds, destructuring included."
  [params]
  (into #{} (comp (partition-all 2) (map first)) (destructure [params (gensym)])))


(defn- arities
  "A literal fn form's arities, as `[bound-names forms]`; nil if `form` isn't one."
  [form]
  (when (and (seq? form) (contains? '#{fn fn* clojure.core/fn} (first form)))
    (let [fn-name (when (symbol? (second form)) (second form))
          more    (cond-> (rest form) fn-name rest)]
      (for [[params & body] (if (vector? (first more)) [more] more)]
        [(cond-> (bound-names params) fn-name (conj fn-name))
         (cons params body)]))))


(defn- captured
  "The locals in `env` that the form `f` refers to, other than through its own
  params. Symbols are read, not analyzed: a name that the body binds again
  counts, which errs toward refusing."
  [env f]
  (let [refers (if-let [as (arities f)]
                 (into #{} (mapcat (fn [[bound forms]] (remove bound (symbols forms)))) as)
                 (symbols f))]
    (into (sorted-set) (filter #(contains? env %)) refers)))


(defmacro observe
  "The latest value emitted by a resource shared with every reader of the same
  call site and `args`. The island renders again on each new value.

      (observe topic
        (fn [emit! topic]
          (subscribe-feed topic emit!)))

  `f` opens the resource, as in Missionary's `observe`: it is called with an
  `emit!` fn and `args`, only if nobody holds the resource yet.

  - It runs on a virtual thread of its own, so it may block while it connects,
    and in an empty Scoped scope: the resource belongs to no reader, so it gets
    nothing from the render but `args`.
  - It returns what undoes it: a fn, called once when the resource closes; or a
    Quiescent task, cancelled when the resource closes, whose failure fails the
    resource; or nil.
  - `emit!` may be called from any thread, from the moment `f` is called. Only
    the latest value is kept: a session renders the value current when it gets
    to it, so values in between may never be seen. Once the resource has closed
    or failed, `emit!` does nothing.

  The call site and `args` are the resource's key, and so decide who shares it:
  every value `f` depends on must be one of `args`. `f` may refer to no local
  of the render other than through its params; one that does fails to compile.
  `args` are compared with `=`, so a value made anew each render, such as a
  fresh fn, opens a new resource each render. Pass values and stable references.

  If `f` throws, or its task fails, the resource fails and `observe` throws its
  exception. The island keeps the failure until it unmounts or reads other
  `args`; the next reader opens a new resource.

  Given other `args`, the island holds the new resource and releases the old
  one, which lingers before it closes (`resource/linger-ms`).

  The same call made again in one render, with equal `args`, returns the same
  value, from the one resource. So a hook of the app's own wrapping `observe`,
  whose every read shares its call site, may be called by several hooks in
  one render, each asking of the same data."
  [& args+f]
  (when (empty? args+f)
    (throw (IllegalArgumentException. "observe takes a fn, after its args.")))
  (let [args   (vec (butlast args+f))
        f      (last args+f)
        {:keys [line column]} (meta &form)
        ;; Unique to this expansion: two call sites never share, even when a
        ;; macro's expansions carry no position, and reloaded code opens anew.
        site   (symbol (str (ns-name *ns*))
                 (str "observe" (when line (str "@" line ":" column)) "#" (gensym "")))
        locals (captured &env f)]
    (when (seq locals)
      (throw (ex-info (str "observe's fn refers to " (str/join ", " locals) ", local to the render. "
                        "What it depends on decides who shares its resource, so pass these as args: "
                        "(observe " (first locals) " (fn [emit! " (first locals) "] ...)).")
               {:locals (vec locals)})))
    `(observe* '~site ~args ~f)))
