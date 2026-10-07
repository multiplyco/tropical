(ns co.multiply.tropical.names-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.cookie :as cookie]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.names :as names]))


(deftest the-browser-sees-a-digest-of-the-app-s-names
  (with-redefs [names/readable? false]
    (let [id "app/billing-card/invoice-row.42"]
      (is (re-matches #"t[0-9a-f]{16}" (island/element-id id)) "a letter, then hex: a selector, a path and a URL take it")
      (is (= "tffdfcf8682cac656" (island/element-id id))
        "the same on every server and in every version: a reconnect morphs in place, and kept states are found")
      (is (not= (island/element-id id) (island/element-id "app/billing-card/invoice-row.43")))
      (is (not (str/includes? (cookie/signal-name 'acme.billing.internal/debug-open) "billing"))
        "nothing of the namespace or the var")
      (is (re-matches #"t[0-9a-f]{10}" (cookie/signal-name 'acme.billing.internal/debug-open))))))


(deftest names-are-as-written-while-readable
  (with-redefs [names/readable? true]
    (testing "for debugging, with the JVM property set"
      (is (= "app/billing-card/invoice-row.42" (island/element-id "app/billing-card/invoice-row.42")))
      (is (= "acme_billing_internal__debug_open" (cookie/signal-name 'acme.billing.internal/debug-open))))))
