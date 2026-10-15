(ns co.multiply.tropical.script
  "Scripts the page loads for its islands: ES modules, such as the web component
  an island's elements are, or functions its expressions call.

  `defscript` declares one, from a resource on the classpath. An island whose
  elements need it takes it up with `use`, during its render:

      (defscript outline
        \"The page's outline, which follows the reader as they scroll.\"
        {:resource \"app/outline.js\"})

      (defisland article-outline
        []
        (script/use outline)
        [:nav [:page-outline]])

  How it loads:

  - A script is served at a URL of its own, under `path`, named by its var, as
    `co.multiply.tropical.names` shows it, and the hash of its content, by
    `ring/wrap-scripts`. The URL changes with the
    content, so the browser keeps a script as long as it likes, and loads a
    changed one anew.
  - The page's own scripts are those the islands of its first frame declared:
    the page puts them in its head, with the CSP nonce, through `tags`, so they
    load with the page (`ring/handler`'s `:scripts`).
  - Over the stream, the session sends each script once, appended to the
    page's head, from the first frame in which an island declares it. Datastar
    gives the tag the page's nonce. So a script loads whether its island is in
    the first frame or patched in later, and in the first frame of a page that
    left it out of its head, a little later.
  - A module runs once per page, however many tags load it: the browser keeps a
    module per URL for the life of the page.

  What a script must know:

  - It is an ES module: loaded as `<script type=\"module\">`, deferred, and in
    strict mode. It may import other modules by absolute URL.
  - The island's morph matches an element's children to the server's: what a
    script puts into an island's elements is undone by the next render. A web
    component draws into a shadow root, which the morph doesn't reach.
  - A module that fails to load, such as one blocked for want of the nonce,
    stays failed for the page, under any later tag.
  - A page keeps the version of a script it loaded first. A changed script
    loads under its new URL, but `customElements.define` won't redefine a
    name, and a module runs again beside the old one. So a page reloads when
    its stream connects to a server on which a script has changed since the
    page loaded, as after a deploy, or `session/close-all!` in development
    (`ring/handler`'s revision).

  In development, a script whose resource is a file is read again when the file
  changes, at most once a second, so a reload of the page picks up an edit."
  (:refer-clojure :exclude [use])
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.names :as names]
    [co.multiply.tropical.page :as page])
  (:import
    [java.io File]
    [java.net URL]
    [java.nio.charset StandardCharsets]
    [java.security MessageDigest]
    [java.util HexFormat]))


(def path
  "Where scripts are served: `<path><name>.<hash>.js`."
  "/tropical/")


(def ^:private check-ms
  "How often, at most, a script whose resource is a file looks for changes."
  1000)


;; Declaring --------------------------------------------------------------------


;; Name -> Script, for every script declared.
(defonce ^:private !scripts (atom {}))


;; :id the qualified symbol of its var; :name its name in URLs; :file the
;; resource's File, if it is one, read again when it changes; :!version the
;; content now, {:js :url :modified :checked}.
(defrecord Script [id name file !version])


(defn- script-name
  "The name in URLs of the script `id`, a qualified symbol: an opaque digest
  of it, unless names are readable (`co.multiply.tropical.names`)."
  [id]
  (names/opaque (str/replace (str (namespace id) "." (name id)) #"[^A-Za-z0-9._-]" "_") 10))


(defn- digest
  [^String js]
  (subs (HexFormat/.formatHex (HexFormat/of)
          (MessageDigest/.digest (MessageDigest/getInstance "SHA-256") (.getBytes js StandardCharsets/UTF_8)))
    0 16))


(defn- version
  "The version of the script `script-name` whose content is `js`."
  [script-name ^String js modified now]
  {:js       js
   :url      (str path script-name "." (digest js) ".js")
   :modified modified
   :checked  now})


(defn- file-of
  "The file `url` points at, if it is one."
  [^URL url]
  (when (= "file" (URL/.getProtocol url))
    (io/file url)))


(defn script*
  "What `defscript` expands to: the script `id`, a qualified symbol."
  {:no-doc true}
  [id {:keys [resource js]}]
  (when-not (= 1 (count (filter some? [resource js])))
    (throw (IllegalArgumentException. (str "Script " id ": give it a :resource, or :js."))))
  (let [url  (when resource
               (or (io/resource resource)
                 (throw (IllegalArgumentException. (str "Script " id ": no resource " resource " on the classpath.")))))
        file (some-> url file-of)
        n    (script-name id)
        now  (System/currentTimeMillis)
        js   (if url (slurp url :encoding "UTF-8") js)]
    (when-not (string? js)
      (throw (IllegalArgumentException. (str "Script " id ": its :js is a string."))))
    (let [script (->Script id n file (atom (version n js (some-> ^File file File/.lastModified) now)))]
      (swap! !scripts (fn [scripts]
                        (when-some [other (get scripts n)]
                          (when (not= id (:id other))
                            (throw (IllegalArgumentException.
                                     (str "Script " id " would share its URL with " (:id other) "; rename one.")))))
                        (assoc scripts n script)))
      script)))


(defmacro defscript
  "Defines a script: `(defscript name doc? {:resource path})`, an ES module read
  from the resource `path` on the classpath. `{:js string}` gives its content
  instead."
  [script-name & decl]
  (let [[doc decl] (if (string? (first decl)) [(first decl) (next decl)] [nil decl])
        script-name (cond-> script-name doc (vary-meta assoc :doc doc))]
    `(def ~script-name
       (script* '~(symbol (str (ns-name *ns*)) (str script-name)) ~(first decl)))))


;; Versions ---------------------------------------------------------------------


(defn- current
  "The version of `script` now: read again if its file changed."
  [{:keys [^File file !version] :as script}]
  (let [v @!version]
    (if (nil? file)
      v
      (let [now (System/currentTimeMillis)]
        (if (< (- now (long (:checked v))) (long check-ms))
          v
          (let [modified (File/.lastModified file)]
            (reset! !version
              (if (= modified (:modified v))
                (assoc v :checked now)
                (version (:name script) (slurp file :encoding "UTF-8") modified now)))))))))


(defn url
  "The URL `script` is served at now."
  [script]
  (:url (current script)))


(defn urls
  "The URL every script is served at now, in order: the scripts a page could
  load, part of the page's revision (`ring/handler`)."
  {:no-doc true}
  []
  (sort (map url (vals @!scripts))))


(defn lookup
  "The script served at `script-path`, the part of a URL after `path`, as
  `{:js :current?}`, `:current?` false if it named another version of the
  script; nil if no script has its name."
  {:no-doc true}
  [^String script-path]
  (when-some [[_ n] (re-matches #"(.+)\.[0-9a-f]+\.js" script-path)]
    (when-some [script (get @!scripts n)]
      (let [{:keys [js url]} (current script)]
        {:js js :current? (= url (str path script-path))}))))


;; Rendering --------------------------------------------------------------------


(defn use
  "Takes up `script` in this island: the page loads it, once, from the first
  frame in which an island declares it. Returns its URL, for an expression that
  imports it. A hook: call it during the render."
  [script]
  (let [u (url script)]
    (island/declare-script u)
    u))


(defn tags
  "The page's script tags for `scripts`, URLs as `ring/handler` gives the page
  under `:scripts`, with the CSP nonce `nonce`: for the page's head. Given the
  page's own, the stream doesn't send them again."
  [scripts nonce]
  (when (some-> (page/current) :scripts (= (vec scripts)))
    (page/mark! :scripts))
  (for [src scripts]
    [:script (cond-> {:type "module" :src src} nonce (assoc :nonce nonce))]))
