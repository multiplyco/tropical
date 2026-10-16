(ns ^:no-doc co.multiply.tropical.internal.browser-state
  "Naming and declaration invariants shared by browser-owned states, and the
  watch both stores' `observe` reads the page's value through."
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.action :as action]
    [co.multiply.tropical.internal.browser-code :as browser-code]
    [co.multiply.tropical.island :as island]
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


(defn observe
  "The value the page sends of its signal at `path`, a vector of names, for
  the stores' `observe`: `value` until it sends one, then the latest, through
  `read`, a fn of the JSON it sent. The island renders again when it changes.

  A hold under `k`, idempotent, mints a token bound to the session's user, as
  an action's, and the island's root declares the watch the body's handler
  sends to (`watch.js`), `_watch.<token>`: `[path ms]`, or with `known?`,
  `[path ms value]`, where the server renders the page's signal as `value`,
  and the page sends it only once it holds another. The handler sends as the
  watch appears, and `ms` after each change. Sends carry a positive integer
  `sequence`, increasing per token: an older or repeated send cannot replace
  a newer value, regardless of request order. Once the island stops reading,
  the token is revoked, and the next send's 404 drops the watch."
  [k path {:keys [ms read value known?]}]
  (let [{:keys [uid tab]} (island/use-session)
        {:keys [token !sample !handler]}
        (island/use-hold k
          (fn []
            (let [minted (action/mint uid tab)]
              (update minted :value assoc :!sample (atom {:sequence 0 :value value}))))
          {:idempotent true})]
    (reset! !handler (fn [body]
                       (let [sequence (get body "sequence")]
                         (when (pos-int? sequence)
                           (let [value (read (get body "value"))]
                             (swap! !sample (fn [sample]
                                              (if (> sequence (:sequence sample))
                                                {:sequence sequence :value value}
                                                sample))))))
                       nil))
    (island/declare-signal ["_watch" token] (cond-> [path ms] known? (conj value)))
    (island/use-watch !sample :value)))


(def ^:private watch-program
  (browser-code/program "watch.js"))


(defn watch-js
  "The statements of the body's signal-patch handler (`ui/page-attrs`) that
  send each watched value to its `observe`: as its watch appears, and after
  each change."
  []
  (browser-code/call watch-program ["$" "patch" (island/json-js {:globalName "__tropicalWatch"})]))
