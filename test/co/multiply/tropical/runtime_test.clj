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
