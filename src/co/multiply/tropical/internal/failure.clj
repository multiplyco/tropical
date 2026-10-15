(ns ^:no-doc co.multiply.tropical.internal.failure
  "Logging the decisions made by the pump-owned Failure and the island
  definition's shared TraceCache. Bookkeeping retains no throwables; the
  current exception goes directly to the app's tools.logging backend."
  (:require
    [clojure.tools.logging :as log])
  (:import
    [co.multiply.tropical.runtime Failure Failure$Attempt]))


(defn- island-line
  [^Failure run]
  (str "Island " (.-id run) " (" (.-island run) ", element " (.-elementId run) ")"))


(defn- log-attempt!
  [^Failure run ^Failure$Attempt attempt t error-view?]
  (when (.-log attempt)
    (let [message (str (if error-view? (str "The error view of island " (.-id run)) (island-line run))
                    " failed to render"
                    (when (< 1 (.-count attempt)) (str " again, " (.-count attempt) " times in a row"))
                    ". Reference " (.-reference run) ".")]
      (if (.-trace attempt)
        (log/error t message)
        (log/error (str message
                     (when-some [prior (.-tracedAs attempt)]
                       (str " The same failure as reference " prior ", logged with its stack trace."))
                     " " t))))))


(defn ended!
  "Logs the end of `run` when it had more than one failure, with `how` it ended."
  [^Failure run how]
  (let [n (.-count (.-render run))]
    (when (< 1 n)
      (log/info (str (island-line run) " " how " after " n " failed renders. Reference " (.-reference run) ".")))))


(defn failed!
  "Records and logs `t` for island `id`, under `definition`. Keeps `run` and its
  reference while it fails the same way; a new run ends the previous one."
  [run t definition id element-id]
  (let [next (Failure/failed run (:traces definition) t (:name definition) id element-id)]
    (when (and run (not (identical? run next)))
      (ended! run "failed another way"))
    (log-attempt! next (.-render next) t false)
    next))


(defn view-failed!
  "Records and logs `t` from `view`, independently of the render that began `run`."
  [^Failure run view t]
  (log-attempt! run (Failure/.viewFailed run view t) t true))
