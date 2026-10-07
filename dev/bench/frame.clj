(ns bench.frame
  "What one change costs one session: the demo page's alpha ticker publishes a
  reading, and the session renders the frame and serializes its patches, as its
  pump would. No pump and no SSE. Reports time, allocation and chars sent per
  change, measured on the calling thread after a warm-up.

  Needs the demo's alpha feed to connect (300 ms). From the REPL:

      (require 'bench.frame)
      (bench.frame/run)"
  (:require
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.resource :as resource]
    [co.multiply.tropical.runtime :as runtime]
    [demo.page :as page])
  (:import
    [co.multiply.tropical.signal Cell Inbox]
    [java.lang.management ManagementFactory]
    [java.util ArrayList]))


(set! *warn-on-reflection* true)


(defn run
  ([] (run {}))
  ([{:keys [warmup n] :or {warmup 5000 n 50000}}]
   (let [mx      ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)
         ctx     {:tab "bench-tab" :uid "bench-uid"}
         inbox   (Inbox.)
         changed (ArrayList.)
         rt      (runtime/runtime ctx inbox)
         root    (page/root ctx)
         !prev   (atom nil)
         !chars  (volatile! 0)
         step    (fn []
                   (Inbox/.drain inbox changed)
                   (let [f                      (runtime/frame! rt root changed)
                         {:keys [morph insert]} (island/patches @!prev f)
                         out                    (mapv island/html (into morph (mapcat :frames) insert))]
                     (ArrayList/.clear changed)
                     (reset! !prev f)
                     (vswap! !chars + (transduce (map count) + out))))]
     (try
       (step)
       (Thread/sleep 1500)
       (step)
       ;; The alpha feed's resource: keyed by its call site and `["alpha"]`,
       ;; as the topic's one-off query is, and told from it by its readings.
       (let [^Cell cell (some (fn [[[_ args] {:keys [!value]}]]
                                (when (and (= ["alpha"] args) (contains? @!value :reading))
                                  !value))
                          @resource/!entries)
             bench      (fn [n]
                          (vreset! !chars 0)
                          (let [a0 (.getCurrentThreadAllocatedBytes mx)
                                t0 (System/nanoTime)]
                            (dotimes [i n]
                              (Cell/.reset cell {:n i :reading i :at "12:00:00.000"})
                              (step))
                            {:us-per-change    (/ (- (System/nanoTime) t0) n 1000.0)
                             :bytes-per-change (quot (- (.getCurrentThreadAllocatedBytes mx) a0) n)
                             :chars-per-change (quot @!chars n)}))]
         (bench warmup)
         (bench n))
       (finally
         (runtime/dispose! rt))))))
