(ns co.multiply.tropical.signal-test
  (:require
    [clojure.test :refer [deftest is testing]])
  (:import
    [clojure.lang ARef]
    [co.multiply.tropical.signal Cell Inbox RefSignal Sub]
    [java.util ArrayList]
    [java.util.concurrent CountDownLatch TimeUnit]))


(defn- drain
  [inbox]
  (let [subs (ArrayList.)]
    (Inbox/.drain inbox subs)
    (vec subs)))


(deftest a-subscription-is-in-its-inbox-at-most-once
  (let [cell  (Cell. 0)
        inbox (Inbox.)
        sub   (Inbox/.subscribe inbox cell)]
    (dotimes [n 1000]
      (Cell/.reset cell (inc n)))
    (is (= [sub] (drain inbox)) "a thousand changes, one entry")
    (is (= 1000 @sub) "read when taken: the latest value")
    (testing "a change after the drain marks it again"
      (is (= [] (drain inbox)))
      (Cell/.reset cell :again)
      (is (= [sub] (drain inbox))))
    (testing "an identical value marks nothing"
      (Cell/.reset cell :again)
      (is (= [] (drain inbox))))
    (testing "a cancelled subscription is no longer marked"
      (Sub/.cancel sub)
      (Cell/.reset cell :gone)
      (is (= [] (drain inbox)))
      (is (zero? (.subscriberCount cell))))))


(deftest a-ref-has-one-watch-however-many-sessions-read-it
  (let [!ref    (atom 0)
        watches #(count (ARef/.getWatches !ref))
        before  (RefSignal/count)
        a       (Inbox.)
        b       (Inbox.)
        sub-a   (Inbox/.subscribe a !ref)
        sub-b   (Inbox/.subscribe b !ref)]
    (is (= 1 (watches)))
    (is (= (inc before) (RefSignal/count)))
    (swap! !ref inc)
    (is (= [sub-a] (drain a)))
    (is (= [sub-b] (drain b)))
    (is (= 1 @sub-a @sub-b))
    (testing "the watch goes with the last subscription"
      (Sub/.cancel sub-a)
      (is (= 1 (watches)))
      (Sub/.cancel sub-b)
      (is (zero? (watches)))
      (is (= before (RefSignal/count))))
    (testing "and comes back with the next"
      (let [sub (Inbox/.subscribe a !ref)]
        (is (= 1 (watches)))
        (swap! !ref inc)
        (is (= [sub] (drain a)))
        (Sub/.cancel sub)))))


(defn- waiting-owner
  "Starts a virtual thread that binds `inbox`, counts down `ready`, and waits on
  it for up to 10 s. Returns a promise of how long it waited, in ms."
  [inbox ^CountDownLatch ready]
  (let [waited (promise)]
    (Thread/startVirtualThread
      (fn []
        (Inbox/.bind inbox)
        (CountDownLatch/.countDown ready)
        (let [t0 (System/nanoTime)]
          (Inbox/.await inbox 10000)
          (deliver waited (quot (- (System/nanoTime) t0) 1000000)))))
    waited))


(deftest the-owner-wakes-for-a-mark-or-an-event
  (testing "a mark"
    (let [cell   (Cell. 0)
          inbox  (Inbox.)
          _      (Inbox/.subscribe inbox cell)
          ready  (CountDownLatch. 1)
          waited (waiting-owner inbox ready)]
      (CountDownLatch/.await ready)
      (Thread/sleep 50)
      (Cell/.reset cell 1)
      (is (< (deref waited 2000 Long/MAX_VALUE) 1000))))
  (testing "an event"
    (let [inbox  (Inbox.)
          ready  (CountDownLatch. 1)
          waited (waiting-owner inbox ready)]
      (CountDownLatch/.await ready)
      (Thread/sleep 50)
      (Inbox/.post inbox :hello)
      (is (< (deref waited 2000 Long/MAX_VALUE) 1000))
      (is (= :hello (Inbox/.poll inbox))))))


(deftest no-change-is-lost-under-contention
  ;; Writers race to change the cell while the owner drains and reads. Whatever
  ;; the interleaving, the owner reads the last value, and reads only when
  ;; marked: a lost mark leaves it parked.
  (let [cell     (Cell. -1)
        inbox    (Inbox.)
        sub      (Inbox/.subscribe inbox cell)
        writers  4
        n        100000
        start    (CountDownLatch. 1)
        done     (CountDownLatch. writers)
        last     (promise)
        reads    (volatile! 0)
        deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 20))]
    (Thread/startVirtualThread
      (fn []
        (Inbox/.bind inbox)
        (CountDownLatch/.countDown start)
        (loop []
          (Inbox/.await inbox 1000)
          (if (and (seq (drain inbox))
                (= :done (do (vswap! reads inc) @sub)))
            (deliver last :done)
            (when (< (System/nanoTime) deadline)
              (recur))))))
    (CountDownLatch/.await start)
    (dotimes [w writers]
      (Thread/startVirtualThread
        (fn []
          (dotimes [i n]
            (Cell/.reset cell [w i]))
          (CountDownLatch/.countDown done))))
    (is (CountDownLatch/.await done 10 TimeUnit/SECONDS))
    (Cell/.reset cell :done)
    (is (= :done (deref last 5000 :lost)))
    (is (< @reads (* writers n)) "changes coalesced")
    (Sub/.cancel sub)))
