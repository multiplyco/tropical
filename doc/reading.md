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
    (if (= hooks/pending reading)
      [:p "Connecting…"]
      [:p (:value reading)])))
```

- **Shared by call site and arguments.** Every session reading one call site with equal `args` shares one resource,
  opened by the first reader and closed after the last leaves, once it has lingered. So `args` decide who shares it:
  everything `f` depends on must be among them, and `f` may refer to no local of the render except through its params.
  One that does fails to compile. Data that differs per user needs the user, or their account, among the `args`. Pass
  values and stable references: `args` are compared with `=`, so a fresh closure would open a new resource each render.
- **Read twice, held once.** The same call made again in one render, with equal `args`, returns the same value. So an
  app's own hook over `observe`, whose reads all share its call site, may be called by several hooks in one render,
  each asking its own question of the same data, over one resource. `use-shared` is the same for a state's instance.
- **What `f` returns:** a fn, called once when the resource closes; or a
  [Quiescent](https://github.com/multiplyco/quiescent) task, such as a poll loop's, cancelled when it closes, whose
  failure fails the resource; or nil.
- **Off the render.** `f` runs on a virtual thread of its own, so it may block while it connects. It runs in an empty
  scope, since the resource belongs to no single reader: read a scoped value in the render and pass it,
  `(observe (ask *account*) query-account)`.
- **`emit!` keeps the latest value.** A session renders whatever is current when it gets to it, so values in between
  may never be seen. It may be called from any thread, and does nothing once the resource has closed.
- **Pending and failure.** `observe` returns `hooks/pending` until the first value; a session's first frame can wait
  briefly for it (`:first-frame-ms`, in [Serving pages](serving.md)). It throws in the render when `f` throws or its
  task fails, so `try` handles a failure; uncaught, the island renders the error view. The failed resource is closed,
  and the next reader opens a new one.
- **Other `args`** release the old resource, which lingers, and hold the new one.

Reads of a shape of their own build on it: a function, whose one call site every caller shares, so its resources are
keyed by its `args` alone, or a macro, which makes a call site at each use:

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

## A resource's life

- One physical resource per key, an `observe` call site and its `args`, opened by the first subscriber, however many
  sessions read it.
- After the last subscriber leaves, it lingers for `linger-ms` (5 s) before closing. This absorbs remount churn:
  switching back and forth, navigation, reconnects.
- A resource runs in a Quiescent task of its own, which belongs to the registry, not to whichever session opened it.
  Closing cancels the task, which calls the cleanup fn `f` returned, or cancels the task `f` returned. A cleanup fn
  returned after the resource closed is called as soon as it is returned.
- A resource whose task fails is closed, its readers throw, and the next subscriber opens a new one.
- A shared state's instance is a resource under a key of its own, its state and key. It opens holding the state's
  `:init`, lingers for the state's `:linger-ms`, and changes by `shared/swap!`.
- Closing doesn't wait for the old resource to tear down. A reader arriving just after a close opens a new one, while
  the old may still be closing: briefly, two connections to one source. A source that takes any number of readers
  doesn't mind; one that takes something exclusive, a lock or a sole consumer, may find it still held.
- `(resource/summary)` lists the open resources, each with its state (`:connecting`, `:live` or
  `:lingering`) and how many islands read it.
