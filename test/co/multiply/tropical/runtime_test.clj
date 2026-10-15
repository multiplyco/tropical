(ns co.multiply.tropical.runtime-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.test-util :refer [harness]])
  (:import
    [clojure.lang ARef]))


(deftest a-failed-selection-stays-owned-and-can-recover
  (doseq [initial [nil 1]]
    (testing (str "starting with " initial)
      (let [source (atom initial)
            reader (island/island-fn "reader" nil
                     (fn [] [:p (island/use-watch source inc)]))
            {:keys [step rt]} (harness reader)]
        (try
          (step)
          (reset! source nil)
          (step)
          (reset! source 2)
          (is (re-find #">3<" (island/html (step))) "a failed selector retries when its source changes")
          (reset! source nil)
          (step)
          (finally
            (runtime/dispose! rt)))
        (is (empty? (ARef/.getWatches source)) "disposing a failed read removes its watch")))))


(deftest unmounting-a-failed-reader-keeps-other-readers
  (let [source (atom nil)
        shown (atom true)
        failed (island/island-fn "failed" nil (fn [] [:p (island/use-watch source inc)]))
        healthy (island/island-fn "healthy" nil (fn [] [:p (str (island/use-watch source))]))
        root (island/island-fn "root" nil
               (fn [] [:main (when (island/use-watch shown) (failed)) (healthy)]))
        {:keys [step rt]} (harness root)]
    (try
      (step)
      (reset! shown false)
      (step)
      (reset! source 7)
      (is (re-find #">7<" (island/html (step))))
      (is (= 1 (count (ARef/.getWatches source))))
      (finally
        (runtime/dispose! rt)))
    (is (empty? (ARef/.getWatches source)))))


(deftest each-selection-of-a-source-can-invalidate-its-reader
  (let [source (atom {:a 1 :b 1})
        other (atom 1)
        reader (island/island-fn "reader" nil
                 (fn []
                   [:p (island/use-watch source :a) "/"
                    (island/use-watch source :b) "/" (island/use-watch other)]))
        sibling (island/island-fn "sibling" nil (fn [] [:p (island/use-watch source :a)]))
        root (island/island-fn "root" nil (fn [] [:main (reader) (sibling)]))
        {:keys [step rt]} (harness root)]
    (try
      (let [initial (step)]
        (swap! source assoc :b 2)
        (is (re-find #">1/2/1<" (island/html (step))) "the second read can invalidate the island")
        (is (= {"root" 1 "root/reader" 2 "root/sibling" 1} (runtime/render-counts rt)))
        (reset! other 2)
        (is (re-find #">1/2/2<" (island/html (step))) "another source's read can too")
        (let [frame (step)]
          (swap! source assoc :ignored 3)
          (is (identical? frame (step)) "unchanged selections reuse the frame"))
        (is (re-find #">1/1/1<" (island/html initial)) "retained frames are snapshots"))
      (finally (runtime/dispose! rt)))
    (is (empty? (ARef/.getWatches source)))
    (is (empty? (ARef/.getWatches other)))))


(deftest list-edits-keep-survivors-and-release-only-removed-children
  (let [items (atom [[:a "A"] [:b "B"]])
        values (atom {:a 0 :b 0 :c 0 :d 0})
        opened (atom {})
        released (atom {})
        row (island/island-fn "row" {:key (fn [k _] k)}
              (fn [k label]
                (island/use-hold :lifetime
                  #(do (swap! opened update k (fnil inc 0))
                     {:value k :release (fn [] (swap! released update k (fnil inc 0)))}))
                [:li label ":" (island/use-watch values #(get % k))]))
        root (island/island-fn "root" nil
               (fn [] (into [:ul] (map (fn [[k label]] (row k label))) (island/use-watch items))))
        {:keys [step rt]} (harness root)]
    (try
      (let [initial (step)
            html (island/html initial)]
        (swap! values assoc :a 1)
        (is (re-find #">A:1<" (island/html (step))))
        (is (= {"root" 1 "root/row.a" 2 "root/row.b" 1} (runtime/render-counts rt)))
        (testing "reorder, insertion, new arguments and a source change together"
          (reset! items [[:b "B"] [:a "edited"] [:c "C"]])
          (swap! values assoc :b 2)
          (let [updated (island/html (step))]
            (is (re-find #">edited:1<" updated))
            (is (re-find #">B:2<" updated)))
          (is (= {:a 1 :b 1 :c 1} @opened))
          (is (empty? @released)))
        (testing "a removed child may also have a pending invalidation"
          (reset! items [[:b "B"] [:c "C"]])
          (swap! values assoc :a 2)
          (step)
          (is (= {:a 1} @released))
          (is (= #{"root" "root/row.b" "root/row.c"} (runtime/mounted rt))))
        (reset! items [[:c "C"] [:d "D"]])
        (step)
        (is (= {:a 1 :b 1} @released))
        (reset! items [])
        (step)
        (is (= {:a 1 :b 1 :c 1 :d 1} @released))
        (is (empty? (ARef/.getWatches values)))
        (reset! items [[:b "back"]])
        (step)
        (is (= {:a 1 :b 2 :c 1 :d 1} @opened) "a removed key mounts afresh")
        (is (= html (island/html initial)) "list edits never mutate an earlier frame"))
      (finally (runtime/dispose! rt)))
    (is (= @opened @released) "every acquisition is released exactly once")
    (is (empty? (ARef/.getWatches values)))
    (is (empty? (ARef/.getWatches items)))))


(deftest changing-many-reads-keeps-every-selection-and-drops-unused-sources
  (let [sources (mapv (fn [_] (atom {:a 0 :b 0})) (range 24))
        chosen (atom [])
        reader (island/island-fn "reader" nil
                 (fn []
                   (into [:p]
                     (map (fn [i]
                            (let [source (nth sources i)]
                              [:span (island/use-watch source :a) "/" (island/use-watch source :b)])))
                     (island/use-watch chosen))))
        {:keys [step rt]} (harness reader)]
    (try
      (step)
      (doseq [selection [(vec (range 20)) [5] (vec (reverse (range 20))) [] [23 5]]]
        (reset! chosen selection)
        (step)
        (let [selected (set selection)]
          (doseq [i (range (count sources))]
            (is (= (contains? selected i) (boolean (seq (ARef/.getWatches (nth sources i)))))
              "only the current render's sources stay watched")))
        (when-some [i (last selection)]
          (let [renders (get (runtime/render-counts rt) "reader")]
            (swap! (nth sources i) update :b inc)
            (step)
            (is (= (inc renders) (get (runtime/render-counts rt) "reader"))
              "a second selection of the same source still invalidates, after growing or shrinking the reads"))))
      (finally (runtime/dispose! rt)))
    (is (every? #(empty? (ARef/.getWatches %)) sources))
    (is (empty? (ARef/.getWatches chosen)))))


(deftest an-invalid-selector-is-still-an-owned-read
  (let [source (atom 0)
        failures (atom 0)
        reader (island/island-fn "reader" nil (fn [] [:p (island/use-watch source 42)]))
        {:keys [step rt]} (harness reader {:tab "tab" :uid "user"}
                            {:on-failure (fn [_] (swap! failures inc))})]
    (try
      (step)
      (is (seq (ARef/.getWatches source)))
      (reset! source 1)
      (step)
      (is (= 2 @failures) "even an invalid selector stays recorded and retries")
      (finally (runtime/dispose! rt)))
    (is (empty? (ARef/.getWatches source)))))


(deftest an-aborted-render-releases-its-new-acquisitions
  (let [source (atom 1)
        released (atom 0)
        reader (island/island-fn "reader" nil
                 (fn []
                   (island/use-watch source)
                   (island/use-hold :held #(hash-map :value :held :release (fn [] (swap! released inc))))
                   (throw (ex-info "render failed" {}))))
        {:keys [step rt]} (harness reader {:tab "tab" :uid "user"}
                            {:on-failure (fn [_] (throw (ex-info "error boundary failed" {})))})]
    (try
      (is (thrown? clojure.lang.ExceptionInfo (step)))
      (is (= 1 @released) "the instance was never committed, but its new hold was released")
      (is (empty? (ARef/.getWatches source)))
      (finally
        (runtime/dispose! rt)))
    (is (= 1 @released) "disposal doesn't release it twice")))


(deftest an-aborted-child-leaves-its-ancestors-holds-owned
  (doseq [first-render? [true false]]
    (testing (if first-render? "on the first frame" "after a successful frame")
      (let [old-readers (atom 1) ; A reader outside this tree must keep its claim.
            new-readers (atom 0)
            acquire (fn [readers]
                      (swap! readers inc)
                      {:value readers :release #(swap! readers dec)})
            hold (fn [fail?]
                   (island/use-hold (if fail? :new :old)
                     #(acquire (if fail? new-readers old-readers))))
            child (island/island-fn "child" nil
                    (fn [fail?]
                      (when fail? (throw (OutOfMemoryError. "abort this frame")))
                      [:p "ready"]))
            parent (island/island-fn "parent" nil
                     (fn [fail?]
                       (hold fail?)
                       [:section (child fail?)]))
            root (island/island-fn "root" nil
                   (fn [fail?]
                     (hold fail?)
                     [:main (parent fail?)]))
            fail? (atom first-render?)
            {:keys [step rt]} (harness #(root @fail?))]
        (try
          (when-not first-render?
            (step)
            (is (= 3 @old-readers))
            (reset! fail? true))
          (is (thrown? OutOfMemoryError (step)))
          (is (= 1 @old-readers) "both ancestors already dropped their old holds")
          (is (= 2 @new-readers) "both ancestors acquired their new holds before the child failed")
          (finally
            (runtime/dispose! rt)))
        (is (= 1 @old-readers) "disposal doesn't release the ancestors' old holds again")
        (is (zero? @new-readers) "disposal releases every new ancestor hold")
        (runtime/dispose! rt)
        (is (= [1 0] [@old-readers @new-readers]) "a second disposal changes nothing")))))


(deftest an-aborted-list-edit-disposes-kept-removed-and-new-children
  (let [items (atom [:a :b])
        opened (atom {})
        released (atom {})
        row (island/island-fn "row" {:key identity}
              (fn [k]
                (island/use-hold :lifetime
                  #(do (swap! opened update k (fnil inc 0))
                     {:value k :release (fn [] (swap! released update k (fnil inc 0)))}))
                (when (= :fail k) (throw (OutOfMemoryError. "abort this list edit")))
                [:li (name k)]))
        root (island/island-fn "root" nil (fn [] [:ul (mapv row (island/use-watch items))]))
        {:keys [step rt]} (harness root)]
    (try
      (let [initial (step)
            html (island/html initial)]
        (reset! items [:a :c :fail])
        (is (thrown? OutOfMemoryError (step)))
        (is (= {:a 1 :b 1 :c 1 :fail 1} @opened))
        (is (= html (island/html initial)) "a partial edit cannot mutate a retained frame"))
      (finally (runtime/dispose! rt)))
    (is (= @opened @released) "all children release exactly once, even when pruning was interrupted")
    (is (empty? (ARef/.getWatches items)))))
