(ns ^:no-doc co.multiply.tropical.sse
  "The wire: Datastar's events, written to an `EventStream`.

  The events and their data lines are those of Datastar's SDK specification
  (`sdk/ADR.md` in the Datastar repository), which is the contract with the
  client. Only what tropical sends is here, with each option at its default."
  (:require
    [s-exp.oda :as json])
  (:import
    [co.multiply.tropical.sse EventStream]))


(defn patch!
  "Morphs each HTML string in `htmls` into the element with the same id, all in
  one event. Given a `:mode` of `:before`, `:after` or `:append` and a
  `:selector`, inserts them, in order, before, after or at the end of the
  element it selects instead. Returns false if the stream is closed."
  ([stream htmls]
   (patch! stream htmls nil))
  ([stream htmls {:keys [mode selector]}]
   (let [;; The elements, a line each with its prefix, and the event line.
         size  (reduce (fn [n ^String html] (+ n 16 (String/.length html))) (+ 96 (count selector)) htmls)
         event (EventStream/event "datastar-patch-elements" (int size))]
     (when selector
       (EventStream/data event "selector" selector))
     (when mode
       (EventStream/data event "mode" (name mode)))
     (run! #(EventStream/data event "elements" %) htmls)
     (EventStream/.send stream event))))


(defn remove!
  "Removes the elements `selector` selects. Returns false if the stream is
  closed."
  [stream selector]
  (EventStream/.send stream
    (-> (EventStream/event "datastar-patch-elements")
      (EventStream/data "selector" selector)
      (EventStream/data "mode" "remove"))))


(defn signals!
  "Merges map `m` into the client's signals, as a JSON Merge Patch. Returns
  false if the stream is closed."
  [stream m]
  (EventStream/.send stream
    (EventStream/data (EventStream/event "datastar-patch-signals") "signals" (json/write-str m))))


(defn heartbeat!
  "A comment, which the client ignores: a dead connection is noticed, and an
  idle one kept open. Returns false if the stream is closed."
  [stream]
  (EventStream/.comment stream))


(defn together
  "Calls `f`, which sends events on `stream`, and flushes them once it
  returns: they reach the client together, and a compressed stream encodes
  them as one. Returns what `f` returns, or false if the stream closed."
  [stream f]
  (EventStream/.hold stream)
  (let [result (try
                 (f)
                 (catch Throwable t
                   (EventStream/.release stream)
                   (throw t)))]
    (and (EventStream/.release stream) result)))


(defn close!
  [stream]
  (EventStream/.close stream))


(defn script!
  "Runs the JavaScript `js` in the page, once: a script appended to the body,
  which removes itself. A `<` in it is written `\\u003c`, so it can't end the
  script element early; `js` holds one only in strings. Returns false if the
  stream is closed."
  [stream ^String js]
  (EventStream/.send stream
    (-> (EventStream/event "datastar-patch-elements")
      (EventStream/data "selector" "body")
      (EventStream/data "mode" "append")
      (EventStream/data "elements" (str "<script data-effect=\"el.remove()\">" (String/.replace js "<" "\\u003c")
                                     "</script>")))))


(defn reload!
  "Reloads the page on the other end of `stream`, then closes it."
  [stream]
  (script! stream "location.reload()")
  (close! stream))
