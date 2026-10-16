(ns co.multiply.tropical.resource-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [co.multiply.quiescent :as q]
    [co.multiply.tropical.action :as action :refer [use-action]]
    [co.multiply.tropical.hooks :as hooks]
    [co.multiply.tropical.island :as island :refer [defisland use-watch]]
    [co.multiply.tropical.resource :as resource]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.shared :as shared :refer [defshared]]
    [co.multiply.tropical.test-util :refer [entry harness manual-source observe-source]])
  (:import
    [java.util.concurrent CountDownLatch LinkedBlockingQueue TimeUnit]))


(use-fixtures :each
  (fn [t]
    (with-redefs [resource/linger-ms 200]
      (t))))


(defn- reader
  [key subject]
  (island/island-fn "reader" nil (fn [] [:p (str (observe-source key subject))])))


(defn- await-latch
  "Waits for `latch`, ignoring interrupts: a subject that is slow to notice it
  was cancelled."
  [latch]
  (loop []
    (when-not (try
                (CountDownLatch/.await latch)
                true
                (catch InterruptedException _ false))
      (recur))))


(deftest sessions-share-one-resource
  (let [[key subject] (manual-source)
        opened (:opened @resource/!totals)
        a      (harness (reader key subject))
        b      (harness (reader key subject))]
    ((:step a)) ((:step b))
    (is (= {:state :connecting :subscribers 2} (entry key)))
    (is (= (inc opened) (:opened @resource/!totals)) "opened once")
    (runtime/dispose! (:rt a))
    (is (= 1 (:subscribers (entry key))) "still held by b")
    (runtime/dispose! (:rt b))
    (is (= :lingering (:state (entry key))))
    (Thread/sleep 400)
    (is (nil? (entry key)) "closed after the linger")))


(deftest emitting-re-renders-the-reader
  (let [[key subject emit!] (manual-source)
        {:keys [step rt]} (harness (reader key subject))]
    (is (re-find #"pending" (island/html (step))))
    (emit! 42)
    (is (re-find #">42<" (island/html (step))))
    (is (= :live (:state (entry key))))
    (runtime/dispose! rt)))


(deftest a-subject-runs-on-a-thread-of-its-own
  (let [connected (CountDownLatch. 1)
        key       [::slow (random-uuid)]
        subject   (fn [emit!]
                    (CountDownLatch/.await connected)
                    (emit! :connected)
                    nil)
        {:keys [step rt]} (harness (reader key subject))]
    (testing "a subject still connecting holds up neither the render nor the registry"
      (let [frame (deref (q/task (step)) 1000 nil)]
        (is (some? frame) "the render didn't wait for the subject")
        (is (re-find #"pending" (str (some-> frame island/html)))))
      (let [[other other-subject] (manual-source)
            !value (deref (q/task (resource/acquire! other other-subject)) 1000 nil)]
        (is (some? !value) "opening another resource didn't wait for the subject")
        (is (= :connecting (:state (get (resource/summary @resource/!entries) other))))
        (some->> !value (resource/release! other))))
    (CountDownLatch/.countDown connected)
    (Thread/sleep 100)
    (is (re-find #">:connected<" (island/html (step))))
    (runtime/dispose! rt)))


(deftest closing-undoes-the-subject
  (testing "a cleanup fn is called once, when the resource closes"
    (let [!cleanups (atom 0)
          key       [::cleanup (random-uuid)]
          {:keys [step rt]} (harness (reader key (fn [_] #(swap! !cleanups inc))))]
      (step)
      (runtime/dispose! rt)
      (Thread/sleep 100)
      (is (zero? @!cleanups) "not while it lingers")
      (Thread/sleep 300)
      (is (nil? (entry key)))
      (is (= 1 @!cleanups))))
  (testing "a task the subject started is cancelled when the resource closes"
    (let [stopped (promise)
          key     [::loop (random-uuid)]
          subject (fn [emit!]
                    (q/task
                      (try
                        (loop [n 0]
                          (emit! n)
                          (Thread/sleep 10)
                          (recur (inc n)))
                        (finally
                          (deliver stopped true)))))
          {:keys [step rt]} (harness (reader key subject))]
      (step)
      (Thread/sleep 50)
      (is (re-find #">\d+<" (island/html (step))))
      (runtime/dispose! rt)
      (is (deref stopped 1000 false))
      (is (nil? (entry key))))))


(deftest a-resource-is-forgotten-before-it-tears-down
  ;; Its teardown runs on other threads, and usually ends after the registry is
  ;; done closing, whichever starts first. Closing is made to wait for the
  ;; teardown here, so the order shows every time: a teardown started before
  ;; the registry forgot the resource would find its entry.
  (let [cancel! @#'resource/cancel!
        seen    (promise)
        key     [::forgotten (random-uuid)]
        subject (fn [emit!]
                  (q/task
                    (try
                      (loop [n 0]
                        (emit! n)
                        (Thread/sleep 10)
                        (recur (inc n)))
                      (finally
                        (deliver seen (entry key))))))]
    (with-redefs [resource/cancel! (fn [task] @(cancel! task))]
      (let [{:keys [step rt]} (harness (reader key subject))]
        (step)
        (runtime/dispose! rt)
        (is (nil? (deref seen 1000 ::not-torn-down)) "gone from the registry as its teardown runs")))))


(deftest every-close-stops-its-resource
  (let [stopped (vec (repeatedly 30 promise))
        opened  (mapv (fn [p]
                        (let [key [::many (random-uuid)]]
                          [key (resource/acquire! key (fn [_]
                                                        (q/task
                                                          (try
                                                            (Thread/sleep 100000)
                                                            (finally
                                                              (deliver p true))))))]))
                  stopped)]
    (Thread/sleep 50)
    (run! #(apply resource/release! %) opened)
    (is (every? #(deref % 2000 false) stopped))))


(deftest a-subject-that-returns-after-its-resource-closed-is-undone
  (let [connected (CountDownLatch. 1)
        cleaned   (promise)
        key       [::late (random-uuid)]
        subject   (fn [emit!]
                    (await-latch connected)
                    (emit! :late)
                    #(deliver cleaned true))
        !value    (resource/acquire! key subject)]
    (resource/release! key !value)
    (Thread/sleep 400)
    (is (nil? (get @resource/!entries key)) "closed while the subject was still connecting")
    (CountDownLatch/.countDown connected)
    (is (deref cleaned 1000 false) "its cleanup fn is called as it returns")
    (is (= hooks/pending @!value) "and its emit does nothing")))


(defn- failing-source
  "A source for `observe-source` whose resources emit `:ok`, then fail when the test
  says. Returns `[key subject opened]`: take from `opened` to wait for the next
  resource to have emitted, and get the fn that fails it."
  []
  (let [opened (LinkedBlockingQueue.)]
    [[::failing (random-uuid)]
     (fn [emit!]
       (let [p (promise)]
         (emit! :ok)
         (LinkedBlockingQueue/.put opened #(deliver p (ex-info "connection lost" {})))
         (q/task (throw @p))))
     opened]))


(defn- next-opened
  [opened]
  (or (LinkedBlockingQueue/.poll opened 1 TimeUnit/SECONDS)
    (throw (ex-info "No resource opened." {}))))


(deftest a-failed-resource-throws-and-the-next-reader-opens-another
  (let [[key subject opened] (failing-source)
        n (:opened @resource/!totals)
        a (harness (reader key subject))]
    ((:step a))
    (let [fail! (next-opened opened)]
      (is (re-find #">:ok<" (island/html ((:step a)))))
      (is (= :live (:state (entry key))))
      (fail!))
    (Thread/sleep 100)
    (is (re-find #"failed to render.*connection lost" (island/html ((:step a)))))
    (is (nil? (entry key)) "the failed resource is closed")
    (testing "the next reader opens a new resource, which the failed one's release leaves alone"
      (let [b (harness (reader key subject))]
        ((:step b))
        (next-opened opened)
        (is (re-find #">:ok<" (island/html ((:step b)))))
        (is (= (+ n 2) (:opened @resource/!totals)))
        (runtime/dispose! (:rt a))
        (is (= 1 (:subscribers (entry key))))
        (runtime/dispose! (:rt b))))))


(deftest a-subject-that-throws-fails-its-resource
  (let [key [::throws (random-uuid)]
        {:keys [step rt]} (harness (reader key (fn [_] (throw (ex-info "refused" {})))))]
    (step)
    (Thread/sleep 100)
    (is (re-find #"failed to render.*refused" (island/html (step))))
    (is (nil? (entry key)))
    (runtime/dispose! rt)))


(deftest concurrent-emissions-cannot-replace-a-terminal-failure
  (let [fail! @#'resource/fail!]
    ;; Scheduling-sensitive: more rounds improve detection, but do not guarantee it.
    (dotimes [_ 1000]
      (let [opened (promise)
            fail (promise)
            failed (promise)
            gate (CountDownLatch. 1)
            key [::racing-failure (random-uuid)]
            error (ex-info "source stopped" {})]
        (with-redefs [resource/fail! (fn [k cell e]
                                       (fail! k cell e)
                                       (when (= k key) (deliver failed true)))]
          (let [cell (resource/acquire! key (fn [emit!]
                                              (deliver opened emit!)
                                              (q/task (throw @fail))))
                emit! (deref opened 2000 nil)]
            (try
              (is (some? emit!))
              (when emit!
                (let [workers (mapv (fn [_]
                                      (q/task
                                        (when-not (CountDownLatch/.await gate 2 TimeUnit/SECONDS)
                                          (throw (ex-info "emitters were not started" {})))
                                        (dotimes [_ 2000] (emit! :late))))
                                (range 6))]
                  (CountDownLatch/.countDown gate)
                  (deliver fail error)
                  (is (deref failed 2000 false) "failure publication finishes alongside the emitters")
                  (is (every? #(deref (q/await % 2000) 3000 false) workers))
                  (is (every? #(nil? (q/get-ex %)) workers))
                  (emit! :after)
                  (is (identical? error (:error @cell)) "neither an in-flight nor a later emit can revive it")))
              (finally
                (CountDownLatch/.countDown gate)
                (deliver fail error)
                (deref failed 2000 false)
                (resource/release! key cell)))))))))


(deftest a-new-key-releases-the-old-resource
  (let [[_ subject] (manual-source)
        !key (atom [::path :a])
        {:keys [step rt]} (harness #((reader @!key subject)))]
    (step)
    (reset! !key [::path :b])
    (step)
    (is (= :lingering (:state (entry [::path :a]))))
    (is (= 1 (:subscribers (entry [::path :b]))))
    (runtime/dispose! rt)))


(def ^:private !allowed (atom true))


(defisland panel
  [key subject]
  [:div (str (observe-source key subject))
   [:button {:data-on:click (use-action :go (fn [_] nil))} "Go"]])


(defisland denied
  []
  [:p "No access"])


(defisland gate
  [key subject]
  (if (use-watch !allowed)
    (panel key subject)
    (denied)))


(deftest a-gate-releases-what-its-branch-held
  (reset! !allowed true)
  (let [[key subject emit!] (manual-source)
        uid    (str (random-uuid))
        tokens #(count (filter (fn [[_ v]] (= uid (:uid v))) @action/!actions))
        {:keys [step rt]} (harness #(gate key subject) {:tab "tab" :uid uid})]
    (step)
    (is (= 1 (:subscribers (entry key))))
    (is (= 1 (tokens)))
    (testing "revoking unmounts the panel: resource released, action token revoked"
      (reset! !allowed false)
      (is (re-find #"No access" (island/html (step))))
      (is (= :lingering (:state (entry key))))
      (is (zero? (tokens))))
    (testing "granting again within the linger remounts without reopening"
      (let [opened (:opened @resource/!totals)]
        (reset! !allowed true)
        (step)
        (is (= 1 (:subscribers (entry key))))
        (is (= opened (:opened @resource/!totals)))
        (is (= 1 (tokens)))))
    (testing "the token stays the same across renders, and is revoked on dispose"
      (let [token  #(re-find #"/act/[0-9a-f-]+" (island/html %))
            before (token (step))]
        (emit! 7)
        (let [frame (step)]
          (is (re-find #">7<" (island/html frame)))
          (is (= before (token frame))))
        (runtime/dispose! rt)
        (is (zero? (tokens)))))))


;; Watching the registry ---------------------------------------------------------


(defisland named-reader
  {:key (fn [id & _] id)}
  [_id key subject]
  [:p (str (observe-source key subject))])


(defisland open-view
  "The resources `use-summary` gives for `scope`, those `names` names, as
  `name:subscribers:state`."
  {:key (fn [scope _] (name scope))}
  [scope names]
  [:p {:class (str "view-" (name scope))}
   (str/join " " (sort (keep (fn [[k {:keys [subscribers state]}]]
                               (when-some [n (names (first (:args (resource/describe k))))]
                                 (str n ":" subscribers ":" (name state))))
                         (resource/use-summary scope))))])


(defisland tab-root
  "A tab whose islands read `rows`, `[id key subject]` each, and, given
  `names`, show both views of what is open."
  [rows names]
  [:div (map (fn [[id k s]] (named-reader id k s)) rows)
   (when names
     (list (open-view :session names) (open-view :server names)))])


(defn- view
  "What `open-view` of `scope` shows in `html`."
  [scope html]
  (second (re-find (re-pattern (str "class=\"view-" (name scope) "\"[^>]*>([^<]*)<")) html)))


(deftest a-session-sees-what-its-islands-hold
  (let [[k1 s1] (manual-source)
        [k2 s2] (manual-source)
        [k3 s3] (manual-source)
        names   {k1 "one" k2 "two" k3 "three"}
        !a      (atom [["a" k1 s1] ["b" k1 s1] ["c" k2 s2]])
        tab-a   (harness #(tab-root @!a names) {:tab "a" :uid "alice"})
        !b      (atom [["d" k2 s2] ["e" k3 s3]])
        tab-b   (harness #(tab-root @!b nil) {:tab "b" :uid "bob"})]
    (try
      ((:step tab-b))
      (let [html (island/html ((:step tab-a)))]
        (is (= "one:2:connecting two:1:connecting" (view :session html))
          "each of its own, counting its own readers, and none of another tab's")
        (is (= "one:2:connecting three:1:connecting two:2:connecting" (view :server html))
          "where the server's has every resource, counting every reader"))
      (testing "another tab's change leaves this tab's view as it was"
        (reset! !b [["e" k3 s3]])
        ((:step tab-b))
        (let [html (island/html ((:step tab-a)))]
          (is (= "one:2:connecting two:1:connecting" (view :session html)))
          (is (= "one:2:connecting three:1:connecting two:1:connecting" (view :server html)))))
      (testing "a resource this tab lets go leaves its view, though it lingers on the server"
        (reset! !a [["c" k2 s2]])
        ((:step tab-a))
        (let [html (island/html ((:step tab-a)))]
          (is (= "two:1:connecting" (view :session html)))
          (is (= "one:0:lingering three:1:connecting two:1:connecting" (view :server html)))))
      (finally
        (runtime/dispose! (:rt tab-a))
        (runtime/dispose! (:rt tab-b))))))


(defshared described
  {:init 0})


(defisland self-summary
  [keys before?]
  (let [summarize #(into {} (map (fn [[k v]] [(:key (resource/describe k)) (:subscribers v)]))
                     (resource/use-summary :session))
        before (when before? (summarize))]
    (doseq [k keys]
      (shared/use-shared described k))
    [:p (pr-str (if before? before (summarize)))]))


(deftest a-session-summary-includes-the-island-reading-it
  (doseq [before? [false true]]
    (let [!keys (atom [:a :a])
          {:keys [step rt]} (harness #(self-summary @!keys before?))
          summary #(read-string (second (re-find #">([^<]*)</p>" (island/html (step)))))]
      (try
        (let [first-summary (summary)]
          (when-not before?
            (is (= {:a 1} first-summary) "a hold acquired before the summary is visible in the first frame"))
          (is (= {:a 1} (summary)) "both hook orders settle, counting an idempotent hold once"))
        (reset! !keys [:b])
        (summary)
        (is (= {:b 1} (summary)) "replaced holds leave the view even while they linger")
        (reset! !keys [])
        (summary)
        (is (= {} (summary)) "released holds leave the view")
        (reset! !keys [:a])
        (summary)
        (is (= {:a 1} (summary)) "an existing island includes its newly acquired holds too")
        (let [renders (runtime/render-counts rt)]
          (summary)
          (is (= renders (runtime/render-counts rt)) "a settled summary needs no further renders"))
        (finally
          (runtime/dispose! rt))))))


(deftest a-key-is-described-as-data
  (let [{:keys [step rt]} (harness (island/island-fn "both" nil
                                     (fn []
                                       (hooks/observe 1 (fn [emit! n] (emit! n) nil))
                                       (shared/use-shared described :k)
                                       [:p "both"])))]
    (try
      (step)
      (let [ds (map resource/describe (keys @resource/!entries))]
        (is (some #(and (= :observe (:kind %)) (= 'co.multiply.tropical.resource-test (:ns %)) (= [1] (:args %))
                     (pos-int? (:line %)) (pos-int? (:column %)))
              ds)
          "an observe by its namespace, its position, and its args")
        (is (some #(= {:kind :shared :state `described :key :k} %) ds) "a shared state's instance"))
      (is (= {:kind :observe :ns 'a.b :args [2]} (resource/describe [(resource/call-site 'a.b nil nil) [2]]))
        "an observe whose form had no position")
      (is (= {:kind :other :key [:x]} (resource/describe [:x])))
      (finally
        (runtime/dispose! rt)))))
