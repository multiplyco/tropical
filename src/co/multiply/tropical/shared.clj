(ns co.multiply.tropical.shared
  "State on the server that islands share across sessions: every island that
  reads a state with the same key reads one value, and renders again when it
  changes. It sits between what an island keeps, which a page load ends, and a
  `def`, which nothing ends.

      (defshared toasts
        \"The toasts a user's tabs show.\"
        {:init []})

      (defisland toast-list
        []
        (let [{:keys [uid]} (island/use-session)
              [messages swap-messages!] (use-shared toasts uid)]
          [:ul (for [m messages] [:li (:text m)])]))

      ;; Anywhere: an action, a resource, a task of the app's own.
      (shared/swap! toasts uid conj {:text \"Saved.\"})

  The key decides how shared a state is: a user's id makes it the user's, in
  every tab and across page loads; a document's id makes it everyone's who has
  the document open; nil, everyone's.

  An instance, a state with a key, is created with the state's `:init` by the
  first island to read it, ready in that render. It is held while any island
  reads it, in any session, and lingers after the last stops, as a resource
  does (`co.multiply.tropical.resource`, whose registry holds it), so a page
  load finds it as the last page left it. Then it is gone, and a `swap!` finds
  nothing to change.

  It lives in this JVM's memory: a restart loses it, and each server behind a
  balancer holds its own. What must last, or reach a user wherever they are
  connected, belongs in a database or a stream the app reads with `observe`.

  The key is the access: whoever reads a state with a key reads its value. Build
  it from what the session vouches for, such as `use-session`'s `:uid`, never
  from signals or an action's value."
  (:refer-clojure :exclude [swap!])
  (:require
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.resource :as resource])
  (:import
    [co.multiply.tropical.signal Cell]))


(defrecord Shared [id init linger-ms])


(defn shared*
  "What `defshared` expands to: the state `id`, a qualified symbol."
  {:no-doc true}
  [id {:keys [init linger-ms] :or {linger-ms resource/linger-ms}}]
  (when-not (nat-int? linger-ms)
    (throw (IllegalArgumentException. (str "Shared state " id ": :linger-ms is a number of milliseconds, not "
                                        (pr-str linger-ms) "."))))
  (->Shared id init linger-ms))


(defmacro defshared
  "Defines a shared state: `(defshared name doc? {:init v, :linger-ms n})`.
  `:init` is the value of an instance when it is created, nil if absent.
  `:linger-ms` is how long an instance outlasts its last reader, by default a
  resource's (`resource/linger-ms`): long enough for a page load.

  The var is the state's identity, so two states never share an instance,
  whatever their keys. Evaluated again, as on a reload, it keeps its instances,
  and a changed `:init` applies to those created after."
  [state-name & decl]
  (let [[doc decl] (if (string? (first decl)) [(first decl) (next decl)] [nil decl])
        state-name (cond-> state-name doc (vary-meta assoc :doc doc))]
    `(def ~state-name
       (shared* '~(symbol (str (ns-name *ns*)) (str state-name)) ~(first decl)))))


(defn- resource-key
  [state key]
  [::shared (:id state) key])


(defn swap!
  "Sets the instance of `state` for `key` to `(apply f value args)`, if there is
  one: one an island reads, or one lingering after. Every island reading it
  renders again. Returns whether there was one: a value no island reads would
  be seen by nobody, so it is dropped.

  `f` runs once, under the instance's lock: keep it quick."
  [state key f & args]
  (if-some [^Cell cell (resource/cell (resource-key state key))]
    (do (locking cell
          (Cell/.reset cell (apply f (Cell/.deref cell) args)))
      true)
    false))


(defn use-shared
  "The instance of `state` for `key`, nil if omitted, created with its `:init`
  if there is none, and held while the island reads it. Returns
  `[value swap-value!]`, where `(swap-value! f & args)` is
  `(swap! state key f & args)`. The island renders again when the value
  changes.

  `key` is a value, compared with `=`: a fresh closure would be a new instance
  each render. A hook: read again in one render, with an equal `key`, it
  returns the same instance."
  ([state]
   (use-shared state nil))
  ([state key]
   (let [k  (resource-key state key)
         !v (island/use-hold k
              (fn []
                (let [!v (resource/acquire! k (fn [_] nil) (select-keys state [:init :linger-ms]))]
                  {:value   !v
                   :release #(resource/release! k !v)}))
              {:idempotent true})]
     [(island/use-watch !v)
      (fn [f & args] (apply swap! state key f args))])))
