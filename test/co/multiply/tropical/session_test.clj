(ns co.multiply.tropical.session-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.quiescent :as q]
    [co.multiply.scoped :refer [ask scoping]]
    [co.multiply.tropical.action :as action :refer [use-action]]
    [co.multiply.tropical.hooks :as hooks]
    [co.multiply.tropical.island :as island :refer [defisland]]
    [co.multiply.tropical.resource :as resource]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.script :as script :refer [defscript]]
    [co.multiply.tropical.session :as session]
    [co.multiply.tropical.sse :as sse]
    [co.multiply.tropical.test-util :refer [entry manual-source observe-source]])
  (:import
    [java.util.concurrent CountDownLatch]))


(defn- patched-ids
  [htmls]
  (mapv #(second (re-find #"^<[a-z]+ id=\"([^\"]+)\"" %)) htmls))


(defn- e
  "The element id of island `id`, as the page sees it."
  [id]
  (island/element-id id))


(defn- sel
  "The selector of island `id`'s element."
  [id]
  (str "[id=\"" (e id) "\"]"))


(defisland value-view
  [key subject]
  [:p (str (observe-source key subject))
   [:button {:data-on:click (use-action :go (fn [_] nil))} "Go"]])


(defisland shell
  [key subject]
  [:div (value-view key subject)])


(deftest session-lifecycle
  (let [!sent  (atom [])
        uid    (str (random-uuid))
        tokens #(count (filter (fn [[_ v]] (= uid (:uid v))) @action/!actions))
        [key subject emit!] (manual-source)]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn [conn htmls] (swap! !sent conj [conn (patched-ids htmls)]) true)
                  sse/signals! (fn [_ _] true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [_ _] true)
                  sse/close! (fn [_] true)
                  session/grace-ms 300
                  session/frame-ms 0
                  resource/linger-ms 100]
      (let [s (session/create! (str (random-uuid)) uid {:root (fn [_] (shell key subject))})]
        (testing "the page is rendered from the first frame"
          (is (str/includes? (island/html (:frame (session/render-page! s))) (str "id=\"" (e "shell") "\""))))
        (testing "attaching after SSR sends only what changed since"
          (session/attach! s :conn-1 "load-1")
          (emit! 1)
          (Thread/sleep 200)
          (is (= [[:conn-1 [(e "shell/value-view")]]] @!sent)))
        (testing "a reconnect resyncs the whole root, without reopening the resource"
          (let [opened (:opened @resource/!totals)]
            (session/detach! s :conn-1)
            (session/attach! s :conn-2 "load-1")
            (Thread/sleep 200)
            (is (= [:conn-2 [(e "shell")]] (last @!sent)))
            (is (= opened (:opened @resource/!totals)))))
        (testing "past the grace period the session closes: tokens revoked, resources released"
          (is (= 1 (tokens)))
          (session/detach! s :conn-2)
          (Thread/sleep 600)
          (is (nil? (get @session/!sessions (:tab s))))
          (is (zero? (tokens)))
          (Thread/sleep 300)
          (is (nil? (entry key))))))))


;; Unbound, so `ask` returns its default where nothing binds it, rather than the root.
#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:dynamic *viewer*)


(defisland scope-view
  []
  [:p (str (ask *viewer* :none))])


(deftest a-session-renders-in-the-scope-that-created-it
  (with-redefs [sse/together (fn [_ f] (f))
                sse/close! (fn [_] true)]
    (let [s (scoping [*viewer* :page-request]
              (session/create! (str (random-uuid)) "u" {:root (fn [_] (scope-view))}))]
      (is (re-find #">:page-request<" (island/html (:frame (session/render-page! s))))
        "rendered on the session's pump, after the scope that created it has ended")
      (session/close! s)
      (is (deref (:closed s) 1000 false)))))


(deftest a-copy-of-the-page-is-told-to-reload
  (let [!sent     (atom [])
        !reloaded (atom [])
        [key subject emit!] (manual-source)]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn [conn htmls] (swap! !sent conj [conn (patched-ids htmls)]) true)
                  sse/signals! (fn [_ _] true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [_ _] true)
                  sse/close! (fn [_] true)
                  sse/reload! (fn [conn] (swap! !reloaded conj conn) true)
                  session/frame-ms 0]
      (let [s (session/create! (str (random-uuid)) "u" {:root (fn [_] (shell key subject))})]
        (session/render-page! s)
        (session/attach! s :conn-1 "load-1")
        (session/attach! s :copy "load-2")
        (emit! 1)
        (Thread/sleep 200)
        (is (= [:copy] @!reloaded))
        (is (= [[:conn-1 [(e "shell/value-view")]]] @!sent) "the first page load keeps the session")
        (testing "the first page load still reconnects"
          (session/detach! s :conn-1)
          (session/attach! s :conn-2 "load-1")
          (Thread/sleep 200)
          (is (= [:conn-2 [(e "shell")]] (last @!sent)))
          (is (= [:copy] @!reloaded)))
        (session/close! s)
        (is (deref (:closed s) 1000 false))))))


(deftest patch-signals-go-with-each-patch
  (let [!signals (atom [])
        [key subject emit!] (manual-source)]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn [_ _] true)
                  sse/signals! (fn [_ m] (swap! !signals conj m) true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [_ _] true)
                  sse/close! (fn [_] true)
                  session/frame-ms 0]
      (let [s (session/create! (str (random-uuid)) "u"
                {:root          (fn [_] (shell key subject))
                 :patch-signals (fn [{:keys [uid patched chars frames total runtime]}]
                                  {:uid     uid
                                   :patched (mapv :id patched)
                                   :frames  frames
                                   :chars   (and (pos? chars) (= chars total))
                                   :renders (runtime/render-counts runtime)})})]
        (session/render-page! s)
        (session/attach! s :conn "load-1")
        (emit! 1)
        (Thread/sleep 200)
        (is (= [{:uid     "u"
                 :patched ["shell/value-view"]
                 :frames  1
                 :chars   true
                 :renders {"shell" 1 "shell/value-view" 2}}]
               @!signals))
        (session/close! s)
        (is (deref (:closed s) 1000 false))))))


(deftest a-session-whose-pump-fails-closes-its-stream
  (let [!closed (atom [])
        [key subject emit!] (manual-source)]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn [_ _] true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [_ _] true)
                  sse/close! (fn [conn] (swap! !closed conj conn) true)
                  session/frame-ms 0]
      (let [tab (str (random-uuid))
            s   (session/create! tab "u" {:root          (fn [_] (shell key subject))
                                          :patch-signals (fn [_] (throw (ex-info "broken" {})))})]
        (session/render-page! s)
        (session/attach! s :conn "load-1")
        (emit! 1)
        (is (deref (:closed s) 1000 false))
        (is (= [:conn] @!closed) "so the client reconnects into a new session")
        (is (nil? (get @session/!sessions tab)))))))


(deftest a-root-that-throws-closes-the-session
  (let [tab (str (random-uuid))
        s   (session/create! tab "u" {:root (fn [_] (throw (ex-info "no root" {})))})
        t0  (System/nanoTime)]
    (is (nil? (session/render-page! s)))
    (is (< (- (System/nanoTime) t0) 1e9) "the page doesn't wait out the render timeout")
    (is (deref (:closed s) 1000 false))
    (is (nil? (get @session/!sessions tab)))))


(deftest a-closed-session-turns-events-away
  (let [!closed (atom [])
        page    (promise)]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/close! (fn [conn] (swap! !closed conj conn) true)
                  sse/heartbeat! (fn [_] true)]
      (#'session/handle {:closed? true} [:attach :conn-late])
      (#'session/handle {:closed? true} [:ssr page]))
    (is (= [:conn-late] @!closed) "a connection is closed, so its client retries into a new session")
    (is (and (realized? page) (nil? @page)) "a page render gets nil")))


(defisland blank
  []
  [:p "blank"])


(deftest concurrent-obtains-share-one-session
  (let [tab      (str (random-uuid))
        start    (CountDownLatch. 1)
        obtains  (doall (repeatedly 8 #(future (CountDownLatch/.await start) (session/obtain! tab "u" {:root (fn [_] (blank))}))))
        _        (CountDownLatch/.countDown start)
        sessions (into #{} (map deref) obtains)]
    (is (= 1 (count sessions)))
    (is (identical? (first sessions) (get @session/!sessions tab)))
    (session/close! (first sessions))
    (is (deref (:closed (first sessions)) 1000 false))))


(defisland go-button
  []
  [:button {:data-on:click (use-action :go (fn [_] nil))} "Go"])


(defn- reconnected
  "Runs a session of `shell`: attached, a value emitted, then the stream ended
  by `end!`, given the session, then attached again as the same page load,
  with the last mark id the first stream was sent if `last-id?`, then another
  value emitted. Returns what each connection was sent, `[conn ids]`, in order,
  and the connections closed. `opts` go in the app."
  [end! last-id? opts]
  (let [!sent   (atom [])
        !closed (atom [])
        !marks  (atom [])
        [key subject emit!] (manual-source)]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn [conn htmls] (swap! !sent conj [conn (patched-ids htmls)]) true)
                  sse/signals! (fn [_ _] true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [conn id] (swap! !marks conj [conn id]) true)
                  sse/close! (fn [conn] (swap! !closed conj conn) true)
                  session/frame-ms 0]
      (let [s (session/create! (str (random-uuid)) "u" (merge {:root (fn [_] (shell key subject))} opts))]
        (session/render-page! s)
        (session/attach! s :conn-1 "load-1")
        (emit! 1)
        (Thread/sleep 100)
        (let [early @!closed]
          (end! s)
          (session/attach! s :conn-2 "load-1" (when last-id? (second (last @!marks))))
          (Thread/sleep 100)
          (let [reattached @!sent]
            (emit! 2)
            (Thread/sleep 100)
            (session/close! s)
            (deref (:closed s) 1000 false)
            {:early early :closed @!closed :reattached reattached :sent @!sent}))))))


(defn- resent?
  "Whether `conn` was sent the whole root of `shell`."
  [sent conn]
  (some #(= [conn [(e "shell")]] %) sent))


(deftest a-stream-is-ended-at-its-age-and-reconnects-to-its-session
  (let [{:keys [early closed reattached sent]} (reconnected (fn [_] (Thread/sleep 350)) true {:stream-max-ms 300})]
    (is (empty? early) "not before its age")
    (is (= :conn-1 (first closed)) "ended at its age")
    (is (not-any? #(= :conn-2 (first %)) reattached) "the reconnect, with the last id it read, gets nothing it has")
    (is (= [:conn-2 [(e "shell/value-view")]] (last sent)) "then a change, as a patch of its island")))


(deftest a-reconnect-gets-what-its-page-lacks
  (let [drop! #(session/detach! % :conn-1)]
    (testing "the page read the last mark: what changed since"
      (let [{:keys [reattached sent]} (reconnected drop! true nil)]
        (is (not (resent? reattached :conn-2)))
        (is (= [:conn-2 [(e "shell/value-view")]] (last sent)))))
    (testing "without it, it may lack any of it: the whole root"
      (is (resent? (:reattached (reconnected drop! false nil)) :conn-2)))))


(deftest a-flush-that-fails-resyncs-the-next-stream
  (let [!sent   (atom [])
        !fail   (atom false)
        !marks  (atom [])
        [key subject emit!] (manual-source)]
    (with-redefs [sse/together (fn [_ f] (let [r (f)] (and (not @!fail) r)))
                  sse/patch! (fn [conn htmls] (swap! !sent conj [conn (patched-ids htmls)]) true)
                  sse/signals! (fn [_ _] true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [conn id] (swap! !marks conj [conn id]) true)
                  sse/close! (fn [_] true)
                  session/frame-ms 0]
      (let [s (session/create! (str (random-uuid)) "u" {:root (fn [_] (shell key subject))})]
        (session/render-page! s)
        (session/attach! s :conn-1 "load-1")
        (emit! 1)
        (Thread/sleep 100)
        (let [read-id (second (last @!marks))]
          (reset! !fail true)
          (emit! 2)
          (Thread/sleep 100)
          (reset! !fail false)
          (session/attach! s :conn-2 "load-1" read-id)
          (Thread/sleep 100)
          (is (resent? @!sent :conn-2)
            "the client may have part of what failed, though it read the mark before: the whole root")
          (is (apply distinct? (map second @!marks)) "a failed flush's mark id isn't used again"))
        (session/close! s)
        (is (deref (:closed s) 1000 false))))))


(deftest closing-a-user-closes-their-sessions-only
  (with-redefs [sse/together (fn [_ f] (f))
                sse/close! (fn [_] true)]
    (let [uid    (str (random-uuid))
          tokens #(count (filter (fn [[_ v]] (= uid (:uid v))) @action/!actions))
          app    {:root (fn [_] (go-button))}
          mine   (doall (repeatedly 2 #(session/create! (str (random-uuid)) uid app)))
          theirs (session/create! (str (random-uuid)) (str (random-uuid)) app)]
      (run! session/render-page! (conj mine theirs))
      (is (= 2 (tokens)))
      (session/close-user! uid)
      (is (every? #(deref (:closed %) 1000 false) mine))
      (is (not-any? #(contains? @session/!sessions (:tab %)) mine))
      (is (zero? (tokens)) "their actions are revoked")
      (is (not (realized? (:closed theirs))) "another user's session stays")
      (is (identical? theirs (get @session/!sessions (:tab theirs))))
      (session/close! theirs)
      (is (deref (:closed theirs) 1000 false)))))


(defn- fresh-key
  []
  [::read (random-uuid)])


(defn- after
  "A source for `observe-source` that emits `v` once `ms` have passed since it
  opened."
  [ms v]
  (fn [emit!]
    (q/then (q/sleep ms) (fn [_] (emit! v) (q/promise)))))


(defisland reading
  [key subject]
  (let [v (observe-source key subject)]
    [:p (if (= hooks/pending v) "pending" (str "read " v))]))


(defisland two-step
  "Reads `second` only once `first` has landed."
  [[k1 s1] [k2 s2]]
  (let [v (observe-source k1 s1)]
    [:div (if (= hooks/pending v) "pending" (reading k2 s2))]))


(defn- first-frame
  "The page rendered for a new session whose root is `root`, with more of the
  app in `app`, and how long it took: `[html ms]`."
  [root app]
  (let [start (System/nanoTime)
        s     (session/create! (str (random-uuid)) "alice" (merge {:root (fn [_] root)} app))
        html  (island/html (:frame (session/render-page! s)))
        ms    (quot (- (System/nanoTime) start) 1000000)]
    (session/close! s)
    [html ms]))


(deftest a-first-frame-waits-for-its-reads
  (testing "reads that land within the wait are in the first frame"
    (let [[html ms] (first-frame (two-step [(fresh-key) (after 30 1)] [(fresh-key) (after 30 2)])
                      {:first-frame-ms 2000})]
      (is (re-find #"read 2" html) "a read that starts only once an earlier one lands, too")
      (is (< ms 2000))))
  (testing "a read that doesn't land within the wait is pending in it"
    (let [[html ms] (first-frame (reading (fresh-key) (fn [_] nil)) {:first-frame-ms 100})]
      (is (re-find #"pending" html))
      (is (<= 100 ms 1000))))
  (testing "without a wait, the first frame goes out at once"
    (let [[html ms] (first-frame (reading (fresh-key) (after 500 1)) {})]
      (is (re-find #"pending" html))
      (is (< ms 500)))))


(deftest a-rebuilt-session-waits-for-its-reads-too
  (let [!sent (atom [])]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn [_ htmls] (swap! !sent conj htmls) true)
                  sse/signals! (fn [_ _] true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [_ _] true)
                  sse/close! (fn [_] true)]
      (let [s (session/obtain! (str (random-uuid)) "alice"
                {:root (fn [_] (reading (fresh-key) (after 50 1))) :first-frame-ms 2000})]
        (session/attach! s :conn "load")
        (Thread/sleep 400)
        (is (re-find #"read 1" (ffirst @!sent)) "its first patch, which a client resyncs from, has the read")
        (session/close! s)
        (is (deref (:closed s) 1000 false))))))


(def ^:private !rows (atom []))


(defisland row
  {:key identity}
  [n]
  [:li "row " n])


(defisland rows
  []
  [:ul (map row (island/use-watch !rows))])


(deftest a-list-sends-the-rows-it-gains-and-loses
  (reset! !rows [1 2 3])
  (let [!events (atom [])]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn
                               ([_ htmls] (swap! !events conj [:morph (patched-ids htmls)]) true)
                               ([_ htmls {:keys [mode selector]}]
                                (swap! !events conj [mode selector (patched-ids htmls)])
                                true))
                  sse/remove! (fn [_ selector] (swap! !events conj [:remove selector]) true)
                  sse/signals! (fn [_ _] true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [_ _] true)
                  sse/close! (fn [_] true)
                  session/frame-ms 0]
      (let [s (session/create! (str (random-uuid)) "alice" {:root (fn [_] (rows))})]
        (session/render-page! s)
        (session/attach! s :conn "load")
        (swap! !rows conj 4)
        (Thread/sleep 200)
        (is (= [[:after (sel "rows/row.3") [(e "rows/row.4")]]] @!events) "the new row, after the last")
        (reset! !events [])
        (swap! !rows #(vec (rest %)))
        (Thread/sleep 200)
        (is (= [[:remove (sel "rows/row.1")]] @!events) "the removed row, by its id")
        (session/close! s)
        (is (deref (:closed s) 1000 false))))))


(defscript chart
  {:js "export const chart = 1;"})


(def ^:private !charted (atom false))


(defisland chart-view
  []
  (script/use chart)
  [:figure "chart"])


(defisland dashboard
  []
  [:div (when (island/use-watch !charted) (chart-view))])


(defn- loads-chart?
  "Whether `event` appends to the page's head a module tag for `chart`."
  [[mode selector htmls]]
  (and (= [:append "head"] [mode selector])
    (= 1 (count htmls))
    (str/includes? (first htmls) "type=\"module\"")
    (str/includes? (first htmls) (str "src=\"" (script/url chart) "\""))))


(deftest a-script-goes-to-the-page-once
  (let [!events (atom [])]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn
                               ([_ htmls] (swap! !events conj [:morph (patched-ids htmls)]) true)
                               ([_ htmls {:keys [mode selector]}] (swap! !events conj [mode selector htmls]) true))
                  sse/signals! (fn [_ _] true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [_ _] true)
                  sse/close! (fn [_] true)
                  session/frame-ms 0]
      (testing "a script of the first frame"
        (reset! !charted true)
        (let [s (session/create! (str (random-uuid)) "alice" {:root (fn [_] (dashboard))})]
          (is (= [(script/url chart)] (:scripts (session/render-page! s))) "is the page's")
          (session/attach! s :conn "load")
          (Thread/sleep 200)
          (is (= 1 (count @!events)))
          (is (loads-chart? (first @!events)) "and goes over the stream too, for a page that left it out")
          (testing "isn't sent again on a reconnect"
            (reset! !events [])
            (session/detach! s :conn)
            (session/attach! s :conn-2 "load")
            (Thread/sleep 200)
            (is (= [[:morph [(e "dashboard")]]] @!events)))
          (session/close! s)
          (is (deref (:closed s) 1000 false))))
      (testing "a script first declared in a later frame goes ahead of it"
        (reset! !charted false)
        (reset! !events [])
        (let [s (session/create! (str (random-uuid)) "alice" {:root (fn [_] (dashboard))})]
          (is (empty? (:scripts (session/render-page! s))))
          (session/attach! s :conn "load")
          (reset! !charted true)
          (Thread/sleep 200)
          (is (= 2 (count @!events)))
          (is (loads-chart? (first @!events)))
          (is (= [:morph [(e "dashboard")]] (second @!events)))
          (testing "and once"
            (reset! !events [])
            (reset! !charted false)
            (Thread/sleep 100)
            (reset! !charted true)
            (Thread/sleep 200)
            (is (not-any? loads-chart? @!events)))
          (session/close! s)
          (is (deref (:closed s) 1000 false)))))))


;; Signals an island patches -------------------------------------------------------


(def ^:private !patch (atom nil))
(def ^:private !importing (atom nil))
(def ^:private !form-shown (atom true))


(defisland import-form
  []
  (reset! !patch (island/use-patch-signals))
  [:form [:input {:data-bind "draft"}] [:p (str (island/use-watch !importing))]])


(defisland import-page
  []
  [:main (when (island/use-watch !form-shown) (import-form))])


(deftest an-island-patches-signals-outside-an-action
  (reset! !importing nil)
  (reset! !form-shown true)
  (let [!events (atom [])]
    (with-redefs [sse/together (fn [_ f] (f))
                  sse/patch! (fn [_ htmls & _] (swap! !events conj [:morph (patched-ids htmls)]) true)
                  sse/remove! (fn [_ selector] (swap! !events conj [:remove selector]) true)
                  sse/signals! (fn [_ m] (swap! !events conj [:signals m]) true)
                  sse/heartbeat! (fn [_] true)
                  sse/mark! (fn [_ _] true)
                  sse/close! (fn [_] true)
                  session/frame-ms 0]
      (let [s (session/create! (str (random-uuid)) "alice" {:root (fn [_] (import-page))})]
        (session/render-page! s)
        (session/attach! s :conn "load")
        (Thread/sleep 100)
        (testing "from another thread, after the frame that came with them"
          @(q/task
             (reset! !importing :done)
             (@!patch {:draft "converted"}))
          (Thread/sleep 200)
          (is (= [[:morph [(e "import-page/import-form")]] [:signals {"draft" "converted"}]] @!events)))
        (testing "while the tab is away, kept, merged where one patch does both, and sent once it's back"
          (reset! !events [])
          (session/detach! s :conn)
          (Thread/sleep 50)
          (@!patch {:draft "a" :form {:step 1}})
          (@!patch {"draft" "b" :form {:done true}})
          ;; Replacing a value with nil, then merging a map into it: not one patch.
          (@!patch {:form nil})
          (@!patch {:form {:step 2}})
          (Thread/sleep 100)
          (is (= [] @!events))
          (session/attach! s :conn-2 "load")
          (Thread/sleep 200)
          (is (= [[:morph [(e "import-page")]]
                  [:signals {"draft" "b" "form" nil}]
                  [:signals {"form" {"step" 2}}]]
                 @!events)))
        (testing "from an island no longer mounted, dropped, even once one is mounted again under its id"
          (let [stale @!patch]
            (reset! !form-shown false)
            (Thread/sleep 100)
            (reset! !events [])
            (stale {:draft "late"})
            (reset! !form-shown true)
            (Thread/sleep 200)
            (stale {:draft "later"})
            (Thread/sleep 100)
            (is (not-any? #(= :signals (first %)) @!events))
            (@!patch {:draft "fresh"})
            (Thread/sleep 100)
            (is (= [:signals {"draft" "fresh"}] (last @!events)))))
        (testing "signals are a map"
          (is (thrown? IllegalArgumentException (@!patch [:draft "x"]))))
        (session/close! s)
        (is (deref (:closed s) 1000 false))))))
