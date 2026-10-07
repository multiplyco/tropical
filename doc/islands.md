# Islands

An island is a function returning hiccup, defined with `defisland`: the unit that renders, the unit sent to the
browser, and the owner of what it holds. This guide covers writing them, the hooks they use, and where to draw their
boundaries.

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

- **An island is a function of its arguments.** Calling one inside another island's hiccup places it there; the runtime
  decides whether it renders. It renders when it is new, when its arguments changed (`=`), or when something it read
  changed. Otherwise its last output is reused.
- **Ids are scoped by parent, as React keys are.** An island's slot is its name, unique among its siblings. For several
  instances under one parent, give a `:key` fn of the arguments: `(defisland message {:key :n} [msg] ...)` gives the
  slot `message.17`. The island's id is the path of slots from the root, `app/vault-card/messages/message.17`, so an
  island can be placed anywhere, any number of times. Its element, the root element the island returns, gets an opaque
  digest of it, which Datastar patches ([how it works](how-it-works.md)). Any key works: characters other than letters,
  digits and `-` are escaped.
- **The root element's id is the island's.** An island that sets one on its root fails, and one whose root isn't an
  element is wrapped in a `:div`. An element the app needs to find, from a script or a label's `for`, gets an id of its
  own, inside.
- **A key is the island's identity, as in React.** An island under a new key is a new one: it starts over, its state,
  holds and actions fresh, and the old one unmounts. So to start an island over, such as a dialog opened again, change
  its key: `{:key :opened-at}`. Only the new island is sent, in the old one's place, not its parent.
- **Hooks take a key, not a call order.** Each hook names what it is, so conditionals and loops around hooks are fine
  (`co.multiply.tropical.island`, unless noted):
  - `(use-watch ref)` or `(use-watch ref select)` reads an atom (anything watchable), or a signal. The island renders
    again when the selected value changes, so `#(allowed? % uid :vault)` ignores every other user's change.
  - `(use-state :k init)` returns `[value set-value!]`: island-local state, kept while the island is mounted.
    `set-value!` can be called from an action.
  - `(use-action :k handler)` (`co.multiply.tropical.action`) returns a Datastar `@post(...)` expression, bound to a
    token that exists while the island renders it. `handler` gets the client's signals, and returns a map of signals to
    patch into the client, nil, or `(action/navigate "/items/42")` to open a page once it is done. With
    `{:value js}`, a JavaScript expression evaluated when the action is invoked, `handler` also gets its value:
    `(handler signals value)`. See [Actions](actions.md).
  - `(use-uploads :k opts)` (`co.multiply.tropical.upload`) takes files that the browser sends straight to storage:
    [Files from the browser](uploads.md).
  - `(use-shared state key)` (`co.multiply.tropical.shared`) reads state the server keeps for every island reading it
    with that key, across sessions: [State shared on the server](shared-state.md).
  - `(use-cookie state)` (`co.multiply.tropical.cookie`) and `(use-storage state)` (`co.multiply.tropical.storage`)
    take up state the browser owns, kept in a cookie or in its storage, and `(storage/observe handle)` reads the
    browser's value of the latter: [State the browser owns](browser-state.md).
  - `(use-session)` returns the session's `{:tab :uid :request}`: its tab, its user, and the request that created it,
    for what every island may need from the page's request, such as its path, `(-> (use-session) :request :uri)`
    ([Many pages](serving.md#many-pages)).
  - `(use-patch-signals)` returns `(patch! signals)`, which patches the page's signals from any thread while the
    island is mounted, as an action's answer does: for work that lands after its action answered
    ([Actions](actions.md)).
  - `(use-hold :k acquire)` is the primitive under `use-action` and `observe`: something acquired once and released
    when the island stops asking for it. A render asks for each key once. Asking twice throws, so two buttons can't end
    up sharing one action token. With `{:idempotent true}`, asking again returns what the first ask got: for a hold
    whose key is what it holds, such as a read.
- **Reading from outside the page** goes through `observe`: [Reading from outside the page](reading.md).
- **The gate is `if`.** A branch that isn't rendered holds nothing and exposes no actions.
- **A render is a plain call on the session's thread.** A render that blocks stalls its own session, so slow work
  belongs in `observe`. A task started in a render belongs to the session, not the island, and isn't cancelled when the
  island unmounts: what should live as long as the island is a hold, or a resource.

The same rules as React apply, and the same mistakes are possible:

- A value read without a hook (a bare `@atom`) is not tracked, so the island will not render again when it changes.
- Passing a fresh closure or collection as an argument makes it unequal on every render, so the child renders every
  time. Pass data.
- The work is slicing islands at the right size: [When to make an island](#when-to-make-an-island).

## When to make an island

Not every component is an island. An island is a boundary: the unit that renders again, the unit sent to the browser,
and the owner of what it reads and holds. Make one for a component that:

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
([logbook](logbook.md)). What it pays back is the patch: of 1,000 rows, one that changed went out as 71 chars as an
island of its own, and as the whole 55,909-char list as part of one. Too few islands make large patches, and Datastar
morphs everything around a change; too many cost memory in every session. Roughly: a root island per page, an island
per card or section that reads data, keyed islands for rows that change on their own, and plain functions for the
rest.

What changes should be small:

- **A text input belongs in an island that reads nothing that ticks.** Each render morphs the island back to what the
  server rendered, and with it what the user is typing.
- **A list sends only the rows it gains or loses**, inserted next to a kept sibling or removed by id, when the rows are
  the islands' own root elements, `[:ul (map message msgs)]` rather than `[:li (message m)]`, and nothing else in the
  list's markup changes with them. Put what changes with the list, such as a count, in a sibling island.
- **A row that changes often reads its own data.** A list island that reads every message renders again over all of
  them for each change to one, though only the changed one is sent. Have the list read the ids, and each row its own
  content.
