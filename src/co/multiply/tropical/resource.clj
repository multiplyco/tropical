(ns co.multiply.tropical.resource
  "Shared, reference-counted stateful resources, such as subscriptions to a
  database or poll loops.

  There is one physical resource per key, however many sessions watch it. It
  opens on the first subscriber. After the last subscriber leaves, it lingers for
  `linger-ms` and then closes, unless someone subscribes again first. The linger
  absorbs remount churn: switching back and forth, navigation, and reconnects.

  A resource is opened by a subject, `(fn [emit!] undo)`, as in Missionary's
  `observe`. Keys are a call site and its args. Islands subscribe with
  `co.multiply.tropical.hooks/observe`, which
  holds a subscription for as long as the island's renders keep asking for it: a
  render that stops, or the island unmounting, releases it. Nobody calls
  `release!` by hand.

  The instances of shared states (`co.multiply.tropical.shared`) are resources
  too, under keys of their own: they open holding a value, with a linger of
  their state's, and change by its `swap!`, not by a subject.

  A resource whose task fails is closed: its value becomes `Failed`, so its
  readers throw, and the next subscriber to its key opens a new one.

  For a view of what is open, `use-summary` gives the registry, live, of the
  whole server or of what one session's islands hold, and `describe` says
  what opened each."
  (:require
    [clojure.tools.logging :as log]
    [co.multiply.quiescent :as q]
    [co.multiply.scoped :as scoped]
    [co.multiply.tropical.island :as island])
  (:import
    [co.multiply.tropical.signal Cell]
    [java.util.concurrent.atomic AtomicReference]))


(def linger-ms
  "How long, in ms, a resource lingers after its last reader leaves, unless it
  was opened with a `:linger-ms` of its own."
  5000)


;; A resource's value until its first publish.
(def ^:no-doc pending ::pending)


;; The value of a resource, or of a one-off task, that failed: readers throw `error`.
(defrecord Failed [error])


(defn ^:no-doc failed?
  [v]
  (instance? Failed v))


(defonce ^:private lock (Object.))


;; key -> {:subscribers n, :live? bool, :linger-ms n, :!value Cell, :task Task, :linger Task}
;; Changes only on lifecycle events, never on publish: values live in `:!value`.
;; `:!value` also identifies the entry: a key that closes and opens again gets a new one.
(defonce ^{:doc "The open resources, for display: an atom of a map keyed by
  resource, which changes only as a resource opens, lingers or closes. Watch
  it through `summary`: `(use-watch resource/!entries resource/summary)`."}
  !entries
  (atom {}))


(defonce ^{:doc "How many resources have opened and closed since the JVM started,
  as an atom of `{:opened :closed}`."}
  !totals
  (atom {:opened 0 :closed 0}))


;; What a resource's undo slot holds once it has closed: its `emit!` does nothing.
(def ^:private closed (Object.))


(defn- cancel!
  "Cancels `task`, compelled. A cancel made inside a task is that task's child:
  if the task settles first, as a linger's callback does at once, the cancel is
  itself cancelled and may never take effect."
  [task]
  (q/compel (q/cancel task)))


(defn- current?
  "Whether `!value` belongs to the entry for `key` now. Caller holds `lock`."
  [key !value]
  (identical? !value (get-in @!entries [key :!value])))


(defn- mark-live!
  [key !value]
  (locking lock
    (when (current? key !value)
      (swap! !entries assoc-in [key :live?] true))))


(defn- fail!
  "Closes the entry whose task failed with `e`. Its readers get `Failed`, and
  the next subscriber to `key` opens a new resource."
  [key !value e]
  (locking lock
    (when (current? key !value)
      (log/warn e "Resource failed:" key)
      (some-> (get-in @!entries [key :linger]) cancel!)
      (swap! !entries dissoc key)
      (swap! !totals update :closed inc)))
  (Cell/.reset !value (->Failed e)))


(defn- undo!
  [key undo]
  (try
    (undo)
    (catch Throwable t
      (log/warn t "Cleaning up resource failed:" key))))


(defn- start
  "Runs `subject` on a virtual thread of its own, so a subject that blocks while
  connecting holds up neither the registry nor the session that opened it, and
  in an empty scope, since the resource belongs to no reader. Returns the
  resource's task: it fails if the resource fails, and cancelling it closes the
  resource.

  `!undo` holds nil until the subject returns a cleanup fn, then that fn, and
  `closed` once the resource closes or fails. A subject that returns its cleanup
  fn after the resource closed calls it at once."
  [key subject emit! ^AtomicReference !undo]
  (scoped/with-scope {}
    (-> (q/task
          (let [undo (subject emit!)]
            (cond
              ;; A task the subject started is the resource's lifetime, and a
              ;; child of this one: cancelling this cancels it.
              (q/taskable? undo) undo

              (or (nil? undo) (fn? undo))
              (do (when (and undo (not (AtomicReference/.compareAndSet !undo nil undo)))
                    (undo! key undo))
                ;; Nothing to wait for: settles only when cancelled.
                (q/promise))

              :else
              (throw (ex-info "A resource's subject returns a fn, a task, or nil." {:key key :returned undo})))))
      (q/finally
        (fn [_ e cancelled?]
          (when (or e cancelled?)
            ;; Serialize closure with an emission's check-and-publish. Once
            ;; this finishes, no in-flight emit can overwrite fail!'s Failed.
            (let [undo (locking !undo (AtomicReference/.getAndSet !undo closed))]
              (when (fn? undo)
                (undo! key undo)))))))))


(defn- open!
  "Starts the physical resource for `key` and returns its entry, not yet
  registered. Caller holds `lock`, which a first `emit!` waits for, so the
  entry is registered by the time it is marked live. With an `init` other than
  `pending`, it opens live, holding `init`."
  [key subject {:keys [init] :or {init pending} :as opts}]
  (let [!value (Cell. init)
        !undo  (AtomicReference.)
        live?  (volatile! (not (identical? pending init)))
        emit!  (fn [v]
                 (let [first? (locking !undo
                                (when-not (identical? closed (AtomicReference/.get !undo))
                                  (Cell/.reset !value v)
                                  (when-not @live?
                                    (vreset! live? true)
                                    true)))]
                   ;; Registry work stays outside the publication lock: opening
                   ;; and closing hold the registry lock and may cancel tasks.
                   (when first? (mark-live! key !value)))
                 nil)
        ;; Compelled: the resource belongs to the registry, not to whichever
        ;; session happened to subscribe first.
        task   (q/compel (start key subject emit! !undo))]
    (log/debug "Resource opened:" key)
    (swap! !totals update :opened inc)
    {:subscribers 0
     :live?       @live?
     :linger-ms   (:linger-ms opts linger-ms)
     :!value      !value
     :task        task}))


(defn- watch-failure!
  "Closes the registered entry for `key` if its task fails. Compelled, like the
  task. Attached after registration, so a task that has already failed finds
  its entry."
  [key {:keys [task !value]}]
  (q/compel (q/err task #(fail! key !value %))))


(defn- close-if-idle!
  [key !value]
  (locking lock
    (when (and (current? key !value) (zero? (get-in @!entries [key :subscribers])))
      (log/debug "Resource closed:" key)
      ;; Forgotten, then cancelled: the teardown runs on other threads, and
      ;; whatever it does, or whoever waits on it, finds the registry without
      ;; it. Were it forgotten only once torn down, an `acquire!` meanwhile
      ;; would find it, and subscribe to a resource already cancelled.
      (let [task (get-in @!entries [key :task])]
        (swap! !entries dissoc key)
        (swap! !totals update :closed inc)
        (cancel! task)))))


(defn ^:no-doc acquire!
  "Registers a subscriber to `key`, opening the resource with `subject` if
  nobody holds it (`co.multiply.tropical.hooks/observe` has the contract).
  Returns the resource's value, a `Cell` (`co.multiply.tropical.signal`) holding
  `pending` until the first `emit!`; pass it to `release!`.

  `opts`, used if it opens: `:init`, a value it holds from the start, live, in
  place of `pending`; and `:linger-ms`, in place of `linger-ms`."
  ([key subject]
   (acquire! key subject nil))
  ([key subject opts]
   (locking lock
     (let [entry (get @!entries key)
           fresh (when-not entry (open! key subject opts))
           entry (or entry fresh)]
       (some-> (:linger entry) cancel!)
       (swap! !entries assoc key (-> entry (update :subscribers inc) (dissoc :linger)))
       (when fresh
         (watch-failure! key fresh))
       (:!value entry)))))


(defn ^:no-doc release!
  "Unregisters a subscriber to `key`, given the value cell `acquire!` returned.
  The last one out starts the linger. Releasing a resource that has since failed
  does nothing: it is already closed."
  [key !value]
  (locking lock
    (when (current? key !value)
      (let [{:keys [subscribers] :as entry} (update (get @!entries key) :subscribers dec)]
        (swap! !entries assoc key
          (cond-> entry
            (zero? subscribers)
            (assoc :linger (q/compel
                             (-> (q/sleep (:linger-ms entry linger-ms))
                               (q/then (fn [_] (close-if-idle! key !value))))))))))))


(defn ^:no-doc cell
  "The value cell of the resource open under `key`, lingering or not, or nil."
  [key]
  (get-in @!entries [key :!value]))


(defn ^:no-doc state
  [{:keys [subscribers live?]}]
  (cond
    (zero? subscribers) :lingering
    live? :live
    :else :connecting))


(defn summary
  "The open resources, for display: key -> `{:state :subscribers}`, the state
  `:connecting`, `:live` or `:lingering`. Of the registry, or of `entries`, a
  value it held."
  ([]
   (summary @!entries))
  ([entries]
   (into (sorted-map-by #(compare (str %1) (str %2)))
     (map (fn [[k e]] [k {:state (state e) :subscribers (:subscribers e)}]))
     entries)))


;; Describing ------------------------------------------------------------------


(defn ^:no-doc call-site
  "The site of an `observe` written in `ns` at `line` and `column`, if known:
  a symbol unique to this call, so two call sites never share a resource,
  even when a macro's expansions carry no position, and reloaded code opens
  anew. `describe` reads it back."
  [ns line column]
  (symbol (str ns) (str "observe" (when line (str "@" line ":" column)) "#" (gensym ""))))


(defn describe
  "What opened the resource under `key`, as data: for an `observe`,
  `{:kind :observe, :ns, :line, :column, :args}`, where `:ns` is a symbol,
  the position its form's, if it had one, and `:args` its arguments; for a
  shared state's instance (`co.multiply.tropical.shared`), `{:kind :shared,
  :state, :key}`, where `:state` is the state's qualified symbol. For a key of
  another shape, `{:kind :other, :key key}`."
  [key]
  (let [[site & more] key]
    (cond
      ;; `co.multiply.tropical.shared`'s, which requires this namespace.
      (and (= :co.multiply.tropical.shared/shared site) (= 2 (count more)))
      {:kind :shared :state (first more) :key (second more)}

      (and (symbol? site) (namespace site) (= 1 (count more)))
      (let [[_ line column] (re-find #"^observe@(\d+):(\d+)#" (name site))]
        (cond-> {:kind :observe :ns (symbol (namespace site)) :args (first more)}
          line (assoc :line (parse-long line) :column (parse-long column))))

      :else
      {:kind :other :key key})))


;; Watching ---------------------------------------------------------------------


(defn- held-by
  "The `summary` of the resources in `entries` whose value is among `held`,
  values a session's islands hold, each counting the holds among `held`."
  [entries held]
  (let [counts (frequencies (filter #(instance? Cell %) held))]
    (summary (into {}
               (keep (fn [[k e]]
                       (when-some [n (get counts (:!value e))]
                         [k (assoc e :subscribers n)])))
               entries))))


(defn use-summary
  "The open resources, as `summary` gives them, kept current: the island
  renders again as one opens, gains or loses a reader, lingers or closes.

  - `:server`, the default: every resource open on this server, each
    counting every island that reads it, in any session.
  - `:session`: the resources this session's islands hold, each counting
    those islands only. A resource the session no longer holds is gone from
    it, though it may linger for others.

      (let [open (resource/use-summary :session)]
        [:ul (for [[k {:keys [state subscribers]}] open]
               [:li (pr-str (resource/describe k)) \" \" (name state) \" \" subscribers])])

  The server's view holds every user's reads, their arguments among them,
  so show it only to whoever may see them all. The session's view holds what
  the page already reads. For a diagnostic: it is worked out again over the
  whole registry each time it changes. A hook: call it during the render."
  ([]
   (use-summary :server))
  ([scope]
   (case scope
     :server (island/use-watch !entries summary)
     :session (let [held (island/use-held)]
                (island/use-watch !entries #(held-by % (held))))
     (throw (IllegalArgumentException. (str "use-summary takes :server or :session, not " (pr-str scope) "."))))))


(comment
  @!entries
  @!totals
  (summary @!entries)
  (map describe (keys @!entries)))
