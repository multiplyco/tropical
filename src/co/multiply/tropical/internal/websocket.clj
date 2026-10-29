(ns co.multiply.tropical.internal.websocket
  "Replaceable Ring transport for a logical channel. Only a broken link is
  closed on IO failure; the generation's handlers and buffers keep running."
  (:require
    [clojure.tools.logging :as log]
    [co.multiply.quiescent :as q]
    [co.multiply.tropical.internal.channel :as channel]
    [co.multiply.tropical.internal.channel-wire :as wire]
    [ring.websocket.protocols :as ws])
  (:import
    [co.multiply.tropical.channel Buffers$Entry Buffers$Outgoing]
    [java.nio ByteBuffer]
    [java.util.concurrent Semaphore TimeUnit]))


(defn wake!
  [link]
  (when link (Semaphore/.release ^Semaphore (:wake link))))


(defn close!
  [{:keys [!state socket ended!] :as link} code reason]
  (let [old (locking !state
              (when-not (:closed @!state)
                (let [old @!state]
                  (swap! !state assoc :closed true)
                  old)))]
    (when old
      (when-some [p (:write old)] (q/fail p (ex-info "Channel transport ended." {:code code})))
      (wake! link)
      (try (ws/-close socket code reason) (catch Throwable t (log/debug t "Closing channel socket failed.")))
      (ended! link code))))


(defn- write!
  [{:keys [!state socket opts]} value]
  (let [p (q/promise)]
    (locking !state
      (when (:closed @!state) (throw (ex-info "Channel transport ended." {})))
      (swap! !state assoc :write p)
      (ws/-send-async socket value #(deliver p true) #(q/fail p %)))
    (try @(q/timeout p (:send-timeout-ms opts))
      (finally (swap! !state dissoc :write)))))


(defn- heartbeat!
  [{:keys [!state socket opts] :as link}]
  (let [now (System/currentTimeMillis)
        {:keys [ping-at last-ping started phase]} @!state]
    (cond
      (and (not= phase :open) (> (- now started) (:init-timeout-ms opts)))
      (close! link 4000 "Channel handshake timed out")
      (and ping-at (> (- now ping-at) (:pong-timeout-ms opts)))
      (close! link 4000 "Channel heartbeat timed out")
      (and (nil? ping-at) (> (- now last-ping) (:ping-ms opts)))
      (let [ping (doto (ByteBuffer/allocate 8) (ByteBuffer/.putLong now) ByteBuffer/.flip)]
        (swap! !state assoc :ping (ByteBuffer/.asReadOnlyBuffer ping) :ping-at now :last-ping now)
        (ws/-ping socket ping)))))


(defn pong!
  [{:keys [!state] :as link} value]
  (locking !state
    (when (= (:ping @!state) value)
      (swap! !state dissoc :ping :ping-at)
      (wake! link))))


(defn- valid-position?
  [[receipt messages bytes maximum]]
  (and (every? wire/counter? [receipt messages bytes maximum])
    (<= receipt messages) (pos? maximum)))


(defn- peer-position!
  [{:keys [!state] :as link} receipt messages bytes]
  (let [{:keys [gen message-grant byte-grant]} @!state]
    (when-not (and (every? wire/counter? [receipt messages bytes])
                (<= receipt messages) (<= receipt (:sent-through @(:!state gen) 0))
                (>= messages message-grant) (>= bytes byte-grant))
      (throw (ex-info "Invalid channel receipt or credit." {})))
    (channel/acknowledge! gen receipt)
    (swap! !state assoc :message-grant messages :byte-grant bytes)
    (wake! link)))


(defn welcome!
  [link gen reason peer-position]
  (let [[receipt messages bytes maximum] peer-position
        !state (:!state link)]
    (when (= reason :resumed)
      (channel/acknowledge! gen receipt))
    (locking !state
      (when-not (:closed @(:!state link))
        (swap! (:!state link) assoc :gen gen :phase :welcome
          :cursor (if (= reason :resumed) receipt 0)
          :message-grant (if (= reason :resumed) messages 0)
          :byte-grant (if (= reason :resumed) bytes 0)
          :peer-maximum maximum
          :welcome (into ["welcome" (:id gen) (name reason)]
                     (conj (channel/receipt gen) (get-in gen [:opts :max-message-bytes]))))))
    (wake! link)))


(defn receive!
  [{:keys [!state opts hello!] :as link} raw]
  (when-not (:closed @!state)
    (try
      (let [[kind & args :as frame] (wire/read-frame raw (:max-message-bytes opts))
            {:keys [phase gen]} @!state]
        (case kind
          "hello"
          (let [[client generation & position] args]
            (when-not (and (= phase :hello) (= 7 (count frame)) (parse-uuid (str client))
                        (or (nil? generation) (parse-uuid (str generation))) (valid-position? position))
              (throw (ex-info "Invalid channel hello." {})))
            (swap! !state assoc :phase :establishing)
            (hello! link client generation position))
          "ready"
          (let [[id receipt messages bytes] args]
            (when-not (and (= phase :ready-wait) (= 5 (count frame)) (= id (:id gen)))
              (throw (ex-info "Unexpected channel ready." {})))
            (peer-position! link receipt messages bytes)
            (swap! (:!state gen) assoc :confirmed true)
            (swap! !state assoc :phase :open :cursor receipt)
            (wake! link))
          "ack"
          (do
            (when-not (and (= phase :open) (= 4 (count frame)))
              (throw (ex-info "Unexpected channel acknowledgement." {})))
            (apply peer-position! link args))
          "data"
          (do
            (when-not (= phase :open) (throw (ex-info "Channel is not ready for messages." {})))
            (apply channel/accept! gen args))
          (throw (ex-info "Unknown channel control." {}))))
      (catch Throwable t
        (log/debug t "Channel protocol refused.")
        (close! link 1002 "Invalid channel protocol")))))


(defn- write-loop!
  [{:keys [!state wake] :as link}]
  (try
    (loop []
      (when-not (:closed @!state)
        (heartbeat! link)
        (let [{:keys [phase gen welcome cursor message-grant byte-grant sent-receipt peer-maximum]} @!state
              wrote?
              (cond
                (:closed @!state) false
                welcome
                (do
                  (swap! !state #(-> % (dissoc :welcome) (assoc :phase :ready-wait)))
                  (write! link (wire/control welcome))
                  true)
                (= phase :open)
                (let [receipt (channel/receipt gen)
                      entry (Buffers$Outgoing/.next (:outgoing gen) cursor)
                      eligible? (and entry (<= (Buffers$Entry/.sequence entry) message-grant)
                                  (<= (Buffers$Entry/.totalBytes entry) byte-grant))]
                  (when (and entry (> (Buffers$Entry/.bytes entry) peer-maximum))
                    (close! link 1009 "Message exceeds peer capacity")
                    (throw (ex-info "Message exceeds peer capacity." {})))
                  (when (not= receipt sent-receipt)
                    (write! link (wire/control (into ["ack"] receipt)))
                    (swap! !state assoc :sent-receipt receipt))
                  (when eligible?
                    (let [sequence (Buffers$Entry/.sequence entry)]
                      (swap! (:!state gen) update :sent-through (fnil max 0) sequence)
                      (write! link (wire/data-frame sequence (Buffers$Entry/.value entry)))
                      (swap! !state assoc :cursor sequence)))
                  (or eligible? (not= receipt sent-receipt)))
                :else false)]
          (when-not wrote?
            (let [{:keys [phase started ping-at last-ping]} @!state
                  deadline (if ping-at (+ ping-at (:pong-timeout-ms (:opts link)))
                               (+ last-ping (:ping-ms (:opts link))))
                  deadline (if (= phase :open) deadline (min deadline (+ started (:init-timeout-ms (:opts link)))))]
              (Semaphore/.tryAcquire wake (max 1 (- deadline (System/currentTimeMillis))) TimeUnit/MILLISECONDS))))
        (recur)))
    (catch Throwable t
      (when-not (:closed @!state)
        (log/debug t "Channel transport failed.")
        (close! link 4000 "Channel transport failed")))))


(defn connection
  [socket opts hello! ended!]
  {:socket socket :opts opts :hello! hello! :ended! ended! :wake (Semaphore. 0)
   :!state (atom {:phase :hello :started (System/currentTimeMillis)
                  :last-ping (System/currentTimeMillis) :closed false})})


(defn start!
  [link]
  (if (satisfies? ws/AsyncSocket (:socket link))
    (q/compel (q/task (write-loop! link)))
    (close! link 1011 "Ring AsyncSocket required"))
  nil)
