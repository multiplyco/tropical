(ns ^:no-doc co.multiply.tropical.internal.signal-patch
  "Composing pending JSON merge patches without changing their meaning.
  Some pairs must stay separate: merging a map after replacing its parent
  cannot be expressed by one patch against the original value.")


(defn- signal-keys
  "`signals` with its keys as their names in JSON, so that a keyword and a
  string naming one signal merge."
  [signals]
  (into {}
    (map (fn [[k v]]
           [(if (keyword? k) (str (symbol k)) (str k)) (cond-> v (map? v) signal-keys)]))
    signals))


(defn- merge-patch
  "The one patch that does what the signal patches `a` and then `b` do, as
  Datastar applies them (JSON merge patches), or `::apart` if none does. None
  does where `a` replaces a value, nil included, and `b` merges a map into
  what `a` put there: merged into one, the map would merge into the value `a`
  replaced."
  [a b]
  (reduce-kv (fn [acc k v]
               (cond
                 (not (contains? acc k)) (assoc acc k v)
                 (not (map? v)) (assoc acc k v)
                 (map? (get acc k)) (let [m (merge-patch (get acc k) v)]
                                      (if (identical? ::apart m) (reduced m) (assoc acc k m)))
                 :else (reduced ::apart)))
    a
    b))


(defn append
  "`pending`, the signal patches waiting for the client, with `signals` after
  them, merged into the last where one patch does both."
  [pending signals]
  (let [signals (signal-keys signals)
        merged  (some-> (peek pending) (merge-patch signals))]
    (if (or (nil? merged) (identical? ::apart merged))
      (conj (or pending []) signals)
      (conj (pop pending) merged))))
