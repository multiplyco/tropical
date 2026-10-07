(ns bench.sse
  "What writing an event costs: a patch of one small island (170 chars), a full
  resync (27 KB), and the footer's signals, each written whole to a null output
  stream. Measured on a platform thread, after a warm-up, with the thread's
  allocation counter. From the REPL:

      (require 'bench.sse)
      (bench.sse/run)"
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.sse :as sse])
  (:import
    [co.multiply.tropical.sse EventStream]
    [java.io OutputStream]
    [java.lang.management ManagementFactory]))


(set! *warn-on-reflection* true)


(def ^:private small
  (str "<div id=\"app/feed-card/ticker\" class=\"space-y-1\"><p class=\"text-4xl font-mono\">471</p>"
    "<p class=\"text-xs\">reading #31 at 14:01:47.553</p><span>x</span><span>x</span></div>"))


(def ^:private large
  (str "<div id=\"app/bulk\">"
    (str/join (repeat 300 "<tr><td class=\"px-2 py-1 font-mono\">row 123</td><td class=\"px-2\">some text here</td></tr>"))
    "</div>"))


(def ^:private signals
  {"_wire" {"frames" 50 "last" 187 "page" 21452 "total" 13917 "patched" "app/feed-card/ticker" "renders" "app 1"}})


(defn- on-platform
  [f]
  (let [p (promise)]
    (Thread/.start (Thread. ^Runnable #(deliver p (try (f) (catch Throwable t t)))))
    @p))


(defn- measure
  [f iters]
  (let [mx ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)]
    (on-platform
      (fn []
        (dotimes [_ (quot iters 2)] (f))
        (let [b0 (.getCurrentThreadAllocatedBytes mx)
              t0 (System/nanoTime)]
          (dotimes [_ iters] (f))
          {:ns    (Math/round (/ (- (System/nanoTime) t0) (double iters)))
           :bytes (Math/round (/ (- (.getCurrentThreadAllocatedBytes mx) b0) (double iters)))})))))


(defn run
  "Per event: ns and bytes allocated, and the chars of HTML it carried."
  []
  (let [s (EventStream. (OutputStream/nullOutputStream))]
    {:small   (assoc (measure #(sse/patch! s [small]) 200000) :chars (count small))
     :large   (assoc (measure #(sse/patch! s [large]) 20000) :chars (count large))
     :signals (measure #(sse/signals! s signals) 200000)}))
