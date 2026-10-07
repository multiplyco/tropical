(ns co.multiply.tropical.hooks-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.quiescent :as q]
    [co.multiply.scoped :refer [ask scoping]]
    [co.multiply.tropical.hooks :refer [observe]]
    [co.multiply.tropical.island :as island :refer [use-watch]]
    [co.multiply.tropical.resource :as resource]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.test-util :refer [harness]])
  (:import
    [clojure.lang Compiler$CompilerException]))


(def ^:private !opened (atom []))


(defn- times-ten
  [emit! x]
  (swap! !opened conj x)
  (emit! (* 10 x))
  nil)


(deftest a-call-site-and-its-args-key-the-resource
  (reset! !opened [])
  (let [!x     (atom 1)
        !other (atom 0)
        island (island/island-fn "q" nil
                 (fn [] [:p (str (observe (use-watch !x) times-ten)) " / " (use-watch !other)]))
        a      (harness island)
        b      (harness island)]
    ((:step a)) ((:step b))
    (Thread/sleep 100)
    (is (re-find #">10 / 0<" (island/html ((:step a)))))
    (is (= [1] @!opened) "two sessions at one call site with equal args share one resource")
    (testing "a render for another reason doesn't open it again"
      (swap! !other inc)
      (is (re-find #">10 / 1<" (island/html ((:step a)))))
      (is (= [1] @!opened)))
    (testing "new args open another"
      (reset! !x 2)
      ((:step a))
      (Thread/sleep 100)
      (is (re-find #">20 / 1<" (island/html ((:step a)))))
      (is (= [1 2] @!opened)))
    (runtime/dispose! (:rt a))
    (runtime/dispose! (:rt b))))


(deftest call-sites-never-share
  (reset! !opened [])
  (let [one  (island/island-fn "one" nil (fn [] [:p (str (observe 3 times-ten))]))
        two  (island/island-fn "two" nil (fn [] [:p (str (observe 3 times-ten))]))
        both (island/island-fn "both" nil (fn [] [:div (one) (two)]))
        {:keys [step rt]} (harness both)]
    (step)
    (Thread/sleep 100)
    (is (= [3 3] @!opened))
    (runtime/dispose! rt)))


(defn- refused
  "Compiles `form` in a fn, without running it: nil if it compiles, else the
  locals `observe` refused."
  [form]
  (try
    (eval (list 'fn [] form))
    nil
    (catch Compiler$CompilerException e
      (or (:locals (ex-data (ex-cause e))) (throw e)))))


(defn- read-ten
  "Reads `x` times ten: one call site for every caller, as an app's own hook
  over `observe` is."
  [x]
  (observe x times-ten))


(defn- held
  "The registry's entries for `read-ten`'s reads, by their args."
  []
  (into {}
    (keep (fn [[[site args] summary]]
            (when (re-find #"hooks-test/observe" (str site))
              [args summary])))
    (resource/summary @resource/!entries)))


(deftest hooks-reading-the-same-thing-in-one-render-share-it
  (reset! !opened [])
  (with-redefs [resource/linger-ms 100]
    (let [!show  (atom true)
          both   (island/island-fn "both" nil
                   (fn [] [:p (str (read-ten 4)) " " (str (read-ten 4)) " " (str (read-ten 5))]))
          host   (island/island-fn "host" nil (fn [] [:div (when (use-watch !show) (both))]))
          {:keys [step rt]} (harness host)]
      (step)
      (Thread/sleep 100)
      (is (re-find #">40 40 50<" (island/html (step))) "the same read twice is one value")
      (is (= [4 5] @!opened) "from one resource")
      (is (= 1 (:subscribers (get (held) [4]))) "held once")
      (testing "released once, when no render asks for it"
        (reset! !show false)
        (step)
        (Thread/sleep 300)
        (is (empty? (select-keys (held) [[4] [5]]))))
      (runtime/dispose! rt))))


(deftest the-fn-takes-what-it-depends-on-as-args
  (testing "refused: a local of the render, closed over"
    (is (= '[topic] (refused '(let [topic 1] (co.multiply.tropical.hooks/observe (fn [emit!] (emit! topic)))))))
    (is (= '[x] (refused '(let [x 1] (co.multiply.tropical.hooks/observe (partial clojure.core/identity x))))))
    (is (= '[f] (refused '(let [f clojure.core/identity] (co.multiply.tropical.hooks/observe f)))))
    (is (= '[d] (refused '(let [d 1] (co.multiply.tropical.hooks/observe {} (fn [emit! {:keys [a] :or {a d}}] (emit! a))))))
      "in a destructuring default"))
  (testing "allowed: params, even ones shadowing a local, and vars"
    (is (nil? (refused '(let [topic 1] (co.multiply.tropical.hooks/observe topic (fn [emit! topic] (emit! topic)))))))
    (is (nil? (refused '(let [id 1] (co.multiply.tropical.hooks/observe {:id id} (fn [emit! {:keys [id]}] (emit! id)))))))
    (is (nil? (refused '(let [x 1] (co.multiply.tropical.hooks/observe x #(%1 %2))))))
    (is (nil? (refused '(let [x 1] (co.multiply.tropical.hooks/observe x clojure.core/identity)))))))


(defn- failing
  [_]
  (q/task (throw (ex-info "nope" {}))))


(deftest failures-throw-in-the-render
  (let [plain  (island/island-fn "plain" nil (fn [] [:p (str (observe failing))]))
        caught (island/island-fn "caught" nil
                 (fn []
                   [:p (try (str (observe failing))
                         (catch Exception e (str "caught " (ex-message e))))]))
        both   (island/island-fn "both" nil (fn [] [:div (plain) (caught)]))
        {:keys [step rt]} (harness both)]
    (step)
    (Thread/sleep 200)
    (let [html (island/html (step))]
      (is (re-find (re-pattern (str "id=\"" (island/element-id "both/plain") "\">[^<]*nope")) html))
      (is (re-find #">caught nope<" html)))
    (runtime/dispose! rt)))


;; Unbound, so `ask` returns its default where nothing binds it, rather than the root.
#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:dynamic *viewer*)


(defn- viewer
  [emit!]
  (emit! (ask *viewer* :none))
  nil)


(deftest the-fn-gets-nothing-from-the-render-but-its-args
  (scoping [*viewer* :reader]
    (let [island (island/island-fn "scoped" nil
                   (fn []
                     [:p (str (ask *viewer* :none)
                           " / " (observe viewer)
                           " / " (observe (ask *viewer* :none) (fn [emit! v] (emit! v) nil)))]))
          {:keys [step rt]} (harness island)]
      (step)
      (Thread/sleep 100)
      (is (re-find #">:reader / :none / :reader<" (island/html (step)))
        "the render sees its scope; the fn sees none, but a value read in the render and passed as an arg")
      (runtime/dispose! rt))))


(deftest an-unmounted-reader-releases-its-resource
  (with-redefs [resource/linger-ms 100]
    (let [!show   (atom true)
          stopped (promise)
          child   (island/island-fn "child" nil
                    (fn [] [:p (str (observe stopped (fn [_ stopped] #(deliver stopped true))))]))
          host    (island/island-fn "host" nil (fn [] [:div (when (use-watch !show) (child))]))
          {:keys [step rt]} (harness host)]
      (step)
      (reset! !show false)
      (step)
      (is (deref stopped 1000 false) "closed after the linger")
      (runtime/dispose! rt))))
