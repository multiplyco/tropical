(ns demo.server
  "Jetty 12, as a production app would run it: sync handlers on virtual threads,
  and responses gzipped (Jetty leaves `text/event-stream` alone)."
  (:require
    [ring.adapter.jetty9 :as jetty])
  (:import
    [org.eclipse.jetty.server Server]
    [org.eclipse.jetty.server.handler.gzip GzipHandler]))


(defonce !jetty-server (atom nil))


(defn- add-gzip-handler!
  [^Server server]
  (Server/.setHandler server
    (doto (GzipHandler.)
      (GzipHandler/.setMinGzipSize 1024)
      (GzipHandler/.setHandler (Server/.getHandler server)))))


(defn start!
  [handler & {:as opts}]
  (let [opts (merge {:port             8080
                     :join?            false
                     :virtual-threads? true
                     :configurator     add-gzip-handler!}
               opts)]
    (println "Starting server on port:" (:port opts))
    (jetty/run-jetty handler opts)))


(defn stop!
  [server]
  (println "Stopping server")
  (Server/.stop server))


(defn reboot-jetty-server!
  [handler & {:as opts}]
  (swap! !jetty-server
    (fn [server]
      (when server
        (stop! server))
      (start! handler opts))))


(comment
  (require '[demo.core :as c])
  (reboot-jetty-server! #'c/handler))
