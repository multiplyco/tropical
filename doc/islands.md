# Islands

An island is a function returning hiccup, defined with `defisland`: the unit that renders, the unit sent to the
browser, and the owner of what it reads and holds. This guide covers writing them, the hooks they use, what happens
when one throws, and where to draw their boundaries.

```clojure
(defisland message {:key :n}
  [{:keys [text]}]
  [:li text])

(defisland messages
  []
  (let [posted (use-watch !messages)]
    [:ul (map message posted)]))
```

## Writing islands

- **An island is a function of its arguments.** Calling one inside another island's hiccup places it there, as a
  React element does, and the runtime decides whether it renders. It renders when it is new, when its arguments
  changed (`=`), or when something it read through a hook changed. Otherwise its last output is reused.
- **Pass data.** A fresh closure, or a collection built anew in each render, is unequal every time, so the child
  renders every time.
- **Read through a hook.** A bare `@atom` is not tracked, so the island will not render again when it changes.
  `use-watch` reads it, and `observe` reads from outside the page ([Reading from outside the page](reading.md)).
- **The gate is `if`.** A branch that isn't rendered reads nothing, holds nothing and exposes no actions.
- **A render is a plain call on the session's thread.** A render that blocks stalls its own session, so slow work
  belongs in `observe`. A task started in a render belongs to the session, not the island, and isn't cancelled when the
  island unmounts: what should live as long as the island is a hold, or a resource.

## Slots, keys and ids

- **An island's slot is its name**, unique among its siblings. For several instances under one parent, give a `:key`
  fn of the arguments: `(defisland message {:key :n} [msg] ...)` gives the slot `message.17`. Any key works:
  characters other than letters, digits and `-` are escaped.
- **Its id is the path of slots from the root**, `app/vault-card/messages/message.17`. Ids are scoped by parent, as
  React's keys are, so an island can be placed anywhere, any number of times.
- **The id goes on the island's root element**, as an opaque digest of the path, which is how a patch for the island
  finds its element in the page ([Serving pages](serving.md#names-in-the-page)). So an island that sets an id on its
  root fails, and one whose root isn't an element is wrapped in a `:div`. An element the app needs to find, from a
  script or a label's `for`, gets an id of its own, inside.
- **A key is the island's identity, as in React.** An island under a new key is a new one: it starts over, its state,
  holds and actions fresh, and the old one unmounts. So to start an island over, such as a dialog opened again, change
  its key: `{:key :opened-at}`. Only the new island is sent, in the old one's place, not its parent.

## Hooks

Hooks take a key, not a call order: each names what it is, so conditionals and loops around them are fine. They are in
`co.multiply.tropical.island`, unless noted.

- `(use-watch ref)` or `(use-watch ref select)` reads an atom (anything watchable), or a signal. The island renders
  again when the selected value changes, so `#(allowed? % uid :vault)` ignores every other user's change.
- `(use-state :k init)` returns `[value set-value!]`: state of the island's own, kept while it is mounted.
  `set-value!` can be called from an action.
- `(observe & args f)` (`co.multiply.tropical.hooks`) reads a subscription, a poll loop or a query, shared by every
  reader of its call site and `args`: [Reading from outside the page](reading.md).
- `(use-action :k handler)` (`co.multiply.tropical.action`) returns a Datastar `@post(...)` expression, bound to a
  token that exists while the island renders it. `handler` gets the page's signals: [Actions](actions.md).
- `(use-session)` returns the session's `{:tab :uid :request}`: its tab, its user, and the request that created it,
  for what every island may need from the page's request, such as its path, `(-> (use-session) :request :uri)`
  ([Many pages](serving.md#many-pages)).
- `(use-patch-signals)` returns `(patch! signals)`, which patches the page's signals from any thread while the
  island is mounted, for work that lands after its action answered ([Actions](actions.md)).
- `(use-cookie state)` (`co.multiply.tropical.cookie`) and `(use-storage state)` (`co.multiply.tropical.storage`)
  take up state the browser owns, kept in a cookie or in its storage, and `(cookie/observe handle)` and
  `(storage/observe handle)` read the page's value, for what the server renders from it:
  [State the browser owns](browser-state.md).
- `(use-shared state key)` (`co.multiply.tropical.shared`) reads state the server keeps for every island reading it
  with that key, across sessions: [State shared on the server](shared-state.md).
- `(use-uploads :k opts)` (`co.multiply.tropical.upload`) takes files that the browser sends straight to storage:
  [Files from the browser](uploads.md).
- `(script/use script)` (`co.multiply.tropical.script`) has the page load a script, once:
  [Scripts the page needs](scripts.md).
- `(use-hold :k acquire)` is the primitive under `use-action` and `observe`: something acquired once and released
  when the island stops asking for it. A render asks for each key once. Asking twice throws, so two buttons can't end
  up sharing one action token. With `{:idempotent true}`, asking again returns what the first ask got: for a hold
  whose key is what it holds, such as a read.

## When a render throws

A render that throws renders an error view in the island's place, and the rest of the page goes on.

- **The failure is the island's.** Its children unmount, and its parent never sees the exception: each island renders
  on its own, so there is nothing for a parent to catch, as React's error boundaries do. An `AssertionError` from
  `assert` or a `:pre` counts too. Only what leaves the JVM in no state to go on, such as running out of memory, goes
  past the island and closes the session.
- **A failed island is still an island.** It renders again, as any island does, when its arguments change or
  something it read before it threw changes, and a render that succeeds takes the view's place.
- **The default view shows a reference, not the exception.** It says that a part of the page failed, with a reference
  such as `4KF7-Q2XM`, which the exception is logged under: an exception's message can carry what the user shouldn't
  see. The app's `:error-view` replaces the default ([Serving pages](serving.md#failures)), and an island's own
  replaces the app's for that island:

  ```clojure
  (defisland chart
    {:error-view (fn [{:keys [ref]}] [:p "The chart couldn't be drawn. Reference " ref "."])}
    [series]
    ...)
  ```

- **A view is a fn of the failure**, a map of the reference, `:ref`; the exception, `:error`; the island's qualified
  name, `:island`; its id, `:path`, and its element's, `:element-id`; its `:args`; and the session's
  `{:tab :uid :request}`, `:session`. What it returns reaches the browser. A view that throws gives way: an island's to
  the app's, and the app's to the default.
- **`try` handles a failure where it happens,** as in any function, such as a read whose source failed
  ([Reading from outside the page](reading.md)). The error view is for what nothing handled.

## Recursive islands

An island can call itself to place child islands. For a tree stored in `!nodes`, an atom mapping each node id to its
label and child ids:

```clojure
(defisland tree-node {:key identity}
  [id]
  (let [{:keys [label children]} (use-watch !nodes #(get % id))]
    [:li [:span label]
     (when (seq children)
       [:ul (map tree-node children)])]))
```

Each call to `tree-node` places a separate island, with its own state, reads, holds and actions. Sibling nodes need
distinct keys, and their ids are scoped under their parent as usual. Reading each node by id lets a label change
render only that node; removing a branch releases everything its nodes held. A plain recursive helper function
still runs within the calling island, so its hooks belong to that island.

## When to make an island

Not every component is an island. Make one for a component that:

- **has hooks**: it reads (`use-watch`, `observe`), holds state (`use-state`), or renders actions (`use-action`);
- **is a list's row that changes on its own**, such as a message: a keyed island renders, and is sent, alone;
- **is expensive to render, with arguments that rarely change**: an island whose arguments are unchanged isn't
  rendered again.

Everything presentational, such as buttons, badges, icons, layout and formatting, stays a plain function returning
hiccup, called inside islands.

Hooks work in a plain function called from an island's render, but they belong to the island that called it. A read
there renders that whole island again, and the whole island is sent. A helper called twice in one island repeats its
hook keys: a second `use-action` or `use-hold` throws, and a second `use-state` shares the first's state. So a
component with hooks is an island of its own.

Each island costs something per session: about 1.3 KB kept, and a first render about seven times a plain function's
([logbook](logbook/2026-10-07-what-an-island-costs.md)). What it pays back is work and size. Of 1,000 rows, one that
changed is rendered alone as an island of its own, and goes out as 71 chars; as part of one island, the whole list is
rendered again, and goes out as 55,909 chars. Too few islands render and send more than changed; too many cost memory in
every session. Roughly: a root island per page, an island per card or section that reads data, keyed islands for rows
that change on their own, and plain functions for the rest.

What changes should be small:

- **A text input belongs in an island that reads nothing that ticks.** Each time the island is sent, Datastar morphs
  it back to what the server rendered, what the user is typing included.
- **A list sends only the rows it gains or loses**, inserted next to a kept sibling or removed by id, when the rows are
  the islands' own root elements, `[:ul (map message msgs)]` rather than `[:li (message m)]`, and nothing else in the
  list's markup changes with them. Put what changes with the list, such as a count, in a sibling island.
- **A row that changes often reads its own data.** A list island that reads every message renders again over all of
  them for each change to one, though only the changed one is sent. Have the list read the ids, and each row its own
  content.
