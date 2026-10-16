# State the browser owns

Some state changes in the browser without a round trip, and outlasts a page load. Where the browser keeps it decides
what the server knows of it, so each store has a namespace of its own:

|                    | `co.multiply.tropical.cookie`                        | `co.multiply.tropical.storage`                   |
|--------------------|------------------------------------------------------|--------------------------------------------------|
| For                | whether a section is open, a sidebar folded          | a draft the user is writing                      |
| Kept in            | a cookie                                             | `localStorage`                                   |
| The server sees it | as the session begins, so it renders the first frame | when an action passes it, or an island reads it  |
| An island reads it | from the first frame on, as it changes (`observe`)   | after the first frame, as it changes (`observe`) |
| Values             | booleans, numbers and strings, about 3.5 KB in all   | JSON, of any size                                |
| Sent               | with every request                                   | only as an action's value                        |
| Per user           | no                                                   | yes                                              |
| Dropped            | the oldest first, past the cookie's size             | 30 days after it was last written, by default    |

Both work the same way in the page:

- **A state is a Datastar signal**, local to the page, which an island declares on its root only if it is missing
  (`data-signals__ifmissing`), so a later render, a patch or the island mounting again never undoes what the user did.
- **The island gets a handle** for the helpers in `co.multiply.tropical.ui` ([The `ui` helpers](#the-ui-helpers)).
- **The page writes each change into the store.** The element the app mounts in, `(ring/mount :body attrs)`, carries
  `ui/page-attrs`, whose handler does it ([Serving pages](serving.md)).

## In a cookie

```clojure
(defcookie group-open
  "Whether a sidebar group is open, per group."
  {:default true :valid? boolean?})

(defisland sidebar-group
  {:key :id}
  [group]
  (let [open (use-cookie group-open (:id group))]
    [:section
     [:button (ui/aria-expanded {:data-on:click (ui/toggle open)} open) (:name group)]
     [:ul (ui/shown open) ...]]))
```

- **The var is the state.** `defcookie` declares it, with a default and what a value must be, wherever the code using
  it lives: next to one island, or in a namespace several share. Any island in the page takes it up with `use-cookie`,
  with an item, such as a group's id, for one per item. Islands using one state follow one signal.
- **Right in the first frame.** The server reads the cookie of the request that created the session, validated per
  state. `ui/shown` and `ui/aria-expanded` render the plain attribute from it, and bind it to the signal; `ui/signal`
  gives the signal for expressions of your own, such as a pane's width in `data-style`.
- **`cookie/value` is for the first paint only.** It is the session's value, and the browser's may have moved on
  since, so bind what must follow it, or read it with `cookie/observe`.
- **Kept whole across tabs.** The body's handler writes each change into the cookie, merged, so tabs don't undo one
  another. It keeps only values other than their default, and stays under 4 KB.
- **Read when the server renders from it.** `ui/shown` only hides: the server goes on rendering what the element
  holds, and the islands in it go on reading. `(cookie/observe open)` is the page's value, for an island that places
  that content only while it is shown:

  ```clojure
  (let [open (use-cookie resources-open)]
    [:section
     [:button (ui/aria-expanded {:data-on:click (ui/toggle open)} open) "Resources"]
     [:div (ui/shown open)
      (when (cookie/observe open) (resource-list))]])
  ```

  It is the session's value in the first frame, then the page's, which the browser sends as it changes, while the
  island reads it. A folded panel then reads nothing, and costs the server nothing. Shown again, the element shows at
  once, and its content a round trip later, so keep what is cheap to keep live under `ui/shown` alone. Each tab has
  its own value: tabs share the cookie, but each session renders what its tab shows.
- **Set by an action.** A handler returns `(cookie/patch [[group-open id false]])`, merged with its own signals: the
  islands using the state follow, and the cookie keeps it, for an item the page doesn't render too. Values are checked
  as the handler builds its result, and an invalid one throws.
- **Set before a page it opens.** For a new item's page, with a choice made before the item existed, the handler
  passes the same signals to `navigate`:
  `(action/navigate (str "/groups/" id) {:signals (cookie/patch [[group-open id false]])})` has the cookie written
  before the page loads, so its first frame has them.

## In the browser's storage

```clojure
(defstorage edits
  "Edits to a suggestion, before the user acts on it."
  {:default {} :valid? map?})

(defisland rework
  [{:keys [id text]}]
  (let [draft (use-storage edits id {:default {"text" text}})
        start (use-action :start
                (fn [_ value]
                  (when-some [{t "text"} (storage/decode edits value)]
                    (start! id t))
                  (storage/forget [[edits id]]))
                {:value (ui/signal draft)})]
    [:form
     [:textarea {:data-bind (ui/path draft "text")}]
     [:button {:type "button" :data-on:click start} "Start"]]))
```

- **The server stores none of it.** The browser keeps a value through closing what shows it, a page load, and the
  server restarting or being deployed again, in the one browser. An action takes it as its value, as `start` does, and
  `storage/decode` reads it back: through the state's `:decode`, and nil unless `:valid?`, since it is untrusted.
- **Declared from the browser's entry.** `use-storage`, with an item for one per item, declares the signal from the
  browser's entry, or from the default if there is none. A default of the island's own, `{:default v}`, is the item's
  starting point, such as the suggestion's text, and the browser keeps an entry only while the value differs from it:
  only edits are stored, and a default the server revises shows through where the user changed nothing.
- **Shown by bindings.** The server doesn't know the browser's value, so the first frame renders the default, and the
  browser fills in its own as the page starts. Bind what shows it: `data-bind` takes `ui/path`, a path into the value,
  and expressions take `ui/signal`.
- **Read when the server must render from it.** `(storage/observe draft)` returns `pending`, then the browser's value,
  decoded, and the latest each time the user stops typing, while the island reads it: for what only the server can
  render, such as a row per item, or a count. The browser sends it once the page has the island, so the first frame
  never has it, and doesn't wait for it.
- **JSON, both ways.** The browser changes values itself, in expressions and bindings, so they are JSON. `:encode`
  makes one from a Clojure value, and `:decode` reads one back; `defstorage` checks that its default comes back as it
  went, so a lossy mapping, such as keywords without a `:decode`, fails as it is declared. A value only ever
  round-tripped can be a string, such as Transit, at the cost of binding to it.
- **The user's own, and bounded.** An entry's key holds a digest of the session's user, and a page loaded for another
  user drops it, as it drops entries older than the state's `:max-age-ms`, stored under another `:version` (change it
  when the values' shape changes), or of a state no longer declared. When the storage is full, the oldest go first.
- **Set and dropped by an action.** A handler returns `(storage/patch [[edits id v]])` or
  `(storage/forget [[edits id]])`, merged with its own signals, and the browser that made the action keeps or drops
  the entries, whether or not the page uses them. An island using a forgotten entry goes back to the default it
  declared.
- **Dropped before a page it opens.** An action that opens another page passes them to `navigate`'s `:signals`, so a
  draft the action used up is gone before that page loads:
  `(action/navigate (str "/projects/" id) {:signals (storage/forget [[edits suggestion-id]])})`.

## The `ui` helpers

`ui/signal`, `ui/path`, `ui/toggle`, `ui/shown` and `ui/aria-expanded` take a handle of either store. `ui/attr`
takes any expression, over the handles' signals or the page's own, and `ui/json` puts a value of the server's into one.

- **Any attribute, over any expression.** `(ui/attr attrs k value expr)` is what `shown` and `aria-expanded` are built
  on. The server renders `value`, its reckoning of the expression, for the first paint. Datastar then sets the attribute
  `k` from the expression `expr`, and keeps it as the browser has it when the island is sent again (`data-attr`,
  `data-preserve-attr`). An `aria-*` state gets the words `true` and `false`, where Datastar would leave a boolean's
  `true` empty, which counts as no state. Thread several onto one element, rather than `merge` them, so each stays
  preserved:

  ```clojure
  [:button (-> {:role "tab" :data-on:click "$tab = 'files'"}
             (ui/attr :aria-selected (= tab :files) "$tab === 'files'")
             (ui/attr :tabindex (if (= tab :files) 0 -1) "$tab === 'files' ? 0 : -1"))
   "Files"]
  [:button (ui/attr {:type "submit"} :disabled (= draft saved) "$draft === $saved") "Save"]
  ```

  Text that follows an expression needs no helper: `[:span {:data-text expr} first-paint-text]`, and Datastar puts the
  browser's text back when the island is sent again, before the page paints.
- **A handle's signal.** `ui/signal` names it in an expression, `ui/path` in a `data-bind`, and `ui/toggle` flips a
  boolean one.
- **A value of the server's, in an expression.** `ui/json` writes it as a JavaScript literal, `(str "$tab = " (ui/json
  tab))`, where a value spliced in as it is written could become code ([Security](security.md)).
