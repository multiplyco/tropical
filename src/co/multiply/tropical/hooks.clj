(ns co.multiply.tropical.hooks
  "`observe`, the hook for reading from outside the page, such as a database
  subscription, a poll loop or a query.

  It returns `pending` until the first value arrives, and throws in the render
  when what it reads fails: the island renders the error view unless it catches
  it. A read is a hold keyed by what it reads, so hooks of an app's own that
  read the same thing in one render share it: one resource, one value.

  A read that depends on another waits for it with `if-loaded`."
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.resource :as resource]))


(def pending
  "What `observe` returns until its first value arrives."
  resource/pending)


(defn pending?
  "Whether `x` is `pending`: a read's value until it has loaded."
  [x]
  (identical? pending x))


(defn observe*
  "What `observe` expands to: the resource keyed by `site`, the call site, and
  `args`, opened by calling `f` with `emit!` and `args`. `pending`, holding
  nothing, if one of `args` is."
  {:no-doc true}
  [site args f]
  (if (some pending? args)
    pending
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
        v))))


;; The check that `f` takes everything it depends on through `args`.


(defn- symbols
  [form]
  (into #{} (filter symbol?) (tree-seq coll? seq form)))


(defn- bound-names
  "The names that a fn's param vector, or a binding's pattern, binds,
  destructuring included."
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

  If one of `args` is `pending`, as another read's value is until it loads, it
  returns `pending` and opens nothing, so a read that depends on another starts
  once that one has loaded. A value taken from a pending one, such as
  `(:id user)`, is nil, not `pending`: wait for it with `if-loaded`.

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
        site   (resource/call-site (ns-name *ns*) line column)
        locals (captured &env f)]
    (when (seq locals)
      (throw (ex-info (str "observe's fn refers to " (str/join ", " locals) ", local to the render. "
                        "What it depends on decides who shares its resource, so pass these as args: "
                        "(observe " (first locals) " (fn [emit! " (first locals) "] ...)).")
               {:locals (vec locals)})))
    `(observe* '~site ~args ~f)))


;; `if-loaded`: which bindings wait for which.


(defn- loaded-bindings
  "`if-loaded`'s binding pairs as a `let`'s bindings, each value bound first to
  a name of its own, its raw value: `pending`, without evaluating it, where its
  expression refers to a binding whose raw value is. Its pattern is bound to
  it only once it has loaded, so destructuring never sees `pending`. Returns
  `[bindings raws]`."
  [pairs]
  (loop [[[pattern expr :as pair] & more] pairs
         binders  {}
         bindings []
         raws     []]
    (if-not pair
      [bindings raws]
      (let [raw   (gensym "raw")
            waits (into [] (comp (keep binders) (distinct)) (symbols expr))]
        (recur more
          ;; A name bound again is the later binding's.
          (into binders (map (fn [n] [n raw])) (bound-names pattern))
          (conj bindings
            raw (if (seq waits)
                  `(if (or ~@(map (fn [w] `(pending? ~w)) waits)) pending ~expr)
                  expr)
            pattern `(when-not (pending? ~raw) ~raw))
          (conj raws raw))))))


(defmacro if-loaded
  "`then`, with `bindings` bound as a `let`'s, once every value they bind has
  loaded; else `else`, or `pending` without one.

      (if-loaded [user   (observe uid load-user)
                  orders (observe (:id user) load-orders)]
        [:ul (for [o orders] [:li (:title o)])]
        [:p \"Loading…\"])

  A value has loaded once it isn't `pending`. A binding whose expression refers
  to one that hasn't isn't evaluated, and hasn't loaded either: a read that
  depends on another starts once that one has loaded, never with `pending`, or
  a value taken from it, among its args. One that refers to none is evaluated,
  so reads that don't depend on each other start in the same render.

  References are found by name, in the expression as written, so a name bound
  again inside it still counts, which errs toward waiting. `else` sees none of
  the bindings."
  ([bindings then]
   `(if-loaded ~bindings ~then pending))
  ([bindings then else]
   (when-not (and (vector? bindings) (even? (count bindings)))
     (throw (IllegalArgumentException. "if-loaded takes a vector of bindings, in pairs.")))
   (let [[bindings raws] (loaded-bindings (partition 2 bindings))]
     `(let [result# (let [~@bindings]
                      (if (or ~@(map (fn [r] `(pending? ~r)) raws)) ::unloaded ~then))]
        (if (identical? ::unloaded result#) ~else result#)))))
