(ns ^:no-doc co.multiply.tropical.internal.frame
  "Compiling island output and planning patches between immutable frames."
  (:require
    [co.multiply.tropical.internal.model :as model]
    [dev.onionpancakes.chassis.core :as h])
  (:import
    [co.multiply.tropical.frame Splice]
    [co.multiply.tropical.internal.model Call Hole]
    [java.util ArrayList HashSet]))


(defn- with-id
  "Puts island `id`, and the attributes `more` the runtime adds, on the root
  element of `node`, wrapping a root that isn't one."
  [id node more]
  (if (and (vector? node) (keyword? (first node)))
    (let [[tag & body] node
          [attrs children] (if (map? (first body)) [(first body) (rest body)] [{} body])]
      (when (or (contains? attrs :id) (re-find #"#" (name tag)))
        (throw (IllegalArgumentException. "its root element gets the island's id; don't set one.")))
      (when-some [k (some #(when (contains? attrs %) %) (keys more))]
        (throw (IllegalArgumentException. (str "its root element gets " (name k) " from the signals it declares; "
                                            "don't set one."))))
      (into [tag (merge (assoc attrs :id (model/element-id id)) more)] children))
    [:div (assoc more :id (model/element-id id)) node]))


(defn- flush-part!
  "Adds what `sb` holds to `parts` as a string, if anything, and empties it."
  [^ArrayList parts ^StringBuilder sb]
  (when (pos? (StringBuilder/.length sb))
    (ArrayList/.add parts (StringBuilder/.toString sb))
    (StringBuilder/.setLength sb 0)))


(defn ^:no-doc compile-island
  "Serializes the hiccup `node` that island `id` rendered, once, with the island's
  id on its root. Returns `[template calls]`: the template's parts are strings
  and holes for child islands, and `calls` are the child islands in document
  order.
  Throws if two children share a slot, or the root sets its own id. The
  messages complete \"Island <id> failed to render: \".

  `more` are attributes the runtime adds to the root, such as the signals the
  render declared (`declare-signal`).

  A list island compiles a template with a hole per row on every render, so the
  walk collects into mutable lists, and checks slots as it goes, rather than
  building a collection per child."
  ([id node]
   (compile-island id node nil))
  ([id node more]
   (let [sb    (StringBuilder.)
         parts (ArrayList.)
         calls (ArrayList.)
         slots (HashSet.)]
     (h/reduce-node
       (fn [_ token]
         (if (instance? Call token)
           (let [slot (model/call-slot token)]
             (when-not (HashSet/.add slots slot)
               (throw (IllegalArgumentException. "it places two islands in one slot; give them distinct :key values.")))
             (flush-part! parts sb)
             (ArrayList/.add parts (model/->Hole slot))
             (ArrayList/.add calls token))
           (h/append-fragment-to token sb))
         nil)
       nil
       (with-id id node more))
     (flush-part! parts sb)
     [(model/->Template (vec parts) (transduce (comp (filter string?) (map count)) + parts))
      calls])))


(defn- hole?
  [part]
  (instance? Hole part))


(defn- inserts
  "How to insert the children `splice` added to `frame`, against `sent`: each
  run of them next to one another goes in one insert, after the kept child
  before it, or before the one after it, or, with neither, after the child it
  takes the place of (`Splice`), which is removed after. Nil if a run has none
  of these."
  [sent frame ^Splice splice]
  (let [parts   (:parts (:template frame))
        slots   (:slots frame)
        added   (.-added splice)
        anchors (.-anchors splice)
        n       (count parts)
        len     (alength ^ints added)
        child   #(get slots (:slot (nth parts %)))
        kept-id (fn [i] (when (and (< -1 i n) (hole? (nth parts i))) (:id (child i))))]
    (loop [k 0, acc []]
      (if (= k len)
        acc
        (let [start (aget added k)
              end   (long (loop [e (inc k)]
                            (if (and (< e len) (= (aget added e) (+ start (- e k)))) (recur (inc e)) e)))
              after (+ start (- end k))]
          (when-some [insert (if-some [id (kept-id (dec start))]
                               {:mode :after :target id}
                               (if-some [id (kept-id after)]
                                 {:mode :before :target id}
                                 (when-some [slot (aget ^objects anchors k)]
                                   {:mode :after :target (:id (get (:slots sent) slot))})))]
            (recur end (conj acc (assoc insert :frames (mapv child (range start after)))))))))))


(def ^:no-doc no-patches
  "Nothing to send: `patches` between a frame and itself."
  {:morph [] :insert [] :remove []})


(declare patches)


(defn- append-patches
  "Appends the nonempty categories of `more` to `acc`. The first child's
  immutable result can stand as it is, including through unchanged ancestors."
  [acc more]
  (if (identical? acc no-patches)
    more
    (cond-> acc
      (seq (:morph more)) (update :morph into (:morph more))
      (seq (:insert more)) (update :insert into (:insert more))
      (seq (:remove more)) (update :remove into (:remove more)))))


(defn- child-patches
  "The patches of the children of `frame` in `slots`, against those of `sent`."
  [sent frame slots]
  (reduce (fn [acc slot] (append-patches acc (patches (get (:slots sent) slot) (get (:slots frame) slot))))
    no-patches
    slots))


(defn- changed-slots
  "The slots of `frame`'s children that aren't the same frame in `sent`: few,
  in a wide island, so they are found without visiting the rest's patches."
  [sent frame]
  (let [before (:slots sent)]
    (persistent!
      (reduce-kv (fn [acc slot child] (if (identical? child (get before slot)) acc (conj! acc slot)))
        (transient [])
        (:slots frame)))))


(defn- splice
  "The patches between `sent` and `frame`, two frames of one island whose
  templates differ only by child islands added or removed (`Splice`): the added
  children inserted next to kept ones or where the removed ones were, the
  removed ones removed, and the kept ones that changed patched. Nil if the
  templates differ otherwise, or an added child has neither a kept one beside
  it nor a removed one in its place."
  [sent frame]
  (when-some [^Splice splice (Splice/of (:parts (:template sent)) (:parts (:template frame))
                               (:slots sent) (:slots frame))]
    (when-some [insert (inserts sent frame splice)]
      (append-patches
        {:morph [] :insert insert :remove (mapv #(:id (get (:slots sent) %)) (.-removed splice))}
        (child-patches sent frame (.-changed splice))))))


(defn ^:no-doc patches
  "What to send to bring a client showing `sent` up to `frame`, as
  `{:morph [frame ...], :insert [{:mode :after|:before, :target id,
  :frames [frame ...]} ...], :remove [id ...]}`:

  - `:morph`  the topmost islands whose own template changed, each morphed
              into the element with its id. An island with an identical
              template recurses into its children: its children are then the
              same islands, since their slots are part of the template.
  - `:insert` children added to an island whose template changed only by
              that, each run of them inserted after or before a kept sibling,
              so a growing list sends its new items, not the list; or after
              the child it replaces, so a child whose key changed sends itself,
              not its parent.
  - `:remove` the ids of children removed from such an island, after the
              inserts.

  A template that changed in any other way (a reorder, the island's own
  markup) morphs the whole island."
  [sent frame]
  (cond
    (identical? sent frame)
    no-patches

    (or (nil? sent) (not= (:id sent) (:id frame)))
    (assoc no-patches :morph [frame])

    (identical? (:template sent) (:template frame))
    (child-patches sent frame (changed-slots sent frame))

    :else
    (or (splice sent frame) (assoc no-patches :morph [frame]))))
