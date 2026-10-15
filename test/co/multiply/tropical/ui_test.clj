(ns co.multiply.tropical.ui-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.scoped :refer [scoping]]
    [co.multiply.tropical.cookie :as cookie]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.storage :as storage]
    [co.multiply.tropical.test-util :refer [datastar-statement harness]]
    [co.multiply.tropical.ui :as ui]
    [co.multiply.tropical.upload :as upload]
    [dev.onionpancakes.chassis.core :as h]
    [s-exp.oda :as json]))


(cookie/defcookie panel-open
  {:default true :valid? boolean?})


(def ^:private panel-signal (:signal panel-open))


(defn- preserved
  [attrs]
  (set (str/split (:data-preserve-attr attrs) #" ")))


(deftest an-attribute-follows-an-expression-from-the-first-paint
  (testing "a boolean attribute: present while true, the expression as it is"
    (let [attrs (ui/attr :disabled true "$draft === $saved")]
      (is (true? (:disabled attrs)))
      (is (= "$draft === $saved" (:data-attr:disabled attrs)))
      (is (= #{"disabled"} (preserved attrs))))
    (is (not (str/includes? (h/html [:button (ui/attr :disabled false "$x")]) " disabled"))))
  (testing "an aria state: the words true and false, from the server and the expression alike"
    (let [on  (ui/attr :aria-selected true "$tab === 1")
          off (ui/attr :aria-selected false "$tab === 1")]
      (is (= "true" (:aria-selected on)))
      (is (= "false" (:aria-selected off)))
      (is (str/includes? (:data-attr:aria-selected on) "$tab === 1"))
      (is (not= "$tab === 1" (:data-attr:aria-selected on)) "the browser's booleans are turned into words too")))
  (testing "other values are as they are, and nil leaves the attribute out"
    (is (= "page" (:aria-current (ui/attr :aria-current "page" "$page"))))
    (is (= 0 (:tabindex (ui/attr :tabindex 0 "$tab === 1 ? 0 : -1"))))
    (is (not (str/includes? (h/html [:a (ui/attr :aria-current nil "$page")]) " aria-current="))))
  (testing "threaded, every attribute is preserved, the element's own too"
    (let [attrs (-> {:class "tab" :data-preserve-attr "open"}
                  (ui/attr :aria-selected true "$tab === 1")
                  (ui/attr :tabindex 0 "$tab === 1 ? 0 : -1"))]
      (is (= "tab" (:class attrs)))
      (is (= #{"open" "aria-selected" "tabindex"} (preserved attrs)))))
  (testing "the helpers for a state's handle are built on it, and thread too"
    (let [{:keys [step rt]} (harness (island/island-fn "probe" nil
                                       (fn []
                                         (let [open (cookie/use-cookie panel-open)]
                                           [:div (-> {:class "x"} (ui/shown open) (ui/aria-expanded open))]))))]
      (try
        (let [html (island/html (step))]
          (is (str/includes? html "class=\"x\""))
          (is (str/includes? html "aria-expanded=\"true\""))
          (is (re-find #"data-preserve-attr=\"hidden aria-expanded\"" html)))
        (finally
          (runtime/dispose! rt))))))


(deftest a-path-names-a-signal-for-data-bind
  (let [handle {:path ["_storage" "app__edits" "42"]}]
    (is (= "_storage.app__edits.42" (ui/path handle)))
    (is (= "_storage.app__edits.42.sections.0" (ui/path handle :sections 0)))
    (is (thrown? IllegalArgumentException (ui/path handle "a.b")) "a step Datastar would split")))


(deftest page-attrs-keep-the-state-the-browser-owns
  (let [owned #(scoping [storage/*owner* (storage/owner "alice")] (ui/page-attrs %))
        {:keys [data-on-signal-patch data-on-signal-patch-filter data-effect] :as attrs} (owned {:data-init "x"})]
    (is (= "x" (:data-init attrs)) "added to the body's own")
    (let [include (re-pattern (second (re-find #"/(.*)/" data-on-signal-patch-filter)))]
      (doseq [root ["_cookie" "_storage" "_storageForget" "_storageWatch"]]
        (is (re-find include (str root ".x.y")) root))
      (is (not (re-find include "_wire.frames")) "and only those"))
    (is (str/includes? data-on-signal-patch (str "\"" panel-signal "\":true")) "with each cookie state's default")
    (is (str/includes? data-on-signal-patch (storage/owner "alice")) "and the user whose entries it keeps")
    (is (str/includes? data-effect (storage/owner "alice")))
    (is (not= (owned {}) (scoping [storage/*owner* (storage/owner "bob")] (ui/page-attrs))) "per user")
    (is (str/starts-with? data-on-signal-patch "void ") "an expression, not a function")
    (is (= ["a = 1" " b = 'x;y'" " (() => {c; d})()"]
           (re-seq datastar-statement "a = 1; b = 'x;y'; (() => {c; d})()"))
      "the port")
    (is (= 1 (count (re-seq datastar-statement (str/trim data-on-signal-patch))))
      "one statement to Datastar, which would otherwise return its last")
    (is (= 1 (count (re-seq datastar-statement (str/trim data-effect))))
      "the pruning program also stays one statement")
    (is (thrown? IllegalArgumentException (owned {:data-effect "mine"})) "an attribute of the body's own it would replace")
    (is (= (upload/drop-guard) (select-keys attrs (keys (upload/drop-guard))))
      "files dropped outside a drop area refused, on every page")
    (is (thrown? IllegalArgumentException (owned {:data-on:drop__window "mine"})))
    (is (thrown? IllegalStateException (ui/page-attrs)) "outside a page ring renders, for no user")))


(deftest a-value-goes-into-an-expression-as-json
  (let [v {:tab :files "path" "C:\\dir\\" "note" "it's \"quoted\"; with a semicolon" :n [1 2.5 nil true]}]
    (is (= {"tab" "files" "path" "C:\\dir\\" "note" "it's \"quoted\"; with a semicolon" "n" [1 2.5 nil true]}
           (json/parse (ui/json v)))
      "it reads back as the value, keywords as strings")
    (let [a (str "a = " (ui/json "C:\\dir\\"))
          b (str " b = " (ui/json "x;y"))]
      (is (= [a b] (re-seq datastar-statement (str a ";" b)))
        "a backslash doesn't make Datastar split a later string"))
    (is (= 1 (count (re-seq datastar-statement (str "$note = " (ui/json (get v "note"))))))
      "a quote or a semicolon in a value stays in it")))
