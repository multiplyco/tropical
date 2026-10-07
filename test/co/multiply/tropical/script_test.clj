(ns co.multiply.tropical.script-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.island :refer [defisland]]
    [co.multiply.tropical.ring :as ring]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.script :as script :refer [defscript]]
    [co.multiply.tropical.session :as session]
    [co.multiply.tropical.test-util :refer [harness]])
  (:import
    [java.io File]))


(defscript widget
  "A component."
  {:js "customElements.define('x-widget', class extends HTMLElement {});"})


(defscript helper
  {:js "globalThis.helper = () => 1;"})


(defisland widget-view
  {:key identity}
  [n]
  (script/use widget)
  [:li [:x-widget n]])


(defisland page
  [n]
  [:ul (map widget-view (range n))])


(deftest a-script-is-served-at-a-url-of-its-content
  (let [url     (script/url widget)
        version #(re-find #"\.[0-9a-f]+\.js$" (script/url %))]
    (is (str/starts-with? url script/path))
    (is (not= url (script/url helper)))
    (is (= (version (script/script* 'x.content/a {:js "1"})) (version (script/script* 'x.content/b {:js "1"})))
      "the same content, the same version")
    (is (not= (version (script/script* 'x.content/a {:js "1"})) (version (script/script* 'x.content/c {:js "2"}))))))


(deftest islands-declare-the-scripts-they-use
  (let [{:keys [step rt]} (harness #(page 3))]
    (step)
    (is (= [(script/url widget)] (runtime/scripts rt)) "once, however many islands use it")
    (runtime/dispose! rt)))


(deftest defscript-refuses-what-it-cant-serve
  (is (thrown? IllegalArgumentException (script/script* 'x.y/z {})) "no content")
  (is (thrown? IllegalArgumentException (script/script* 'x.y/z {:js "1" :resource "a.js"})) "two")
  (is (thrown? IllegalArgumentException (script/script* 'x.y/z {:resource "no/such/script.js"})) "a resource that isn't there")
  (is (thrown? IllegalArgumentException (script/script* 'x.y/z {:js 1})))
  (script/script* 'x.y/z {:js "1"})
  (is (some? (script/script* 'x.y/z {:js "2"})) "the same script again, as on a reload")
  (is (thrown? IllegalArgumentException (script/script* 'x/y.z {:js "1"})) "another with the same URL"))


(deftest a-script-from-a-file-follows-the-file
  (let [f      (doto (File/createTempFile "script" ".js") (.deleteOnExit))
        _      (spit f "export const v = 1;")
        script (script/script* `from-file {:js ""})
        script (assoc script :file f :!version (atom {:js "" :url "" :modified 0 :checked 0}))]
    (with-redefs [script/check-ms 0]
      (let [one (script/url script)]
        (is (= one (script/url script)) "unchanged")
        (spit f "export const v = 2;")
        (.setLastModified f (+ (.lastModified f) 2000))
        (is (not= one (script/url script)) "changed")))))


(defn- serve
  [uri]
  ((ring/wrap-scripts (constantly {:status 404})) {:request-method :get :uri uri}))


(deftest wrap-scripts-serves-them
  (let [url  (script/url widget)
        resp (serve url)]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/javascript"))
    (is (str/includes? (get-in resp [:headers "Cache-Control"]) "immutable") "kept for good")
    (is (str/includes? (:body resp) "x-widget")))
  (testing "another version of it, asked for by a page from before it changed"
    (let [resp (serve (str/replace (script/url widget) #"\.[0-9a-f]+\.js$" ".0123456789abcdef.js"))]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "x-widget") "gets the current one")
      (is (= "no-cache" (get-in resp [:headers "Cache-Control"])))))
  (testing "what isn't a script passes"
    (is (= 404 (:status (serve (str script/path "no.such.script.0123.js")))))
    (is (= 404 (:status (serve "/elsewhere.js"))))
    (is (= 404 (:status ((ring/wrap-scripts (constantly {:status 404}))
                         {:request-method :post :uri (script/url widget)}))))))


(deftest the-page-loads-them-as-modules
  (is (= [[:script {:type "module" :src "/a.js" :nonce "n"}]] (script/tags ["/a.js"] "n")))
  (is (= [[:script {:type "module" :src "/a.js"}]] (script/tags ["/a.js"] nil)) "without a CSP"))


(deftest the-page-gets-the-scripts-of-its-first-frame
  (let [uid     (str (random-uuid))
        handler (ring/handler {:uid  (constantly uid)
                               :root (fn [_] (page 2))
                               ;; The scripts, then the stream every page opens.
                               :page (fn [{:keys [tab scripts]}] (str (pr-str scripts) " " (ring/stream-init tab)))})]
    (is (str/starts-with? (:body (handler {:request-method :get :uri "/p" :headers {}})) (pr-str [(script/url widget)])))
    (run! session/close! (filter #(= uid (:uid %)) (vals @session/!sessions)))))
