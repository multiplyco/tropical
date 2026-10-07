(ns co.multiply.tropical.test-util
  (:require
    [co.multiply.tropical.hooks :refer [observe]]
    [co.multiply.tropical.resource :as resource]
    [co.multiply.tropical.runtime :as runtime])
  (:import
    [co.multiply.tropical.signal Inbox]
    [java.util ArrayList]))


(defn message-view
  "An error view that shows the exception's message, so tests can tell which
  failure an island rendered."
  [id e]
  [:div "Island " id " failed to render: " (ex-message e)])


(defn harness
  "A runtime for `root-fn` (a fn of no arguments returning the root `Call`),
  driven the way a session pump drives it. Returns `{:step f, :rt rt}`: `step`
  renders a frame from the changes marked since the previous one. `opts` are
  the runtime's, with `message-view` as the error view unless given."
  ([root-fn]
   (harness root-fn {:tab "tab" :uid "user"}))
  ([root-fn ctx]
   (harness root-fn ctx {:error-view message-view}))
  ([root-fn ctx opts]
   (let [inbox (Inbox.)
         rt    (runtime/runtime ctx inbox opts)]
     {:rt   rt
      :step (fn []
              (let [changed (ArrayList.)]
                (Inbox/.drain inbox changed)
                (runtime/frame! rt (root-fn) changed)))})))


(defn observe-source
  "Observes `subject`, a `(fn [emit!] undo)`, under `key`. One call site for
  every caller, the way an app wraps `observe` in a function of its own: the
  resources it opens differ by `key` and `subject`."
  [key subject]
  (observe key subject (fn [emit! _ subject] (subject emit!))))


(defn entry
  "The registry's summary of the resource `observe-source` opened for `key`."
  [key]
  (some (fn [[[_ args] summary]] (when (= key (first args)) summary))
    (resource/summary @resource/!entries)))


(defn manual-source
  "A source for `observe-source`, under a unique key, whose values the test
  emits. Returns `[key subject emit!]`. `emit!` emits through the first resource
  that `subject` opened, waiting for the subject to have run, since it runs on a
  thread of its own."
  []
  (let [p (promise)]
    [[::manual (random-uuid)]
     (fn [emit!]
       (deliver p emit!)
       nil)
     (fn [v]
       (if-let [emit! (deref p 1000 nil)]
         (emit! v)
         (throw (ex-info "The source was never opened." {}))))]))


(def datastar-statement
  "A statement of an expression that returns a value, as Datastar 1.0.4 finds
  them before it returns the last, ported from its source: what lies between
  `;`s, outside strings, regex literals, and an arrow function that takes no
  arguments, called with none."
  #"(?:/(?:\\/|[^/])*/|\"(?:\\\"|[^\"])*\"|'(?:\\'|[^'])*'|`(?:\\`|[^`])*`|\(\s*(?:(?:function)\s*\(\s*\)|(?:\(\s*\))\s*=>)\s*(?:\{[\s\S]*?\}|[^;){]*)\s*\)\s*\(\s*\)|[^;])+")
