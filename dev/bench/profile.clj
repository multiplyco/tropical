(ns bench.profile
  "Isolated rendering workloads, with wall time, thread allocation and JFR.

  Run in a fresh JVM (no server is started):

      clj -J-Xms512m -J-Xmx512m -M -i dev/bench/profile.clj

  Then call `(bench.profile/-main \"measure\")` or
  `(bench.profile/-main \"record\" \"sparse-1000\")`. `-main` exits the JVM
  when done. See doc/development.md for shell commands.

  Results go under target/perf. Workloads publish a value, drain the inbox,
  reconcile, prepare patches and write a batch to a null stream. They exclude
  the session's pacing, HTTP, browser work and compression. Allocation is on
  the calling platform thread; JFR runs separately from timing samples."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [co.multiply.tropical.internal.delivery :as delivery]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.sse :as sse])
  (:import
    [co.multiply.tropical.signal Cell Inbox]
    [co.multiply.tropical.sse EventStream]
    [java.io OutputStream]
    [java.lang.management ManagementFactory]
    [java.nio.file Paths]
    [java.time Duration]
    [java.util ArrayList]
    [jdk.jfr Configuration Recording]
    [jdk.jfr.consumer RecordedEvent RecordedFrame RecordingFile]))


(set! *warn-on-reflection* true)


(island/defisland row {:key (fn [id _ _] id)}
  [id source select]
  [:li {:class "row"}
   [:span "Row " id]
   [:strong (island/use-watch source select)]])


(island/defisland rows
  [sources size select]
  (into [:ul]
    (map (fn [i] (row i (nth sources i) select)))
    (range (island/use-watch size))))


(island/defisland branch
  [child]
  [:section child])


(island/defisland many-reads
  [sources]
  [:p (reduce (fn [total source] (+ total (island/use-watch source))) 0 sources)])


(def workloads
  {"sparse-10"     {:width 10}
   "sparse-100"    {:width 100}
   "sparse-1000"   {:width 1000}
   "sparse-10000"  {:width 10000}
   "deep-20"       {:width 1 :depth 20}
   "fanout-100"    {:width 100 :mode :fanout}
   "fanout-1000"   {:width 1000 :mode :fanout}
   "filtered-1000" {:width 1000 :mode :filtered}
   "reads-128"     {:width 128 :mode :many-reads}
   "churn-1000"    {:width 1000 :mode :churn}})


(defn fixture
  "A workload with `:step`, `:close`, and its most recent prepared patch.
  `:stage :render` stops before patch preparation and writing."
  [{:keys [width depth mode stage] :or {depth 2 mode :sparse}}]
  (let [source   (Cell. 100000)
        size     (Cell. width)
        sources  (if (#{:fanout :filtered} mode)
                   (vec (repeat (inc width) source))
                   (assoc (vec (repeatedly (inc width) #(Cell. 100000))) 0 source))
        select   (if (= mode :filtered) (constantly 0) identity)
        inbox    (Inbox.)
        changed  (ArrayList.)
        rt       (runtime/runtime {:tab "profile" :uid "profile"} inbox)
        root     (nth (iterate branch (if (= mode :many-reads)
                                        (many-reads (subvec sources 0 width))
                                        (rows sources size select)))
                   depth)
        conn     (EventStream. (OutputStream/nullOutputStream))
        !before  (volatile! (runtime/frame! rt root []))
        !last    (volatile! nil)
        !n       (volatile! 100000)]
    {:runtime rt
     :prepared !last
     :step (fn []
             (let [n (if (= 100000 @!n) 100001 100000)]
               (vreset! !n n)
               (if (= mode :churn)
                 (Cell/.reset size (+ width (bit-and n 1)))
                 (Cell/.reset source n)))
             (Inbox/.drain inbox changed)
             (let [frame (runtime/frame! rt root changed)]
               (ArrayList/.clear changed)
               (when-not (= stage :render)
                 (let [prepared (delivery/prepare-frame @!before frame)]
                   (vreset! !last prepared)
                   (sse/together conn #(delivery/send-frame! conn prepared))))
               (vreset! !before frame)))
     :close (fn [] (runtime/dispose! rt) (sse/close! conn))}))


(defn- batch
  [step n]
  (dotimes [_ n] (step)))


(defn- exercise
  [step milliseconds]
  (let [until (+ (System/nanoTime) (* (long milliseconds) 1000000))]
    (loop [n 0]
      (batch step 32)
      (if (< (System/nanoTime) until)
        (recur (+ n 32))
        (+ n 32)))))


(defn- sample
  [step n]
  (when (.isVirtual (Thread/currentThread))
    (throw (IllegalStateException. "Allocation measurements need a platform thread; use q/cpu-task from the REPL.")))
  (let [mx ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)
        a0 (.getCurrentThreadAllocatedBytes mx)
        t0 (System/nanoTime)]
    (batch step n)
    {:us (/ (- (System/nanoTime) t0) (double n) 1000.0)
     :bytes (/ (- (.getCurrentThreadAllocatedBytes mx) a0) (double n))}))


(defn measure
  [opts]
  (let [{:keys [step close prepared runtime]} (fixture opts)]
    (try
      (let [n       (max 32 (quot (exercise step 2000) 8))
            samples (vec (repeatedly 7 #(sample step n)))
            us      (sort (map :us samples))]
        {:median-us (nth us 3)
         :range-us [(first us) (last us)]
         :bytes (long (/ (reduce + (map :bytes samples)) 7))
         :chars (:chars @prepared)
         :patch-counts (when @prepared (update-vals (:patches @prepared) count))
         :instances (count @(:!instances runtime))
         :iterations-per-sample n
         :samples samples})
      (finally (close)))))


(defn record
  [name seconds]
  (let [{:keys [step close]} (fixture (workloads name))
        path (str "target/perf/" name ".jfr")]
    (try
      (io/make-parents path)
      (exercise step 3000)
      (with-open [recording (Recording. (Configuration/getConfiguration "profile"))]
        (-> (.enable recording "jdk.ExecutionSample") (.withPeriod (Duration/ofMillis 1)))
        (-> (.enable recording "jdk.ObjectAllocationSample") (.with "throttle" "1000/s"))
        (.start recording)
        (let [n (exercise step (* 1000 seconds))]
          (.stop recording)
          (.dump recording (Paths/get path (make-array String 0)))
          {:path path :steps n :seconds seconds}))
      (finally (close)))))


(defn environment
  []
  {:java (System/getProperty "java.runtime.version")
   :clojure (clojure-version)
   :os (System/getProperty "os.name")
   :arch (System/getProperty "os.arch")
   :processors (.availableProcessors (Runtime/getRuntime))
   :max-heap (.maxMemory (Runtime/getRuntime))
   :jvm-args (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))})


(defn summarize
  "Summarizes the main thread's CPU and weighted allocation samples in `path`.
  Inclusive percentages overlap; allocation samples estimate bytes, not counts."
  [path]
  (let [!counts (volatile! {})
        add! (fn [group name n] (vswap! !counts update-in [group name] (fnil + 0) n))]
    (with-open [file (RecordingFile. (Paths/get path (make-array String 0)))]
      (while (.hasMoreEvents file)
        (let [^RecordedEvent event (.readEvent file)
              kind (.getName (.getEventType event))]
          (when (#{"jdk.ExecutionSample" "jdk.ObjectAllocationSample"} kind)
            (let [thread (.getThread event (if (= kind "jdk.ExecutionSample") "sampledThread" "eventThread"))
                  trace (.getStackTrace event)]
              (when (and thread (= "main" (.getJavaName thread)) trace)
                (let [stack (mapv (fn [^RecordedFrame frame]
                                    (let [method (.getMethod frame)
                                          class-name (.getName (.getType method))]
                                      (if (.contains class-name "$") class-name
                                          (str class-name "." (.getName method)))))
                              (.getFrames trace))
                      tropical (filter #(.startsWith ^String % "co.multiply.tropical.") stack)]
                  (add! :events kind 1)
                  (if (= kind "jdk.ExecutionSample")
                    (do (add! :cpu-leaf (first stack) 1)
                      (doseq [method (set tropical)] (add! :cpu-inclusive method 1)))
                    (let [weight (.getLong event "weight")]
                      (add! :allocation-class (.getName (.getClass event "objectClass")) weight)
                      (add! :allocation-site (or (first tropical) (first stack)) weight)
                      (doseq [method (set tropical)] (add! :allocation-inclusive method weight)))))))))))
    (let [counts @!counts
          cpu (reduce + (vals (:cpu-leaf counts)))
          allocated (reduce + (vals (:allocation-class counts)))
          result (into {:events (:events counts) :sampled-bytes allocated}
                   (for [[group entries] (dissoc counts :events)]
                     [group (mapv (fn [[name n]]
                                    {:name name :percent (* 100.0 (/ n (if (#{:cpu-leaf :cpu-inclusive} group) cpu allocated)))})
                              (take 25 (sort-by val > entries)))]))]
      (spit (str path ".edn") (pr-str result))
      result)))


(defn -main
  [& [command name seconds]]
  (.mkdirs (io/file "target/perf"))
  (try
    (prn {:environment (environment)})
    (case command
      "measure"
      (let [selected (if name [name] (sort (keys workloads)))
            results (into (sorted-map)
                      (for [name selected]
                        (let [result (measure (workloads name))]
                          (prn {name result})
                          (flush)
                          [name result])))]
        (spit (str "target/perf/measure" (when name (str "-" name)) ".edn")
          (pr-str {:environment (environment) :results results})))

      "record"
      (prn (record name (if seconds (edn/read-string seconds) 10)))

      "summarize"
      (prn (summarize name)))
    (shutdown-agents)
    (System/exit 0)
    (catch Throwable t
      (.printStackTrace t)
      (System/exit 1))))
