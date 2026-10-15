(ns demo.core
  "HTTP: two pages, `/` and `/topic/:topic`, each served with its stream and
  actions by a tropical handler (`co.multiply.tropical.ring/handler`) of its
  own, whose root reads the route.

  The middleware is a typical production stack: cookies, sessions, params, and a
  nonce-based CSP. Outside it all, `wrap-bucket` stands in for an object store
  taking uploads, `wrap-scripts` serves the scripts islands declare, and
  `wrap-refused-streams` turns a stream the routes refuse into a reload of its
  page: a topic's route refuses a topic that doesn't exist. The stream is
  compressed with zstd, brotli or gzip (`:compression`). A body
  parser such as Muuntaja would go on the app's other routes, not the page's:
  tropical reads an action's Datastar signals from the request body itself, so
  nothing may decode it first.

  A user is a random id in a cookie, made on the first visit."
  (:require
    [co.multiply.tropical.ring :as ring]
    [demo.bucket :as bucket]
    [demo.page :as page]
    [demo.state :as state]
    [reitit.ring :as rr]
    [ring-middleware-csp.core :refer [wrap-csp]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.session :refer [wrap-session]]
    [ring.util.response :as resp]))


(def ^:private uid-cookie "uid")


(defn- request-uid
  [req]
  (get-in req [:cookies uid-cookie :value]))


(defn- wrap-uid
  "Gives a request without a user a new one: a random id, set as a cookie on the
  response."
  [handler]
  (fn [req]
    (if (request-uid req)
      (handler req)
      (let [uid (str (random-uuid))]
        (-> (handler (assoc-in req [:cookies uid-cookie :value] uid))
          (resp/set-cookie uid-cookie uid {:path "/" :http-only true :same-site :lax}))))))


;; What the pages share. Each route adds its root.
(def app
  {:uid            request-uid
   :page           (fn [{:keys [request]}]
                     (page/shell (:csp-nonce request)))
   ;; What the shell's head loads: a tab opened before it changed reloads.
   :revision       page/revision
   :patch-signals  page/wire-stats
   :error-view     page/error-view
   ;; A cold page waits this long for its reads: alpha's feed, which connects in
   ;; 50 ms, makes it into the page; the 700 ms topic summary doesn't.
   :first-frame-ms 150
   ;; The stream, compressed with the first of these the browser accepts. The
   ;; pages are Jetty's to compress.
   :compression    [:zstd :br :gzip]})


(defn- wrap-topic
  "Finds the topic whose page is at the request's URL, for the root, under
  `:demo/topic`. Refuses a topic that doesn't exist, with a 404, as an app
  refuses a page whose subject is gone: the page, its stream and its actions
  alike. A topic that moved is redirected to, as a route redirects to an item
  that moved: a page loaded at its old URL loads at its new one."
  [handler]
  (fn [req]
    (let [s     (get-in req [:path-params :topic])
          paths @state/!paths]
      (if-some [topic (state/topic-at paths s)]
        (handler (assoc req :demo/topic topic))
        (if (contains? state/topics s)
          (resp/redirect (str "/topic/" (state/path-name paths s)))
          {:status  404
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (page/not-found s)})))))


(def ^:private csp
  ;; No 'unsafe-eval': in CSP mode (the page's `data-nonce`), Datastar compiles
  ;; its expressions into nonce'd scripts instead of with `Function()`.
  {:policy     {:object-src [:none]
                :base-uri   [:self]
                :script-src [:nonce :strict-dynamic "https:"]}
   :use-nonce? true})


(def routes
  [["" {:middleware [[wrap-csp csp] wrap-uid]}
    ["/" {:handler (ring/handler (assoc app :root page/root))}]
    ["/topic/:topic" {:middleware [wrap-topic]
                      :handler    (ring/handler (assoc app :root page/topic-root))}]]])


(def handler
  (bucket/wrap-bucket
    (rr/ring-handler
      (rr/router routes)
      (rr/create-default-handler)
      {:middleware [ring/wrap-scripts
                    ring/wrap-refused-streams
                    wrap-cookies
                    wrap-session
                    wrap-params
                    wrap-keyword-params
                    wrap-multipart-params]})))
