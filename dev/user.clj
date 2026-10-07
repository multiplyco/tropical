(ns user
  (:require
    [clj-reload.core :as reload]
    [co.multiply.remontoire :as remontoire]
    [co.multiply.tropical.resource :as resource]
    [co.multiply.tropical.session :as session]))


(alter-var-root #'*warn-on-reflection* (constantly true))


(reload/init
  {:no-reload ['user]})


;; MCP endpoint into this JVM, for agents: http://127.0.0.1:7888/mcp
(remontoire/init!
  {:describe "a local dev JVM running Tropical, a server-side island runtime for Datastar, and its demo page"})


(defn start!
  "Starts the demo server, or restarts it on the handler as loaded now. `bb dev`
  calls this on startup."
  []
  ;; By name, not by alias: a reload replaces `demo.core`, and an alias here
  ;; would keep pointing into the namespace it replaced.
  ((requiring-resolve 'demo.server/reboot-jetty-server!) (requiring-resolve 'demo.core/handler)))


(defn reload!
  "Reloads changed namespaces, restarts the server, and closes every session
  (their resources close after the linger), so open tabs reconnect into fresh
  sessions of the new code. Closing comes last: a tab that reconnected before
  the server restarted would get a session of the old code. Java sources need
  `bb compile:java` and a restart instead."
  []
  (reload/reload)
  (start!)
  (session/close-all!))


(comment
  (reload!)

  ;; sessions, by tab
  @session/!sessions

  ;; the resource registry
  (resource/summary @resource/!entries)
  @resource/!totals

  *e)
