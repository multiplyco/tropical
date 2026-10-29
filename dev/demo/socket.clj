(ns demo.socket
  "A shared room with opaque text/binary messages. Its resource emits the room
  once; traffic never changes the observed value or renders an island."
  (:require
    [clojure.core.async :as async]
    [co.multiply.quiescent :as q]
    [co.multiply.tropical.action :refer [use-action]]
    [co.multiply.tropical.hooks :as hooks :refer [observe]]
    [co.multiply.tropical.island :as island :refer [defisland]]
    [co.multiply.tropical.script :as script]
    [co.multiply.tropical.channel :as channel]))


(script/defscript component {:resource "demo/socket.js"})


(defn- open-peer
  [!peers endpoint]
  (q/task
    (let [id (random-uuid)]
      (swap! !peers assoc id endpoint)
      (try
        (loop []
          (when-some [message (async/<!! endpoint)]
            (doseq [peer (vals @!peers)] (async/>!! peer message))
            (recur)))
        (finally (swap! !peers dissoc id))))))


(defisland room
  [revision]
  (let [peers (observe (fn [emit!] (emit! (atom {}))))]
    [:div
     [:p {:class [:text-xs :text-zinc-500 :mb-3]} "Server render revision: " revision]
     (if (= hooks/pending peers)
       [:p "Opening the room…"]
       (let [descriptor (channel/use-channel [:room peers] (fn [{:keys [channel]}] (open-peer peers channel)))]
         (script/use component)
         [:socket-room (channel/attrs descriptor)]))]))


(defisland card
  []
  (let [[shown show!] (island/use-state :shown true)
        [revision revise!] (island/use-state :revision 0)]
    [:section {:class [:rounded-xl :border :border-zinc-200 :bg-white :p-5 :space-y-3]}
     [:h2 {:class [:text-lg :font-semibold]} "Resumable channel"]
     [:p {:class [:text-sm :text-zinc-500]}
      "Open another tab to share this room. Text and binary messages go straight to the component; "
      "sending leaves the server render revision unchanged. The draft and history stay through a reconnect or rerender."]
     [:div {:class [:flex :gap-3 :text-sm]}
      [:button {:class [:underline :cursor-pointer]
                :data-on:click (use-action :toggle (fn [_] (show! (not shown))))}
       (if shown "Unmount room" "Mount room")]
      [:button {:class [:underline :cursor-pointer]
                :data-on:click (use-action :render (fn [_] (revise! (inc revision))))}
       "Rerender island"]]
     (when shown (room revision))]))
