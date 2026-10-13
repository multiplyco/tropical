(ns demo.state
  "Stand-in for the database: application state shared by every session.

  - `feed` and `vault` stand in for database subscriptions, read with
    `observe`: a ticker loop each, after a simulated connection delay.
  - `describe-topic` stands in for a one-off query, also read with `observe`.
  - The atoms stand in for state the app writes to its database; islands
    read them with `use-watch`. A topic's page can move to another URL
    (`move!`)."
  (:require
    [clojure.string :as str]
    [co.multiply.quiescent :as q])
  (:import
    [java.time LocalTime]
    [java.time.format DateTimeFormatter]))


(def ^:private reading-clock (DateTimeFormatter/ofPattern "HH:mm:ss.SSS"))


(defn- ticker
  "A simulated subscription: after a `connect-ms` handshake, a loop in a
  Quiescent task emits a new reading every `period-ms` until the task is
  cancelled. Returns the task, which `observe` cancels when the resource
  closes."
  [emit! {:keys [connect-ms period-ms]}]
  (q/task
    (Thread/sleep (long connect-ms))
    (loop [n 1]
      (emit! {:n       n
              :reading (rand-int 1000)
              :at      (LocalTime/.format (LocalTime/now) reading-clock)})
      (Thread/sleep (long period-ms))
      (recur (inc n)))))


(def topics
  {"alpha" {:connect-ms 50 :period-ms 1000 :about "Steady"}
   "beta"  {:connect-ms 600 :period-ms 400 :about "Fast"}
   "gamma" {:connect-ms 900 :period-ms 2500 :about "Slow"}})


(defonce ^{:doc "Topic -> the name in its page's URL, for each topic moved from its own (`move!`)."}
  !paths
  (atom {}))


(defn path-name
  "The name in the URL of `topic`'s page, given `paths`, a value of `!paths`."
  [paths topic]
  (get paths topic topic))


(defn topic-at
  "The topic whose page is at the URL naming `s` now, given `paths`; nil if
  none is."
  [paths s]
  (some #(when (= s (path-name paths %)) %) (keys topics)))


(defn move!
  "Moves `topic`'s page to the URL naming `s`, as an item moved into another
  group gets a new URL. Its open tabs follow it. `(move! topic topic)` moves it
  back."
  [topic s]
  (when-not (and (contains? topics topic) (re-matches #"[a-z0-9-]+" s))
    (throw (IllegalArgumentException. "move! takes a topic, and a name of lowercase letters, digits and dashes.")))
  (swap! !paths (fn [paths]
                  (when-some [other (topic-at paths s)]
                    (when (not= other topic)
                      (throw (IllegalArgumentException. (str "Topic " other "'s page is at " s ".")))))
                  (assoc paths topic s)))
  nil)


(defn feed
  "For `observe`: the readings of `topic`'s feed."
  [emit! topic]
  (ticker emit! (topics topic)))


(defn vault
  "For `observe`: the readings of the restricted feed."
  [emit!]
  (ticker emit! {:connect-ms 800 :period-ms 1500}))


;; How often `describe-topic` ran, and how those runs ended.
(defonce !queries (atom {:started 0 :completed 0 :cancelled 0}))


(def query-ms 700)


(def ^:private clock (DateTimeFormatter/ofPattern "HH:mm:ss"))


(defn describe-topic
  "For `observe`: a one-off read standing in for a query, which emits a
  description of `topic` after `query-ms`. Returns its task, so that a lookup
  still running when the resource closes is cancelled. Counts its runs in
  `!queries`."
  [emit! topic]
  (swap! !queries update :started inc)
  (-> (q/task
        (Thread/sleep (long query-ms))
        (emit! (assoc (topics topic) :looked-up-at (LocalTime/.format (LocalTime/now) clock))))
    (q/finally (fn [_ _ cancelled]
                 (swap! !queries update (if cancelled :cancelled :completed) inc)))))


;; uid -> #{permission}
(defonce !acl (atom {}))


;; Oldest first, capped at `max-messages`. Each is {:n, :uid, :text, :at}; `:n` is
;; unique, and `:at` when it was posted, in epoch ms.
(defonce !messages (atom []))


(defonce ^:private !message-n (atom 0))


(def max-messages 5)


(def max-message-length 140)


(defn ensure-user!
  "First sight of `uid`: grant the vault, so the demo starts with it mounted.
  Called whenever a session starts."
  [uid]
  (swap! !acl update uid #(or % #{:vault})))


(defn allowed?
  [acl uid permission]
  (contains? (get acl uid) permission))


(defn toggle!
  [uid permission]
  (swap! !acl update uid #(if (contains? % permission) (disj % permission) (conj (or % #{}) permission))))


(defn post-message!
  "Validates `text`, which arrived as a client signal and is untrusted, then posts it.
  Returns true if posted."
  [uid text]
  (let [text (when (string? text)
               (str/trim (subs text 0 (min (count text) max-message-length))))]
    (when (seq text)
      (let [n (swap! !message-n inc)]
        (swap! !messages #(vec (take-last max-messages (conj % {:n n :uid uid :text text :at (System/currentTimeMillis)})))))
      true)))


;; uid -> the stars they rated the demo, from 1 to 5.
(defonce !ratings (atom {}))


(defn rate!
  "Records `uid`'s rating, `stars`, which arrived from the browser and is
  untrusted: a whole number from 1 to 5, or nothing. Returns true if recorded."
  [uid stars]
  (when (contains? #{1 2 3 4 5} stars)
    (swap! !ratings assoc uid stars)
    true))


(defn unrate!
  "Drops `uid`'s rating."
  [uid]
  (swap! !ratings dissoc uid))
