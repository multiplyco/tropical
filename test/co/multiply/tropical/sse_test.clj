(ns co.multiply.tropical.sse-test
  "The wire is the contract with Datastar's client: these pin it to Datastar's
  SDK specification, byte for byte."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.quiescent :as q]
    [co.multiply.tropical.sse :as sse])
  (:import
    [co.multiply.tropical.sse EventStream]
    [java.io ByteArrayOutputStream FilterOutputStream IOException OutputStream]))


(defn- open-stream
  []
  (let [out (ByteArrayOutputStream.)]
    [(EventStream. out) #(.toString out "UTF-8")]))


(deftest events-follow-the-datastar-sdk-specification
  (testing "patch-elements: one event, a data line per line of each element"
    (let [[s written] (open-stream)]
      (is (true? (sse/patch! s ["<p id=\"a\">1</p>" "<div id=\"b\">\n  <b>2</b>\n</div>"])))
      (is (= (str "event: datastar-patch-elements\n"
               "data: elements <p id=\"a\">1</p>\n"
               "data: elements <div id=\"b\">\n"
               "data: elements   <b>2</b>\n"
               "data: elements </div>\n"
               "\n")
             (written)))))
  (testing "patch-elements inserting after an element: selector, then mode, then the elements"
    (let [[s written] (open-stream)]
      (is (true? (sse/patch! s ["<li id=\"l/i.2\">2</li>" "<li id=\"l/i.3\">3</li>"]
                   {:mode :after :selector "[id=\"l/i.1\"]"})))
      (is (= (str "event: datastar-patch-elements\n"
               "data: selector [id=\"l/i.1\"]\n"
               "data: mode after\n"
               "data: elements <li id=\"l/i.2\">2</li>\n"
               "data: elements <li id=\"l/i.3\">3</li>\n"
               "\n")
             (written)))))
  (testing "patch-elements removing: a selector and the mode, no elements"
    (let [[s written] (open-stream)]
      (is (true? (sse/remove! s "[id=\"l/i.1\"], [id=\"l/i.2\"]")))
      (is (= (str "event: datastar-patch-elements\n"
               "data: selector [id=\"l/i.1\"], [id=\"l/i.2\"]\n"
               "data: mode remove\n"
               "\n")
             (written)))))
  (testing "patch-signals"
    (let [[s written] (open-stream)]
      (is (true? (sse/signals! s {"a" 1 "b" {"c" "d"}})))
      (is (= "event: datastar-patch-signals\ndata: signals {\"a\":1,\"b\":{\"c\":\"d\"}}\n\n" (written)))))
  (testing "a mark: an empty signal patch with an id, which the client sends back as Last-Event-ID"
    (let [[s written] (open-stream)]
      (is (true? (sse/mark! s "k3x.7")))
      (is (= "event: datastar-patch-signals\nid: k3x.7\ndata: signals {}\n\n" (written)))))
  (testing "a heartbeat is a comment, which the client ignores"
    (let [[s written] (open-stream)]
      (is (true? (sse/heartbeat! s)))
      (is (= ":\n\n" (written)))))
  (testing "reload: a script appended to the body, removing itself, and the stream closes"
    (let [[s written] (open-stream)]
      (sse/reload! s)
      (is (= (str "event: datastar-patch-elements\n"
               "data: selector body\n"
               "data: mode append\n"
               "data: elements <script data-effect=\"el.remove()\">location.reload()</script>\n"
               "\n")
             (written)))
      (is (not (EventStream/.isOpen s))))))


(deftest data-lines-split-on-any-line-ending
  (is (= "event: e\ndata: k a\ndata: k b\ndata: k c\ndata: k \ndata: k d\n"
         (str (EventStream/data (EventStream/event "e") "k" "a\nb\r\nc\r\rd"))))
  (is (= "event: e\ndata: k \n" (str (EventStream/data (EventStream/event "e") "k" "")))
    "an empty value is one empty line")
  (is (= "event: e\ndata: k a\n" (str (EventStream/data (EventStream/event "e") "k" "a\n")))
    "a final line ending adds no line"))


(deftest an-event-arrives-whole-and-in-utf-8
  (let [[s written] (open-stream)
        html (str "<div id=\"big\">" (str/join (repeat 2000 "åäö 🌴 ")) "</div>")]
    (sse/patch! s [html])
    (is (= (str "event: datastar-patch-elements\ndata: elements " html "\n\n") (written))
      "larger than the write buffer, and multi-byte")))


(deftest closing-is-final
  (let [closes (atom 0)
        out    (ByteArrayOutputStream.)
        s      (EventStream. (proxy [FilterOutputStream] [out]
                               (close [] (swap! closes inc))))]
    (sse/close! s)
    (sse/close! s)
    (is (= 1 @closes) "the output stream is closed once")
    (is (false? (sse/patch! s ["<p id=\"a\">1</p>"])))
    (is (false? (sse/heartbeat! s)))
    (is (zero? (.size out)) "nothing is written after closing")))


(deftest a-failed-write-closes-the-stream
  (let [s (EventStream. (proxy [OutputStream] []
                          (write
                            ([_] (throw (IOException. "gone")))
                            ([_ _ _] (throw (IOException. "gone"))))))]
    (is (false? (sse/heartbeat! s)))
    (is (not (EventStream/.isOpen s)))
    (is (false? (sse/heartbeat! s)))))


(deftest await-returns-once-closed
  (testing "closed by another thread"
    (let [[s] (open-stream)
          started (promise)
          t (q/task
              (deliver started true)
              (EventStream/.await s))]
      (try
        (is (true? (deref started 1000 :timeout)))
        (sse/close! s)
        (is (nil? (deref t 1000 :timeout)))
        (is (not (EventStream/.isOpen s)))
        (finally (sse/close! s)))))
  (testing "interrupted, it closes the stream"
    (let [[s] (open-stream)
          started (promise)
          returned (promise)
          t (q/task
              (deliver started true)
              (EventStream/.await s)
              (deliver returned (Thread/.isInterrupted (Thread/currentThread))))]
      (try
        (is (true? (deref started 1000 :timeout)))
        (is (true? (deref (q/cancel t) 1000 :timeout)))
        ;; Cancellation can settle before the interrupted thread returns.
        (is (true? (deref returned 1000 :timeout)) "await returned with the interrupt status restored")
        (is (not (EventStream/.isOpen s)))
        (finally (sse/close! s))))))
