(ns co.multiply.tropical.session
  "Tab sessions: one island tree per browser tab, outliving any single SSE
  connection.

  A session is created when the page is served, and the page is rendered from
  its first frame. The tab's stream (`ring/stream-init`) then attaches to it,
  and the session belongs to that page load from then on. Datastar drops that
  stream routinely: a hidden tab closes it, network blips retry it.
  A detached session keeps its islands, and so their resources, for `grace-ms`.
  A reconnect within that window gets what the page lacks, without reopening
  anything: what changed since, if the last event id it read is the mark that
  followed what it was sent (`sse/mark!`), and otherwise the whole cached
  frame. After the window, every island is unmounted and everything they held
  is released.

  Each session is run by one pump: a virtual thread that owns all of the
  session's mutable state, its island runtime included, and consumes the
  session's inbox (`co.multiply.tropical.signal.Inbox`). The inbox holds
  lifecycle events (attach, detach, close) and the subscriptions marked since
  the pump last looked. A change to something the islands read only marks its subscription,
  once until the pump takes it. Rendering happens on the pump, never on the
  thread that made the change, so a shared resource's loop never renders on
  anyone's behalf. Changes that arrive while a frame is being sent coalesce into
  the next one, so a slow client gets fewer, later frames, never a backlog, and
  the inbox never holds more than one mark per subscription.

  A session runs an app, a map:

  - `:root`           `(fn [{:keys [tab uid request]}] call)`: the session's root
                      island, given the session's context, which every island
                      reads with `use-session`. `:request` is the request that
                      created the session, where `co.multiply.tropical.ring`
                      created it.
  - `:patch-signals`  optional, `(fn [info] signals)`: a map of signals to send
                      with each patch, or nil. See `patch-signals`.
  - `:error-view`     optional, `(fn [failure] hiccup)`: what renders in place
                      of an island whose render throws, unless the island has
                      a view of its own (see `island/error-view`).
  - `:first-frame-ms` optional: how long, in ms, the session's first frame may
                      wait for reads still pending (below). Defaults to 0.
  - `:stream-max-ms`  optional: how long, in ms, the tab's stream stays open
                      before the session ends it, and Datastar reconnects it
                      at once, to this session, which sends what changed
                      meanwhile, since the page has the rest. Off by default.
                      For a balancer whose stickiness lapses, such as a
                      cookie that lasts ten minutes: set under that, each
                      reconnect passes the balancer while the tab is open,
                      and renews it if each request extends the cookie's
                      life, not if its life counts from when it was set.
                      A stream ends at a random point in the last tenth of
                      it, so streams opened together don't reconnect
                      together.

  The first frame a session delivers, to the page or over the stream that
  rebuilt the session, is the first a client sees from it. Its reads are often
  cold: a new tab, a first visit, every open tab after a restart. Sent at once,
  it shows their pending states, which the data replaces a moment later. So it
  waits until no read of its islands is pending, or `:first-frame-ms` passes,
  and goes out as it is then. It renders as reads land meanwhile, so islands
  that appear only once an earlier read lands get to read too. Later frames
  don't wait. A page waits at most 5 s for its frame (`render-page!`), so keep
  the wait well under that: 100 to 200 ms covers the reads worth waiting for.

  Scripts the islands declare (`co.multiply.tropical.script`) go over the
  stream once each, appended to the page's head, ahead of the first frame that
  needs them: those of the first frame too, unless the page put them in its
  head (`scripts-in-page!`), in case it left them out.
  A session belongs to one page load, which keeps the modules it loaded, so a
  reconnect doesn't send them again.

  A session whose pump fails, because a step outside the islands' renders
  threw, is closed, and so is its stream: the client reconnects into a new
  session instead of waiting on one that sends nothing."
  (:require
    [clojure.tools.logging :as log]
    [co.multiply.quiescent :as q]
    [co.multiply.tropical.internal.delivery :as delivery]
    [co.multiply.tropical.internal.frame :as frame]
    [co.multiply.tropical.internal.signal-patch :as signal-patch]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.sse :as sse])
  (:import
    [co.multiply.tropical.signal Inbox]
    [java.util ArrayList]
    [java.util.concurrent ThreadLocalRandom]))


(def grace-ms
  "How long, in ms, a session whose stream went away keeps its islands, and
  what they hold, for the tab to reconnect, before it closes."
  15000)


(def frame-ms
  "The least time, in ms, between a session's frames. Changes within it
  coalesce into the next frame, and the latest wins."
  50)


(def heartbeat-ms
  "How long, in ms, a quiet stream goes before it writes a heartbeat, an SSE
  comment: a dead connection is noticed only on write, and a proxy's idle
  timeout would close a live one."
  10000)


;; tab -> {:tab, :uid, :inbox, :closed, :attached?}
;; Changes only on lifecycle events (create, attach, detach, close).
(defonce ^{:doc "The open sessions, for display: an atom of a map keyed by tab,
  which changes only on a session's lifecycle events. Watch it through
  `summary`."}
  !sessions
  (atom {}))


(defn- now
  []
  (System/currentTimeMillis))


(defn- recycle-at
  "When a stream attached now is ended, for `app`'s `:stream-max-ms`: at a
  random point in the last tenth of it. Nil without one."
  [app]
  (when-some [ms (:stream-max-ms app)]
    (- (+ (now) (long ms)) (long (rand (quot (long ms) 10))))))


(defn- post!
  [session event]
  (Inbox/.post (:inbox session) event))


(defn- set-attached!
  [{:keys [tab inbox]} attached?]
  (swap! !sessions (fn [ss]
                     (if (identical? inbox (get-in ss [tab :inbox]))
                       (assoc-in ss [tab :attached?] attached?)
                       ss))))


(defn- unregister!
  [{:keys [tab inbox]}]
  (swap! !sessions (fn [ss]
                     (if (identical? inbox (get-in ss [tab :inbox]))
                       (dissoc ss tab)
                       ss))))


;; Pump -----------------------------------------------------------------------
;;
;; The pump's state is a map threaded through its loop:
;;   :app         the app the session runs
;;   :rt          the session's island runtime
;;   :root        the root island's Call
;;   :changed     an ArrayList the pump drains the inbox's marked subscriptions into
;;   :latest      the last rendered Frame
;;   :conn        the attached SSE generator, or nil
;;   :load        the page load the session belongs to: the first to attach
;;   :recycle-at  when the attached stream is ended for its age (`:stream-max-ms`), or nil
;;   :sent        the Frame the client is showing; nil means unknown, so resync
;;   :sent-id     the id of the mark (`sse/mark!`) that went out after `sent`, or nil for the page's own
;;   :marks       how many marks were sent, which numbers the next; :mark-prefix this session's, in their ids
;;   :ssr         a promise awaiting the first frame, for server-side rendering
;;   :scripts     how many of the runtime's scripts (`runtime/scripts`) the client was sent
;;   :ssr-scripts how many of them went with the first frame, for the page's head
;;   :frames      how many frames were sent; :chars how many chars they patched
;;   :signals     the signal patches the islands made (`island/use-patch-signals`) that the client
;;                wasn't sent, in order, merged where one patch does what two do
;;   :hold-until  until when the first frame waits for pending reads; nil once it doesn't
;;   :closed?     the session is closed and the pump exits


(defn- detach
  "Detaches the stream. What the client was sent stays known: the stream that
  attaches next says, by the last id it read, whether it has it (`:attach`)."
  [st]
  (set-attached! (:session st) false)
  (assoc st :conn nil :detached-at (now)))


(defn- close
  "Unmounts every island, releasing what they held, and ends the pump."
  [{:keys [rt conn session] :as st}]
  (log/debug "Session closing:" (:tab session))
  (unregister! session)
  (when conn (sse/close! conn))
  (runtime/dispose! rt)
  (assoc st :closed? true :conn nil))


(defn- fail
  "Closes the session after its pump threw `t`."
  [st t]
  (log/error t "Session failed:" (:tab (:session st)))
  (close st))


(defn- turn-away!
  "Answers an event that reached a closed session: a connection is closed, so
  its client retries into a new session, and a page render gets nil."
  [event]
  (when (vector? event)
    (case (first event)
      :attach (sse/close! (second event))
      :ssr (deliver (second event) nil)
      nil)))


(defn- handle
  [st event]
  (if (:closed? st)
    (do (turn-away! event)
      st)
    (case (if (vector? event) (first event) event)
      :close (close st)
      :ssr (assoc st :ssr (second event))
      ;; The page has the first frame's scripts in its head: don't send them.
      :scripts-in-page (update st :scripts #(max (long (or % 0)) (long (:ssr-scripts st 0))))
      :attach (let [[_ conn load last-id] event
                    prev (:conn st)]
                (if (and (contains? st :load) (not= load (:load st)))
                  ;; Another page with this tab id: a copy, such as a duplicated
                  ;; tab. Reloaded, it gets a tab of its own.
                  (do (sse/reload! conn)
                    st)
                  (do (when (and prev (not (identical? prev conn)))
                        (sse/close! prev))
                    (set-attached! (:session st) true)
                    ;; The client has what it was sent if the last id it read is
                    ;; the mark after it: then it gets only what changed since.
                    ;; Otherwise it may have any part of it, and resyncs.
                    (cond-> (assoc st :conn conn :load load :detached-at nil :recycle-at (recycle-at (:app st)))
                      (not= last-id (:sent-id st)) (assoc :sent nil)))))
      :detach (if (identical? (second event) (:conn st))
                (detach st)
                st)
      ;; From an island still mounted, as the instance that made it.
      :signals (let [[_ id !state signals] event]
                 (if (runtime/mounted-as? (:rt st) id !state)
                   (update st :signals signal-patch/append signals)
                   st)))))


(defn- render
  "Renders a new frame when something an island read has changed. Renders
  whether or not a client is attached, so what the islands hold, and the
  actions they expose, always follow the current state."
  [{:keys [rt root ^ArrayList changed closed? session] :as st}]
  (if (and (not closed?) (pos? (Inbox/.drain (:inbox session) changed)))
    (let [frame (runtime/frame! rt root changed)]
      (ArrayList/.clear changed)
      (assoc st :latest frame))
    st))


(defn- hold
  "`st`, holding back its first frame while one of its islands' reads is
  pending, until `:hold-until`. Once it stops holding, it never holds again."
  [{:keys [hold-until rt] :as st}]
  (cond
    (nil? hold-until) st
    (and (< (now) (long hold-until)) (runtime/pending? rt)) st
    :else (assoc st :hold-until nil)))


(defn- deliver-ssr
  [{:keys [ssr latest hold-until rt] :as st}]
  (if (and ssr latest (nil? hold-until))
    (let [scripts (runtime/scripts rt)]
      (deliver ssr {:frame latest :scripts scripts})
      (assoc st :ssr nil :sent latest :ssr-scripts (count scripts)))
    st))


(defn- patch-signals
  "The signals to send with a patch: what the app's `:patch-signals` returns,
  given a map of
    :tab, :uid  the session's
    :runtime    its island runtime, for `runtime/render-counts`
    :frame      the frame the client now shows
    :patched    the frames this patch sent
    :chars      how many chars this patch sent
    :frames     how many frames the session has sent, this one included
    :total      how many chars the session has sent, this patch included"
  [{:keys [app session rt latest frames chars]} patched n]
  (when-let [f (:patch-signals app)]
    (f {:tab     (:tab session)
        :uid     (:uid session)
        :runtime rt
        :frame   latest
        :patched patched
        :chars   n
        :frames  frames
        :total   chars})))


(defn- send-frame
  "Sends the frame's patches, and the app's signals with them. Nil if the
  stream closed."
  [{:keys [conn sent latest] :as st}]
  (let [{:keys [patches patched chars] :as prepared} (delivery/prepare-frame sent latest)
        st (-> st (update :frames (fnil inc 0)) (update :chars (fnil + 0) chars))]
    (cond
      (= patches frame/no-patches)
      (assoc st :sent latest)

      (and (delivery/send-frame! conn prepared)
        (if-some [signals (patch-signals st patched chars)]
          (sse/signals! conn signals)
          true))
      (assoc st :sent latest)

      :else
      nil)))


(defn- unsent-scripts
  "The URLs of the scripts the islands declared that the client wasn't sent."
  [{:keys [rt scripts]}]
  (let [declared (runtime/scripts rt)
        n        (long (or scripts 0))]
    (when (< n (count declared))
      (subvec declared n))))


(defn- send-scripts
  "Sends the scripts the client wasn't, appended to the page's head: Datastar
  gives each tag the page's nonce. Nil if the stream closed."
  [{:keys [conn rt] :as st}]
  (if-some [urls (unsent-scripts st)]
    (when (delivery/send-scripts! conn urls)
      (assoc st :scripts (count (runtime/scripts rt))))
    st))


(defn- send-signals
  "Sends the signal patches the islands made that the client wasn't, in
  order, after the frame that came with them. Nil if the stream closed."
  [{:keys [conn signals] :as st}]
  (when (every? #(sse/signals! conn %) signals)
    (dissoc st :signals)))


(defn- send-mark
  "Sends the mark `id` after what went out (`sse/mark!`), which the client
  sends back when it reconnects. Nil if the stream closed."
  [st id]
  (when (sse/mark! (:conn st) id)
    (assoc st :sent-id id)))


(defn- sync-client
  "Sends what the attached client lacks (`frame/patches`): the scripts its
  islands declared, then the topmost changed islands, and the children a list
  gained or lost, or the whole root when what it has is unknown; then the
  signals the islands patched; then a mark, all in one flush. Or a heartbeat,
  so a dead connection is noticed."
  [{:keys [conn sent latest last-send hold-until signals] :as st}]
  (cond
    (or (nil? conn) hold-until)
    st

    (or (not (identical? sent latest)) (unsent-scripts st) (seq signals))
    ;; One flush, ending in a mark: a client that read the mark has all of it.
    ;; A mark's id is never used again, even when its flush fails: the client
    ;; may have read it.
    (let [frame? (not (identical? sent latest))
          n      (inc (long (or (:marks st) 0)))
          sent   (sse/together conn
                   #(some-> st send-scripts (cond-> frame? send-frame) send-signals
                      (send-mark (str (:mark-prefix st) "." n))))]
      (if sent
        (do (when frame? (Thread/sleep (long frame-ms)))
          (assoc sent :last-send (now) :marks n))
        ;; The client may have any part of it: its next stream resyncs, and gets
        ;; the signal patches again.
        (assoc (detach st) :sent nil :marks n)))

    (< (+ (or last-send 0) (long heartbeat-ms)) (now))
    (if (sse/heartbeat! conn)
      (assoc st :last-send (now))
      (detach st))

    :else
    st))


(defn- recycle
  "Ends a stream that has been open for the app's `:stream-max-ms`. Datastar
  reconnects it at once, through whatever stands in front of the server, as
  the same page load, and with the last id it read gets only what changed
  since (`:attach`): often nothing, so the page isn't patched."
  [{:keys [conn recycle-at] :as st}]
  (if (and conn recycle-at (<= (long recycle-at) (now)))
    (do (sse/close! conn)
      (detach st))
    st))


(defn- expire
  [{:keys [conn detached-at closed?] :as st}]
  (if (and (nil? conn) (not closed?) (< (+ detached-at (long grace-ms)) (now)))
    (close st)
    st))


(defn- wait-ms
  [{:keys [conn detached-at hold-until recycle-at]}]
  (cond-> (if conn
            heartbeat-ms
            (max 1 (- (+ detached-at (long grace-ms)) (now))))
    hold-until (min (max 1 (- (long hold-until) (now))))
    (and conn recycle-at) (min (max 1 (- (long recycle-at) (now))))))


(defn- handle-events
  "Waits until the inbox has something or `ms` elapse, then handles the queued
  events. Marked subscriptions stay in the inbox for `render`."
  [st ^Inbox inbox ms]
  (Inbox/.await inbox ms)
  (loop [st st]
    (if-some [ev (Inbox/.poll inbox)]
      (recur (handle st ev))
      st)))


(defn- finish!
  [{:keys [session ssr]}]
  (some-> ssr (deliver nil))
  (deliver (:closed session) true)
  ;; Events that raced the close are turned away, here or by `attach!` and
  ;; `render-page!` themselves.
  (loop []
    (when-some [ev (Inbox/.poll (:inbox session))]
      (turn-away! ev)
      (recur)))
  (log/debug "Session closed:" (:tab session)))


(defn- context
  "The session's context, for its root and its islands' `use-session`: its tab
  and user, and the request that created it, if `app` carries one."
  [session app]
  (cond-> (select-keys session [:tab :uid])
    (contains? app ::request) (assoc :request (::request app))))


(defn- init
  "The pump's first state: the app's root island, rendered."
  [session app rt]
  (let [root  ((:root app) (context session app))
        start (now)]
    {:session     session
     :app         app
     :rt          rt
     :root        root
     :latest      (runtime/frame! rt root [])
     :changed     (ArrayList.)
     :detached-at start
     :mark-prefix (Long/toString (ThreadLocalRandom/.nextLong (ThreadLocalRandom/current) Long/MAX_VALUE) 36)
     :hold-until  (when (pos? (long (:first-frame-ms app 0)))
                    (+ start (long (:first-frame-ms app))))}))


(defn- step
  "Handles what arrived, then renders and sends what changed. A step that
  throws closes the session, with the connection it may just have attached."
  [st inbox]
  (let [st (handle-events st inbox (wait-ms st))]
    (try
      (-> st render hold deliver-ssr sync-client recycle expire)
      (catch Throwable t
        (fail st t)))))


(defn- pump!
  [{:keys [^Inbox inbox] :as session} app]
  (Inbox/.bind inbox)
  (let [rt (runtime/runtime (context session app) inbox (select-keys app [:error-view]))]
    (try
      (loop [st (try
                  (init session app rt)
                  (catch Throwable t
                    (fail {:session session :rt rt} t)))]
        (if (:closed? st)
          (finish! st)
          (recur (step st inbox))))
      (finally
        (runtime/dispose! rt)))))


;; API ------------------------------------------------------------------------


(defn- new-session
  [tab uid]
  {:tab tab :uid uid :inbox (Inbox.) :closed (promise) :attached? false})


(defn- start!
  "Starts the pump of a registered `session`, running `app`."
  [{:keys [tab] :as session} app]
  (log/debug "Session created:" tab)
  (q/compel
    (q/task
      (try
        (pump! session app)
        (catch Throwable t
          ;; The pump closes the session when a step fails. This is a failure
          ;; outside a step, or in closing.
          (log/error t "Session pump failed:" tab)
          (unregister! session)
          (finish! {:session session})))))
  session)


(defn ^:no-doc create!
  "Starts a session for the new `tab`, owned by `uid`, running `app` (see the
  namespace docstring)."
  [tab uid app]
  (let [session (new-session tab uid)]
    (swap! !sessions assoc tab session)
    (start! session app)))


(defn ^:no-doc held?
  "Whether this process holds a session for `tab`."
  [tab]
  (contains? @!sessions tab))


(defn ^:no-doc obtain!
  "The session for `tab` if `uid` owns it. A fresh one if `tab` is unknown: it
  expired, or the server restarted, and the client resyncs from its first frame.
  Concurrent calls for one unknown `tab` get one session. Nil if another user
  owns it."
  [tab uid app]
  (let [fresh   (new-session tab uid)
        [_ now] (swap-vals! !sessions #(if (contains? % tab) % (assoc % tab fresh)))
        session (get now tab)]
    (when (identical? session fresh)
      (start! session app))
    (when (= uid (:uid session))
      session)))


(defn ^:no-doc scripts-in-page!
  "Records that the page rendered from the session's first frame has that
  frame's scripts in its head, so the stream doesn't send them again."
  [session]
  (post! session :scripts-in-page))


(defn ^:no-doc render-page!
  "The session's first frame, for rendering the page server-side, as
  `{:frame :scripts}`, `:scripts` the URLs of the scripts its islands declared.
  The session records that the client shows the frame, so attaching sends only
  what changed since. Nil if the session closed, or took longer than 5 s."
  [session]
  (let [p (promise)]
    (post! session [:ssr p])
    (when (realized? (:closed session))
      (deliver p nil))
    (deref p 5000 nil)))


(defn ^:no-doc attach!
  "Attaches the connection `sse` from the page load `load`, whose client last
  read the event id `last-id`, or none. The first load to attach owns the
  session. Another is a copy of the page, with the same tab id, and is told to
  reload."
  ([session sse load]
   (attach! session sse load nil))
  ([session sse load last-id]
   (post! session [:attach sse load last-id])
   (when (realized? (:closed session))
     (sse/close! sse))))


(defn ^:no-doc detach!
  [session sse]
  (post! session [:detach sse]))


(defn ^:no-doc close!
  [session]
  (post! session :close))


(defn close-all!
  "Closes every session, as after reloading code in development. Each tab's
  stream ends, and Datastar reconnects about a second later into a fresh
  session, rendered by the code loaded then, whose first frame updates the page
  in place.

  Call it once the reloaded code serves requests: a tab that reconnects before
  that gets a session of the old code. A tab whose page loaded what has
  changed since, such as an edited script, or a head the app's `:revision`
  names anew, reloads instead (`ring/handler`)."
  []
  (run! close! (vals @!sessions)))


(defn close-user!
  "Closes every session of the user `uid` in this JVM, as when they sign out or
  are removed: their islands unmount, their actions are revoked, and their
  tabs' streams end. Datastar reconnects each about a second later, through the
  route's middleware. Once the app has ended the user's sign-in, that refuses
  the stream, and `ring/wrap-refused-streams` reloads the page into the app's
  own answer, such as its sign-in page. A user still signed in reconnects into
  a fresh session.

  End the sign-in first: a tab that reconnects before that gets a fresh session.
  A request that passed the middleware before the sign-in ended, and creates
  its session after this, keeps it. With several servers, each closes its own
  sessions, so the app tells every one of them. The Security guide has an
  island that follows the user's standing instead, which covers both."
  [uid]
  (run! close! (filter #(= uid (:uid %)) (vals @!sessions))))


(defn summary
  "How many sessions have their stream, and how many are waiting for it, as
  `{:attached :detached}`. Of every session, or of `sessions`, a value
  `!sessions` held."
  ([]
   (summary @!sessions))
  ([sessions]
   (let [attached (count (filter :attached? (vals sessions)))]
     {:attached attached
      :detached (- (count sessions) attached)})))


(comment
  @!sessions
  (close-all!))
