(ns co.multiply.tropical.failure-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging :as log]
    [co.multiply.quiescent :as q]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.test-util :refer [capturing harness]])
  (:import
    [co.multiply.tropical.runtime TraceCache]
    [java.util.concurrent CountDownLatch TimeUnit]
    [java.util.function LongSupplier]))


(defn- exception-at
  "An exception with a known top `site`, or no stack trace."
  [site]
  (doto (IllegalStateException. "failure")
    (.setStackTrace (into-array StackTraceElement
                      (when site [(StackTraceElement. "app.widget" site "widget.clj" 1)])))))


(deftest a-stackless-run-learns-its-site-and-logs-the-available-trace
  (let [source (atom nil)
        !logged (atom [])
        !failures (atom [])
        broken (island/island-fn "broken" nil
                 (fn [] (throw (exception-at (second (island/use-watch source))))))
        {:keys [step rt]} (harness broken {} {:on-failure #(swap! !failures conj %)})]
    (binding [log/*logger-factory* (capturing !logged)]
      (try
        (doseq [value [[0 nil] [1 nil] [2 "a"] [3 "b"] [4 nil]]]
          (reset! source value)
          (step))
        (let [[missing missing' a b missing-again] (map :ref @!failures)
              sites (for [[_ _ t] @!logged :when t, frame (take 1 (.getStackTrace ^Throwable t))]
                      (.getMethodName ^StackTraceElement frame))]
          (is (= missing missing' a) "learning a site keeps the user's reference")
          (is (not= a b) "a different known site starts another run")
          (is (= b missing-again) "losing the trace keeps the known site")
          (is (= ["a" "b"] sites) "each available trace is logged, even at a suppressed repeat count"))
        (finally (runtime/dispose! rt))))))


(deftest an-error-view-failure-has-its-own-repeat-count-and-recovery
  (let [source (atom 0)
        !logged (atom [])
        !failures (atom [])
        primary (exception-at "render")
        secondary (exception-at "error-view")
        broken (island/island-fn "broken" nil
                 (fn [] (island/use-watch source) (throw primary)))
        view (fn [_]
               (if (or (<= 2 @source 12) (= 14 @source))
                 (throw secondary)
                 [:p "custom view"]))
        {:keys [step rt]} (harness broken {} {:error-view view :on-failure #(swap! !failures conj %)})
        view-lines #(filter (fn [[_ message]] (str/includes? message "error view")) @!logged)]
    (binding [log/*logger-factory* (capturing !logged)]
      (try
        (doseq [n (range 14)]
          (reset! source n)
          (let [html (island/html (step))]
            (when (= 2 n)
              (is (str/includes? html "failed to render") "the broken view falls back")
              (is (= [secondary] (map #(nth % 2) (view-lines))) "its first exception has a trace"))))
        (is (= 4 (count (view-lines))) "eleven view failures log at their own counts 1, 2, 4 and 8")
        (reset! source 14)
        (step)
        (is (= 5 (count (view-lines))) "a view that recovered logs its next failure, even if the render is suppressed")
        (is (= 1 (count (set (map :ref @!failures)))) "view failures share the island's reference")
        (finally (runtime/dispose! rt))))))


(deftest an-error-views-first-trace-is-independent-across-sessions
  (let [!logged (atom [])
        primary (exception-at "render")
        secondary (exception-at "error-view")
        broken (island/island-fn "broken" nil (fn [] (throw primary)))
        view (fn [{:keys [session]}]
               (if (:broken-view session) (throw secondary) [:p "custom view"]))]
    (binding [log/*logger-factory* (capturing !logged)]
      (doseq [ctx [{} {:broken-view true} {:broken-view true}]]
        (let [{:keys [step rt]} (harness broken ctx {:error-view view})]
          (try (step) (finally (runtime/dispose! rt))))))
    (is (= [primary secondary] (keep #(nth % 2) @!logged)) "the second session logs the new view exception")
    (let [lines (filter #(str/includes? (second %) "error view") @!logged)
          ref (second (re-find #"Reference (\w{4}-\w{4})" (second (first lines))))]
      (is (= 2 (count lines)))
      (is (str/includes? (second (second lines)) ref) "the third session names the view's trace"))))


(deftest trace-caches-belong-to-definitions
  (let [!logged (atom [])
        boom (exception-at "shared")
        render (fn [] (throw boom))
        definitions [(island/island-fn 'app/widget nil render)
                     (island/island-fn 'app/widget nil render)]]
    (binding [log/*logger-factory* (capturing !logged)]
      (doseq [definition definitions, _ (range 2)]
        (let [{:keys [step rt]} (harness definition)]
          (try (step) (finally (runtime/dispose! rt))))))
    (is (= 2 (count (filter #(nth % 2) @!logged))) "each definition logs its own trace, even with the same name")))


(deftest trace-cache-expiry-and-capacity-are-bounded
  (let [clock (atom 0)
        cache (TraceCache. 2 10 (reify LongSupplier
                                  (getAsLong [_] @clock)))
        site #(StackTraceElement. "app" % "app.clj" 1)
        claim #(TraceCache/.claim cache false IllegalStateException (site %1) %2)]
    (is (nil? (claim "a" "a1")))
    (reset! clock 5)
    (is (= "a1" (claim "a" "a2")))
    (is (nil? (claim "b" "b1")))
    (reset! clock 10)
    (is (nil? (claim "a" "a3")) "repeats do not extend the original window")
    (is (= "b1" (claim "b" "b2")) "a more recent trace remains available")
    (is (nil? (claim "c" "c1")))
    (is (nil? (claim "b" "b3")) "capacity evicts the oldest trace even within the window"))
  (testing "renders and error views cannot suppress one another's first trace"
    (let [cache (TraceCache.)
          site (StackTraceElement. "app" "shared" "app.clj" 1)]
      (is (nil? (TraceCache/.claim cache false IllegalStateException site "render")))
      (is (nil? (TraceCache/.claim cache true IllegalStateException site "view")))
      (is (= "render" (TraceCache/.claim cache false IllegalStateException site "later-render")))
      (is (= "view" (TraceCache/.claim cache true IllegalStateException site "later-view"))))))


(deftest concurrent-trace-claims-pick-one-reference-per-window
  (let [clock (atom 0)
        cache (TraceCache. 8 10 (reify LongSupplier
                                  (getAsLong [_] @clock)))
        site (StackTraceElement. "app" "shared" "app.clj" 1)]
    (doseq [at [0 10]]
      (reset! clock at)
      (let [claims @(q/task
                      (let [ready (CountDownLatch. 32)
                            go (CountDownLatch. 1)
                            tasks (mapv (fn [n]
                                          (q/task
                                            (CountDownLatch/.countDown ready)
                                            (when-not (CountDownLatch/.await go 5 TimeUnit/SECONDS)
                                              (throw (ex-info "Claims never started" {})))
                                            (let [ref (str at "-" n)]
                                              [ref (TraceCache/.claim cache false IllegalStateException site ref)])))
                                    (range 32))]
                        (try
                          (when-not (CountDownLatch/.await ready 5 TimeUnit/SECONDS)
                            (throw (ex-info "Claimers never became ready" {})))
                          (finally (CountDownLatch/.countDown go)))
                        tasks))
            winners (filter (comp nil? second) claims)]
        (is (= 1 (count winners)))
        (is (= #{(ffirst winners)} (set (keep second claims))) "all other sessions name the winning trace")))))
