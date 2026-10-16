# 2026-10-08 — UI state in the library

`co.multiply.tropical.ui` replaces the prototype: `defstate` for a state, `use` in any island, which declares the
state's signal on the island's root with `data-signals__ifmissing`, and `page-attrs`, a `data-on-signal-patch` handler
on the body that keeps the cookie. Read in Datastar 1.0.4's bundle first:

- `data-signals__ifmissing` merges per leaf: it sets only the signals missing, at any depth.
- `$a.b` in an expression becomes `$['a']['b']`, a lookup on the reactive root, and a following `["x"]` stays member
  access on it. Item keys go in brackets as JSON strings, which keeps any id safe: `$a.b-c` would split on the `-`.
- `data-on-signal-patch` runs with `patch`, the changed signals that pass `data-on-signal-patch-filter`. Declarations
  patch signals too, which is why the handler drops values equal to their state's default. An expression starting with
  an arrow function is taken for a function, so the handler is `void (() => {...})()`.

Live, in Chrome, under the demo's CSP, no console messages:

- The resources card declared its state on its root, and the table stayed folded through every patch (67 mutations of
  the card, none of them to `hidden`).
- After a reload, the card declared `false`, read from the cookie, and the table was server-rendered folded.
- Folded, then access revoked and granted again: the vault panel came back as a new element declaring the session's
  stale `true`, and its messages stayed folded.
- Item signals, on elements put in the page by hand: `$_ui.t["a-b/c"]` toggled the bound attribute, and an element put
  back declaring the stale value kept the browser's.
- The cookie the browser then sent, captured on the server, held only `{"demo_page__resources_open":false}`: the
  messages, back at their default, were dropped, and so were the prototype's keys, no longer declared.
