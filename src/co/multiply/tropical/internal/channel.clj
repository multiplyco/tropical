(ns co.multiply.tropical.internal.channel
  "A generation's duplex core.async endpoint and resumable wire queues.
  Port commits establish transfer; the application's lifetime task establishes
  processing and cleanup completion. Transport loss cancels neither."
  (:require
    [clojure.core.async :as a]
    [clojure.core.async.impl.protocols :as async]
    [clojure.tools.logging :as log]
    [co.multiply.quiescent :as q]
    [co.multiply.tropical.internal.channel-wire :as wire])
  (:import
    [co.multiply.tropical.channel Buffers$Entry Buffers$Incoming Buffers$Outgoing]
    [java.util ArrayDeque Iterator]
    [java.util.concurrent.locks Lock]))


(def defaults
  {:max-message-bytes 1048576 :max-queued-bytes 4194304 :max-queued-messages 64
   :init-timeout-ms 10000 :send-timeout-ms 10000
   :ping-ms 30000 :pong-timeout-ms 10000 :resume-ms 60000})


(defn options
  [opts]
  (doseq [[k v] opts]
    (when-not (and (contains? defaults k) (pos-int? v) (wire/counter? v))
      (throw (IllegalArgumentException. (str "Invalid channel option: " k " " v)))))
  (let [opts (merge defaults opts)]
    (when (> (:max-message-bytes opts) (:max-queued-bytes opts))
      (throw (IllegalArgumentException. "Channel max-message-bytes must not exceed max-queued-bytes.")))
    opts))


(defn- failure
  [reason]
  (ex-info "Channel delivery cannot continue." {:type :channel-ended :reason reason}))


(defn- box
  [value]
  (reify clojure.lang.IDeref
    (deref [_] value)))


(defn- commit!
  [handler]
  (Lock/.lock ^Lock handler)
  (try (when (async/active? handler) (async/commit handler))
    (finally (Lock/.unlock ^Lock handler))))


(defn- dispatch!
  [callbacks]
  ;; Callbacks run outside both the endpoint and core.async handler locks.
  (doseq [[callback value] callbacks]
    (q/compel (q/task (try (callback value)
                        (catch Throwable t (log/error t "Channel port callback failed.")))))))


(defn- clean-puts!
  [{:keys [!state puts]}]
  (let [it (ArrayDeque/.iterator puts)]
    (while (Iterator/.hasNext it)
      (let [[handler _ n] (Iterator/.next it)]
        (when-not (async/active? handler)
          (Iterator/.remove it)
          (swap! !state update :pending-bytes - n))))))


(defn- pump-puts!
  "Called under the endpoint lock. Retirement consumes pending puts into a sink."
  [{:keys [!state puts outgoing]}]
  (loop [callbacks []]
    (if-let [[handler value n] (ArrayDeque/.peekFirst puts)]
      (if (or (not (async/active? handler)) (:closing @!state) (Buffers$Outgoing/.canOffer outgoing n))
        (do
          (ArrayDeque/.removeFirst puts)
          (swap! !state update :pending-bytes - n)
          (if-let [callback (commit! handler)]
            (do
              (when-not (:closing @!state) (Buffers$Outgoing/.offer outgoing value n))
              (recur (conj callbacks [callback true])))
            (recur callbacks)))
        callbacks)
      callbacks)))


(defn- pump-takes!
  [{:keys [!state takes incoming]}]
  (let [it (ArrayDeque/.iterator takes)]
    (while (Iterator/.hasNext it)
      (when-not (async/active? (Iterator/.next it)) (Iterator/.remove it))))
  (loop [callbacks []]
    (if-let [handler (ArrayDeque/.peekFirst takes)]
      (if (or (not (async/active? handler)) (Buffers$Incoming/.peek incoming) (:closing @!state))
        (do
          (ArrayDeque/.removeFirst takes)
          (if-let [callback (commit! handler)]
            (let [entry (Buffers$Incoming/.poll incoming)]
              (recur (conj callbacks [callback (when entry (Buffers$Entry/.value entry))])))
            (recur callbacks)))
        callbacks)
      callbacks)))


(defn- take-port!
  [{:keys [!state takes incoming opts notify!] :as gen} handler]
  (let [[result callbacks]
        (locking !state
          (let [callbacks (pump-takes! gen)]
            (cond
              (not (async/active? handler)) [nil callbacks]
              (or (Buffers$Incoming/.peek incoming) (:closing @!state))
              [(when (commit! handler)
                 (let [entry (Buffers$Incoming/.poll incoming)]
                   (box (when entry (Buffers$Entry/.value entry))))) callbacks]
              (async/blockable? handler)
              (do
                (when (>= (ArrayDeque/.size takes) (:max-queued-messages opts))
                  (throw (ex-info "Too many pending channel takes." {:type :backpressure})))
                (ArrayDeque/.addLast takes handler)
                [nil callbacks])
              :else [nil callbacks])))]
    (dispatch! callbacks)
    (notify!)
    result))


(defn- put-port!
  [{:keys [!state puts outgoing opts notify!] :as gen} value handler]
  (let [[value n] (wire/payload value (:max-message-bytes opts))
        result
        (locking !state
          (clean-puts! gen)
          (cond
            (not (async/active? handler)) nil
            (:closing @!state) (when (commit! handler) (box false))
            (and (ArrayDeque/.isEmpty puts) (Buffers$Outgoing/.canOffer outgoing n))
            (when (commit! handler)
              (Buffers$Outgoing/.offer outgoing value n)
              (box true))
            (async/blockable? handler)
            (do
              (when (or (>= (ArrayDeque/.size puts) (:max-queued-messages opts))
                      (> (+ (:pending-bytes @!state) n) (:max-queued-bytes opts)))
                (throw (ex-info "Channel pending-put limit exceeded." {:type :backpressure})))
              (ArrayDeque/.addLast puts [handler value n])
              (swap! !state update :pending-bytes + n)
              nil)
            :else nil))]
    (notify!)
    result))


(declare retire!)


(deftype Endpoint [gen]
  async/ReadPort
  (take! [_ handler] (take-port! gen handler))
  async/WritePort
  (put! [_ value handler] (put-port! gen value handler))
  async/Channel
  (close! [_] (retire! gen :closed) nil)
  (closed? [_] (boolean (:closing @(:!state gen)))))


(defn endpoint-generation
  [^Endpoint endpoint]
  (.-gen endpoint))


(defn flush!
  "Waits for receipt of committed puts; pending puts have not been admitted."
  [{:keys [!state outgoing opts]}]
  (let [p (q/promise)]
    (locking !state
      (let [target (Buffers$Outgoing/.lastSequence outgoing)]
        (cond
          (:closing @!state) (q/fail p (failure (:closing @!state)))
          (<= target (Buffers$Outgoing/.acknowledged outgoing)) (deliver p nil)
          (>= (count (:waiters @!state)) (:max-queued-messages opts))
          (q/fail p (ex-info "Too many channel flush waiters." {:type :backpressure}))
          :else (swap! !state assoc-in [:waiters p] target))))
    (q/finally p (fn [& _] (swap! !state update :waiters dissoc p)))))


(defn acknowledge!
  [{:keys [!state outgoing notify!] :as gen} receipt]
  (let [callbacks
        (locking !state
          (when-not (:closing @!state)
            (Buffers$Outgoing/.acknowledge outgoing receipt)
            (doseq [[p target] (:waiters @!state) :when (<= target receipt)]
              (swap! !state update :waiters dissoc p)
              (deliver p nil))
            (pump-puts! gen)))]
    (dispatch! callbacks)
    (notify!)))


(defn receipt
  [{:keys [incoming]}]
  (vec (Buffers$Incoming/.receipt incoming)))


(defn accept!
  [{:keys [!state incoming notify!] :as gen} sequence value size]
  (let [callbacks
        (locking !state
          (case (Buffers$Incoming/.offer incoming sequence value size)
            (0 1) (pump-takes! gen)
            2 (throw (ex-info "Peer exceeded channel credit." {}))
            3 nil))]
    (dispatch! callbacks)
    (notify!)))


(defn retire!
  "Close both admissions. Keep incoming data; consume pending puts into a sink.
  Completion still belongs to the application task, independently of closure."
  [{:keys [!state incoming outgoing drained ended! notify!] :as gen} reason]
  (let [callbacks
        (locking !state
          (when-not (:closing @!state)
            (swap! !state assoc :closing reason)
            (Buffers$Incoming/.seal incoming)
            (Buffers$Outgoing/.discard outgoing)
            (doseq [[p _] (:waiters @!state)] (q/fail p (failure reason)))
            (swap! !state assoc :waiters {})
            (into (pump-puts! gen) (pump-takes! gen))))]
    (when callbacks
      (dispatch! callbacks)
      (notify!)
      (ended! reason))
    drained))


(defn generation
  [client opts notify! ended!]
  (let [drained (promise)
        gen {:id (str (random-uuid)) :client client :opts opts
             :incoming (Buffers$Incoming. (:max-queued-bytes opts) (:max-queued-messages opts))
             :outgoing (Buffers$Outgoing. (:max-queued-bytes opts) (:max-queued-messages opts))
             :puts (ArrayDeque.) :takes (ArrayDeque.)
             :!state (atom {:waiters {} :pending-bytes 0}) :ready (promise) :drained drained :finished drained
             :notify! notify! :ended! ended!}]
    (assoc gen :endpoint (Endpoint. gen))))


(defn start!
  [gen open reason]
  (q/compel
    (q/task
      (try
        (if (:closing @(:!state gen))
          (deliver (:ready gen) false)
          (do
            ;; Readiness is admission, not completion of the peer's lifetime.
            ;; Its task can take immediately or await asynchronous acquisition.
            (deliver (:ready gen) true)
            @(q/task
               (let [work (open {:channel (:endpoint gen) :reason reason :generation (:id gen)})]
                 (if (satisfies? async/ReadPort work) (a/<!! work) work)))))
        (catch Throwable t
          (log/error t "Channel application task failed.")
          (retire! gen :application-failed))
        (finally
          (retire! gen :completed)
          (deliver (:drained gen) true)))))
  nil)
