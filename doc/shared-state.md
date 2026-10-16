# State shared on the server

Some state is the server's, and outlives a page: a user's toasts, which every tab of theirs shows and the page a
navigation opens still has, or who has a document open. An island's own state ends with the page, and a `def` never
ends. `co.multiply.tropical.shared` keeps state between the two, for as long as an island reads it.

```clojure
(defshared toasts
  "The toasts a user's tabs show."
  {:init []})

(defisland toast-list
  []
  (let [{:keys [uid]} (use-session)
        [messages swap-messages!] (use-shared toasts uid)]
    [:ul (for [m messages] [:li (:text m)])]))

;; Anywhere: an action, a resource, a task of the app's own.
(shared/swap! toasts uid conj {:text "Saved."})
```

- **The key decides who shares.** Every island reading a state with an equal key reads one value, in any session: a
  user's id makes it the user's, a document's id everyone's who has it open, nil everyone's. The var is the state's
  identity, so two states never share, whatever their keys, and evaluated again, as on a reload, it keeps its
  instances.
- **The key is the access.** Build it from what the session vouches for, such as `use-session`'s `:uid`, never from
  signals or an action's value. A key taken from a read goes in `if-loaded`
  ([Reading](reading.md#reads-that-depend-on-reads)): while the read is pending, `(:id user)` is nil, and nil is
  everyone's.
- **Created by its first reader,** with the state's `:init`, ready in that render. Each change renders every island
  reading it again.
- **Changed from anywhere.** `shared/swap!` applies a fn under the instance's lock, from an action, a resource, or a
  task of the app's own, so the fn should be quick. The `swap-value!` that `use-shared` returns is the same, bound to
  its state and key.
- **Held while read.** An instance is a resource ([A resource's life](reading.md#a-resources-life)): held while any
  island reads it, it lingers after the last stops (`:linger-ms`, 5 s by default), so a page load finds it as the last
  page left it. Then it is gone, and a `swap!` finds nothing to change and returns false: a value no island reads
  would be seen by nobody.
- **In memory.** A restart loses it, and each server behind a balancer holds its own: for what must last or reach a
  user wherever they are connected, use a database or a stream, read with `observe`.
