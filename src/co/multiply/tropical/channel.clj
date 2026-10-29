(ns co.multiply.tropical.channel
  "Island-owned bidirectional channels with ordered, resumable text and binary
  messages. A socket is a replaceable transport. A generation owns its endpoint,
  accepted input and unacknowledged output. Network changes never render islands."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [co.multiply.quiescent :as q]
    [co.multiply.scoped :refer [current-scope with-scope]]
    [co.multiply.tropical.internal.channel :as channel]
    [co.multiply.tropical.internal.websocket :as transport]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.script :as script]
    [co.multiply.tropical.session :as session]
    [ring.websocket.protocols :as ws]
    [s-exp.oda :as json])
  (:import
    [co.multiply.tropical.channel Buffers$Outgoing]
    [java.net URI]
    [java.util.concurrent Semaphore]))


(script/defscript client {:resource "co/multiply/tropical/channel.js"})
(defonce ^:private !endpoints (atom {}))


(declare revoke!)


(defn- current-session?
  [{:keys [session tab]}]
  (and session (identical? (:inbox session) (:inbox (get @session/!sessions tab)))))


(defn- detach!
  [endpoint link code]
  (locking endpoint
    (when (identical? link (:connection @endpoint))
      (let [state @endpoint marker (Object.)]
        (swap! endpoint assoc :connection nil :detached marker)
        (when-let [gen (:gen @(:!state link))]
          (when (#{1002 1009 1011 4003 4004} code)
            (channel/retire! gen :peer-ended)))
        (if (:revoked? state)
          (session/detach-transport! (:session state) (:lease state))
          (let [task (q/compel
                       (q/task
                         (Thread/sleep (long (get-in state [:options :resume-ms])))
                         (locking endpoint
                           (when (and (identical? marker (:detached @endpoint)) (nil? (:connection @endpoint)))
                             (session/detach-transport! (:session state) (:lease state))))))]
            (swap! endpoint assoc :timer task))))))
  nil)


(defn- end-generation!
  [endpoint gen reason]
  (when (identical? gen (:generation @endpoint))
    (when-let [link (:connection @endpoint)]
      (when (identical? gen (:gen @(:!state link)))
        (transport/close! link (if (= reason :application-failed) 1011 4003) "Channel ended")))))


(defn- establish!
  [endpoint link client previous position scope]
  (with-scope scope
    (let [old (:generation @endpoint)
          resumable? (and old (= client (:client old)) (not (:closing @(:!state old)))
                       (or (= previous (:id old))
                         (and (not (:confirmed @(:!state old))) (= previous (:previous-id old))))
                       (if (= previous (:id old))
                         (and (Buffers$Outgoing/.canResume (:outgoing old) (first position))
                           (<= (first position) (:sent-through @(:!state old) 0)))
                         true))]
      (when (and old (not resumable?))
        (channel/retire! old :continuity-lost)
        @(:drained old))
      (when (and (identical? link (:connection @endpoint)) (not (:revoked? @endpoint)))
        (let [reason (if (or old previous) :continuity-lost :initial)
              gen (if resumable? old
                      (let [!gen (atom nil)
                            gen (assoc (channel/generation client (:options @endpoint)
                                         #(transport/wake! (:connection @endpoint))
                                         #(end-generation! endpoint @!gen %))
                                  :previous-id previous :reason reason)]
                        (reset! !gen gen)
                        (locking endpoint
                          (if (:revoked? @endpoint)
                            (channel/retire! gen :revoked)
                            (swap! endpoint assoc :generation gen)))
                        (channel/start! gen (:open @endpoint) reason)
                        gen))]
          (if @(:ready gen)
            (when (and (identical? link (:connection @endpoint)) (not (:revoked? @endpoint)))
              (transport/welcome! link gen (if (= previous (:id gen)) :resumed (:reason gen)) position)
              ;; Retirement can precede installation on the replacement link.
              (when-let [reason (:closing @(:!state gen))]
                (end-generation! endpoint gen reason)))
            (transport/close! link 1011 "Channel initialization failed")))))))


(defn- manage!
  [endpoint]
  (q/compel
    (q/task
      (loop []
        (Semaphore/.acquire ^Semaphore (:manager @endpoint))
        (when-not (:revoked? @endpoint)
          (when-let [[link client generation position scope]
                     (locking endpoint
                       (let [hello (:hello @endpoint)] (swap! endpoint dissoc :hello) hello))]
            (try (establish! endpoint link client generation position scope)
              (catch Throwable t
                (log/error t "Channel establishment failed.")
                (transport/close! link 1011 "Channel establishment failed"))))
          (recur)))))
  nil)


(defn- revoke!
  [token endpoint hard?]
  (swap! !endpoints dissoc token)
  (let [state (locking endpoint
                (when-not (:revoked? @endpoint)
                  (let [state @endpoint]
                    (swap! endpoint assoc :revoked? true)
                    state)))]
    (when state
      (when-let [timer (:timer state)] (q/cancel timer))
      (Semaphore/.release ^Semaphore (:manager state))
      (when (or hard? (nil? (:generation state)))
        (when-let [link (:connection state)] (transport/close! link 4003 "Channel revoked")))
      (if-let [gen (:generation state)]
        (do
          (channel/retire! gen :unmounted)
          (q/compel (q/task @(:finished gen)
                      (when-let [session (:session state)]
                        (session/detach-transport! session (:lease state))))))
        (when-let [session (:session state)]
          (session/detach-transport! session (:lease state)))))))


(defn use-channel
  "Declares a stable bidirectional channel under this island's key `k`.
  Returns a descriptor for `attrs` and the browser module's mount/connect.

  `open` takes {:channel endpoint :reason :initial|:continuity-lost
  :generation id}. It runs once per generation, in its first upgrade's Scoped
  scope, on a compelled task. Run the peer's receive loop in this function or
  return its task (or a core.async completion channel, as returned by go).
  Completion must include processing and cleanup. Ordinary reconnects retain
  the identical endpoint. Continuity loss closes it, waits
  for this work to finish, then calls open with a fresh endpoint.

  The endpoint implements core.async ReadPort, WritePort and Channel. Take
  String/owned ByteBuffer messages; put String/byte[]/ByteBuffer (copied).
  nil is not a message. Takes return queued input, then nil after retirement.
  Process messages serially before taking again if processing must be ordered.
  Put completion means local admission, not peer receipt; use flush! for that.
  Full outgoing queues park puts. offer! never parks. Pending puts are FIFO,
  with a separate budget using the same byte/message limits as the outbox;
  exceeding it throws ex-info with :type :backpressure.

  Unmount, revocation or core.async/close! closes both admissions. Accepted
  input remains readable. Outgoing data is discarded and pending puts complete
  true as they are drained; new puts complete false. No socket event cancels
  the application's task. Keep its resources through finally cleanup. Closing
  an endpoint does not prove application processing is finished.

  Options (positive integers): :max-message-bytes 1048576,
  :max-queued-bytes 4194304, :max-queued-messages 64,
  :init-timeout-ms 10000 (transport handshake), :send-timeout-ms 10000,
  :ping-ms 30000, :pong-timeout-ms 10000, :resume-ms 60000.
  max-message-bytes must not exceed max-queued-bytes. Credits return on take.
  resume-ms retains a disconnected session; live SSE can retain it longer.
  Buffers are in memory. No traffic or status invalidates an island."
  ([k open] (use-channel k open nil))
  ([k open opts]
   (when-not (ifn? open) (throw (IllegalArgumentException. "use-channel needs an initializer.")))
   (let [opts (channel/options opts)
         {:keys [tab uid request]} (island/use-session)
         path (str (:uri request) (when-some [s (:query-string request)] (str "?" s)))
         {:keys [token endpoint]} (island/use-hold [::endpoint k]
                                    (fn []
                                      (let [token (str tab "_" (random-uuid))
                                            endpoint (atom {:uid uid :tab tab :path path :open open :options opts
                                                            :lease (Object.) :manager (Semaphore. 0)
                                                            :session (get @session/!sessions tab)})]
                                        (swap! !endpoints assoc token endpoint)
                                        (manage! endpoint)
                                        {:value {:token token :endpoint endpoint}
                                         :release #(revoke! token endpoint (not (current-session? @endpoint)))})))]
     (locking endpoint (swap! endpoint assoc :open open :options opts))
     {:token token :path path :module (script/use client)})))


(defn flush!
  "Returns a task waiting for peer receipt of puts committed before this call.
  Await put completion first to include a pending put. Receipt is neither
  application processing nor durable storage. Retirement fails this wait;
  cancelling the wait does not retract messages."
  [endpoint]
  (channel/flush! (channel/endpoint-generation endpoint)))


(defn attrs
  "Render the descriptor on the custom element that calls mount(element, opts)."
  [descriptor]
  {:data-tropical-channel (json/write-str descriptor)})


(defn- protocols
  [req]
  (some-> (get-in req [:headers "sec-websocket-protocol"]) (str/split #",")
    (->> (map str/trim))))


(defn request-kind
  "Internal Ring dispatch: :channel-check, :channel, or nil."
  {:no-doc true}
  [req]
  (when (= :get (:request-method req))
    (cond
      (= "websocket" (some-> (get-in req [:headers "upgrade"]) str/lower-case)) :channel
      (get-in req [:headers "tropical-channel"]) :channel-check)))


(defn- origin
  [s]
  (try
    (let [u (URI. ^String s)
          scheme (some-> (URI/.getScheme u) str/lower-case)
          host (some-> (URI/.getHost u) str/lower-case)
          port (URI/.getPort u)]
      (when (and (#{"http" "https"} scheme) host
              (nil? (URI/.getUserInfo u)) (nil? (URI/.getQuery u)) (nil? (URI/.getFragment u))
              (#{"" "/"} (URI/.getPath u)))
        [scheme host (if (= -1 port) (if (= scheme "https") 443 80) port)]))
    (catch Exception _ nil)))


(defn- same-origin?
  [app req]
  (let [expected (if-some [f (:channel-origin app)]
                   (origin (f req))
                   [(some-> (:scheme req) name) (some-> (:server-name req) str/lower-case)
                    (or (:server-port req) (if (= :https (:scheme req)) 443 80))])
        actual (origin (get-in req [:headers "origin"]))]
    (and actual (= actual expected))))


(defn- listener
  [token endpoint scope]
  (let [!link (atom nil)]
    (reify ws/Listener
      (on-open
        [_ socket]
        (let [link (transport/connection socket (:options @endpoint)
                     (fn [link client generation position]
                       (locking endpoint
                         (when (and (not (:revoked? @endpoint)) (identical? link (:connection @endpoint)))
                           (swap! endpoint assoc :hello [link client generation position scope])
                           (Semaphore/.release ^Semaphore (:manager @endpoint)))))
                     #(detach! endpoint %1 %2))
              previous (locking endpoint
                         (when-not (:revoked? @endpoint)
                           (let [old (:connection @endpoint)]
                             (when-let [timer (:timer @endpoint)] (q/cancel timer))
                             (swap! endpoint dissoc :timer)
                             (swap! endpoint assoc :connection link :detached nil)
                             (session/attach-transport! (:session @endpoint) (:lease @endpoint)
                               #(revoke! token endpoint true))
                             [old])))]
          (reset! !link link)
          (if previous
            (do
              (when-let [old (first previous)] (transport/close! old 4001 "Transport replaced"))
              (transport/start! link))
            (transport/close! link 4003 "Channel revoked"))))

      (on-message [_ _ value] (when-let [link @!link] (transport/receive! link value)))

      (on-pong [_ _ value] (when-let [link @!link] (transport/pong! link value)))

      (on-close [_ _ code _] (when-let [link @!link] (transport/close! link code "Transport ended")))

      (on-error [_ _ _] (when-let [link @!link] (transport/close! link 4000 "Transport failed"))))))


(defn response
  "Checks/upgrades a declared capability. Rechecks authority on every attempt;
  a successful HTTP check never authorizes a later upgrade by itself."
  {:no-doc true}
  [app req uid]
  (let [upgrade? (= :channel (request-kind req))
        offered (protocols req)
        tokens (keep #(when (str/starts-with? % "tropical.cap.") (subs % 13)) offered)
        token (if upgrade? (when (= 1 (count tokens)) (first tokens))
                  (get-in req [:headers "tropical-channel"]))
        endpoint (get @!endpoints token)
        {:keys [tab session path revoked?] owner :uid} (some-> endpoint deref)
        token-tab (some-> (re-matches #"([0-9a-f-]{36})_[0-9a-f-]{36}" (str token)) second)
        here (str (:uri req) (when-some [s (:query-string req)] (str "?" s)))
        resp (cond
               (and upgrade? (not (same-origin? app req)))
               {:status 403 :body "Channel Origin refused."}

               (or (nil? token) (and upgrade? (not (some #{"tropical.channel.v2"} offered))))
               {:status 400 :body "Missing Channel capability or protocol."}

               (and endpoint (not= uid owner))
               {:status 403 :body "Not your Channel."}

               (or (and endpoint (not (and session (identical? (:inbox session) (:inbox (get @session/!sessions tab))))))
                 (and (nil? endpoint) (parse-uuid (str token-tab)) (not (session/held? token-tab))))
               (do (log/warn "Channel's session is absent; its page must reopen its stream.")
                 {:status 409 :headers {"Tropical-Reopen" "true"} :body "Reopen the page's stream."})

               (or (nil? endpoint) revoked? (not= here path))
               {:status 404 :body "Channel capability is gone."}

               upgrade?
               {:status 101 :ring.websocket/protocol "tropical.channel.v2"
                :ring.websocket/listener (listener token endpoint (current-scope))}

               :else {:status 204})]
    (update resp :headers merge {"Cache-Control" "no-store"})))
