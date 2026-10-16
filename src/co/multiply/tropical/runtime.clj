(ns co.multiply.tropical.runtime
  "The island runtime of one session: which islands are mounted, what each one
  holds and reads, and how a frame is produced.

  An island renders when it is new, when its arguments changed, or when a value
  it read changed. Its children are then reconciled against what it placed:
  children with unchanged arguments keep their last output, and children it no
  longer places are unmounted, releasing what they held and unsubscribing from
  what they read. A frame visits only the islands that need rendering and their
  ancestors; every other subtree is reused as it is.

  Single-threaded: only the session's pump calls in. The session subscribes
  once to each source its islands read (`co.multiply.tropical.signal`). A change marks the
  subscription in the session's inbox, from whatever thread made it, and the
  pump passes the marked subscriptions to `frame!`."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [co.multiply.tropical.internal.failure :as failure]
    [co.multiply.tropical.internal.frame :as frame]
    [co.multiply.tropical.internal.model :as model]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.resource :as resource])
  (:import
    [co.multiply.tropical.island Js]
    [co.multiply.tropical.runtime Failure Instance ReadBuffer ReadBuffer$Read]
    [co.multiply.tropical.signal Inbox Sub]
    [java.util Collection HashSet Iterator Set]))


;; Instance holds one island's mutable bookkeeping, owned by the session pump.
;; Its committed reads and the next render's reads have separate reusable buffers:
;; an escaped render discards only its own acquisitions. Instances never appear in
;; frames, whose templates and child maps stay immutable for delivery and resyncs.


(defn ^:no-doc runtime
  "A runtime for the session `ctx` (`{:tab :uid :request}`, which
  `use-session` returns), subscribing through `inbox`.

  `:error-view`, `(fn [failure] hiccup)`, renders in place of an island whose
  render throws, unless the island has one of its own. It defaults to
  `island/error-view`, which says only that the island failed: what it shows
  reaches the client, and an exception's message can carry what the user
  shouldn't see. `:on-failure`, `(fn [failure])`, is called with each failure,
  whichever view renders it."
  ([ctx inbox]
   (runtime ctx inbox nil))
  ([ctx inbox {:keys [error-view on-failure]}]
   {:ctx        ctx
    :inbox      inbox
    :error-view error-view
    :on-failure on-failure
    :!instances (volatile! {})
    :!root-id   (volatile! nil)
    ;; source -> Sub, one per source however many islands read it. The Sub's
    ;; `readers` holds which islands do.
    :!subs      (volatile! {})
    ;; The URLs of the scripts renders declared, in the order first declared.
    ;; A page keeps a module it loaded, so they stay when their islands go.
    :!scripts   (volatile! [])}))


(defn- release-quietly!
  [id {:keys [release]}]
  (when release
    (try
      (release)
      (catch Throwable t
        ;; Cleanup is best-effort even for fatal throwables: an unmount has
        ;; already removed its instance, and an abandoned render's new holds
        ;; were never installed. Later disposal cannot recover those holds.
        ;; Render failures still obey fatal?; cleanup attempts every release.
        (log/warn t "Releasing a hold of island" id "failed.")))))


(defn- add-reader!
  "Registers island `id` as a reader of `source`, subscribing to it first if no
  island did. Returns the subscription."
  [{:keys [!subs inbox]} source id]
  (let [^Sub sub (or (get @!subs source)
                   (let [sub (Inbox/.subscribe inbox source)]
                     (vswap! !subs assoc source sub)
                     sub))]
    (set! (.-readers sub) (conj (or (.-readers sub) #{}) id))
    sub))


(defn- remove-reader!
  "Unregisters island `id` as a reader of `sub`, unsubscribing if it was the last."
  [{:keys [!subs]} ^Sub sub id]
  (let [ids (disj (.-readers sub) id)]
    (if (empty? ids)
      (do (set! (.-readers sub) nil)
        (Sub/.cancel sub)
        (when (identical? sub (get @!subs (.-source sub)))
          (vswap! !subs dissoc (.-source sub))))
      (set! (.-readers sub) ids))))


(defn- remove-reads!
  "Unregisters reads in `before` that aren't in `after`, once per subscription."
  [rt id ^ReadBuffer before ^ReadBuffer after]
  (dotimes [i (ReadBuffer/.subscriptionCount before)]
    (let [sub (ReadBuffer/.subscriptionAt before i)]
      (when (or (nil? after) (not (ReadBuffer/.contains after sub)))
        (remove-reader! rt sub id)))))


(defn- fatal?
  "Whether `t` leaves the JVM in no state to go on, as running out of memory
  does. A render's other throwables, an `AssertionError` from `assert` or a
  `:pre` among them, are the island's failure. A stack overflow is too, since
  its stack has unwound by the time it is caught."
  [t]
  (and (instance? VirtualMachineError t) (not (instance? StackOverflowError t))))


(defn- error-template
  "The template of the error view of island `id`, placed by `call`, whose render
  threw `t`, logged under `run` (`failure/failed!`): the island's own view, else
  the app's, else the default. One that throws gives way to the next, with its
  failures logged independently under the same reference."
  [rt id call t ^Failure run]
  (let [{island-name :name, :keys [error-view]} (model/call-island call)
        failure {:ref        (.-reference run)
                 :error      t
                 :island     island-name
                 :path       id
                 :element-id (model/element-id id)
                 :args       (model/call-args call)
                 :session    (:ctx rt)}]
    (when-some [on-failure (:on-failure rt)]
      (on-failure failure))
    (loop [[view & more] (distinct (remove nil? [error-view (:error-view rt) island/error-view]))]
      (if-not more
        (frame/compile-island id (view failure))
        (or (try
              (let [compiled (frame/compile-island id (view failure))]
                (Failure/.viewRendered run view)
                compiled)
              (catch Throwable t2
                (when (fatal? t2) (throw t2))
                (failure/view-failed! run view t2)
                nil))
          (recur more))))))


(defn- signals-js
  "The JavaScript object of the signals a render declared, `signals`: JSON, with
  each `island/js` value as its expression."
  [signals]
  (cond
    (instance? Js signals) (str "(" (.-expr ^Js signals) ")")
    (map? signals) (str "{"
                     (str/join "," (map (fn [[k v]]
                                          (str (island/json-js (if (keyword? k) (str (symbol k)) (str k))) ":"
                                            (signals-js v)))
                                     signals))
                     "}")
    :else (island/json-js signals)))


(defn- same-slots?
  "Whether the child islands `calls` are in the same slots, in the same order, as
  `before`, the last render's."
  [before calls]
  (let [n (count calls)]
    (and (some? before)
      (= n (count before))
      (loop [i 0]
        (or (= i n)
          (and (= (model/call-slot (nth before i)) (model/call-slot (nth calls i)))
            (recur (inc i))))))))


(defn- read!
  "Subscribes before reading, recording the read even if deref or selection
  throws. A failed read stays owned and retries on the next invalidation."
  [rt id ^ReadBuffer reads source select]
  (let [sub (add-reader! rt source id)]
    (try
      (let [v (select @sub)]
        (ReadBuffer/.add reads sub select v false)
        v)
      (catch Throwable t
        (ReadBuffer/.add reads sub select nil true)
        (throw t)))))


(defn- held-values
  "What every island of `rt` holds, including what `inst` has acquired in this
  render, before its holds (or the instance itself) have been committed. Old
  holds remain until released after the render, which invalidates the
  registry. On the session's thread only, which owns the instances."
  [rt ^Instance inst acquired]
  (into []
    (comp (mapcat vals) (map :value))
    (cons (merge (.-holds inst) acquired)
      (keep (fn [^Instance other]
              (when-not (identical? inst other) (.-holds other)))
        (vals @(:!instances rt))))))


(defn- render-hooks
  "The hooks of one render, recording what it holds, reads and declares."
  [rt ^Instance inst !holds reads !signals]
  (let [id (.-id inst)
        old-holds (.-holds inst)]
    {:session  (:ctx rt)
     :state    (.-state inst)
     :hold!    (fn [k acquire idempotent]
                 (if-some [held (get @!holds k)]
                   (if idempotent
                     (:value held)
                     (throw (IllegalArgumentException.
                              (str "it asks for the hold " (pr-str k) " twice in one render."))))
                   (let [held (or (get old-holds k) (acquire))]
                     (vswap! !holds assoc k held)
                     (:value held))))
     ;; Subscribe before reading, so no change between the two is missed.
     :read!    (fn [source select]
                 (read! rt id reads source select))
     :declare! (fn [path value]
                 (vswap! !signals assoc-in path value)
                 nil)
     :held     (fn [] (held-values rt inst @!holds))
     :signals! (fn [signals]
                 (when-not (map? signals)
                   (throw (IllegalArgumentException.
                            (str "Signals to patch are a map, not " (pr-str signals) "."))))
                 (Inbox/.post (:inbox rt) [:signals id (.-state inst) signals])
                 nil)
     :script!  (fn [url]
                 (let [!scripts (:!scripts rt)]
                   (when-not (some #(= url %) @!scripts)
                     (vswap! !scripts conj url)))
                 nil)}))


(defn- abandon-render!
  "Releases acquisitions not owned by the previous instance when rendering
  escapes its error boundary. The previous instance is still owned by rt."
  [rt ^Instance inst acquired ^ReadBuffer read]
  (try
    (run! (fn [[k held]] (when-not (contains? (.-holds inst) k) (release-quietly! (.-id inst) held))) acquired)
    (remove-reads! rt (.-id inst) read (.-reads inst))
    (finally (ReadBuffer/.clear read))))


(defn- render-island
  "Renders `call` with hooks bound and settles what the render held and read
  against the previous render of `inst`, updating it in place. A render
  that throws renders the error view instead, keeping only what it held and read
  before throwing, unless what it threw is `fatal?`. Failing the same way as
  the last render keeps its reference (`failure/failed!`)."
  [rt ^Instance inst call]
  (let [id        (.-id inst)
        !holds    (volatile! {})
        ;; The swap after a render, or abandonment, already cleared this buffer.
        reads     (.-nextReads inst)
        !signals  (volatile! nil)
        old-failure (.-failure inst)
        old-holds (.-holds inst)
        hooks     (render-hooks rt inst !holds reads !signals)
        [template calls] (binding [island/*render* hooks]
                           (try
                             (let [node (apply (model/call-render call) (model/call-args call))
                                   compiled (frame/compile-island id node
                                              (when-some [signals @!signals]
                                                {:data-signals__ifmissing (signals-js signals)}))]
                               (set! (.-failure inst) nil)
                               compiled)
                             (catch Throwable t
                               (try
                                 (when (fatal? t) (throw t))
                                 (let [run (failure/failed! (.-failure inst) t (model/call-island call) id
                                             (model/element-id id))]
                                   (set! (.-failure inst) run)
                                   (error-template rt id call t run))
                                 (catch Throwable escaped
                                   (abandon-render! rt inst @!holds reads)
                                   (throw escaped))))))
        holds     @!holds
        kept?     (same-slots? (.-calls inst) calls)
        child-ids (if kept?
                    (.-childIds inst)
                    (let [slots (:slots (.-frame inst))]
                      (mapv (fn [call]
                              (let [slot (model/call-slot call)]
                                (or (:id (get slots slot)) (model/child-id id slot))))
                        calls)))]
    (run! (fn [[k held]] (when-not (contains? holds k) (release-quietly! id held))) old-holds)
    (remove-reads! rt id (.-reads inst) reads)
    (set! (.-render inst) (model/call-render call))
    (set! (.-args inst) (model/call-args call))
    (set! (.-holds inst) holds)
    (set! (.-pending inst) (ReadBuffer/.hasPending reads resource/pending))
    (Instance/.commitReads inst)
    ;; Equal output keeps the old template, so nothing is patched.
    (when-not (= (:parts template) (:parts (.-template inst)))
      (set! (.-template inst) template))
    (set! (.-calls inst) calls)
    (set! (.-childIds inst) child-ids)
    (set! (.-kept inst) kept?)
    (set! (.-renders inst) (inc (.-renders inst)))
    (when (and old-failure (nil? (.-failure inst)))
      (failure/ended! old-failure "rendered again"))
    inst))


(defn- unmount!
  [rt ^Instance inst]
  (run! #(release-quietly! (.-id inst) (val %)) (.-holds inst))
  (remove-reads! rt (.-id inst) (.-reads inst) nil)
  (when-some [run (.-failure inst)]
    (failure/ended! run "was unmounted")
    (set! (.-failure inst) nil))
  (set! (.-holds inst) {})
  (ReadBuffer/.clear (.-reads inst))
  (ReadBuffer/.clear (.-nextReads inst)))


(defn- unmount-tree!
  "Unmounts island `id` and the islands it placed."
  [rt id]
  (when-let [^Instance inst (get @(:!instances rt) id)]
    (vswap! (:!instances rt) dissoc id)
    (run! #(unmount-tree! rt %) (.-childIds inst))
    (unmount! rt inst)))


(defn- changed?
  "Whether `call` differs from what `inst` last rendered."
  [^Instance inst call]
  (or (nil? inst)
    (not (identical? (.-render inst) (model/call-render call)))
    (not= (.-args inst) (model/call-args call))))


(defn- reconcile
  "The current frame of `call`, the island `id` placed by island `parent`.
  Renders it if it is new, changed or `dirty`, and visits its children if it
  rendered or is an ancestor of an island that must (`on-path`). Otherwise its
  last frame stands."
  [rt id call parent dirty on-path]
  (let [^Instance inst (get @(:!instances rt) id)]
    (if (and inst (not (changed? inst call)) (not (contains? on-path id)))
      (.-frame inst)
      (let [fresh?  (nil? inst)
            render? (or (changed? inst call) (contains? dirty id))
            before  (when inst (.-childIds inst))
            before-calls (when inst (.-calls inst))
            ^Instance inst (or inst (Instance. id parent))
            _       (when render? (render-island rt inst call))
            ;; Existing instances already own the render's committed holds and
            ;; reads. Install a new instance before a child can abort the frame,
            ;; so disposal sees current ownership even without a finished frame.
            _       (when fresh? (vswap! (:!instances rt) assoc id inst))
            old     (.-frame inst)
            kept?   (and old (or (not render?) (.-kept inst)))
            calls   (.-calls inst)
            ids     (.-childIds inst)
            n       (count calls)
            ;; Keep surviving child frames and their map, even across inserts,
            ;; removals and reorders. The template records their document order.
            slots   (if kept?
                      (loop [i 0, slots (:slots old)]
                        (if (= i n)
                          slots
                          (let [child-id (nth ids i)]
                            ;; Without a new parent render, its calls are the
                            ;; same: only children on a changed path need a visit.
                            (if (and (not render?) (not (contains? on-path child-id)))
                              (recur (inc i) slots)
                              (let [call  (nth calls i)
                                    slot  (model/call-slot call)
                                    child (reconcile rt child-id call id dirty on-path)]
                                (recur (inc i) (if (identical? child (get slots slot)) slots (assoc slots slot child))))))))
                      (let [slots (loop [i 0, slots (transient (or (:slots old) {}))]
                                    (if (= i n)
                                      slots
                                      (let [call  (nth calls i)
                                            slot  (model/call-slot call)
                                            child (reconcile rt (nth ids i) call id dirty on-path)]
                                        (recur (inc i) (if (identical? child (get slots slot)) slots (assoc! slots slot child))))))
                            ;; Unmount after visiting the new children, in the
                            ;; previous document order. Old frames stay immutable.
                            slots (if (seq before)
                                    (let [placed (HashSet. ^Collection ids)]
                                      (reduce-kv (fn [slots i child-id]
                                                   (if (HashSet/.contains placed child-id)
                                                     slots
                                                     (do (unmount-tree! rt child-id)
                                                       (dissoc! slots (model/call-slot (nth before-calls i))))))
                                        slots before))
                                    slots)]
                        (persistent! slots)))
            frame   (if (and old (identical? (:template old) (.-template inst)) (identical? slots (:slots old)))
                      old
                      (model/->Frame id (.-template inst) slots))]
        (set! (.-frame inst) frame)
        frame))))


(defn- stale?
  "Whether a read's selected value differs from what the render saw."
  [^ReadBuffer$Read read]
  (or (.-failed read)
    (try
      (not= ((.-select read) @(.-sub read)) (.-seen read))
      (catch Throwable t
        (when (fatal? t) (throw t))
        true))))


(defn- stale-read?
  "Whether any of `reads` from `sub` would now select a different value."
  [^ReadBuffer reads sub]
  (loop [i 0]
    (when (< i (ReadBuffer/.size reads))
      (let [read (ReadBuffer/.get reads i)]
        (if (and (identical? sub (.-sub read)) (stale? read))
          true
          (recur (inc i)))))))


(defn- dirty-islands
  "Ids of islands that read one of the `changed` subscriptions and would now see
  a different value."
  [rt changed]
  (let [instances @(:!instances rt)]
    (persistent!
      (reduce (fn [dirty ^Sub sub]
                (if-some [readers (.-readers sub)]
                  ;; A shared source can have many readers in one session.
                  ;; Iterate directly, without a lazy seq and pair per reader.
                  (let [it (Set/.iterator readers)]
                    (loop [dirty dirty]
                      (if (Iterator/.hasNext it)
                        (let [id (Iterator/.next it)]
                          (recur (if (stale-read? (.-reads ^Instance (get instances id)) sub)
                                   (conj! dirty id)
                                   dirty)))
                        dirty)))
                  dirty))
        (transient #{}) changed))))


(defn- with-ancestors
  [rt ids]
  (let [instances @(:!instances rt)]
    (loop [todo (seq ids) acc (transient #{})]
      (if-let [[id & more] todo]
        (if (contains? acc id)
          (recur more acc)
          (recur (if-some [parent (some-> ^Instance (get instances id) .-parent)] (cons parent more) more)
            (conj! acc id)))
        (persistent! acc)))))


(defn ^:no-doc frame!
  "Brings the island tree under `root` (a `Call`) up to date and returns its
  frame. `changed` holds the subscriptions the inbox marked since the last call.
  If this throws, `rt` may hold incomplete frames: call `dispose!`, not `frame!`
  again. The session disposes its runtime when its pump fails."
  [rt root changed]
  (let [dirty   (dirty-islands rt changed)
        root-id (model/call-slot root)
        frame   (reconcile rt root-id root nil dirty (with-ancestors rt dirty))]
    (when-not (contains? #{nil root-id} @(:!root-id rt))
      (unmount-tree! rt @(:!root-id rt)))
    (vreset! (:!root-id rt) root-id)
    frame))


(defn ^:no-doc dispose!
  "Unmounts every island."
  [rt]
  (run! #(unmount! rt %) (vals @(:!instances rt)))
  (vreset! (:!instances rt) {})
  ;; Also cover subscriptions acquired by an interrupted render before its
  ;; instance could be installed. Each remaining subscription is cancelled once.
  (doseq [^Sub sub (vals @(:!subs rt))]
    (set! (.-readers sub) nil)
    (Sub/.cancel sub))
  (vreset! (:!subs rt) {})
  (vreset! (:!root-id rt) nil))


(defn ^:no-doc pending?
  "Whether a mounted island's last render read a resource still waiting on its
  first value (`hooks/pending`)."
  [rt]
  (boolean (some (fn [^Instance inst] (.-pending inst)) (vals @(:!instances rt)))))


(defn ^:no-doc scripts
  "The URLs of the scripts the islands declared (`island/declare-script`), in
  the order first declared."
  [rt]
  @(:!scripts rt))


(defn render-counts
  "Island id -> how many times it rendered, over the mounted islands."
  [rt]
  (into (sorted-map) (map (fn [[id ^Instance inst]] [id (.-renders inst)])) @(:!instances rt)))


(defn ^:no-doc mounted
  "Ids of the mounted islands."
  [rt]
  (set (keys @(:!instances rt))))


(defn ^:no-doc held
  "What the mounted island `id` holds under `k` (`island/use-hold`), or nil."
  [rt id k]
  (when-some [^Instance inst (get @(:!instances rt) id)]
    (get-in (.-holds inst) [k :value])))


(defn ^:no-doc mounted-as?
  "Whether the island `id` is mounted, as the instance whose state is
  `!state`: not one mounted again under its id since."
  [rt id !state]
  (identical? !state (some-> ^Instance (get @(:!instances rt) id) .-state)))
