(ns ^:no-doc co.multiply.tropical.internal.model
  "Calls, templates and frames shared by the island API and its runtime."
  (:require
    [co.multiply.tropical.names :as names]
    [dev.onionpancakes.chassis.core :as h]))


(declare write-frame!)


(deftype Call [slot render args island]
  h/Node
  (branch? [_] false)
  (children [_] nil)

  h/Token
  (append-fragment-to [_ _]
    (throw (IllegalStateException. (str "Island " slot " rendered outside the island runtime."))))
  (fragment [_]
    (throw (IllegalStateException. (str "Island " slot " rendered outside the island runtime.")))))


(defn ^:no-doc call-slot
  "The island's slot in its parent: its name, plus `.key` if it has a key."
  [^Call call]
  (.-slot call))


(defn ^:no-doc call-render
  [^Call call]
  (.-render call))


(defn ^:no-doc call-args
  [^Call call]
  (.-args call))


(defn ^:no-doc call-island
  "What the island's definition says of it, the same for every call:
  `{:name sym, :error-view f, :traces cache}`, its qualified name, its own error
  view if it has one, and the recent traces shared by its instances."
  [^Call call]
  (.-island call))


(defn ^:no-doc child-id
  "The id of the island in `slot` under the island `parent-id`; the root's
  (`parent-id` nil) is its slot."
  [parent-id slot]
  (if parent-id
    (str parent-id "/" slot)
    slot))


(defn element-id
  "The id of island `id`'s element in the page: an opaque digest of it, or the
  id itself while names are readable (`co.multiply.tropical.names`)."
  [id]
  (names/opaque id 16))


;; Where a child island goes in its parent's template.
(defrecord Hole [slot])


;; `parts`: strings and Holes, in document order.
;; `chars`: total length of the string parts.
(defrecord Template [parts chars])


;; `id`: the island's element id. `slots`: child slot -> child Frame.
(defrecord Frame [id template slots]
  h/Node
  (branch? [_] false)
  (children [_] nil)

  h/Token
  (append-fragment-to [this sb]
    (write-frame! sb this))
  (fragment [this]
    (str (write-frame! (StringBuilder.) this))))


(defn ^:no-doc write-frame!
  "Appends the HTML of `frame` to `sb`: its template's string parts, with each hole
  filled by the corresponding child frame. Returns `sb`."
  [^StringBuilder sb frame]
  (let [slots (:slots frame)]
    (run! (fn [part]
            (if (string? part)
              (StringBuilder/.append sb ^String part)
              (write-frame! sb (get slots (:slot part)))))
      (:parts (:template frame)))
    sb))


(defn ^:no-doc html
  "The full HTML of `frame`, children included."
  [frame]
  (str (write-frame! (StringBuilder.) frame)))


(defn frame-chars
  "Length of the HTML `frame` emits, computed from cached template sizes."
  [frame]
  (transduce (map frame-chars) + (:chars (:template frame)) (vals (:slots frame))))
