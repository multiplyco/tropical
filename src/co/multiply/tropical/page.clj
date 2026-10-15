(ns ^:no-doc co.multiply.tropical.page
  "The page `co.multiply.tropical.ring` is rendering, while the app's `:page`
  renders it: what the helpers that put the session into the page read
  (`ring/mount`, `ring/scripts`), and a record of what they put there
  (`ring/stream-init`, `ui/page-attrs`), which the handler checks once the
  page is rendered. Its own namespace, so that `ui` records into it without
  depending on `ring`."
  (:require
    [co.multiply.scoped :refer [ask]]))


;; `{:tab :frame :scripts :placeholder :open-when-hidden :revision :!marks}`, bound by
;; `co.multiply.tropical.ring` around the app's `:page`. Without a root, so that
;; `ask` gets its default where nothing binds it.
#_{:clj-kondo/ignore [:uninitialized-var]}
(def ^:dynamic *page*)


(defn current
  "The page being rendered, or nil outside the app's `:page`."
  []
  (ask *page* nil))


(defn mark!
  "Records that the page being rendered has `k`, such as `:stream`, and
  returns whether it had it already. Does nothing outside the app's `:page`."
  [k]
  (when-some [{:keys [!marks]} (current)]
    (let [[before _] (swap-vals! !marks conj k)]
      (contains? before k))))
