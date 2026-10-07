(ns co.multiply.tropical.ui
  "Attributes that follow the browser, and the page's part in keeping the
  state it owns.

  The browser owns some state, and changes it without a round trip. Where it
  keeps it decides what the server knows of it:

  - `co.multiply.tropical.cookie`: small values, which every request carries,
    so the server renders them in the first frame. Whether a section is open,
    a sidebar folded, a pane's width.
  - `co.multiply.tropical.storage`: values of any size, in `localStorage`,
    which the server sees only when an action passes them, or it reads them
    (`storage/observe`). A draft the user is writing.

  Either gives a handle, for the helpers here: `signal` and `path` name its
  signal, for expressions and bindings of the app's own, `toggle` flips a
  boolean, and `shown` and `aria-expanded` render an element and its control
  from it. They are built on `attr`, which has any attribute follow any
  expression, from the first paint. `json` writes a value of the server's
  into an expression.

  The element the app mounts in (`co.multiply.tropical.ring/mount`) carries
  `page-attrs`, which keep both stores, and refuse files dropped outside a
  drop area (`co.multiply.tropical.upload`)."
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.cookie :as cookie]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.page :as page]
    [co.multiply.tropical.storage :as storage]
    [co.multiply.tropical.upload :as upload]))


;; Handles ----------------------------------------------------------------------


(defn signal
  "The Datastar expression for `handle`'s signal, such as `$_cookie.app__open`,
  for expressions of the app's own: `{:data-text (str (signal h) \" ? 'Hide' : 'Show'\")}`."
  [handle]
  (:expr handle))


(defn path
  "The path of `handle`'s signal, or of `ks` within it, for `data-bind`, which
  takes a path rather than an expression: `{:data-bind (path draft \"text\")}`.
  Throws for a key holding a `.`, which Datastar reads as a step."
  [handle & ks]
  (let [steps (into (:path handle) (map #(if (keyword? %) (str (symbol %)) (str %))) ks)]
    (when-some [k (first (filter #(str/includes? % ".") steps))]
      (throw (IllegalArgumentException. (str "A signal's path can't have a step holding a '.', as " (pr-str k) " does."))))
    (str/join "." steps)))


(defn toggle
  "The expression that flips `handle`'s state, a boolean."
  [handle]
  (str (:expr handle) " = !" (:expr handle)))


;; Values -----------------------------------------------------------------------


(defn json
  "`v` as a value in a Datastar expression, for a value the server has and
  the expression needs: JSON, which is a JavaScript literal.

      {:data-on:click (str \"$tab = \" (ui/json tab))}

  A value spliced into an expression as it is written can become code, as a
  title holding a quote would; one written with `json` stays a value. Keywords
  become strings, and a map's keys too, as JSON has them. A backslash is
  written `\\u005c`: Datastar 1.0.4 finds an expression's statements with a
  pattern that reads the end of `\"a\\\\\"` as an escaped quote, and would split
  the expression at a `;` in a later string."
  [v]
  (island/json-js v))


;; Attributes -------------------------------------------------------------------


(defn- worded?
  "Whether the attribute `n` takes the words `true` and `false` for its
  states, where a boolean attribute is present or absent."
  [n]
  (or (str/starts-with? n "aria-") (contains? #{"contenteditable" "draggable" "spellcheck"} n)))


(defn- preserved
  "`attrs`'s `:data-preserve-attr`, a string or a collection of names, with `n`."
  [attrs n]
  (let [p     (:data-preserve-attr attrs)
        names (if (string? p) (str/split (str/trim p) #"\s+") p)]
    (str/join " " (distinct (concat (remove str/blank? names) [n])))))


(defn attr
  "`attrs` with the attribute `k` following the Datastar expression `expr` in
  the browser, from the first paint, which shows `value`: the value `expr`
  has as the page loads, worked out on the server. Without `attrs`, the
  attributes alone. For a Save button enabled while a field differs from what
  was saved, and a tab selected in the browser:

      [:button (ui/attr {:type \"submit\"} :disabled (= draft saved) \"$draft === $saved\") \"Save\"]
      [:button (-> {:role \"tab\" :data-on:click \"$tab = 'files'\"}
                 (ui/attr :aria-selected (= tab :files) \"$tab === 'files'\")
                 (ui/attr :tabindex (if (= tab :files) 0 -1) \"$tab === 'files' ? 0 : -1\"))
       \"Files\"]

  The server renders `value`; Datastar sets the attribute from `expr` as the
  page starts and whenever its signals change, and the element keeps the
  browser's value when the island renders again (`data-preserve-attr`). Put
  two on one element by threading `attr`, not with `merge`: one attribute
  lists every one preserved, and a merge keeps only the last list.

  A boolean attribute, such as `hidden` or `disabled`, is present while its
  value is true. An attribute whose states are the words `true` and `false`,
  as `aria-*` states are, gets those words for a boolean, from the server and
  from `expr` alike, which Datastar would otherwise write as an empty value,
  and so no state. Other values are as they are: \"mixed\", \"page\", a
  number. nil leaves the attribute out.

  Text needs no helper: put what the first paint shows in the element, and
  `data-text` keeps it as the browser has it when the island renders again."
  ([k value expr]
   (attr {} k value expr))
  ([attrs k value expr]
   (let [n      (name k)
         worded (worded? n)]
     (assoc attrs
       k (if (and worded (boolean? value)) (str value) value)
       (keyword (str "data-attr:" n)) (if worded
                                        (str "[" expr "].map(v => v === true || v === false ? String(v) : v)[0]")
                                        expr)
       :data-preserve-attr (preserved attrs n)))))


(defn shown
  "Attributes for an element shown while `handle`'s state is true, or
  `attrs` with them, as `attr` adds them."
  ([handle]
   (shown {} handle))
  ([attrs handle]
   (attr attrs :hidden (not (:value handle)) (str "!" (:expr handle)))))


(defn aria-expanded
  "Attributes for the control of an element shown while `handle`'s state is
  true: its `aria-expanded`. Or `attrs` with them, as `attr` adds them."
  ([handle]
   (aria-expanded {} handle))
  ([attrs handle]
   (attr attrs :aria-expanded (boolean (:value handle)) (:expr handle))))


;; The page ---------------------------------------------------------------------


(defn page-attrs
  "Attributes for the page's body, which keep the state the browser owns: a
  handler for each change to a cookie state, which it writes into the cookie,
  or a storage state, which it writes into `localStorage`, and a pass as the
  page starts, dropping the entries it won't use. And they refuse files
  dropped outside a drop area, which the browser would open in the tab
  (`co.multiply.tropical.upload/drop-guard`). `ring/mount` adds them to the
  element the app mounts in. For a body of the page's own, given its own
  attributes, they are added to them:

      [:body (ui/page-attrs {:data-init (ring/stream-init tab)}) ...]

  An attribute of the body's own that one of these would replace throws: an
  element has one `data-on-signal-patch`, one `data-effect`, and one each of
  `data-on:dragover__window` and `data-on:drop__window`.

  `co.multiply.tropical.ring` renders the page for a user, whose entries the
  page keeps, so call it in the app's `:page`; anywhere else it throws."
  ([]
   (page-attrs {}))
  ([attrs]
   (page/mark! :state)
   ;; One statement to Datastar, which splits an expression at each `;` outside
   ;; strings and an arrow function that takes no arguments, called with none,
   ;; and returns the last. `patch` is the handler's own. It starts with `void`:
   ;; Datastar takes an expression starting with an arrow function for one.
   (let [own (merge {:data-on-signal-patch        (str "void (() => {" (cookie/handler-js) (storage/handler-js) "})()")
                     :data-on-signal-patch-filter "{include: /^_(cookie|storage|storageForget|storageWatch)\\./}"
                     ;; Reads no signal, so it runs once.
                     :data-effect                 (storage/prune-js)}
               (upload/drop-guard))]
     (when-some [taken (seq (filter #(contains? attrs %) (keys own)))]
       (throw (IllegalArgumentException.
                (str "page-attrs sets " (str/join ", " (map name taken)) " on the body, which has its own."))))
     (merge attrs own))))
