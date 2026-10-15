(ns co.multiply.tropical.ring-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging :as log]
    [co.multiply.quiescent :as q]
    [co.multiply.scoped :refer [ask scoping]]
    [co.multiply.tropical.action :as action :refer [use-action]]
    [co.multiply.tropical.cookie :as cookie]
    [co.multiply.tropical.island :as island :refer [defisland]]
    [co.multiply.tropical.ring :as ring]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.script :as script]
    [co.multiply.tropical.session :as session]
    [co.multiply.tropical.test-util :refer [capturing datastar-statement harness]]
    [dev.onionpancakes.chassis.core :as h]
    [ring.core.protocols :as protocols]
    [s-exp.oda :as json])
  (:import
    [com.github.luben.zstd ZstdInputStreamNoFinalizer]
    [java.io ByteArrayInputStream ByteArrayOutputStream]
    [java.util.zip GZIPInputStream]))


(defisland hello
  []
  [:p "hello"])


(defisland at
  [uri]
  [:p "at " uri])


(def ^:private app
  {:uid  #(get-in % [:headers "x-uid"])
   :root (fn [{:keys [request]}] (at (:uri request)))
   :page (fn [{:keys [tab frame]}]
           (str "<body data-init=\"" (ring/stream-init tab) "\">" (some-> frame island/html) "</body>"))})


(def ^:private handler
  (ring/handler app))


(defn- request
  "A request to the page at `uri`, by `uid`, with more `headers`."
  [method uri uid headers]
  {:request-method method
   :uri            uri
   :headers        (cond-> headers uid (assoc "x-uid" uid))})


(defn- action-request
  [token uid]
  (request :post "/page" uid {"tropical-action" token "content-type" "application/json"}))


(defn- act
  [token uid & {:as more}]
  (handler (merge (action-request token uid) more)))


(def ^:private uuid
  "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


(defn- tokens
  "The action tokens in `html`, in document order: each its tab, then a uuid."
  [html]
  (map second (re-seq (re-pattern (str "Tropical-Action.*?([A-Za-z0-9-]+_" uuid ")")) html)))


(defisland buttons
  []
  [:div
   [:button {:data-on:click (use-action :nothing (fn [_] nil))} "Nothing"]
   [:button {:data-on:click (use-action :signals (fn [_] {:note ""}))} "Signals"]])


(deftest an-action-is-invoked-for-its-user
  (let [{:keys [step rt]} (harness buttons {:tab "tab" :uid "alice"})
        [nothing signals] (tokens (island/html (step)))]
    (is (every? #(= "tab" (action/token-tab %)) [nothing signals]) "a token names its tab")
    (is (= {:status 204} (act nothing "alice")))
    (is (= {:note ""} (json/parse (:body (act signals "alice")) {:key-fn keyword})))
    (is (= 403 (:status (act nothing "mallory"))) "another user's action")
    (is (= 403 (:status (act nothing nil))) "no user")
    (testing "async"
      (let [p (promise)]
        (handler (action-request nothing "alice") p #(deliver p %))
        (is (= {:status 204} (deref p 1000 :timeout)))))
    (runtime/dispose! rt)
    (is (= 404 (:status (act nothing "alice"))) "revoked with its island")
    (is (= 404 (:status (act (str (random-uuid)) "alice"))) "never minted")))


(deftest an-action-whose-tab-is-held-elsewhere-reopens-the-stream
  (let [tab      (str (random-uuid))
        token    (str tab "_" (random-uuid))
        s        (session/create! tab "alice" {:root (fn [_] (hello))})
        datastar #(handler (update (action-request token "alice") :headers assoc "datastar-request" "true"))]
    (is (= 404 (:status (datastar))) "its tab held here, a token it doesn't know is revoked: the stream updates the page")
    (session/close! s)
    (is (deref (:closed s) 1000 false))
    (testing "its tab held nowhere here"
      (let [resp (datastar)]
        (is (= 200 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
        (is (str/includes? (:body resp) "tropical:reopen") "Datastar's request: events that reopen the page's stream"))
      (let [resp (act token "alice")]
        (is (= 409 (:status resp)))
        (is (some? (get-in resp [:headers "Tropical-Reopen"])) "a script's: a header it reopens the stream on")))
    (is (= 404 (:status (act (str "not-a-tab_" (random-uuid)) "alice"))) "a token naming no tab")
    (is (= 404 (:status (act (str (random-uuid)) "alice"))) "a token naming none, as before tokens named their tab")))


(deftest the-handler-checks-stream-max-ms
  (is (some? (ring/handler (assoc app :stream-max-ms 300000))))
  (doseq [v [0 -1 "5m" 1.5]]
    (is (thrown? IllegalArgumentException (ring/handler (assoc app :stream-max-ms v))) (pr-str v))))


(def ^:dynamic *route* nil)


(defisland whose-request
  []
  [:div
   [:p "render sees " (pr-str (ask action/*request* nil))]
   [:button {:data-on:click (use-action :whose (fn [_]
                                                 {:cookie (get-in (action/request) [:headers "cookie"])
                                                  :route  (ask *route*)}))}
    "Whose"]])


(deftest an-action-has-its-own-request
  (let [handler (let [h (ring/handler (assoc app :root (fn [_] (whose-request))))]
                  ;; The route's middleware, binding what it reads from the request.
                  (fn
                    ([req] (scoping [*route* (:uri req)] (h req)))
                    ([req respond raise] (scoping [*route* (:uri req)] (h req respond raise)))))
        page    (:body (handler (request :get "/page" "alice" {})))
        [whose] (tokens page)
        req     (assoc-in (action-request whose "alice") [:headers "cookie"] "set=by-the-page")
        parse   #(json/parse (:body %) {:key-fn keyword})]
    (is (str/includes? page "render sees nil") "not a render's, although the page's is a request too")
    (is (= {:cookie "set=by-the-page" :route "/page"} (parse (handler req)))
      "the one the action came on, in the scope the middleware bound for it")
    (testing "async"
      (let [p (promise)]
        (handler req p #(deliver p %))
        (is (= {:cookie "set=by-the-page" :route "/page"} (parse (deref p 1000 nil))))))
    (is (thrown? IllegalStateException (action/request)) "nor anywhere else")
    (is (nil? (ask action/*request* nil)))
    (run! session/close! (filter #(= "alice" (:uid %)) (vals @session/!sessions)))))


(defisland mover
  []
  [:div
   [:button {:data-on:click (use-action :push (fn [_] (action/navigate "/items/42?tab=notes")))} "Push"]
   [:button {:data-on:click (use-action :replace (fn [_] (action/navigate "/items/42" {:replace true})))} "Replace"]
   [:button {:data-on:click (use-action :use-up (fn [_] (action/navigate "/items/<7>" {:signals {:_kept {"a" 1}}})))}
    "Use up"]])


(defn- events
  "The events of an event stream's text `body`, as `[event data-lines]`."
  [body]
  (for [block (str/split (str/trim body) #"\n\n")
        :let [lines (str/split-lines block)]]
    [(subs (first (filter #(str/starts-with? % "event: ") lines)) 7)
     (keep #(when (str/starts-with? % "data: ") (subs % 6)) lines)]))


(deftest an-action-can-navigate
  (let [{:keys [step rt]} (harness mover {:tab "tab" :uid "alice"})
        [push replace use-up] (tokens (island/html (step)))
        answer (fn [token]
                 (let [resp (act token "alice")]
                   (is (= 200 (:status resp)))
                   (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/event-stream")
                     "events, which Datastar handles in order")
                   (events (:body resp))))]
    (is (= [["datastar-patch-elements" ["selector body" "mode append"
                                        "elements <script data-effect=\"el.remove()\">location.assign(\"/items/42?tab=notes\")</script>"]]]
           (answer push))
      "a script, which removes itself once it has run")
    (is (str/includes? (last (second (first (answer replace)))) "location.replace(\"/items/42\")"))
    (let [[[signals [data]] [script lines]] (answer use-up)]
      (is (= "datastar-patch-signals" signals) "the signals first, so the page keeps what they set before it leaves")
      (is (= "signals {\"_kept\":{\"a\":1}}" data))
      (is (= "datastar-patch-elements" script))
      (is (str/includes? (last lines) "location.assign(\"/items/\\u003c7>\")") "a < can't end the script early"))
    (runtime/dispose! rt)))


(deftest navigate-takes-a-map-of-signals
  (is (nil? (action/navigation-signals (action/navigate "/items/42" {:signals {}}))))
  (is (thrown? IllegalArgumentException (action/navigate "/items/42" {:signals [[:a 1]]})))
  (is (thrown? IllegalArgumentException (action/navigate "/items/42" {:cookie [[:a 1]]})) "an option it doesn't take"))


(deftest navigate-opens-only-paths-on-the-page's-origin
  (doseq [path ["/" "/items/42" "/items/42?tab=notes#top" "/a/b%20c" "/%2F%2Fhost"]]
    (is (action/navigation (action/navigate path)) path)
    (is (:data-init (second (action/navigate-element path))) path))
  (doseq [path ["//host" "/\\host" "https://host/" "javascript:alert(1)" "items/42" "" "/\t/host" "/\n/host"
                "/a b" nil :items]]
    (is (thrown? IllegalArgumentException (action/navigate path)) (pr-str path))
    (is (thrown? IllegalArgumentException (action/navigate-element path)) (pr-str path))))


(deftest navigate-opens-another-origin-when-told-to
  (doseq [url ["https://example.com/authorize?state=a%20b" "http://localhost:9000/" "HTTPS://example.com"]]
    (is (= (str "location.assign(" (json/write-str url) ")") (action/navigation (action/navigate url {:external true})))
      url)
    (is (str/includes? (:data-init (second (action/navigate-element url {:external true :replace true})))
          (str "location.replace(" (json/write-str url) ")"))
      url))
  (doseq [url ["/items/42" "//host" "javascript:alert(1)" "data:text/html,x" "ftp://host/" "https://" "https://a b"
               "https://\thost/" " https://host/" nil]]
    (is (thrown? IllegalArgumentException (action/navigate url {:external true})) (pr-str url))
    (is (thrown? IllegalArgumentException (action/navigate-element url {:external true})) (pr-str url))))


(defisland moved
  [path]
  [:div
   [:p "Moved"]
   (action/navigate-element path {:replace true})])


(deftest an-element-navigates-once-in-the-page
  (let [init #(:data-init (second (apply action/navigate-element %&)))]
    (is (str/includes? (init "/items/42" {:replace true}) "location.replace(\"/items/42\")")
      "in place of the history entry")
    (is (str/includes? (init "/items/42") "location.assign(\"/items/42\")"))
    (is (< (str/index-of (init "/items/42") "location.href") (str/index-of (init "/items/42") "location.assign"))
      "it compares the path with where the page is before it navigates"))
  (let [{:keys [step rt]} (harness #(moved "/items/42") {:tab "tab" :uid "alice"})
        [_ init] (re-find #"data-init=\"([^\"]*)\"" (island/html (step)))]
    (is (= (:data-init (second (action/navigate-element "/items/42" {:replace true})))
           (str/replace init #"&(?:quot|apos|amp|lt|gt);" {"&quot;" "\"" "&apos;" "'" "&amp;" "&" "&lt;" "<" "&gt;" ">"}))
      "an attribute Datastar runs once the element is in the page")
    (runtime/dispose! rt)))


(defisland echo
  []
  [:button {:data-on:click (use-action :echo (fn [signals] {:got signals}))} "Echo"])


(deftest an-action-reads-the-signals-itself
  (let [{:keys [step rt]} (harness echo {:tab "tab" :uid "alice"})
        [token] (tokens (island/html (step)))
        send (fn [& {:as more}]
               (let [resp (act token "alice" more)]
                 (cond-> resp (= 200 (:status resp)) (update :body json/parse))))
        body (fn [^String s]
               {:headers {"x-uid"           "alice"
                          "tropical-action" token
                          "content-type"    "application/json"
                          "content-length"  (str (count (.getBytes s "UTF-8")))}
                :body    (ByteArrayInputStream. (.getBytes s "UTF-8"))})]
    (is (= {"got" {"note" "hi"}} (:body (send (body "{\"note\":\"hi\"}")))))
    (is (= {"got" {}} (:body (send))) "none")
    (is (= 400 (:status (send (body "{nope")))) "JSON that doesn't parse")
    (is (= 500 (:status (send (assoc (body "{\"note\":\"hi\"}") :body (ByteArrayInputStream. (byte-array 0))))))
      "a body something read first")
    (runtime/dispose! rt)))


(defisland picker
  []
  [:div
   [:div {:data-on:click (use-action :pick (fn [signals value] {:signals signals :value value}) {:value "evt.detail"})}
    "Pick"]
   [:button {:data-on:click (use-action :plain (fn [signals] {:signals signals}))} "Plain"]])


(defn- expressions
  "The `data-on:click` expressions in `html`, in document order."
  [html]
  (map second (re-seq #"data-on:click=\"([^\"]*)\"" html)))


(deftest an-action-takes-a-value-from-its-expression
  (let [{:keys [step rt]} (harness picker {:tab "tab" :uid "alice"})
        html (island/html (step))
        [pick plain] (tokens html)
        send (fn [token ^String s]
               (let [bytes (.getBytes s "UTF-8")
                     resp  (act token "alice" :body (ByteArrayInputStream. bytes))]
                 (cond-> resp (= 200 (:status resp)) (update :body json/parse))))]
    (testing "the value is evaluated in the browser, by the action's own expression"
      (let [[pick-js plain-js] (expressions html)]
        (is (str/includes? pick-js "evt.detail"))
        (is (not (str/includes? plain-js "evt.detail")) "another action's post doesn't carry it")))
    (is (= {"signals" {"note" "hi"} "value" {"id" "r1"}}
           (:body (send pick "{\"signals\":{\"note\":\"hi\"},\"value\":{\"id\":\"r1\"}}")))
      "the handler gets the signals and the value")
    (is (= {"signals" {} "value" nil} (:body (send pick "{}"))) "none")
    (is (= {"signals" {} "value" nil} (:body (send pick "[1]"))) "a body of another shape is no value")
    (is (= {"signals" {"note" "hi"}} (:body (send plain "{\"note\":\"hi\"}"))) "an action without one, as before")
    (runtime/dispose! rt)))


(deftest a-stream-can-stay-open-while-its-page-is-hidden
  (let [tab    (str (random-uuid))
        plain  (ring/stream-init tab)
        hidden (ring/stream-init tab {:open-when-hidden true})]
    (is (not (str/includes? plain "openWhenHidden"))
      "by default Datastar closes it while hidden, and reopens it when shown")
    (is (str/includes? hidden "openWhenHidden: true"))
    (is (= plain (ring/stream-init tab {:open-when-hidden false})))
    (is (every? #(= 1 (count (re-seq datastar-statement %))) [plain hidden]))
    (is (str/includes? hidden "addEventListener('pagehide', () => stream.abort())")
      "kept open, it is cancelled as the page is left")
    (is (str/includes? hidden "addEventListener('pageshow'")
      "and opened again as the page is restored")
    (is (thrown? Exception (ring/stream-init tab {:open-hidden true})) "an option it doesn't know")))


(deftest a-stream-reopens-as-the-same-page-load
  (doseq [init [(ring/stream-init (str (random-uuid))) (ring/stream-init (str (random-uuid)) {:open-when-hidden true})]]
    (is (str/includes? init "addEventListener('tropical:reopen', open)"))
    (is (str/includes? init "'Tropical-Load': load") "with the load's id, made once")
    (is (= 1 (count (re-seq #"Math\.random" init))))
    (is (str/includes? init "stream && stream.abort()") "the stream before it ends")))


(deftest request-kind-tells-the-handlers-requests-apart
  (let [kind #(ring/request-kind (request %1 "/page" nil %2))]
    (is (= :page (kind :get {})))
    (is (= :stream (kind :get {"tropical-tab" "t"})))
    (is (= :action (kind :post {"tropical-action" "t" "content-type" "application/json"})))
    (is (= :action (kind :post {"tropical-action" "t" "content-type" "Application/JSON; charset=utf-8"})))
    (is (nil? (kind :post {"tropical-action" "t" "content-type" "multipart/form-data; boundary=x"}))
      "a body other than JSON, which Ring's parsers might read")
    (is (nil? (kind :post {"tropical-action" "t"})))
    (is (nil? (kind :post {"content-type" "application/json"})))
    (is (nil? (kind :put {"tropical-action" "t" "content-type" "application/json"})))))


(deftest requests-it-does-not-serve
  (is (= 400 (:status (handler (request :post "/page" "alice" {})))) "a POST that isn't an action")
  (is (= 415 (:status (handler (request :post "/page" "alice" {"tropical-action" (str (random-uuid))}))))
    "an action without a JSON body")
  (is (= 405 (:status (handler (request :put "/page" "alice" {}))))))


(deftest an-action-body-has-a-cap
  (let [{:keys [step rt]} (harness echo {:tab "tab" :uid "alice"})
        [token] (tokens (island/html (step)))
        capped (ring/handler (assoc app :max-signals-bytes 16))
        send   (fn [^String s announce?]
                 (let [bytes (.getBytes s "UTF-8")]
                   (:status (capped (cond-> (assoc (action-request token "alice")
                                              :body (ByteArrayInputStream. bytes))
                                      announce? (assoc-in [:headers "content-length"]
                                                  (str (count bytes))))))))]
    (is (= 200 (send "{\"note\":\"12345\"}" true)) "16 bytes, the cap")
    (is (= 413 (send "{\"note\":\"123456\"}" true)) "one over, announced")
    (is (= 413 (send "{\"note\":\"123456\"}" false)) "one over, read without a length, as when chunked")
    (runtime/dispose! rt)))


(defn- stream-request
  "The stream request of `tab`, from a page of the server's revision."
  [uri uid tab]
  (let [req (request :get uri uid (cond-> {"tropical-load" "x"} tab (assoc "tropical-tab" tab)))]
    (assoc-in req [:headers "tropical-revision"] (ring/revision-of app req))))


(defn- reloads?
  "Whether `resp` is a stream that reloads its page."
  [resp]
  (and (= 200 (:status resp))
    (= "text/event-stream" (get-in resp [:headers "Content-Type"]))
    (str/includes? (:body resp) "location.reload()")))


(deftest a-stream-turns-away-what-it-cannot-attach
  (testing "by reloading the page, so the page request gets the answer"
    (is (reloads? (handler (stream-request "/page" nil (str (random-uuid))))) "no user")
    (is (reloads? (handler (stream-request "/page" "alice" "not-a-uuid"))) "not a tab id")
    (let [tab (str (random-uuid))
          s   (session/create! tab "alice" {:root (fn [_] (hello))})]
      (is (reloads? (handler (stream-request "/page" "mallory" tab))) "another user's tab")
      (session/close! s)
      (is (deref (:closed s) 1000 false))))
  (is (= 403 (:status (handler (request :get "/page" nil {})))) "a page is refused as it is"))


(defn- refusing
  "A handler, wrapped in `wrap-refused-streams`, answering every request with
  `resp`."
  [resp]
  (ring/wrap-refused-streams
    (fn
      ([_] resp)
      ([_ respond _] (respond resp)))))


(defn- guard-cookie
  "The `Cookie` header that sends back the guard cookie a reload set."
  [resp]
  (some #(re-find #"tropical-reloaded=[^;]*" %) (get-in resp [:headers "Set-Cookie"])))


(deftest a-refused-stream-reloads-its-page
  (let [stream (stream-request "/items/42" "alice" (str (random-uuid)))]
    (testing "a redirect, or a 4xx that retrying won't resolve"
      (doseq [status [302 303 307 401 403 404 410]]
        (is (reloads? ((refusing {:status status :headers {"Location" "/sign-in"}}) stream)) (str status))))
    (testing "what retrying may resolve, and what isn't a refusal, stay as they are"
      (doseq [status [200 204 408 429 500 502 503]]
        (is (= status (:status ((refusing {:status status}) stream))) (str status))))
    (testing "only a stream: the page gets the app's answer"
      (is (= 404 (:status ((refusing {:status 404}) (request :get "/items/42" "alice" {})))))
      (is (= 404 (:status ((refusing {:status 404}) (action-request (str (random-uuid)) "alice"))))))
    (testing "async"
      (let [p (promise)]
        ((refusing {:status 404}) stream #(deliver p %) #(deliver p %))
        (is (reloads? (deref p 1000 nil)))))
    (testing "the refusal's cookies go with it"
      (let [cookies (get-in ((refusing {:status 302 :headers {"set-cookie" "session=; Max-Age=0"}}) stream)
                      [:headers "Set-Cookie"])]
        (is (some #{"session=; Max-Age=0"} cookies))
        (is (some #(str/starts-with? % "tropical-reloaded=") cookies))))))


(deftest a-page-whose-stream-is-refused-again-is-left-as-it-is
  (let [refuse (refusing {:status 404})
        from   (fn [uri load cookie]
                 (cond-> (assoc-in (stream-request uri "alice" (str (random-uuid))) [:headers "tropical-load"] load)
                   cookie (assoc-in [:headers "cookie"] (str "uid=alice; " cookie))))
        cookie (guard-cookie (refuse (from "/items/42" "load1" nil)))]
    (is (some? cookie))
    (is (reloads? (refuse (from "/items/42" "load1" cookie)))
      "the load told to reload, retrying before it unloads, is told again")
    (is (= 204 (:status (refuse (from "/items/42" "load2" cookie))))
      "the page reloaded and answered, but its stream is refused: a 204 ends the client's retries")
    (is (reloads? (refuse (from "/items/43" "load2" cookie))) "another page")))


(defn- attaches?
  "Whether `resp` is a stream attached to a session."
  [resp]
  (and (= 200 (:status resp)) (not (string? (:body resp)))))


(deftest a-page-of-another-revision-than-the-server's-reloads
  (let [!named (atom "head-1")
        h      (ring/handler (assoc app :revision (fn [_] @!named)))
        page   (fn [] (:body (h (request :get "/page" "alice" {}))))
        body   (page)
        rev    (second (re-find #"'Tropical-Revision': '([0-9a-f]+)'" body))
        stream (fn [tab revision & {:keys [load cookie]}]
                 (h (request :get "/page" "alice"
                      (cond-> {"tropical-tab" tab "tropical-load" (or load "x")}
                        revision (assoc "tropical-revision" revision)
                        cookie (assoc "cookie" cookie)))))
        ;; A tab the server doesn't know: its answer, and whether it built a
        ;; session for it.
        fresh  (fn [revision & more]
                 (let [tab   (str (random-uuid))
                       resp  (apply stream tab revision more)
                       held? (session/held? tab)]
                   (some-> (get @session/!sessions tab) session/close!)
                   [resp held?]))]
    (is (some? rev) "the page's stream carries it")
    (testing "a page of the server's revision"
      (let [tab (second (re-find (re-pattern (str "Tropical-Tab.*?(" uuid ")")) body))]
        (is (attaches? (stream tab rev)))
        (session/close! (get @session/!sessions tab)))
      (is (= rev (second (re-find #"'Tropical-Revision': '([0-9a-f]+)'" (page)))) "every page of it names it alike"))
    (testing "reloads, with no session built"
      (is (= [true false] (update (fresh nil) 0 reloads?)) "a page from before revisions")
      (is (= [true false] (update (fresh "0123456789abcdef") 0 reloads?)) "another revision")
      (reset! !named "head-2")
      (is (reloads? (first (fresh rev))) "the app names what its page loads anew")
      (reset! !named "head-1")
      (is (attaches? (first (fresh rev))) "and as before")
      (script/script* 'co.multiply.tropical.ring-test/revised {:js (str "// " (random-uuid))})
      (is (reloads? (first (fresh rev))) "a script changed")
      (let [rev (second (re-find #"'Tropical-Revision': '([0-9a-f]+)'" (page)))]
        (cookie/state* 'co.multiply.tropical.ring-test/revised {:default (str (random-uuid))})
        (is (reloads? (first (fresh rev))) "what tropical puts in the page changed, as with a cookie state declared")))
    (testing "a page of another revision just after it reloaded for that is rebuilt in place, as servers that disagree"
      (let [cookie  (guard-cookie (first (fresh "0123456789abcdef" :load "load1")))
            !logged (atom [])]
        (is (some? cookie))
        (is (reloads? (first (fresh "0123456789abcdef" :load "load1" :cookie cookie)))
          "the load told to reload, retrying before it unloads, is told again")
        (binding [log/*logger-factory* (capturing !logged)]
          (is (= [true true] (update (fresh "0123456789abcdef" :load "load2" :cookie cookie) 0 attaches?))))
        (is (some (fn [[level]] (= :warn level)) @!logged) "and logged")))
    (is (thrown? IllegalArgumentException (ring/handler (assoc app :revision "head-1"))) "a revision that isn't a fn")))


(defn- eventually
  [pred]
  (loop [n 100]
    (cond
      (pred) true
      (zero? n) false
      :else (do (Thread/sleep 10) (recur (dec n))))))


(defn- open!
  "Opens a stream for a new tab at `uri`, as the server would. Returns what was
  written so far, the task writing it, and the tab's session."
  [arity uri]
  (let [tab  (str (random-uuid))
        req  (stream-request uri "alice" tab)
        resp (case arity
               :sync (handler req)
               :async (let [p (promise)]
                        (handler req #(deliver p %) #(deliver p %))
                        (deref p 1000 nil)))
        out  (ByteArrayOutputStream.)]
    ;; What the server does with the body once the handler returns.
    {:out     out
     :written (q/task (protocols/write-body-to-stream (:body resp) resp out))
     :session (get @session/!sessions tab)}))


(deftest a-stream-stays-open-until-its-session-closes
  (testing "sync: writing the body holds the request until the stream closes"
    (let [{:keys [out written session]} (open! :sync "/page")]
      (is (eventually #(str/includes? (str out) "at /page")) "the root went out over the stream")
      (is (not (realized? written)))
      (session/close! session)
      (is (not= :timeout (deref written 1000 :timeout)))))
  (testing "async: writing the body returns, and the stream stays open"
    (let [{:keys [out written session]} (open! :async "/page")]
      (is (not= :timeout (deref written 1000 :timeout)))
      (is (eventually #(str/includes? (str out) "at /page")))
      (session/close! session)
      (is (deref (:closed session) 1000 false)))))


(deftest a-tab-the-server-does-not-know-is-rebuilt-from-its-route
  (let [{:keys [out session]} (open! :sync "/topic/beta")]
    (is (eventually #(str/includes? (str out) "at /topic/beta"))
      "the stream is requested from the page's URL, so its root is the page's")
    (session/close! session)))


(deftest a-page-is-the-first-frame-of-a-new-session
  (let [resp (handler (request :get "/topic/alpha" "alice" {}))
        tab  (second (re-find (re-pattern (str "Tropical-Tab.*?(" uuid ")")) (:body resp)))
        s    (get @session/!sessions tab)]
    (is (= 200 (:status resp)))
    (is (= "no-store" (get-in resp [:headers "Cache-Control"])) "a restored tab fetches a tab of its own")
    (is (str/includes? (:body resp) (str "<p id=\"" (island/element-id "at") "\">at /topic/alpha</p>"))
      "the root gets the request")
    (is (= "alice" (:uid s)))
    (is (= 403 (:status (handler (request :get "/topic/alpha" nil {})))) "no user")
    (session/close! s)
    (is (deref (:closed s) 1000 false))))


(deftest a-stream-is-an-sse-response
  (let [respond (fn [protocol]
                  (let [tab (str (random-uuid))]
                    (try
                      (:headers (handler (assoc (stream-request "/page" "alice" tab) :protocol protocol)))
                      (finally
                        (some-> (get @session/!sessions tab) session/close!)))))]
    (is (= {"Content-Type" "text/event-stream" "Cache-Control" "no-cache" "Connection" "keep-alive"}
           (respond "HTTP/1.1")))
    (is (= {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"} (respond "HTTP/2"))
      "no connection-specific header on HTTP/2")))


(deftest a-stream-is-compressed-as-its-request-accepts
  (let [compressing (ring/handler (assoc app :compression [:zstd :gzip]))
        open        (fn [accept]
                      (let [tab  (str (random-uuid))
                            resp (compressing (cond-> (stream-request "/page" "alice" tab)
                                                accept (assoc-in [:headers "accept-encoding"] accept)))
                            out  (ByteArrayOutputStream.)
                            done (q/task (protocols/write-body-to-stream (:body resp) resp out))
                            s    (get @session/!sessions tab)]
                        ;; Past a header gzip writes as it opens: the root went out.
                        (eventually #(> (.size out) 32))
                        (session/close! s)
                        @done
                        {:headers (:headers resp) :body (.toByteArray out)}))
        decoded     (fn [encoding ^bytes body]
                      (let [in (ByteArrayInputStream. body)]
                        (slurp (case encoding
                                 "zstd" (ZstdInputStreamNoFinalizer. in)
                                 "gzip" (GZIPInputStream. in)
                                 in))))]
    (doseq [[accept encoding] [["gzip, deflate, br, zstd" "zstd"] ["gzip, deflate" "gzip"] [nil nil]]]
      (testing (str "Accept-Encoding: " accept)
        (let [{:keys [headers body]} (open accept)]
          (is (= encoding (get headers "Content-Encoding")))
          (is (= "Accept-Encoding" (get headers "Vary")) "a response that could be compressed varies by it")
          (is (str/includes? (decoded encoding body) "at /page") "the root, decoded"))))
    (is (thrown? Exception (ring/handler (assoc app :compression [:deflate])))
      "an encoding it can't load is refused at once, not at the first stream")))


(deftest a-frame-reaches-the-client-in-one-flush
  (let [h       (ring/handler (assoc app :patch-signals (fn [_] {"_wire" {"frames" 1}})))
        tab     (str (random-uuid))
        resp    (h (stream-request "/page" "alice" tab))
        out     (ByteArrayOutputStream.)
        !writes (atom [])
        raw     (proxy [java.io.OutputStream] []
                  (write
                    ([b] (if (bytes? b) (.write out ^bytes b) (.write out (int b))))
                    ([b off len] (.write out ^bytes b (int off) (int len))))
                  ;; What was written since the last flush, as the client receives it.
                  (flush [] (swap! !writes conj (String. (.toByteArray out) "UTF-8")) (.reset out)))
        done    (q/task (protocols/write-body-to-stream (:body resp) resp raw))]
    (try
      (is (eventually #(some (fn [w] (str/includes? w "at /page")) @!writes)))
      (let [frame (first (filter #(str/includes? % "at /page") @!writes))]
        (is (str/includes? frame "datastar-patch-signals") "its signals came with it"))
      (finally
        (session/close! (get @session/!sessions tab))
        @done))))


(defn- mounting
  "An app whose page mounts it with `ring/mount`, as `(mount-args)` say, and
  whose root is `root`, a fn of the session's context."
  [root & mount-args]
  {:uid  #(get-in % [:headers "x-uid"])
   :root root
   :page (fn [_] (h/html (apply ring/mount mount-args)))})


(defn- page-of
  "The body of the page `app` serves at `/mounted`, closing its session."
  [app]
  (let [body (:body ((ring/handler app) (request :get "/mounted" "alice" {})))]
    (some->> (re-find (re-pattern (str "Tropical-Tab.*?(" uuid ")")) (str body)) second (get @session/!sessions)
      session/close!)
    body))


(deftest a-page-mounts-the-app-in-an-element-of-its-own
  (let [!roots (atom 0)
        app    (-> (mounting (fn [{:keys [request]}] (swap! !roots inc) (at (:uri request)))
                     :body {:class [:page]} [:div {:id "dialogs"}])
                 (assoc :open-when-hidden true))
        body   (:body ((ring/handler app) (request :get "/mounted" "alice" {})))
        tab    (second (re-find (re-pattern (str "Tropical-Tab.*?(" uuid ")")) body))
        root   (str "<p id=\"" (island/element-id "at") "\">at /mounted</p>")]
    (is (str/starts-with? body "<body class=\"page\"") "the element, with the app's attributes")
    (is (str/includes? body "data-init=") "it opens the stream")
    (is (some? tab) "for the page's tab")
    (is (str/includes? body "openWhenHidden: true") "kept open while hidden, as the app says")
    (is (str/includes? body "data-on-signal-patch=") "and keeps the state the browser owns")
    (is (str/includes? body root) "it holds the first frame")
    (is (< (str/index-of body root) (str/index-of body "id=\"dialogs\"")) "and the children after it")
    (is (= 1 @!roots) "the root is asked for once, by the session")
    (some-> (get @session/!sessions tab) session/close!)))


(deftest without-a-first-frame-the-root-goes-in-a-placeholder
  ;; The session's root throws, so the session closes with no frame; asked
  ;; again, for the placeholder, it answers.
  (let [!roots (atom 0)
        app    (mounting (fn [_] (if (= 1 (swap! !roots inc)) (throw (ex-info "Not yet." {})) (hello))) :main)
        body   (:body ((ring/handler app) (request :get "/mounted" "alice" {})))]
    (is (str/includes? body (str "<div id=\"" (island/element-id "hello") "\"></div>"))
      "an element with the root island's id, which a fresh session's root is patched into")))


(deftest mount-is-for-the-page-only
  (is (thrown? IllegalStateException (ring/mount :body)) "outside the app's :page")
  (is (thrown? IllegalStateException (ring/scripts nil)) "as are its scripts")
  (is (thrown? IllegalArgumentException
        ((ring/handler (mounting (fn [_] (hello)) :body {:data-init "x"})) (request :get "/mounted" "alice" {})))
    "the stream needs the element's data-init")
  (is (thrown? IllegalStateException
        (page-of (assoc (mounting (fn [_] (hello)))
                   :page (fn [_] (h/html [:div (ring/mount :main) (ring/mount :aside)])))))
    "once per page: a second would open a second stream for the tab"))


(deftest a-page-that-opens-no-stream-fails
  (let [uid     (str (random-uuid))
        handler (ring/handler (assoc (mounting (fn [_] (hello))) :page (fn [{:keys [frame]}] (h/html [:body frame]))))]
    (is (thrown-with-msg? IllegalStateException #"stream" (handler (request :get "/mounted" uid {})))
      "it would render, and never update")
    (is (eventually #(empty? (filter (fn [s] (= uid (:uid s))) (vals @session/!sessions)))) "and its session closes")))


(deftest a-page-without-page-attrs-is-logged-once
  (let [!logged (atom [])
        app     (assoc (mounting (fn [_] (hello)))
                  :page (fn [{:keys [tab frame]}] (h/html [:body {:data-init (ring/stream-init tab)} frame])))
        handler (ring/handler app)
        warned  #(count (filter (fn [[level message]] (and (= :warn level) (str/includes? message "page-attrs"))) @!logged))]
    (binding [log/*logger-factory* (capturing !logged)]
      (dotimes [_ 2]
        (let [body (:body (handler (request :get "/mounted" "alice" {})))]
          (is (str/includes? body "hello") "the page serves")
          (some->> (re-find (re-pattern (str "Tropical-Tab.*?(" uuid ")")) body) second (get @session/!sessions)
            session/close!))))
    (is (= 1 (warned)) "warned of, once per handler")
    (binding [log/*logger-factory* (capturing !logged)]
      (page-of (mounting (fn [_] (hello)) :body)))
    (is (= 1 (warned)) "never with ring/mount")))


(defisland scripted
  []
  (island/declare-script "/tropical/chart.abc.js")
  [:p "chart"])


(deftest the-head-takes-the-first-frame's-scripts
  (let [body (page-of (assoc (mounting (fn [_] (scripted)))
                        :page (fn [_] (h/html [:html [:head (ring/scripts "n0nce")] (ring/mount :body)]))))]
    (is (str/includes? body "<script type=\"module\" src=\"/tropical/chart.abc.js\" nonce=\"n0nce\"></script>")
      "each with the page's nonce")))


(defisland where
  []
  [:p "on " (-> (island/use-session) :request :uri)])


(defisland framed
  []
  [:main (where)])


(deftest an-island-reads-the-request-that-created-its-session
  (let [handler (ring/handler {:uid  #(get-in % [:headers "x-uid"])
                               :root (fn [_] (framed))
                               :page (fn [{:keys [tab frame]}] (str (ring/stream-init tab) (some-> frame island/html)))})]
    (testing "the page's"
      (let [body (:body (handler (request :get "/topic/alpha" "alice" {})))
            tab  (second (re-find (re-pattern (str "Tropical-Tab.*?(" uuid ")")) body))]
        (is (str/includes? body "on /topic/alpha") "an island below the root has it")
        (some-> (get @session/!sessions tab) session/close!)))
    (testing "a rebuilt tab's, its stream's, to the same page"
      (let [tab  (str (random-uuid))
            req  (stream-request "/topic/beta" "alice" tab)
            resp (handler req)
            out  (ByteArrayOutputStream.)]
        (q/task (protocols/write-body-to-stream (:body resp) resp out))
        (is (eventually #(str/includes? (str out) "on /topic/beta")))
        (session/close! (get @session/!sessions tab))))))


(def ^:private !ticks (atom 0))


(defisland ticking-chart
  []
  (island/declare-script "/tropical/chart.abc.js")
  [:p "tick " (island/use-watch !ticks)])


(deftest the-stream-sends-the-first-frame's-scripts-only-to-a-page-without-them
  (doseq [[head? page] [[true (fn [_] (h/html [:html [:head (ring/scripts nil)] (ring/mount :body)]))]
                        [false (fn [_] (h/html [:html [:head] (ring/mount :body)]))]]]
    (testing (if head? "a page with them in its head" "a page that left them out")
      (let [handler (ring/handler (assoc (mounting (fn [_] (ticking-chart))) :page page))
            body    (:body (handler (request :get "/mounted" "alice" {})))
            tab     (second (re-find (re-pattern (str "Tropical-Tab.*?(" uuid ")")) body))
            resp    (handler (stream-request "/mounted" "alice" tab))
            out     (ByteArrayOutputStream.)
            tick    (swap! !ticks inc)]
        (q/task (protocols/write-body-to-stream (:body resp) resp out))
        ;; A change after attaching, so the stream has sent what it would by then.
        (Thread/sleep 100)
        (swap! !ticks inc)
        (is (eventually #(str/includes? (str out) (str "tick " (inc tick)))) "the stream is live")
        (is (= (not head?) (str/includes? (str out) "chart.abc.js")))
        (session/close! (get @session/!sessions tab))))))
