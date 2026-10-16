# Reading from outside the page

`(observe & args f)` (`co.multiply.tropical.hooks`) reads a resource: a database subscription, a poll loop, a query.
`f` opens it, as in Missionary's `observe`: it is called with an `emit!` fn and `args`, and returns what undoes it.

```clojure
(defisland ticker
  [topic]
  (let [reading (observe topic
                  (fn [emit! topic]
                    (let [sub (subscribe-feed topic emit!)]
                      #(unsubscribe sub))))]
    (if (hooks/pending? reading)
      [:p "Connecting…"]
      [:p (:value reading)])))
```

## Who shares a resource

- **The call site and `args` are its key.** Every session reading one call site with equal `args` shares one resource.
  So `args` decide who shares it: everything `f` depends on must be among them, and `f` may refer to no local of the
  render except through its params. One that does fails to compile. Data that differs per user needs the user, or
  their account, among the `args`.
- **Pass values and stable references.** `args` are compared with `=`, so a fresh closure would open a new resource
  each render.
- **Read twice, held once.** The same call made again in one render, with equal `args`, returns the same value. So an
  app's own hook over `observe`, whose reads all share its call site, may be called by several hooks in one render,
  each asking its own question of the same data, over one resource. `use-shared` is the same for a state's instance.
- **Other `args`** release the old resource, which lingers, and hold the new one.

## Opening one

- **`f` runs off the render,** on a virtual thread of its own, so it may block while it connects. It runs in an empty
  scope, since the resource belongs to no single reader: read a scoped value in the render and pass it,
  `(observe (ask *account*) query-account)`.
- **`f` returns what undoes it:** a fn, called once when the resource closes; or a
  [Quiescent](https://github.com/multiplyco/quiescent) task, such as a poll loop's, cancelled when it closes, whose
  failure fails the resource; or nil.
- **`emit!` keeps the latest value.** A session renders whatever is current when it gets to it, so values in between
  may never be seen, and a diff sent through `emit!` may be lost: emit the state. It may be called from any thread,
  and does nothing once the resource has closed.

## What a read returns

- **`pending`, then the latest value.** `observe` returns `hooks/pending` until the first value, which
  `hooks/pending?` tells apart. A session's first frame can wait briefly for it (`:first-frame-ms`, in
  [Serving pages](serving.md)).
- **A failure throws in the render.** When `f` throws or its task fails, `observe` throws its exception, so `try`
  handles it; uncaught, the island renders its error view ([Islands](islands.md#when-a-render-throws)). The island
  keeps the failure until it unmounts or reads other `args`, and a reader arriving after the failure opens a new
  resource.
- **Given `pending`, pending.** If one of `args` is `pending`, `observe` returns `pending` and opens nothing, so a read
  given another's value starts once that one has loaded ([Reads that depend on reads](#reads-that-depend-on-reads)).

## Reads of the app's own

An app's own reads build on `observe`. A function has one call site, which every caller shares, so its resources are
keyed by its `args` alone. A macro makes a call site at each use:

```clojure
(defn <-
  "The value at `path`, through a subscription shared by every reader."
  [db path]
  (observe db path
    (fn [emit! db path]
      (let [sub (subscribe db path emit!)]
        #(unsubscribe sub)))))


(defmacro ?
  "A one-off query: the task `(apply f args)`, once per call site and `args`."
  [f & args]
  `(observe ~@args
     (fn [emit!# & args#]
       (q/then (apply ~f args#) emit!#))))
```

A query read like this is shared and cached as a subscription is: identical queries run once, and a reader arriving
while the result is held sees it at once.

## Reads that depend on reads

A read's value is `pending` until it has loaded. `observe` given `pending` returns `pending` and opens nothing, but a
value taken from a pending read, such as `(:id user)`, is nil, and a read given it opens with nil. Branching on
`pending?` avoids that, and renders what has loaded at each step. A read inside a branch is fine, since hooks are keyed
by name, not by call order:

```clojure
(let [user (observe uid load-user)]
  (if (hooks/pending? user)
    [:p "Loading…"]
    [:div
     [:h2 (:name user)]
     (let [orders (observe (:id user) load-orders)]
       (if (hooks/pending? orders)
         [:p "Loading orders…"]
         [:ul (for [o orders] [:li (:title o)])]))]))
```

The inner part could just as well be an island of its own, given `user` once it has loaded. That is the usual shape
where a read gives a collection: an island per item, keyed by it, each reading its own.

```clojure
(defisland item-row {:key identity}
  [item-id]
  (let [item (observe item-id load-item)]
    (if (hooks/pending? item)
      [:li "Loading…"]
      [:li (:title item)])))


(defisland item-list
  [list-id]
  (let [item-ids (observe list-id load-item-ids)]
    (if (hooks/pending? item-ids)
      [:p "Loading…"]
      [:ul (for [item-id item-ids] (item-row item-id))])))
```

Each item's read is a resource of its own, shared by every session showing the item, and each row shows as it loads,
sent alone. A session's first frame waits for the rows' reads too, within its budget: they are reads of islands that
appear once the list's read lands. The check isn't optional here, since `for` over `pending` throws, and the list would
show its error view until its ids load.

Where reads wait together, with nothing to show until all have loaded, `if-loaded` is shorter. It binds as `let` does,
but evaluates a binding only once the bindings it refers to have loaded:

```clojure
(if-loaded [user   (observe uid load-user)
            orders (observe (:id user) load-orders)
            prefs  (observe uid load-prefs)]
  [:section {:class (:theme prefs)}
   [:ul (for [o orders] [:li (:title o)])]]
  [:p "Loading…"])
```

`orders` waits for `user`. `prefs` refers to neither, so it starts in the same render as `user`: reads that don't
depend on each other still load side by side. References are found by name, in the expression as written, so a name
bound again inside it still counts, which errs toward waiting. Without an else, `if-loaded` returns `pending`, which
can feed another read, or another `if-loaded`.

A session's first frame waits for its reads, so a read opened with nil may never show on a cold page load, but no
later frame waits: when `user`'s `args` change, or the island first appears after an action, the read opened with nil
shows, as the error view or as data that isn't. A shared state's key taken from a read is the same:
`(use-shared drafts (:id user))`, with `user` pending, reads the instance for nil, which is everyone's.

## A resource's life

- One physical resource per key, an `observe` call site and its `args`, opened by the first subscriber, however many
  sessions read it.
- After the last subscriber leaves, it lingers for `resource/linger-ms` (5 s) before closing. This absorbs remount
  churn: switching back and forth, navigation, reconnects.
- A resource runs in a Quiescent task of its own, which belongs to the registry, not to whichever session opened it.
  Closing cancels the task, which calls the cleanup fn `f` returned, or cancels the task `f` returned. A cleanup fn
  returned after the resource closed is called as soon as it is returned.
- A resource whose task fails is closed, and the next subscriber opens a new one.
- A shared state's instance lives the same way, under its state and key ([State shared on the server](shared-state.md)).
- Closing doesn't wait for the old resource to tear down. A reader arriving just after a close opens a new one, while
  the old may still be closing: briefly, two connections to one source. A source that takes any number of readers
  doesn't mind; one that takes something exclusive, a lock or a sole consumer, may find it still held.

## Watching what is open

A view of the registry, such as a debug panel's, is an island that reads it with `resource/use-summary`, and renders
again as a resource opens, gains or loses a reader, lingers or closes, never as one emits:

```clojure
(defisland open-reads
  [scope]
  [:ul
   (for [[k {:keys [state subscribers]}] (resource/use-summary scope)
         :let [{:keys [kind ns line args]} (resource/describe k)]]
     [:li (if (= :observe kind) (str ns ":" line " " (pr-str args)) (pr-str k)) " " (name state) " " subscribers])])
```

- **`:server`, the default, is every resource open on this server**, each counting the islands that read it in any
  session. Its rows are every user's reads, their arguments among them: show it only to whoever may see all of them ([Security](security.md)).
- **`:session` is what this tab's islands read**, each counting those islands only. It shows nothing the page doesn't
  already read, so it suits a panel any user may open. A resource the tab lets go leaves it, though it may linger for
  other tabs.
- **`resource/describe` says what opened a resource**, as data: an `observe`'s `{:kind :observe, :ns, :line, :column,
  :args}`, where the position is its form's, or a shared state's instance, `{:kind :shared, :state, :key}`. Read a key
  through it, not by its shape: the call site in a key is a symbol unique to its expansion, whose format is
  Tropical's. A macro of the app's over `observe` keeps its caller's position by passing its form's metadata on,
  ``(with-meta `(observe ...) (meta &form))``; a function's reads all have the function's own, and differ by `:args`.

Both are for diagnostics: a view works the registry out again each time it changes, anywhere on the server. At the
REPL, `(resource/summary)` gives the server's, once.
