(ns co.multiply.tropical.channel-test
  (:require
    [clojure.test :refer [deftest is]]
    [clojure.core.async :as async]
    [clojure.core.async.impl.protocols :as ports]
    [co.multiply.quiescent :as q]
    [co.multiply.scoped :refer [ask scoping]]
    [co.multiply.tropical.channel :as channel]
    [co.multiply.tropical.internal.channel :as engine]
    [co.multiply.tropical.internal.channel-wire :as wire]
    [co.multiply.tropical.island :as island :refer [defisland]]
    [co.multiply.tropical.ring :as ring]
    [co.multiply.tropical.session :as session]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.test-util :refer [harness capturing]]
    [clojure.tools.logging :as log]
    [ring.adapter.jetty :as jetty]
    [ring.websocket.protocols :as ws])
  (:import
    [co.multiply.tropical.channel Buffers$Incoming Buffers$Outgoing Buffers$Entry]
    [co.multiply.tropical.sse EventStream]
    [java.io ByteArrayOutputStream]
    [java.net URI]
    [java.net.http HttpClient WebSocket WebSocket$Listener]
    [java.nio ByteBuffer]
    [java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit]
    [org.eclipse.jetty.server Server ServerConnector]))


(def ^:dynamic *route* nil)


(defn- eventually
  [f]
  (let [until (+ (System/currentTimeMillis) 2500)]
    (loop [] (cond (f) true (> (System/currentTimeMillis) until) false
                   :else (do (Thread/sleep 5) (recur))))))


(defn- bytes-vector
  [^ByteBuffer b]
  (let [copy (byte-array (ByteBuffer/.remaining b))]
    (ByteBuffer/.get (ByteBuffer/.duplicate b) copy) (vec copy)))


(defisland channel-view [!descriptor !renders !key open opts]
  (swap! !renders inc)
  (let [k (island/use-watch !key)]
    [:div (when k
            (reset! !descriptor (channel/use-channel (if (vector? k) (first k) k) open opts))
            [:span "channel"])]))


(defn- page
  [open opts]
  (let [!descriptor (atom nil) !renders (atom 0) !key (atom :document)
        app {:uid #(get-in % [:headers "x-uid"]) ::session/request {:uri "/channel"}
             :root (fn [_] (channel-view !descriptor !renders !key open opts))}
        s (session/create! (str (random-uuid)) "alice" app)]
    (session/render-page! s)
    {:session s :descriptor !descriptor :renders !renders :key !key :app app}))


(defn- handler-page
  "Small test consumer for the wire tests below; production receives a port."
  [open opts]
  (page
    (fn [{:keys [channel] :as context}]
      (q/task
        (let [send! (fn [value]
                      (try
                        (when-not (async/offer! channel value)
                          (throw (ex-info "Full test sender" {:type :backpressure})))
                        (q/as-task nil)
                        (catch Throwable t (q/failed-task t))))
              handlers @(q/task (open (assoc context :send! send! :flush! #(channel/flush! channel))))]
          (try
            (loop []
              (when-some [value (async/<!! channel)]
                @(q/task ((:receive! handlers) value))
                (recur)))
            (finally
              (when-some [close! (:close! handlers)] @(q/task (close!))))))))
    opts))


(defn- stop!
  [p]
  (session/close! (:session p))
  (is (deref (:closed (:session p)) 2000 false)))


(defn- request
  [p upgrade?]
  {:uri "/channel" :request-method :get :scheme :http :server-name "localhost" :server-port 8080
   :headers (merge {"x-uid" "alice"}
              (if upgrade?
                {"origin" "http://localhost:8080" "upgrade" "websocket" "connection" "upgrade"
                 "sec-websocket-protocol" (str "tropical.channel.v2, tropical.cap." (:token @(:descriptor p)))}
                {"tropical-channel" (:token @(:descriptor p))}))})


(defn- peer
  ([p] (peer p {}))
  ([p {:keys [client generation received messages bytes maximum automatic? ping-error write-error fail-data stall-data scope]
       :or {received 0 messages 64 bytes 4194304 maximum 1048576 automatic? true}}]
   (let [frames (atom []) writes (atom []) closed (promise) socket
         (reify
           ws/Socket
           (-open? [_] (not (realized? closed)))

           (-close [_ code _] (deliver closed code))

           (-send [_ _] (throw (AssertionError. "Async writes required")))

           (-ping [_ _] (when ping-error (throw ping-error)))

           (-pong [_ _])


           ws/AsyncSocket

           (-send-async
             [_ value succeed fail]
             (swap! frames conj (wire/read-frame value 1048576))
             (swap! writes conj [succeed fail])
             (cond write-error (throw write-error)
                   (and fail-data (not (string? value))) (fail fail-data)
                   (and stall-data (not (string? value))) nil
                   automatic? (succeed))))
         listener (scoping [*route* scope] (:ring.websocket/listener ((ring/handler (:app p)) (request p true))))
         client (or client (str (random-uuid)))
         control #(ws/on-message listener socket (wire/control %))]
     (ws/on-open listener socket)
     (control ["hello" client generation received messages bytes maximum])
     {:listener listener :socket socket :client client :frames frames :writes writes :closed closed :control control
      :data (fn [sequence value] (ws/on-message listener socket (wire/data-frame sequence value)))
      :disconnect #(ws/on-close listener socket 1006 "lost")})))


(defn- welcome
  [p]
  (is (eventually #(some (fn [f] (= "welcome" (first f))) @(:frames p))))
  (first (filter #(= "welcome" (first %)) @(:frames p))))


(defn- ready
  ([p] (ready p 0 64 4194304))
  ([p received messages bytes]
   (let [w (welcome p)]
     ((:control p) ["ready" (second w) received messages bytes])
     w)))


(defn- data-frames
  [p]
  (filter #(= "data" (first %)) @(:frames p)))


(deftest binary-and-unicode-wire-roundtrip
  (doseq [value ["hello λ 👋" "" (ByteBuffer/wrap (byte-array [0 -1 4]))]]
    (let [[_ seq payload n] (wire/read-frame (wire/data-frame 42 value) 1000)]
      (is (= 42 seq)) (is (<= 0 n))
      (is (= (if (string? value) value (bytes-vector value))
             (if (string? payload) payload (bytes-vector payload)))))))


(deftest text-control-frames-cannot-impersonate-application-data
  (doseq [value [{"unexpected" "object"} "text" [0 1 2]]]
    (let [seen (atom []) cleaned (promise)
          p (handler-page (fn [_] {:receive! #(swap! seen conj %) :close! #(deliver cleaned true)}) {})]
      (try
        (let [a (peer p) forged ["data" 1 value 0]]
          (ready a)
          (is (thrown? Exception (wire/read-frame (wire/control forged) 1048576)))
          ((:control a) forged)
          (is (= 1002 (deref (:closed a) 1000 :timeout)))
          (is (empty? @seen))
          (is (not-any? #(and (= "ack" (first %)) (pos? (second %))) @(:frames a))))
        (finally (stop! p)))
      (is (deref cleaned 1000 false))
      (is (empty? @seen)))))


(deftest message-limit-must-fit-the-queue
  (doseq [opts [{:max-message-bytes 4 :max-queued-bytes 3}
                {:max-queued-bytes 3}
                {:max-message-bytes 4194305}]]
    (is (thrown? IllegalArgumentException
          (channel/use-channel :invalid (fn [_] {:receive! identity}) opts))))
  (let [opened (promise)
        p (handler-page (fn [ctx] (deliver opened ctx) {:receive! identity})
            {:max-message-bytes 3 :max-queued-bytes 3})]
    (try
      (let [a (peer p) w (ready a)]
        (is (= [3 3] (subvec w 5 7)))
        (is (= :message-too-large
               (try @((:send! @opened) "éé") nil (catch Exception e (:type (ex-data e)))))))
      (finally (stop! p)))))


(deftest oversized-output-fails-before-waiting-for-peer-credit
  (doseq [value ["éé" (ByteBuffer/wrap (byte-array [0 1 2 3]))]
          [messages bytes] [[0 3] [1 3] [1 4]]]
    (let [opened (promise)
          p (handler-page (fn [{:keys [send!] :as ctx}]
                            @(send! value)
                            (deliver opened ctx)
                            {:receive! identity}) {})]
      (try
        (let [a (peer p {:messages messages :bytes bytes :maximum 3})]
          (welcome a)
          (let [flushed (q/compel ((:flush! @opened)))]
            (ready a 0 messages bytes)
            (is (= 1009 (deref (:closed a) 1000 :timeout)))
            (is (eventually #(q/exceptional? flushed)))
            (is (empty? (data-frames a)))))
        (finally (stop! p))))))


(deftest input-sealing-preserves-input-and-takes-return-credits
  (let [in (Buffers$Incoming. 3 2)]
    (is (= 0 (Buffers$Incoming/.offer in 1 "a" 2)))
    (is (= 2 (Buffers$Incoming/.offer in 2 "b" 2)))
    (is (= 0 (Buffers$Incoming/.offer in 2 "b" 1)))
    (Buffers$Incoming/.seal in)
    (is (= 3 (Buffers$Incoming/.offer in 3 "c" 0)))
    (is (= 1 (Buffers$Incoming/.offer in 1 "duplicate" 2)))
    (is (= "a" (Buffers$Entry/.value (Buffers$Incoming/.poll in))))
    (is (= [2 3 5] (vec (Buffers$Incoming/.receipt in))))
    (is (= "b" (Buffers$Entry/.value (Buffers$Incoming/.poll in))))
    (is (Buffers$Incoming/.drained in))))


(deftest outgoing-retains-until-receipt-and-exposes-next-entry
  (let [out (Buffers$Outgoing. 3 2)]
    (is (= 1 (Buffers$Outgoing/.offer out "a" 2)))
    (is (= 2 (Buffers$Outgoing/.offer out "b" 1)))
    (is (= 0 (Buffers$Outgoing/.offer out "full" 1)))
    (is (= 1 (Buffers$Entry/.sequence (Buffers$Outgoing/.next out 0))))
    (is (= 2 (Buffers$Entry/.sequence (Buffers$Outgoing/.next out 1))))
    (is (nil? (Buffers$Outgoing/.next out 2)))
    (Buffers$Outgoing/.acknowledge out 1)
    (is (= 3 (Buffers$Outgoing/.offer out "c" 2)))
    (is (not (Buffers$Outgoing/.canResume out 0)))
    (is (thrown? IllegalArgumentException (Buffers$Outgoing/.acknowledge out 4)))))


(deftest same-generation-survives-disconnect-and-deduplicates-lost-ack
  (let [!opened (atom []) !seen (atom []) !cleaned (atom 0)
        p (handler-page (fn [ctx]
                          (swap! !opened conj ctx)
                          {:receive! #(swap! !seen conj %) :close! #(swap! !cleaned inc)}) {})]
    (try
      (let [a (peer p) [_ id] (ready a)]
        ((:data a) 1 "one")
        (is (eventually #(= ["one"] @!seen)))
        ((:disconnect a))
        (is (= 0 @!cleaned))
        (let [b (peer p {:client (:client a) :generation id}) w (ready b)]
          (is (= [id "resumed" 1] (subvec w 1 4)))
          ((:data b) 1 "one")
          ((:data b) 2 "two")
          (is (eventually #(= ["one" "two"] @!seen)))
          (is (= 1 (count @!opened)))
          (is (= 1 @(:renders p)))))
      (finally (stop! p)))
    (is (eventually #(= 1 @!cleaned)))))


(deftest send-admits-offline-flush-waits-for-peer-and-reconnect-replays
  (let [opened (promise) p (handler-page (fn [ctx] (deliver opened ctx) {:receive! identity}) {})]
    (try
      (let [a (peer p) [_ id] (ready a) {:keys [send! flush!]} @opened]
        ((:disconnect a))
        (is (nil? @(send! "offline")))
        (let [flushed (q/compel (flush!))
              b (peer p {:client (:client a) :generation id})]
          (ready b)
          (is (eventually #(= 1 (count (data-frames b)))))
          (is (not (realized? flushed)))
          ((:disconnect b))
          (let [c (peer p {:client (:client a) :generation id})]
            (ready c)
            (is (eventually #(= 1 (count (data-frames c)))))
            (is (= "offline" (nth (first (data-frames c)) 2)))
            ((:control c) ["ack" 1 64 4194304])
            (is (nil? (deref flushed 1000 :timeout))))))
      (finally (stop! p)))))


(deftest accepted-input-drains-after-unmount-with-no-client
  (let [gate (q/promise) seen (atom []) cleaned (promise)
        p (handler-page (fn [_]
                          {:receive! (fn [v] (swap! seen conj v) (when (= v "one") gate))
                           :close! #(deliver cleaned true)}) {})]
    (try
      (let [a (peer p)]
        (ready a)
        ((:data a) 1 "one") ((:data a) 2 "two") ((:data a) 3 "three")
        (is (eventually #(= ["one"] @seen)))
        ((:disconnect a))
        (reset! (:key p) nil)
        (Thread/sleep 50)
        (is (not (realized? cleaned)))
        (deliver gate true)
        (is (deref cleaned 1000 false))
        (is (= ["one" "two" "three"] @seen)))
      (finally (stop! p)))))


(deftest reset-drains-old-input-and-cleanup-before-new-initialization
  (let [gate (q/promise) events (atom [])
        p (handler-page (fn [{:keys [reason]}]
                          (swap! events conj [:open reason])
                          {:receive! (fn [v] (swap! events conj [:receive v]) (when (= v "one") gate))
                           :close! #(swap! events conj [:close])}) {})]
    (try
      (let [a (peer p)]
        (ready a)
        ((:data a) 1 "one") ((:data a) 2 "two")
        (is (eventually #(= 2 (count @events))))
        ((:disconnect a))
        (let [b (peer p {:client (:client a) :generation nil})]
          (Thread/sleep 30)
          (is (empty? @(:frames b)))
          (deliver gate nil)
          (is (= "continuity-lost" (nth (ready b) 2)))
          (is (= [[:open :initial] [:receive "one"] [:receive "two"] [:close] [:open :continuity-lost]] @events))))
      (finally (stop! p)))))


(deftest slow-peer-credit-pauses-output-without-losing-it
  (let [opened (promise) p (handler-page (fn [ctx] (deliver opened ctx) {:receive! identity}) {})]
    (try
      (let [a (peer p {:messages 1 :bytes 3})]
        (ready a 0 1 3)
        (is (nil? @((:send! @opened) "abc")))
        (is (nil? @((:send! @opened) "def")))
        (is (eventually #(= 1 (count (data-frames a)))))
        ((:control a) ["ack" 1 1 3])
        (Thread/sleep 20)
        (is (= 1 (count (data-frames a))))
        ((:control a) ["ack" 1 2 6])
        (is (eventually #(= 2 (count (data-frames a))))))
      (finally (stop! p)))))


(deftest local-backpressure-does-not-close-or-evict
  (let [opened (promise) p (handler-page (fn [ctx] (deliver opened ctx) {:receive! identity}) {:max-queued-messages 1})]
    (try
      (let [a (peer p)]
        (ready a)
        (is (nil? @((:send! @opened) "one")))
        (is (= :backpressure (try @((:send! @opened) "two") nil (catch Exception e (:type (ex-data e))))))
        (is (not (realized? (:closed a))))
        (is (eventually #(seq (data-frames a))))
        (is (= "one" (nth (first (data-frames a)) 2))))
      (finally (stop! p)))))


(deftest checks-bind-capability-to-user-origin-and-route
  (let [p (handler-page (fn [_] {:receive! identity}) {}) handler (ring/handler (:app p))]
    (try
      (is (= :channel-check (ring/request-kind (request p false))))
      (is (= :channel (ring/request-kind (request p true))))
      (is (= 204 (:status (handler (request p false)))))
      (is (= 101 (:status (handler (request p true)))))
      (is (= 403 (:status (handler (assoc-in (request p false) [:headers "x-uid"] "bob")))))
      (doseq [origin [nil "null" "https://evil.test"]]
        (is (= 403 (:status (handler (assoc-in (request p true) [:headers "origin"] origin))))))
      (is (= 404 (:status (handler (assoc (request p false) :uri "/other")))))
      (let [req (request p false)]
        (reset! (:key p) nil)
        (is (eventually #(= 404 (:status (handler req)))))
        (stop! p)
        (is (= 409 (:status (handler req)))))
      (finally (stop! p)))))


(deftest sse-attachment-and-stable-rerender-preserve-the-channel
  (let [p (handler-page (fn [_] {:receive! identity}) {}) stream (EventStream. (ByteArrayOutputStream.))]
    (try
      (session/attach! (:session p) stream "load")
      (is (eventually #(get-in @session/!sessions [(:tab (:session p)) :attached?])))
      (let [a (peer p) w (ready a) desc @(:descriptor p)]
        (reset! (:key p) [:document :rerender])
        (is (eventually #(= 2 @(:renders p))))
        (is (= desc @(:descriptor p)))
        ((:disconnect a))
        (let [b (peer p {:client (:client a) :generation (second w)})]
          (is (= "resumed" (nth (ready b) 2)))))
      (finally (stop! p)))))


(deftest retention-holds-session-through-a-glitch-but-explicit-close-drains-input
  (with-redefs [session/grace-ms 30]
    (let [gate (q/promise) seen (atom []) p (handler-page (fn [_] {:receive! (fn [v] (swap! seen conj v) gate)}) {:resume-ms 200})]
      (try
        (let [a (peer p) w (ready a)]
          ((:data a) 1 "accepted")
          ((:disconnect a))
          (Thread/sleep 80)
          (is (session/held? (:tab (:session p))))
          (let [b (peer p {:client (:client a) :generation (second w)})]
            (ready b)
            (stop! p)
            (is (deref (:closed b) 1000 false))
            (deliver gate nil)
            (is (eventually #(= ["accepted"] @seen)))))
        (finally (deliver gate nil) (stop! p))))))


(deftest unknown-generation-on-new-server-is-explicit-reset
  (let [reasons (atom []) p (handler-page (fn [{:keys [reason]}] (swap! reasons conj reason) {:receive! identity}) {})]
    (try
      (let [a (peer p {:generation (str (random-uuid)) :received 7})]
        (is (= "continuity-lost" (nth (ready a) 2)))
        (is (= [:continuity-lost] @reasons)))
      (finally (stop! p)))))


(deftest handshake-interruption-does-not-repeat-an-unconfirmed-initializer
  (let [reasons (atom []) p (handler-page (fn [{:keys [reason]}] (swap! reasons conj reason) {:receive! identity}) {})]
    (try
      (let [a (peer p) w (welcome a)]
        ((:disconnect a))
        (let [b (peer p {:client (:client a)})]
          (is (= (second w) (second (ready b))))
          (is (= [:initial] @reasons))))
      (finally (stop! p)))))


(deftest failed-and-stalled-writes-preserve-the-generation-and-outbox
  (doseq [mode [:failure :timeout]]
    (let [opened (promise) p (handler-page (fn [ctx] (deliver opened ctx) {:receive! identity}) {:send-timeout-ms 50})]
      (try
        (let [a (peer p (if (= mode :failure) {:fail-data (java.io.IOException. "failed write")} {:stall-data true}))
              w (ready a)]
          (is (nil? @((:send! @opened) "retained")))
          (is (= 4000 (deref (:closed a) 1000 :timeout)))
          (let [b (peer p {:client (:client a) :generation (second w)})]
            (is (= "resumed" (nth (ready b) 2)))
            (is (eventually #(seq (data-frames b))))
            (is (= "retained" (nth (first (data-frames b)) 2)))))
        (finally (stop! p))))))


(deftest thrown-ping-is-only-a-transport-failure
  (let [opened (atom 0) p (handler-page (fn [_] (swap! opened inc) {:receive! identity}) {:ping-ms 20})]
    (try
      (let [a (peer p {:ping-error (java.io.IOException. "ping failed")}) w (ready a)]
        (is (= 4000 (deref (:closed a) 1000 :timeout)))
        (let [b (peer p {:client (:client a) :generation (second w)})]
          (is (= "resumed" (nth (ready b) 2)))
          (is (= 1 @opened))))
      (finally (stop! p)))))


(deftest application-can-close-its-port-and-drain-the-remaining-input
  (let [gate (q/promise) seen (atom []) cleaned (promise)
        p (page (fn [{:keys [channel]}]
                  (q/task
                    (try
                      (loop []
                        (when-some [v (async/<!! channel)]
                          (swap! seen conj v)
                          (when (= v "bad") @gate (async/close! channel))
                          (recur)))
                      (finally (deliver cleaned true))))) {})]
    (try
      (let [a (peer p)]
        (ready a)
        ((:data a) 1 "bad") ((:data a) 2 "accepted")
        (deliver gate nil)
        (is (= 4003 (deref (:closed a) 1000 :timeout)))
        (is (deref cleaned 1000 false))
        (is (= ["bad" "accepted"] @seen)))
      (finally (stop! p)))))


(deftest peer-receipt-rollback-explicitly-loses-continuity
  (let [opened (atom []) p (handler-page (fn [ctx] (swap! opened conj ctx) {:receive! identity}) {})]
    (try
      (let [a (peer p) w (ready a)]
        @((:send! (first @opened)) "one")
        (is (eventually #(seq (data-frames a))))
        ((:control a) ["ack" 1 64 4194304])
        ((:disconnect a))
        (let [b (peer p {:client (:client a) :generation (second w) :received 0})
              next-welcome (ready b)]
          (is (= "continuity-lost" (nth next-welcome 2)))
          (is (not= (second w) (second next-welcome)))
          (is (= [:initial :continuity-lost] (mapv :reason @opened)))))
      (finally (stop! p)))))


(deftest initial-scope-survives-resume-and-reset-uses-its-new-request
  (let [scopes (atom []) p (handler-page (fn [_] (swap! scopes conj (ask *route*)) {:receive! identity}) {})]
    (try
      (let [a (peer p {:scope :first}) w (ready a)]
        ((:disconnect a))
        (let [b (peer p {:scope :second :client (:client a) :generation (second w)})]
          (ready b)
          (is (= [:first] @scopes))
          ((:disconnect b))
          (let [c (peer p {:scope :reset :client (:client a)})]
            (ready c)
            (is (= [:first :reset] @scopes)))))
      (finally (stop! p)))))


(defn- http-peer
  [port token client-state]
  (let [welcomed (promise) messages (LinkedBlockingQueue.)
        client-id (:client @client-state)
        write-lock (Object.)
        ;; Listener replies and test-thread data share the JDK's pending-write
        ;; slot. Serialize through completion, not just the send invocation.
        write! (fn [send]
                 (locking write-lock
                   (let [^CompletableFuture completion (send)]
                     (CompletableFuture/.get completion 2 TimeUnit/SECONDS)
                     completion)))
        listener (reify WebSocket$Listener
                   (onOpen
                     [_ socket]
                     (WebSocket/.request socket 1)
                     (write! #(WebSocket/.sendText socket
                                (wire/control ["hello" client-id (:generation @client-state)
                                               (:received @client-state) (+ 64 (:received @client-state))
                                               (+ 4194304 (:bytes @client-state)) 1048576]) true)))

                   (onText
                     [_ socket text _]
                     (let [[kind generation :as v] (wire/read-frame (str text) 1048576)]
                       (when (= kind "welcome")
                         (when-not (= generation (:generation @client-state))
                           (swap! client-state assoc :received 0 :bytes 0))
                         (swap! client-state assoc :generation generation)
                         (write! #(WebSocket/.sendText socket
                                    (wire/control ["ready" generation (:received @client-state)
                                                   (+ 64 (:received @client-state)) (+ 4194304 (:bytes @client-state))]) true))
                         (deliver welcomed v)))
                     (WebSocket/.request socket 1)
                     (CompletableFuture/completedFuture nil))

                   (onBinary
                     [_ socket data _]
                     (let [[_ sequence value n] (wire/read-frame data 1048576)]
                       (when (> sequence (:received @client-state))
                         (swap! client-state #(-> % (assoc :received sequence) (update :bytes + n)))
                         (LinkedBlockingQueue/.offer messages (if (string? value) value (bytes-vector value))))
                       (write! #(WebSocket/.sendText socket
                                  (wire/control ["ack" (:received @client-state) (+ 64 (:received @client-state))
                                                 (+ 4194304 (:bytes @client-state))]) true)))
                     (WebSocket/.request socket 1)
                     (CompletableFuture/completedFuture nil))

                   (onPing
                     [_ socket data]
                     (WebSocket/.request socket 1)
                     (write! #(WebSocket/.sendPong socket data))))
        builder (-> (HttpClient/newHttpClient) HttpClient/.newWebSocketBuilder
                  (.header "Origin" (str "http://localhost:" port)) (.header "x-uid" "alice")
                  (.subprotocols "tropical.channel.v2" (into-array String [(str "tropical.cap." token)])))
        socket (.join (.buildAsync builder (URI. (str "ws://localhost:" port "/channel")) listener))]
    (is (not= :timeout (deref welcomed 2000 :timeout)))
    {:socket socket :welcome @welcomed :receive #(LinkedBlockingQueue/.poll messages 2 TimeUnit/SECONDS)
     :send! (fn [sequence value] (write! #(WebSocket/.sendBinary socket (wire/data-frame sequence value) true)))}))


(deftest real-jetty-orders-text-and-binary-keeps-alive-and-resumes
  (let [opened (atom []) p (handler-page (fn [{:keys [send!] :as ctx}] (swap! opened conj ctx) {:receive! send!})
                             {:ping-ms 40 :pong-timeout-ms 200})
        server (jetty/run-jetty (ring/handler (:app p)) {:host "127.0.0.1" :port 0 :join? false :ws-idle-timeout 150})
        port (ServerConnector/.getLocalPort (first (Server/.getConnectors server)))
        token (:token @(:descriptor p)) client (atom {:client (str (random-uuid)) :received 0 :bytes 0})]
    (try
      (let [a (http-peer port token client)]
        (try
          (doseq [[i value] (map-indexed vector ["hello λ" (ByteBuffer/wrap (byte-array [0 -1 4])) "three"])]
            ((:send! a) (inc i) value)
            (is (= (if (string? value) value (bytes-vector value)) ((:receive a)))))
          (Thread/sleep 450)
          ((:send! a) 4 "still open")
          (is (= "still open" ((:receive a))))
          ;; Send a burst while the listener acknowledges earlier echoes.
          (let [values (mapv (fn [i]
                               (if (even? i) (str "burst " i " λ")
                                   (ByteBuffer/wrap (byte-array [i -1])))) (range 32))]
            (doseq [[i value] (map-indexed vector values)]
              ((:send! a) (+ 5 i) value))
            (is (= (mapv #(if (string? %) % (bytes-vector %)) values)
                   (mapv (fn [_] ((:receive a))) values))))
          (finally (WebSocket/.abort (:socket a)))))
      @((:send! (first @opened)) "while offline")
      (let [b (http-peer port token client)]
        (try
          (is (= "resumed" (nth (:welcome b) 2)))
          (is (= "while offline" ((:receive b))))
          (is (= 1 (count @opened)))
          (is (= 1 @(:renders p)))
          (finally (WebSocket/.abort (:socket b)))))
      (finally (stop! p) (Server/.stop server)))))


(deftest failure-in-retiring-handlers-cannot-close-the-replacement-transport
  (let [gate (q/promise) events (atom [])
        p (handler-page (fn [{:keys [reason]}]
                          (swap! events conj reason)
                          {:receive! (fn [_] (q/then gate (fn [_] (throw (ex-info "old handler failed" {})))))}) {})]
    (try
      (let [a (peer p)]
        (ready a)
        ((:data a) 1 "accepted")
        ((:disconnect a))
        (let [b (peer p {:client (:client a)})]
          (deliver gate nil)
          (is (= "continuity-lost" (nth (ready b) 2)))
          (is (not (realized? (:closed b))))
          (is (= [:initial :continuity-lost] @events))))
      (finally (stop! p)))))


(deftest a-channel-can-be-rendered-and-released-in-an-isolated-island-test
  (let [descriptor (atom nil) logged (atom [])
        {:keys [step rt]} (harness #(channel-view descriptor (atom 0) (atom :test)
                                      (fn [_] {:receive! identity}) {})
                            {:tab "test" :uid "alice" :request {:uri "/test"}})]
    (with-redefs [log/*logger-factory* (capturing logged)]
      (try
        (step)
        (is (string? (:token @descriptor)))
        (finally (runtime/dispose! rt))))
    (is (empty? @logged))))


(defn- generation
  [opts]
  (engine/generation (str (random-uuid)) (engine/options opts) (fn []) (fn [_])))


(deftest duplex-port-drains-input-to-eof-and-refuses-new-output
  (let [gen (generation {}) port (:endpoint gen)]
    (is (satisfies? ports/ReadPort port))
    (is (satisfies? ports/WritePort port))
    (is (satisfies? ports/Channel port))
    (is (thrown? IllegalArgumentException (async/put! port nil)))
    (engine/accept! gen 1 "first" 5)
    (engine/accept! gen 2 (ByteBuffer/wrap (byte-array [2 3])) 2)
    (async/close! port)
    (is (ports/closed? port))
    (is (false? (async/>!! port "gone")))
    (is (= "first" (async/<!! port)))
    (is (= [2 3] (bytes-vector (async/<!! port))))
    (is (nil? (async/<!! port)))
    (is (nil? (async/<!! port)))))


(deftest pending-puts-retain-owned-bytes-and-commit-in-submission-order
  (let [gen (generation {:max-queued-messages 2 :max-queued-bytes 4 :max-message-bytes 4})
        port (:endpoint gen) a (promise) b (promise) bytes (byte-array [1 2 3])]
    (try
      (is (true? (async/>!! port "full")))
      (async/put! port bytes #(deliver a %))
      (async/put! port "b" #(deliver b %))
      (aset-byte bytes 0 (byte 99))
      (is (not (realized? a)))
      (is (not (realized? b)))
      (is (not (async/offer! port "skip")))
      (engine/acknowledge! gen 1)
      (is (true? (deref a 1000 :timeout)))
      (is (true? (deref b 1000 :timeout)))
      (is (= [1 2 3] (bytes-vector (Buffers$Entry/.value (Buffers$Outgoing/.next (:outgoing gen) 1)))))
      (is (= "b" (Buffers$Entry/.value (Buffers$Outgoing/.next (:outgoing gen) 2))))
      (let [flushed (q/compel (channel/flush! port))]
        (is (not (realized? flushed)))
        (engine/acknowledge! gen 3)
        (is (nil? (deref flushed 1000 :timeout))))
      (finally (async/close! port)))))


(deftest retirement-releases-pending-puts-without-waiting-for-input-processing
  (let [gen (generation {:max-queued-messages 1}) port (:endpoint gen)
        put-result (promise) take-result (promise)]
    (is (true? (async/>!! port "retained")))
    (async/put! port "pending" #(deliver put-result %))
    (async/take! port #(deliver take-result %))
    (let [flushed (q/compel (channel/flush! port))]
      (async/close! port)
      (is (true? (deref put-result 1000 :timeout)))
      (is (nil? (deref take-result 1000 :timeout)))
      (is (eventually #(q/exceptional? flushed)))
      (is (Buffers$Outgoing/.empty (:outgoing gen)))
      (is (false? (async/>!! port "later")))
      (is (not (realized? (:drained gen)))))))


(deftest cancelled-alts-do-not-consume-messages-or-retain-capacity
  (let [gen (generation {:max-queued-messages 2}) port (:endpoint gen)
        other (async/chan) first-take (promise)]
    (async/close! other)
    (try
      (async/take! port #(deliver first-take %))
      (dotimes [_ 10]
        (is (= [nil other] (async/alts!! [port other] :priority true))))
      (engine/accept! gen 1 "first" 5)
      (engine/accept! gen 2 "second" 6)
      (is (= "first" (deref first-take 1000 :timeout)))
      (is (= "second" (async/<!! port)))
      (is (true? (async/>!! port "one")))
      (is (true? (async/>!! port "two")))
      (dotimes [_ 10]
        (is (= [nil other] (async/alts!! [[port "cancelled"] other] :priority true))))
      (let [sent (promise)]
        (async/put! port "real" #(deliver sent %))
        (engine/acknowledge! gen 2)
        (is (true? (deref sent 1000 :timeout)))
        (is (= "real" (Buffers$Entry/.value (Buffers$Outgoing/.next (:outgoing gen) 2)))))
      (finally (async/close! port)))))


(deftest pending-put-budgets-refuse-excess-without-evicting-accepted-work
  (let [gen (generation {:max-queued-messages 2 :max-queued-bytes 3 :max-message-bytes 3})
        port (:endpoint gen) pending (promise)]
    (try
      (async/>!! port "one")
      (async/put! port "two" #(deliver pending %))
      (is (= :backpressure (try (async/put! port "x") nil (catch Exception e (:type (ex-data e))))))
      (is (not (ports/closed? port)))
      (engine/acknowledge! gen 1)
      (is (true? (deref pending 1000 :timeout)))
      (is (= "two" (Buffers$Entry/.value (Buffers$Outgoing/.next (:outgoing gen) 1))))
      (finally (async/close! port)))))


(deftest go-operations-use-the-duplex-port
  (let [gen (generation {}) port (:endpoint gen)
        result (async/go [(async/<! port) (async/>! port "reply") (async/<! port)])]
    (engine/accept! gen 1 "input" 5)
    (is (eventually #(= 1 (Buffers$Outgoing/.lastSequence (:outgoing gen)))))
    (async/close! port)
    (is (= ["input" true nil] (first (async/alts!! [result (async/timeout 2000)]))))))


(deftest reconnect-retains-port-and-reset-waits-for-processing-and-finally
  (let [contexts (atom []) events (atom []) processing (q/promise) cleanup (q/promise)
        p (page (fn [{:keys [channel reason] :as context}]
                  (swap! contexts conj context)
                  (q/task
                    (try
                      (loop []
                        (when-some [v (async/<!! channel)]
                          (swap! events conj v)
                          (when (= v "first") @processing)
                          (recur)))
                      (finally
                        (when (= reason :initial)
                          (swap! events conj :cleanup)
                          @cleanup))))) {:max-queued-messages 1})]
    (try
      (let [a (peer p) [_ id] (ready a)]
        (is (eventually #(= 1 (count @contexts))))
        (let [old (:channel (first @contexts))]
          ((:disconnect a))
          (let [b (peer p {:client (:client a) :generation id})]
            (is (= "resumed" (nth (ready b) 2)))
            (is (identical? old (:channel (first @contexts))))
            (is (= 1 (count @contexts)))
            ((:data b) 1 "first")
            (is (eventually #(= ["first"] @events)))
            ((:data b) 2 "second")
            ((:disconnect b))
            (let [c (peer p {:client (:client a)}) pending (promise)]
              (is (eventually #(ports/closed? old)))
              (async/put! old "obsolete reply" #(deliver pending %))
              (is (false? (deref pending 1000 :timeout)))
              (is (empty? @(:frames c)))
              (deliver processing nil)
              (is (eventually #(= ["first" "second" :cleanup] @events)))
              (is (= 1 (count @contexts)))
              (is (empty? @(:frames c)))
              (deliver cleanup nil)
              (is (= "continuity-lost" (nth (ready c) 2)))
              (is (eventually #(= 2 (count @contexts))))
              (is (not (identical? old (:channel (second @contexts)))))
              (is (false? (async/>!! old "still obsolete")))))))
      (finally (deliver processing nil) (deliver cleanup nil) (stop! p)))))


(deftest a-go-completion-channel-holds-the-peer-through-cleanup
  (let [seen (atom []) cleaned (promise)
        p (page (fn [{:keys [channel]}]
                  (async/go
                    (try
                      (loop []
                        (when-some [message (async/<! channel)]
                          (swap! seen conj message)
                          (recur)))
                      (finally (deliver cleaned true))))) {})]
    (try
      (let [a (peer p)]
        (ready a)
        ((:data a) 1 "one") ((:data a) 2 "two")
        ((:disconnect a))
        (reset! (:key p) nil)
        (is (deref cleaned 1000 false))
        (is (= ["one" "two"] @seen)))
      (finally (stop! p)))))


(deftest unmount-during-resource-acquisition-keeps-accepted-input-and-the-task
  (let [acquire (q/promise) started (promise) seen (atom []) cleaned (promise)
        p (page (fn [{:keys [channel]}]
                  (q/task
                    (deliver started true)
                    @acquire
                    (try
                      (loop []
                        (when-some [message (async/<!! channel)]
                          (swap! seen conj message)
                          (recur)))
                      (finally (deliver cleaned true))))) {})]
    (try
      (let [a (peer p)]
        (ready a)
        (is (deref started 1000 false))
        ((:data a) 1 "before acquisition")
        (reset! (:key p) nil)
        (is (= 4003 (deref (:closed a) 1000 :timeout)))
        (is (not (realized? cleaned)))
        (deliver acquire nil)
        (is (deref cleaned 1000 false))
        (is (= ["before acquisition"] @seen)))
      (finally (deliver acquire nil) (stop! p)))))


(deftest an-application-task-failure-is-terminal
  (let [fail (q/promise)
        p (page (fn [_] (q/task @fail (throw (ex-info "Application failure" {})))) {})]
    (try
      (let [a (peer p)]
        (ready a)
        (deliver fail true)
        (is (= 1011 (deref (:closed a) 1000 :timeout))))
      (finally (deliver fail true) (stop! p)))))
