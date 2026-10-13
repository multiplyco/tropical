(ns co.multiply.tropical.test
  "Islands in a test: rendered as a session renders them, on the test's own
  thread, without a server, a stream or a browser.

  ```clojure
  (with-open [h (test/harness #(app uid) {:uid uid})]
    (is (str/includes? (test/render! h) \"Clicked 0 times\"))
    ((test/action h \"app/counter\" :click) {})
    (is (str/includes? (test/render! h) \"Clicked 1 times\")))
  ```

  A harness holds what its islands hold, as a session does: the resources they
  read, their state, their action tokens. Closing it unmounts them, so their
  resources start to linger, and their tokens are revoked.

  Renders run in the scope of the thread calling `render!`, as a session's run
  in the scope of the request that created it: bind what the route's
  middleware would with `scoping` around them. An island using a cookie state
  (`co.multiply.tropical.cookie`) renders its default."
  (:require
    [co.multiply.scoped :refer [scoping]]
    [co.multiply.tropical.action :as action]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.runtime :as runtime]
    [s-exp.oda :as json])
  (:import
    [co.multiply.tropical.signal Inbox]
    [java.util ArrayList]))


(defrecord Harness [rt ^Inbox inbox root uid request !failures]
  java.lang.AutoCloseable
  (close [_]
    (runtime/dispose! rt)))


(defn harness
  "A session's islands under `root`, for a test: an island's `Call`, such as
  `(app uid)`, or a fn of no arguments returning one, called for each render.
  Render them with `render!`, and close the harness, as `with-open` does, to
  unmount them.

  `opts`:
  - `:uid`, the session's user, which `use-session` returns and actions are
    bound to; `\"user\"` by default. `:tab`, the session's tab.
  - `:error-view`, `(fn [failure] hiccup)`, rendered in place of an island
    whose render throws, as the app's is. The default is the library's.
    `failures` has the failures either way.
  - `:request`, the Ring request that created the session, which
    `use-session` returns, and the one an action's handler gets from
    `action/request`, which in a session is the action's own. Without one,
    `action/request` throws, as it does outside an action."
  (^java.lang.AutoCloseable [root]
   (harness root nil))
  (^java.lang.AutoCloseable [root {:keys [uid tab error-view request] :or {uid "user" tab "tab"}}]
   (let [inbox     (Inbox.)
         !failures (atom [])
         rt        (runtime/runtime (cond-> {:tab tab :uid uid} request (assoc :request request)) inbox
                     {:error-view error-view
                      :on-failure #(swap! !failures conj %)})]
     (->Harness rt inbox root uid request !failures))))


(defn render!
  "Renders what changed since the last render, the whole tree the first time,
  and returns the HTML of the whole tree, as the page would show it.

  While an island's read is still pending (`hooks/pending`), as an `observe`
  whose source hasn't emitted yet, it waits for the reads to land and renders
  again, for up to `:wait-ms` in all, 1000 by default. With `{:wait-ms 0}` it
  renders once, so a test sees what the island shows while it waits."
  ([h]
   (render! h nil))
  ([{:keys [rt ^Inbox inbox root]} {:keys [wait-ms] :or {wait-ms 1000}}]
   (Inbox/.bind inbox)
   (let [until   (+ (System/currentTimeMillis) (long wait-ms))
         changed (ArrayList.)]
     (loop []
       (Inbox/.drain inbox changed)
       ;; Signals patched with `use-patch-signals` go to the page, which a
       ;; harness doesn't have. Dropped, so that waiting isn't woken by them.
       (while (some? (Inbox/.poll inbox)))
       (let [frame (runtime/frame! rt (if (fn? root) (root) root) changed)
             left  (- until (System/currentTimeMillis))]
         (ArrayList/.clear changed)
         (if (and (pos? left) (runtime/pending? rt))
           (do (Inbox/.await inbox left)
             (recur))
           (island/html frame)))))))


(defn action
  "The action that the mounted island at `path` renders under `k`
  (`use-action`), as a fn that invokes it, or nil if the island doesn't render
  it now. `path` is the island's id, the path of slots from the root, such as
  `\"app/messages/message.17\"`: `mounted` lists them.

  The fn takes the signals, and for an action taking a value (`:value`), the
  value. They pass through JSON as the browser's would, so the handler gets
  string keys. It returns what the handler returned: a map of signals, nil, or
  a navigation (`action/navigation` has its script). An action whose island has
  stopped rendering it since throws, its token revoked."
  [{:keys [rt uid request]} path k]
  (when-some [{:keys [token]} (runtime/held rt path [::action/action k])]
    (let [invoke (fn [body]
                   (let [body (json/parse (json/write-str body))
                         [status result] (if (some? request)
                                           (scoping [action/*request* request]
                                             (action/invoke! token uid body))
                                           (action/invoke! token uid body))]
                     (if (= :ok status)
                       result
                       (throw (ex-info (str "The action " (pr-str k) " of " path " is gone: its island stopped rendering it.")
                                {:island path :action k})))))]
      (fn
        ([] (invoke {}))
        ([signals] (invoke signals))
        ([signals value] (invoke {"signals" signals "value" value}))))))


(defn mounted
  "The ids of the mounted islands: the paths of slots from the root."
  [{:keys [rt]}]
  (runtime/mounted rt))


(defn failures
  "The renders that threw, since the harness was made, in order: each the
  failure its error view got (`island/error-view`), with its `:path` and
  `:error`."
  [{:keys [!failures]}]
  @!failures)
