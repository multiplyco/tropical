(ns ^:no-doc co.multiply.tropical.internal.delivery
  "Preparing and writing a session's HTML patches and scripts. This layer
  owns no session state and never flushes: the pump commits delivery only
  after its enclosing batch, including signals and a checkpoint, succeeds."
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.internal.frame :as frame]
    [co.multiply.tropical.internal.model :as model]
    [co.multiply.tropical.sse :as sse]
    [dev.onionpancakes.chassis.core :as h]))


(defn prepare-frame
  "The patches from `sent` to `latest`, serialized once, with the frames and
  character count passed to the app's patch-signals callback."
  [sent latest]
  (let [{:keys [morph insert] :as patches} (frame/patches sent latest)
        htmls (mapv model/html morph)
        inserted (mapv #(mapv model/html (:frames %)) insert)]
    {:patches patches
     :htmls htmls
     :inserted inserted
     :patched (into morph (mapcat :frames) insert)
     :chars (transduce (map count) + (transduce (map count) + htmls) (eduction cat inserted))}))


(defn- selector
  "A CSS selector for the elements of the islands `ids`."
  [ids]
  (str/join ", " (map #(str "[id=\"" (model/element-id %) "\"]") ids)))


(defn- send-patches!
  "Sends `patches` (`frame/patches`), whose frames are `htmls` and
  `inserted`, the HTML of each insert's frames: the morphs in one event, an
  event per insert, and the removals in one. False if the stream closed."
  [conn {:keys [morph insert] removed :remove} htmls inserted]
  (and (or (empty? morph) (sse/patch! conn htmls))
    (reduce (fn [_ [{:keys [mode target]} htmls]]
              (or (sse/patch! conn htmls {:mode mode :selector (selector [target])})
                (reduced false)))
      true
      (map vector insert inserted))
    (or (empty? removed) (sse/remove! conn (selector removed)))))


(defn send-frame!
  "Writes a prepared frame, stopping if the connection closes. Does not flush."
  [conn {:keys [patches htmls inserted]}]
  (send-patches! conn patches htmls inserted))


(defn- script-tag
  [url]
  (h/html [:script {:type "module" :src url}]))


(defn send-scripts!
  "Appends module tags to the page's head. Datastar supplies the page's nonce."
  [conn urls]
  (sse/patch! conn (mapv script-tag urls) {:mode :append :selector "head"}))
