(ns co.multiply.tropical.hooks-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.quiescent :as q]
    [co.multiply.scoped :refer [ask scoping]]
    [co.multiply.tropical.hooks :refer [if-loaded observe pending pending?]]
    [co.multiply.tropical.island :as island :refer [use-watch]]
    [co.multiply.tropical.resource :as resource]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.test-util :refer [harness manual-source observe-source]])
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
  (doseq [order [[4 5] [5 4]]]
    (testing (str "resources open in order " order)
      (reset! !opened [])
      (let [allow-open (zipmap [4 5] (repeatedly promise))
            published  (zipmap [4 5] (repeatedly promise))
            closed     (zipmap [4 5] (repeatedly promise))
            !closed    (atom [])]
        (with-redefs [resource/linger-ms 0
                      times-ten (fn [emit! x]
                                  @(allow-open x)
                                  (swap! !opened conj x)
                                  (emit! (* 10 x))
                                  (deliver (published x) true)
                                  (fn []
                                    (swap! !closed conj x)
                                    (deliver (closed x) true)))]
          (let [!show  (atom true)
                both   (island/island-fn "both" nil
                         (fn [] [:p (str (read-ten 4)) " " (str (read-ten 4)) " " (str (read-ten 5))]))
                host   (island/island-fn "host" nil (fn [] [:div (when (use-watch !show) (both))]))
                {:keys [step rt]} (harness host)]
            (try
              (step)
              ;; Independent subjects may start in either order. Wait for each
              ;; publication before opening the next, without timing assumptions.
              (doseq [x order]
                (deliver (allow-open x) true)
                (is (deref (published x) 2000 false) "the resource published"))
              (is (re-find #">40 40 50<" (island/html (step))) "the same read twice is one value")
              (is (= {4 1 5 1} (frequencies @!opened)) "one physical resource per argument")
              (doseq [x [4 5]]
                (is (= 1 (:subscribers (get (held) [x]))) "held once"))
              (testing "released once, when no render asks for it"
                (reset! !show false)
                (step)
                (doseq [x [4 5]]
                  (is (deref (closed x) 2000 false) "the resource closed"))
                (is (= {4 1 5 1} (frequencies @!closed)) "each cleanup ran once")
                (is (empty? (select-keys (held) [[4] [5]]))))
              (finally
                (doseq [gate (vals allow-open)]
                  (deliver gate true))
                (runtime/dispose! rt)
                (doseq [done (vals closed)]
                  (deref done 2000 false))))))))))


(deftest a-read-given-pending-is-pending-and-opens-nothing
  (reset! !opened [])
  (with-redefs [resource/linger-ms 100]
    (let [[k1 s1 emit!] (manual-source)
          [k2 s2] (manual-source)
          !source (atom [k1 s1])
          island  (island/island-fn "chained" nil
                    (fn []
                      (let [[k s] (use-watch !source)
                            v     (read-ten (observe-source k s))]
                        [:p (if (pending? v) "pending" (str v))])))
          {:keys [step rt]} (harness island)]
      (is (re-find #">pending<" (island/html (step))))
      (Thread/sleep 100)
      (is (= [] @!opened) "nothing opened with pending")
      (is (nil? (get (held) [pending])))
      (testing "it opens once what it's given has loaded"
        (emit! 3)
        (step)
        (Thread/sleep 100)
        (is (re-find #">30<" (island/html (step))))
        (is (= [3] @!opened)))
      (testing "and is released when that is pending again"
        (reset! !source [k2 s2])
        (is (re-find #">pending<" (island/html (step))))
        (is (zero? (:subscribers (get (held) [3]))))
        (Thread/sleep 300)
        (is (nil? (get (held) [3]))))
      (runtime/dispose! rt))))


(deftest if-loaded-waits-only-on-what-a-binding-refers-to
  (let [!evaluated (atom [])
        note       (fn [k v] (swap! !evaluated conj k) v)]
    (is (= [1 2] (if-loaded [a 1 b (inc a)] [a b] :else)))
    (testing "a binding referring to a pending one isn't evaluated, and one referring to none is"
      (is (= :else (if-loaded [a pending
                               b (note :b (inc a))
                               c (note :c 3)
                               d (note :d (+ b c))]
                     [a b c d]
                     :else)))
      (is (= [:c] @!evaluated)))
    (testing "a name bound again is the later binding's"
      (reset! !evaluated [])
      (is (= :else (if-loaded [a pending b (note :b a) a 2 c (note :c (inc a))] [b c] :else)))
      (is (= [:c] @!evaluated)))
    (testing "a pattern destructures only a loaded value"
      (is (= :else (if-loaded [{:keys [id]} pending] id :else)))
      (is (= :else (if-loaded [[x] pending] x :else))))
    (testing "nil has loaded"
      (is (= [nil] (if-loaded [a nil] [a] :else))))
    (testing "without else, pending"
      (is (pending? (if-loaded [a pending] a))))
    (testing "else sees none of the bindings"
      (is (thrown? Compiler$CompilerException
            (eval '(co.multiply.tropical.hooks/if-loaded [a 1] a a)))))))


(deftest if-loaded-starts-a-dependent-read-once-its-input-has-loaded
  (reset! !opened [])
  (with-redefs [resource/linger-ms 100]
    (let [[k s emit!] (manual-source)
          island (island/island-fn "loaded" nil
                   (fn []
                     (if-loaded [{:keys [x]} (observe-source k s)
                                 y          (read-ten (inc x))
                                 z          (read-ten 7)]
                       [:p (str y " " z)]
                       [:p "loading"])))
          {:keys [step rt]} (harness island)]
      (is (re-find #">loading<" (island/html (step))))
      (Thread/sleep 100)
      (is (= [7] @!opened) "the read that refers to none starts, and none opens with a value taken from pending")
      (emit! {:x 1})
      (step)
      (Thread/sleep 100)
      (is (re-find #">20 70<" (island/html (step))))
      (is (= [7 2] @!opened))
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
