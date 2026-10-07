(ns bench.signal
  "What a change costs on the thread that publishes it, per reading session:
  the signal path (`Cell`, and an atom through `RefSignal`) against what it
  replaced, a watch per session putting an event on the session's queue.

  1,000 sessions read one value. Each round publishes, then every session takes
  what it was given and reads the value. Measured on a platform thread, after a
  warm-up, with the thread's allocation counter. From the REPL:

      (require 'bench.signal)
      (bench.signal/run)"
  (:import
    [co.multiply.tropical.signal Cell Inbox]
    [java.lang.management ManagementFactory]
    [java.util ArrayList]
    [java.util.concurrent LinkedBlockingQueue]))


(set! *warn-on-reflection* true)


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
          {:ns    (/ (- (System/nanoTime) t0) (double iters))
           :bytes (/ (- (.getCurrentThreadAllocatedBytes mx) b0) (double iters))})))))


(defn- owner-thread
  []
  (doto (Thread. ^Runnable #(try (Thread/sleep Long/MAX_VALUE) (catch InterruptedException _)))
    (.setDaemon true)
    (.start)))


(defn- watch-bench
  "The baseline: a watch per session on the atom, putting `[:changed ref]` on
  the session's queue, drained into a set."
  [n pubs]
  (let [ref   (atom 0)
        boxes (vec (repeatedly n #(LinkedBlockingQueue.)))]
    (doseq [^LinkedBlockingQueue b boxes]
      (add-watch ref (Object.) (fn [_ _ old new] (when-not (identical? old new) (.put b [:changed ref])))))
    (fn []
      (dotimes [_ pubs] (reset! ref (Object.)))
      (doseq [^LinkedBlockingQueue b boxes]
        (let [evs (ArrayList.)]
          (when-some [ev (.poll b)] (.add evs ev) (.drainTo b evs))
          (reduce (fn [s ev] (conj s (second ev))) #{} evs))))))


(defn- signal-bench
  "An inbox per session, owned by a parked thread, so that marks unpark it as a
  session's pump would be."
  [n pubs source publish!]
  (let [owner   (owner-thread)
        inboxes (vec (repeatedly n #(Inbox.)))
        changed (ArrayList.)]
    (let [f (.getDeclaredField Inbox "owner")]
      (.setAccessible f true)
      (doseq [i inboxes] (.set f i owner)))
    (doseq [^Inbox i inboxes] (.subscribe i source))
    (fn []
      (dotimes [_ pubs] (publish!))
      (doseq [^Inbox i inboxes]
        (.drain i changed)
        (dotimes [k (.size changed)] (deref ^clojure.lang.IDeref (.get changed k)))
        (.clear changed)))))


(defn run
  "Per change and reading session: ns and bytes on the publishing side, for a
  session that keeps up (one change per drain) and one behind (ten)."
  ([] (run {}))
  ([{:keys [sessions iters] :or {sessions 1000 iters 4000}}]
   (let [per (fn [m pubs]
               {:ns    (Math/round (double (/ (:ns m) (* sessions pubs))))
                :bytes (/ (Math/round (* 10 (double (/ (:bytes m) (* sessions pubs))))) 10.0)})]
     (into {}
       (for [[k pubs] [[:keeps-up 1] [:behind 10]]]
         [k {:watch      (per (measure (watch-bench sessions pubs) (quot iters pubs)) pubs)
             :cell       (per (measure (let [c (Cell. 0)]
                                         (signal-bench sessions pubs c #(Cell/.reset c (Object.))))
                                (quot iters pubs))
                           pubs)
             :ref-signal (per (measure (let [a (atom 0)]
                                         (signal-bench sessions pubs a #(reset! a (Object.))))
                                (quot iters pubs))
                           pubs)}])))))
