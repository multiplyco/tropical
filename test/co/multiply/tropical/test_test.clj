(ns co.multiply.tropical.test-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.action :as action :refer [use-action]]
    [co.multiply.tropical.hooks :as hooks :refer [observe]]
    [co.multiply.tropical.island :refer [defisland use-session use-watch]]
    [co.multiply.tropical.test :as test]
    [co.multiply.tropical.test-util :refer [entry manual-source observe-source]]))


(def ^:private !signals (atom nil))


(defisland tally
  [!n]
  (let [n    (use-watch !n)
        bump (use-action :bump (fn [signals]
                                 (reset! !signals signals)
                                 (swap! !n inc)
                                 {:bumped true}))]
    [:p "n=" n [:button {:data-on:click bump}]]))


(defisland counter-page
  [!n]
  [:main (tally !n)])


(deftest a-harness-renders-and-acts-as-a-session-would
  (let [!n (atom 0)]
    (with-open [h (test/harness #(counter-page !n))]
      (is (str/includes? (test/render! h) "n=0"))
      (is (= #{"counter-page" "counter-page/tally"} (test/mounted h)))
      (let [bump (test/action h "counter-page/tally" :bump)]
        (is (= {:bumped true} (bump {:note "hi"})) "the handler's result")
        (is (= {"note" "hi"} @!signals) "signals arrive through JSON, with string keys")
        (is (str/includes? (test/render! h) "n=1") "what the action changed renders")))))


(defisland user-badge
  []
  [:p "user=" (:uid (use-session))])


(defisland page-path
  []
  [:p "path=" (-> (use-session) :request :uri)])


(deftest a-harness-is-a-users-session
  (with-open [h (test/harness user-badge {:uid "u-7"})]
    (is (str/includes? (test/render! h) "user=u-7")))
  (with-open [h (test/harness page-path {:request {:uri "/items/42"}})]
    (is (str/includes? (test/render! h) "path=/items/42") "and the request that created it")))


(defn- slow-ten
  [emit! ms]
  (Thread/sleep (long ms))
  (emit! 10)
  nil)


(defisland slow-reader
  [ms]
  (let [v (observe ms slow-ten)]
    [:p (if (= hooks/pending v) "waiting" (str "got " v))]))


(defisland observer
  [key subject]
  [:p (str (observe-source key subject))])


(deftest render-waits-for-pending-reads
  (testing "a read that lands in time is rendered"
    (with-open [h (test/harness (slow-reader 50))]
      (is (str/includes? (test/render! h) "got 10"))))
  (testing "without waiting, the island shows what it shows while it waits"
    (with-open [h (test/harness (slow-reader 300))]
      (is (str/includes? (test/render! h {:wait-ms 0}) "waiting"))))
  (testing "a read that never lands is rendered pending once the wait is up"
    (let [[key subject] (manual-source)]
      (with-open [h (test/harness (observer key subject))]
        (let [start (System/currentTimeMillis)]
          (is (str/includes? (test/render! h {:wait-ms 100}) "pending"))
          (is (<= 100 (- (System/currentTimeMillis) start))))))))


(def ^:private !allowed (atom true))


(defisland panel
  []
  [:div [:button {:data-on:click (use-action :go (fn [_] :went))}]])


(defisland gate
  []
  [:section (when (use-watch !allowed) (panel))])


(deftest an-action-exists-while-its-island-renders-it
  (reset! !allowed true)
  (with-open [h (test/harness gate)]
    (test/render! h)
    (let [go (test/action h "gate/panel" :go)]
      (is (= :went (go)))
      (is (nil? (test/action h "gate/panel" :other)) "one it doesn't render")
      (is (nil? (test/action h "gate" :go)) "another island's")
      (reset! !allowed false)
      (test/render! h)
      (is (nil? (test/action h "gate/panel" :go)))
      (is (thrown? clojure.lang.ExceptionInfo (go)) "its token is revoked"))))


(defisland picker
  []
  [:div {:data-on:click (use-action :pick (fn [signals value] [signals value]) {:value "evt.detail"})}])


(defisland requester
  []
  [:div {:data-on:click (use-action :uri (fn [_] (:uri (action/request))))}])


(deftest an-action-takes-a-value-and-its-request
  (with-open [h (test/harness picker)]
    (test/render! h)
    (is (= [{"a" 1} {"id" "x"}] ((test/action h "picker" :pick) {:a 1} {:id "x"}))))
  (testing "the harness's request is the action's"
    (with-open [h (test/harness requester {:request {:uri "/items/42"}})]
      (test/render! h)
      (is (= "/items/42" ((test/action h "requester" :uri))))))
  (testing "without one, action/request throws, as it does outside an action"
    (with-open [h (test/harness requester)]
      (test/render! h)
      (is (thrown? IllegalStateException ((test/action h "requester" :uri)))))))


(defisland faulty
  []
  (throw (ex-info "broken" {})))


(defisland holder
  []
  [:div (faulty) (user-badge)])


(deftest failures-are-listed
  (with-open [h (test/harness holder {:error-view (fn [_ e] [:p "view: " (ex-message e)])})]
    (let [html (test/render! h)]
      (is (str/includes? html "view: broken") "the error view renders in its place")
      (is (str/includes? html "user=") "the rest renders"))
    (is (= ["holder/faulty"] (map :island (test/failures h))))
    (is (= "broken" (ex-message (:error (first (test/failures h))))))))


(deftest closing-unmounts
  (let [[key subject] (manual-source)
        h (test/harness (observer key subject))]
    (test/render! h {:wait-ms 0})
    (is (= 1 (:subscribers (entry key))))
    (.close h)
    (is (= :lingering (:state (entry key))) "its resources are released"))
  (reset! !allowed true)
  (let [go (with-open [h (test/harness gate)]
             (test/render! h)
             (test/action h "gate/panel" :go))]
    (is (thrown? clojure.lang.ExceptionInfo (go)) "its tokens are revoked")))
