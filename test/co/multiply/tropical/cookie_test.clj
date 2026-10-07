(ns co.multiply.tropical.cookie-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.scoped :refer [scoping]]
    [co.multiply.tropical.action :as action]
    [co.multiply.tropical.cookie :as cookie]
    [co.multiply.tropical.island :as island :refer [defisland]]
    [co.multiply.tropical.ring :as ring]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.session :as session]
    [co.multiply.tropical.test-util :refer [harness]]
    [co.multiply.tropical.ui :as ui]
    [s-exp.oda :as json])
  (:import
    [java.net URLEncoder]
    [java.nio.charset StandardCharsets]))


(cookie/defcookie panel-open
  "Whether the panel is open."
  {:default true :valid? boolean?})


(cookie/defcookie group-open
  {:default true :valid? boolean?})


(defisland panel
  []
  (let [open (cookie/use-cookie panel-open)]
    [:section
     [:button (ui/aria-expanded {:data-on:click (ui/toggle open)} open) "Panel"]
     [:div (ui/shown open) "body"]]))


(defisland group
  {:key identity}
  [id]
  (let [open (cookie/use-cookie group-open id)]
    [:li [:ul (ui/shown open) "group " id]]))


(defisland groups
  []
  [:ul (map group [1 2])])


(defn- declared
  "The signals the root of `html` declares, if missing."
  [html]
  (some-> (re-find #"^<[a-z]+ [^>]*data-signals__ifmissing=\"([^\"]*)\"" html)
    second
    (str/replace "&quot;" "\"")
    json/parse))


(defn- render
  "The HTML of the island `root-fn` returns, with the browser's state `kept`
  (a map of the cookie's keys to values), as a session's request would carry it."
  [root-fn kept]
  (let [{:keys [step rt]} (harness root-fn)]
    (try
      (scoping [cookie/*cookie* (delay kept)]
        (island/html (step)))
      (finally
        (runtime/dispose! rt)))))


(def ^:private panel-signal (:signal panel-open))
(def ^:private group-signal (:signal group-open))


(deftest a-state-is-declared-by-the-island-that-uses-it
  (let [html (render panel nil)]
    (is (= {"_cookie" {panel-signal true}} (declared html)) "on its root, as its default")
    (is (str/includes? html (str "$_cookie." panel-signal)) "and bound to its signal")
    (is (not (re-find #"<div[^>]* hidden[ >]" html)))))


(deftest the-first-frame-has-the-state-the-browser-keeps
  (let [html (render panel {panel-signal false})]
    (is (= {"_cookie" {panel-signal false}} (declared html)))
    (is (re-find #"<div[^>]* hidden[ >]" html) "hidden for the first paint")
    (is (str/includes? html "aria-expanded=\"false\""))))


(deftest what-the-cookie-holds-is-validated
  (is (= {"_cookie" {panel-signal true}} (declared (render panel {panel-signal "no"}))) "an invalid value")
  (is (= {"_cookie" {panel-signal true}} (declared (render panel {"other" false}))) "another key")
  (testing "the cookie itself"
    (let [header #(str "a=1; " cookie/cookie-name "=" % "; b=2")
          encode #(URLEncoder/encode ^String % StandardCharsets/UTF_8)]
      (is (= {"x" true "y/1" 2} (cookie/parse-cookie (header (encode "{\"x\":true,\"y/1\":2}")))))
      (is (nil? (cookie/parse-cookie (header (encode "[1,2]")))) "not an object")
      (is (nil? (cookie/parse-cookie (header "%7Bnope"))) "not JSON")
      (is (nil? (cookie/parse-cookie "a=1")) "none")
      (is (nil? (cookie/parse-cookie nil))))))


(deftest a-state-per-item
  (let [html (render groups {(str group-signal "/2") false})]
    (is (= [{"_cookie" {group-signal {"1" true}}} {"_cookie" {group-signal {"2" false}}}]
           (map declared (re-seq #"<li [^>]*>" html)))
      "each item's island declares its own")
    (is (str/includes? html (str "$_cookie." group-signal "[&quot;2&quot;]")) "bound by its item")
    (is (re-find #"<ul[^>]* hidden[ >][^>]*>group 2" html) "item 2 as the browser keeps it")))


(cookie/defcookie either-way
  {:default true :valid? boolean?})


(defisland one-way
  [item]
  [:p (cookie/value (cookie/use-cookie either-way item))])


(deftest a-state-is-used-one-way
  (is (str/includes? (render #(one-way "a") nil) "true"))
  (is (str/includes? (render #(one-way nil) nil) "failed to render") "with an item, then without"))


(deftest defcookie-refuses-what-it-cant-keep
  (is (thrown? IllegalArgumentException (cookie/state* 'x.y/z {:default {:a 1}})) "a default that isn't a scalar")
  (is (thrown? IllegalArgumentException (cookie/state* 'x.y/z {:default 1 :valid? string?})) "an invalid default")
  (cookie/state* 'x.y/z {:default true})
  (is (some? (cookie/state* 'x.y/z {:default false})) "the same state again, as on a reload")
  (is (thrown? IllegalArgumentException (cookie/state* 'x-y/z {:default true})) "another with the same signal"))


(defisland claims-the-attribute
  []
  (cookie/use-cookie panel-open)
  [:div {:data-signals__ifmissing "{}"}])


(deftest the-root-s-declaration-is-the-runtime-s
  (is (str/includes? (render claims-the-attribute nil) "failed to render")))


(cookie/defcookie label
  {:default "none"})


(deftest an-action-sets-states-with-patch
  (is (= {:_cookie {group-signal {"1" false "2" true} panel-signal false}}
         (cookie/patch [[group-open 1 false] [group-open 2 true] [panel-open false]]))
    "signals under `_cookie`, as islands using the states declare them")
  (is (= {:_cookie {}} (cookie/patch [])))
  (testing "a value is checked as the cookie's are, when the handler builds its result"
    (is (thrown? IllegalArgumentException (cookie/patch [[panel-open "no"]])) "invalid for its state")
    (is (thrown? IllegalArgumentException (cookie/patch [[label {:a 1}]])) "not a boolean, a number or a string")
    (is (thrown? IllegalArgumentException (cookie/patch [[label nil]])))
    (is (= {:_cookie {(:signal label) "x"}} (cookie/patch [[label "x"]]))))
  (testing "a state is set as it is used, with an item or without"
    (is (thrown? IllegalArgumentException (cookie/patch [[group-open false]])))
    (is (thrown? IllegalArgumentException (cookie/patch [[panel-open 1 false]]))))
  (doseq [e [[] [panel-open] ["panel" true] [panel-open 1 true :x] {:state panel-open} nil]]
    (is (thrown? IllegalArgumentException (cookie/patch [e])) (pr-str e))))


(deftest a-page-an-action-opens-starts-with-the-states-it-set
  (let [nav (action/navigate "/groups/3" {:signals (cookie/patch [[group-open 3 false]])})]
    (is (= {:_cookie {group-signal {"3" false}}} (action/navigation-signals nav))
      "the page takes them, and its body's handler writes them into the cookie, before it opens the next")
    (is (= (action/navigation (action/navigate "/groups/3")) (action/navigation nav)))))


(deftest a-page-is-rendered-with-the-state-its-request-carries
  (let [handler (ring/handler {:uid  (constantly "alice")
                               :root (fn [_] (panel))
                               ;; The frame, then the stream every page opens.
                               :page (fn [{:keys [tab frame]}] (str (some-> frame island/html) (ring/stream-init tab)))})
        cookie  (str cookie/cookie-name "=" (URLEncoder/encode (json/write-str {panel-signal false}) StandardCharsets/UTF_8))
        resp    (handler {:request-method :get :uri "/p" :headers {"cookie" cookie}})]
    (is (= {"_cookie" {panel-signal false}} (declared (:body resp))))
    (is (re-find #"<div[^>]* hidden[ >]" (:body resp)))
    (run! session/close! (filter #(= "alice" (:uid %)) (vals @session/!sessions)))))


(deftest an-item-is-named-without-a-dot
  (is (thrown? IllegalArgumentException (cookie/patch [[group-open "v1.2" false]]))
    "Datastar would read it as a step into the signal")
  (is (str/includes? (render #(group "a.b") nil) "failed to render")))
