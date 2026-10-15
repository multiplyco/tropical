(ns ^:no-doc co.multiply.tropical.internal.browser-state
  "Naming and declaration invariants shared by browser-owned states."
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.names :as names]))


(defn signal-name
  "The name of the signal of the state `id`, a qualified symbol: an opaque
  digest of it, unless names are readable (`co.multiply.tropical.names`)."
  [id]
  (names/opaque (str/replace (str (namespace id) "__" (name id)) #"[^A-Za-z0-9_]" "_") 10))


(defn item-name
  "`item` as the name of a signal: a keyword's name with its namespace, or the
  string of anything else. Throws for one holding a `.`, which Datastar reads
  as a step into the signal."
  [item]
  (let [n (if (keyword? item) (str (symbol item)) (str item))]
    (when (str/includes? n ".")
      (throw (IllegalArgumentException. (str "An item's name can't hold a '.', as " (pr-str n) " does: "
                                          "Datastar reads it as a step into the signal."))))
    n))


(defn claim-shape!
  "Records that `state` is used with an item, or without, if `item` is nil;
  throws if it was used the other way."
  [kind state item]
  (let [shape (if item :items :single)]
    (when-not (= shape (swap! (:shape state) #(or % shape)))
      (throw (IllegalArgumentException.
               (str kind " state " (:id state) " is used " (if item "with an item and without" "without an item and with")
                 "; use it one way."))))))
