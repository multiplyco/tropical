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
    [co.multiply.tropical.internal.frame :as frame]
    [co.multiply.tropical.internal.model :as model]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.resource :as resource])
  (:import
    [co.multiply.tropical.island Js]
    [co.multiply.tropical.signal Inbox Sub]
    [java.util.concurrent ThreadLocalRandom]))


;; Instance: what the runtime keeps per mounted island, keyed by its id.
;;   :id        its element id; :parent its parent's, nil for the root
;;   :render    the render fn, :args its last arguments
;;   :template  the last template; :frame the last frame
;;   :calls     the child islands it placed, as Calls, in document order
;;   :child-ids their element ids, in the same order
;;   :kept?     whether its last render placed the same children, in the same order, as the one before
;;   :holds     hold key -> {:value v, :release f}
;;   :reads     [{:sub s, :select f, :seen v}], or :failed? true when selection threw
;;   :pending?  whether the last render read a resource still waiting on its first value
;;   :!state    the atom behind `use-state`
;;   :renders   how many times it rendered


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
      (catch Exception e
        (log/warn e "Releasing a hold of island" id "failed.")))))


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


(defn- read-subs
  [reads]
  (into #{} (map :sub) reads))


(defn- fatal?
  "Whether `t` leaves the JVM in no state to go on, as running out of memory
  does. A render's other throwables, an `AssertionError` from `assert` or a
  `:pre` among them, are the island's failure. A stack overflow is too, since
  its stack has unwound by the time it is caught."
  [t]
  (and (instance? VirtualMachineError t) (not (instance? StackOverflowError t))))


(def ^:private ref-chars
  "Crockford's base 32: digits and letters, without the I, L, O and U that read
  as other characters."
  "0123456789ABCDEFGHJKMNPQRSTVWXYZ")


(defn- reference
  "A failure's reference: 40 random bits, as `4KF7-Q2XM`, short enough to read
  out."
  []
  (let [r  (ThreadLocalRandom/current)
        sb (StringBuilder. 9)]
    (dotimes [i 8]
      (when (= i 4)
        (StringBuilder/.append sb \-))
      (StringBuilder/.append sb (String/.charAt ref-chars (ThreadLocalRandom/.nextInt r 32))))
    (str sb)))


(defn- error-template
  "Logs the failure of island `id`, placed by `call`, whose render threw `t`, and
  returns the template of its error view: the island's own, else the app's,
  else the default. One that throws gives way to the next."
  [rt id call t]
  (let [{island-name :name, :keys [error-view]} (model/call-island call)
        failure {:ref        (reference)
                 :error      t
                 :island     island-name
                 :path       id
                 :element-id (model/element-id id)
                 :args       (model/call-args call)
                 :session    (:ctx rt)}]
    (log/error t (str "Island " id " (" island-name ", element " (:element-id failure) ") failed to render. "
                   "Reference " (:ref failure) "."))
    (when-some [on-failure (:on-failure rt)]
      (on-failure failure))
    (loop [[view & more] (distinct (remove nil? [error-view (:error-view rt) island/error-view]))]
      (if-not more
        (frame/compile-island id (view failure))
        (or (try
              (frame/compile-island id (view failure))
              (catch Throwable t2
                (when (fatal? t2) (throw t2))
                (log/error t2 (str "The error view of island " id " failed to render. Reference " (:ref failure) "."))
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
  [rt id !reads source select]
  (let [sub (add-reader! rt source id)]
    (try
      (let [v (select @sub)]
        (vswap! !reads conj {:sub sub :select select :seen v})
        v)
      (catch Throwable t
        (vswap! !reads conj {:sub sub :select select :failed? true})
        (throw t)))))


(defn- render-hooks
  "The hooks of one render, recording what it holds, reads and declares."
  [rt {:keys [id] :as inst} !holds !reads !signals]
  (let [old-holds (:holds inst)]
    {:session  (:ctx rt)
     :state    (:!state inst)
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
                 (read! rt id !reads source select))
     :declare! (fn [path value]
                 (vswap! !signals assoc-in path value)
                 nil)
     :signals! (fn [signals]
                 (when-not (map? signals)
                   (throw (IllegalArgumentException.
                            (str "Signals to patch are a map, not " (pr-str signals) "."))))
                 (Inbox/.post (:inbox rt) [:signals id (:!state inst) signals])
                 nil)
     :script!  (fn [url]
                 (let [!scripts (:!scripts rt)]
                   (when-not (some #(= url %) @!scripts)
                     (vswap! !scripts conj url)))
                 nil)}))


(defn- abandon-render!
  "Releases acquisitions not owned by the previous instance when rendering
  escapes its error boundary. The previous instance is still owned by rt."
  [rt {:keys [id holds reads]} acquired read]
  (run! (fn [[k held]] (when-not (contains? holds k) (release-quietly! id held))) acquired)
  (run! #(remove-reader! rt % id) (remove (read-subs reads) (read-subs read))))


(defn- render-island
  "Renders `call` with hooks bound and settles what the render held and read
  against the previous render of `inst`. Returns the updated instance. A render
  that throws renders the error view instead, keeping only what it held and read
  before throwing, unless what it threw is `fatal?`."
  [rt {:keys [id] :as inst} call]
  (let [!holds    (volatile! {})
        !reads    (volatile! [])
        !signals  (volatile! nil)
        old-holds (:holds inst)
        hooks     (render-hooks rt inst !holds !reads !signals)
        [template calls] (binding [island/*render* hooks]
                           (try
                             (let [node (apply (model/call-render call) (model/call-args call))]
                               (frame/compile-island id node
                                 (when-some [signals @!signals]
                                   {:data-signals__ifmissing (signals-js signals)})))
                             (catch Throwable t
                               (try
                                 (when (fatal? t) (throw t))
                                 (error-template rt id call t)
                                 (catch Throwable escaped
                                   (abandon-render! rt inst @!holds @!reads)
                                   (throw escaped))))))
        holds     @!holds
        reads     @!reads
        kept?     (same-slots? (:calls inst) calls)]
    (run! (fn [[k held]] (when-not (contains? holds k) (release-quietly! id held))) old-holds)
    (run! #(remove-reader! rt % id) (remove (read-subs reads) (read-subs (:reads inst))))
    (assoc inst
      :render (model/call-render call)
      :args (model/call-args call)
      :holds holds
      :reads reads
      :pending? (boolean (some #(identical? resource/pending (:seen %)) reads))
      ;; Equal output keeps the old template, so nothing is patched.
      :template (if (= (:parts template) (:parts (:template inst))) (:template inst) template)
      :calls calls
      :child-ids (if kept? (:child-ids inst) (mapv #(model/child-id id (model/call-slot %)) calls))
      :kept? kept?
      :renders (inc (:renders inst 0)))))


(defn- unmount!
  [rt {:keys [id holds reads]}]
  (run! #(release-quietly! id (val %)) holds)
  (run! #(remove-reader! rt % id) (read-subs reads)))


(defn- unmount-tree!
  "Unmounts island `id` and the islands it placed."
  [rt id]
  (when-let [inst (get @(:!instances rt) id)]
    (vswap! (:!instances rt) dissoc id)
    (run! #(unmount-tree! rt %) (:child-ids inst))
    (unmount! rt inst)))


(defn- changed?
  "Whether `call` differs from what `inst` last rendered."
  [inst call]
  (or (nil? inst)
    (not (identical? (:render inst) (model/call-render call)))
    (not= (:args inst) (model/call-args call))))


(defn- reconcile
  "The current frame of `call`, the island `id` placed by island `parent`.
  Renders it if it is new, changed or `dirty`, and visits its children if it
  rendered or is an ancestor of an island that must (`on-path`). Otherwise its
  last frame stands."
  [rt id call parent dirty on-path]
  (let [inst (get @(:!instances rt) id)]
    (if (and inst (not (changed? inst call)) (not (contains? on-path id)))
      (:frame inst)
      (let [render? (or (changed? inst call) (contains? dirty id))
            before  (:child-ids inst)
            inst    (or inst {:id id :parent parent :!state (atom {})})
            inst    (if render? (render-island rt inst call) inst)
            ;; Rendering has already acquired and released holds. Record their
            ;; current owner before a child can abort this frame: disposal must
            ;; release the new holds, never the ones just released.
            _       (when render? (vswap! (:!instances rt) assoc id inst))
            old     (:frame inst)
            ;; The same children as the last frame's: start from its map of child
            ;; frames, which then changes only where a child's frame did.
            kept?   (and old (or (not render?) (:kept? inst)))
            calls   (:calls inst)
            ids     (:child-ids inst)
            n       (count calls)
            slots   (if kept?
                      (loop [i 0, slots (:slots old)]
                        (if (= i n)
                          slots
                          (let [call  (nth calls i)
                                slot  (model/call-slot call)
                                child (reconcile rt (nth ids i) call id dirty on-path)]
                            (recur (inc i) (if (identical? child (get slots slot)) slots (assoc slots slot child))))))
                      (loop [i 0, slots (transient {})]
                        (if (= i n)
                          (persistent! slots)
                          (let [call (nth calls i)]
                            (recur (inc i)
                              (assoc! slots (model/call-slot call) (reconcile rt (nth ids i) call id dirty on-path)))))))
            frame   (if (and old (identical? (:template old) (:template inst)) (identical? slots (:slots old)))
                      old
                      (model/->Frame id (:template inst) slots))]
        (when (and render? (not kept?) (seq before))
          (let [placed (set ids)]
            (run! (fn [child-id] (when-not (contains? placed child-id) (unmount-tree! rt child-id))) before)))
        (vswap! (:!instances rt) assoc id (assoc inst :frame frame))
        frame))))


(defn- stale?
  "Whether a read's selected value differs from what the render saw."
  [{:keys [sub select seen failed?]}]
  (or failed?
    (try
      (not= (select @sub) seen)
      (catch Throwable t
        (when (fatal? t) (throw t))
        true))))


(defn- dirty-islands
  "Ids of islands that read one of the `changed` subscriptions and would now see
  a different value."
  [rt changed]
  (let [instances @(:!instances rt)]
    (into #{}
      (comp (mapcat (fn [^Sub sub] (map #(vector sub %) (.-readers sub))))
        (filter (fn [[sub id]]
                  (some #(and (identical? sub (:sub %)) (stale? %)) (:reads (get instances id)))))
        (map second))
      changed)))


(defn- with-ancestors
  [rt ids]
  (let [instances @(:!instances rt)]
    (loop [todo (seq ids) acc (transient #{})]
      (if-let [[id & more] todo]
        (if (contains? acc id)
          (recur more acc)
          (recur (if-some [parent (:parent (get instances id))] (cons parent more) more)
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
  (boolean (some :pending? (vals @(:!instances rt)))))


(defn ^:no-doc scripts
  "The URLs of the scripts the islands declared (`island/declare-script`), in
  the order first declared."
  [rt]
  @(:!scripts rt))


(defn render-counts
  "Island id -> how many times it rendered, over the mounted islands."
  [rt]
  (into (sorted-map) (map (fn [[id inst]] [id (:renders inst)])) @(:!instances rt)))


(defn ^:no-doc mounted
  "Ids of the mounted islands."
  [rt]
  (set (keys @(:!instances rt))))


(defn ^:no-doc held
  "What the mounted island `id` holds under `k` (`island/use-hold`), or nil."
  [rt id k]
  (get-in @(:!instances rt) [id :holds k :value]))


(defn ^:no-doc mounted-as?
  "Whether the island `id` is mounted, as the instance whose state is
  `!state`: not one mounted again under its id since."
  [rt id !state]
  (identical? !state (:!state (get @(:!instances rt) id))))
