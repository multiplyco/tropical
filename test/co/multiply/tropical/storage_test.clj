(ns co.multiply.tropical.storage-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.action :as action]
    [co.multiply.tropical.island :as island :refer [defisland]]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.storage :as storage]
    [co.multiply.tropical.test-util :refer [datastar-statement harness]]
    [co.multiply.tropical.ui :as ui]))


(storage/defstorage edits
  "Edits to a suggestion."
  {:default {} :valid? map?})


(storage/defstorage note
  {:default "" :valid? string?})


(def ^:private edits-signal (:signal edits))


(defn- declaration
  "The expression the root of `html` declares its signals with, as the browser
  reads it."
  [html]
  (some-> (re-find #"^<[a-z]+ [^>]*data-signals__ifmissing=\"([^\"]*)\"" html)
    second
    (str/replace "&quot;" "\"")
    (str/replace "&amp;" "&")
    (str/replace "&gt;" ">")
    (str/replace "&lt;" "<")))


(defisland rework
  [id text]
  (let [draft (storage/use-storage edits id {:default {"text" text}})]
    [:form [:textarea {:data-bind (ui/path draft "text")}] (ui/signal draft)]))


(deftest an-entry-is-declared-from-the-browser-s-storage
  (let [{:keys [step rt]} (harness #(rework "42" "Ship it; then tell them \\"))]
    (try
      (let [html (island/html (step))
            decl (declaration html)]
        (is (str/includes? decl (str "{\"_storage\":{\"" edits-signal "\":{\"42\":(")) "under its item, by an expression")
        (is (str/includes? decl "localStorage.getItem") "which reads the browser's entry")
        (is (str/includes? decl (storage/owner "user")) "the session's user's")
        (is (not (str/includes? decl "\"user\"")) "by a digest, not the user's id")
        (is (str/includes? decl "{\"text\":\"Ship it; then tell them \\u005c\"}") "or takes the island's default")
        (is (= 1 (count (re-seq datastar-statement decl))) "one statement to Datastar, whatever the default holds")
        (is (str/includes? html (str "data-bind=\"_storage." edits-signal ".42.text\"")) "bound by its path")
        (is (str/includes? html (str "$_storage." edits-signal "[\"42\"]")) "and named in expressions"))
      (finally
        (runtime/dispose! rt)))))


(deftest defstorage-refuses-what-the-browser-cant-keep
  (is (thrown? IllegalArgumentException (storage/state* 'x.y/z {})) "no default")
  (is (thrown? IllegalArgumentException (storage/state* 'x.y/z {:default {"a" nil}})) "nil, which deletes a signal")
  (is (thrown? IllegalArgumentException (storage/state* 'x.y/z {:default {"a.b" 1}})) "a key Datastar splits")
  (is (thrown? IllegalArgumentException (storage/state* 'x.y/z {:default 1 :valid? string?})) "an invalid default")
  (is (thrown? IllegalArgumentException (storage/state* 'x.y/z {:default {} :version 1.5})) "a version")
  (testing "a default that doesn't come back from the browser as it went"
    (is (thrown? IllegalArgumentException (storage/state* 'x.y/z {:default {:a #{1}}})))
    (is (some? (storage/state* 'x.y/z {:default {:a #{1}}
                                       :decode  (fn [m] (update-vals (update-keys m keyword) set))}))))
  (is (some? (storage/state* 'x.y/z {:default {}})) "the same state again, as on a reload")
  (is (thrown? IllegalArgumentException (storage/state* 'x-y/z {:default {}})) "another with the same signal"))


(deftest an-action-sets-and-drops-entries
  (is (= {:_storage {edits-signal {"1" {"text" "x"}} (:signal note) {"_" "hi"}}}
         (storage/patch [[edits 1 {"text" "x"}] [note "hi"]])))
  (is (= {:_storageForget {edits-signal {"1" true "2" true}}}
         (storage/forget [[edits 1] [edits 2]])))
  (testing "a value is checked as the handler builds its result"
    (is (thrown? IllegalArgumentException (storage/patch [[edits 1 "no"]])) "invalid for its state")
    (is (thrown? IllegalArgumentException (storage/patch [[edits 1 {"text" nil}]])) "not one the browser keeps"))
  (testing "a state is used one way, with an item or without"
    (is (thrown? IllegalArgumentException (storage/patch [[edits "x"]])))
    (is (thrown? IllegalArgumentException (storage/forget [[note 1]]))))
  (doseq [e [[] ["edits" 1] [edits 1 2 3] nil]]
    (is (thrown? IllegalArgumentException (storage/forget [e])) (pr-str e))))


(deftest a-value-from-an-action-is-decoded-and-checked
  (let [tagged (storage/state* 'x.y/tagged {:default #{} :valid? set? :encode vec :decode set})]
    (is (= #{"a" "b"} (storage/decode tagged ["a" "b"])))
    (is (nil? (storage/decode tagged 1)) "untrusted: decoding fails")
    (is (nil? (storage/decode edits "a")) "untrusted: not valid")
    (is (nil? (storage/decode edits nil)))))


(defn- watch-token
  "The token the root of `html` asks the browser to send the entry to."
  [html]
  (second (re-find #"&quot;_storageWatch&quot;:\{&quot;([^&]+)&quot;" html)))


(deftest an-observed-boolean-keeps-false
  (let [state (storage/state* 'storage-test/enabled {:default true :valid? boolean?})
        reader (island/island-fn "boolean-reader" nil
                 (fn [] [:p (str (storage/observe (storage/use-storage state)))]))
        {:keys [step rt]} (harness reader)]
    (try
      (let [token (watch-token (island/html (step)))]
        (action/invoke! token "user" {"value" false})
        (is (re-find #">false<" (island/html (step))))
        (action/invoke! token "user" {"value" "invalid"})
        (is (re-find #">true<" (island/html (step))) "invalid values still use the default"))
      (finally
        (runtime/dispose! rt)))))


(defisland counted
  [id reads]
  (let [draft (storage/use-storage edits id {:default {"text" "start"}})
        vs    (repeatedly reads #(storage/observe draft))]
    [:p (if (= storage/pending (first vs)) "…" (str (get (first vs) "text") "/" (count (set vs))))]))


(defisland holder
  [reads]
  [:div (when (pos? reads) (counted "42" reads))])


(deftest the-server-reads-the-browser-s-value
  (let [!reads (atom 1)
        {:keys [step rt]} (harness #(holder @!reads))]
    (try
      (let [html  (island/html (step))
            token (watch-token html)
            send! #(action/invoke! token "user" {"value" %})]
        (is (str/includes? html "…") "pending until the browser sends it")
        (is (not (runtime/pending? rt)) "which the first frame doesn't wait for: the browser can't answer before it")
        (is (some? token) "the island asks the browser for it")
        (is (= [:forbidden] (action/invoke! token "someone else" {"value" {}})) "the user's own")
        (send! {"text" "edited"})
        (is (str/includes? (island/html (step)) "edited/1"))
        (send! "not a map")
        (is (str/includes? (island/html (step)) "start/1") "an invalid value reads as the default")
        (send! nil)
        (is (str/includes? (island/html (step)) "start/1") "as does none")
        (send! {"text" "again"})
        (reset! !reads 2)
        (let [html (island/html (step))]
          (is (str/includes? html "again/1") "two reads in one render share one")
          (is (= token (watch-token html))))
        (reset! !reads 0)
        (step)
        (is (= [:gone] (send! {"text" "late"})) "revoked once nothing reads it"))
      (finally
        (runtime/dispose! rt)))))
