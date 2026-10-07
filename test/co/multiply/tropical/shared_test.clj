(ns co.multiply.tropical.shared-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.island :as island :refer [defisland]]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.shared :as shared :refer [defshared use-shared]]
    [co.multiply.tropical.test-util :refer [harness]]))


(defshared tally
  "A count per key."
  {:init 0 :linger-ms 100})


(defshared other-tally
  {:init 100 :linger-ms 100})


(defisland counter
  [state k]
  [:p "tally " (first (use-shared state k))])


(defn- reader
  "A session reading `state`'s instance for `k`: renders it, returns its
  `{:step :rt}`."
  ([state k]
   (reader state k "user"))
  ([state k uid]
   (let [{:keys [step] :as h} (harness #(counter state k) {:tab (str (random-uuid)) :uid uid})]
     (assoc h :html #(island/html (step))))))


(defn- shows
  [{:keys [html]}]
  (some->> (html) (re-find #"tally (\d+)<") second parse-long))


(deftest readers-with-one-key-share-one-instance
  (let [k  (random-uuid)
        a  (reader tally k "alice")
        b  (reader tally k "bob")
        c  (reader tally (random-uuid))
        all [a b c]]
    (is (= [0 0 0] (map shows all)) "created with its init, in the first render")
    (is (true? (shared/swap! tally k + 5)))
    (is (= [5 5 0] (map shows all)) "every session reading the key, and no other")
    (run! (comp runtime/dispose! :rt) all)))


(deftest an-island-may-read-an-instance-twice
  (let [k      (random-uuid)
        island (island/island-fn "twice" nil
                 (fn [] (let [[a] (use-shared tally k) [b] (use-shared tally k)] [:p a "/" b])))
        {:keys [step rt]} (harness island)]
    (is (re-find #">0/0<" (island/html (step))))
    (shared/swap! tally k inc)
    (is (re-find #">1/1<" (island/html (step))) "one instance, read twice")
    (runtime/dispose! rt)))


(deftest two-states-never-share-an-instance
  (let [k (random-uuid)
        a (reader tally k)
        b (reader other-tally k)]
    (is (= [0 100] (map shows [a b])))
    (shared/swap! tally k inc)
    (is (= [1 100] (map shows [a b])) "the same key, another state")
    (run! (comp runtime/dispose! :rt) [a b])))


(deftest an-instance-outlasts-its-last-reader-by-its-linger
  (let [k (random-uuid)
        a (reader tally k)]
    (shows a)
    (shared/swap! tally k + 3)
    (runtime/dispose! (:rt a))
    (is (true? (shared/swap! tally k inc)) "lingering, it still takes a change")
    (let [b (reader tally k)]
      (is (= 4 (shows b)) "a reader within the linger, as a page load is, finds it as it was left")
      (runtime/dispose! (:rt b)))
    (Thread/sleep 400)
    (is (false? (shared/swap! tally k inc)) "gone after it")
    (let [c (reader tally k)]
      (is (= 0 (shows c)) "the next reader creates it anew")
      (runtime/dispose! (:rt c)))))


(deftest a-change-without-an-instance-is-dropped
  (let [k (random-uuid)]
    (is (false? (shared/swap! tally k inc)))
    (let [a (reader tally k)]
      (is (= 0 (shows a)) "it didn't create one")
      (runtime/dispose! (:rt a)))))


(def ^:private !swap (atom nil))


(defisland keeper
  []
  (let [[n swap-n!] (use-shared tally)]
    (reset! !swap swap-n!)
    [:p "tally " n]))


(deftest the-hook-gives-a-swap-of-its-instance
  (let [{:keys [step rt]} (harness keeper)
        html #(island/html (step))]
    (is (re-find #"tally \d+<" (html)))
    (testing "without a key, the one instance there is"
      (let [n (parse-long (second (re-find #"tally (\d+)<" (html))))]
        (is (true? (@!swap + 2)))
        (is (re-find (re-pattern (str "tally " (+ n 2) "<")) (html)))
        (shared/swap! tally nil inc)
        (is (re-find (re-pattern (str "tally " (+ n 3) "<")) (html)))))
    (runtime/dispose! rt)))


(deftest defshared-refuses-a-linger-that-isnt-one
  (is (thrown? IllegalArgumentException (shared/shared* 'x.y/z {:linger-ms -1})))
  (is (thrown? IllegalArgumentException (shared/shared* 'x.y/z {:linger-ms "5s"})))
  (is (= 5 (:linger-ms (shared/shared* 'x.y/z {:linger-ms 5})))))
