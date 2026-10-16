# 2026-10-08 — A value from the click

`use-action`'s `:value` puts the value in the action's body with the signals, through Datastar's `payload`, which
replaces the signals. The expression puts back the signals `@post` would have sent, filtering out keys starting with
`_` in a `JSON.stringify` replacer, read inside `@peek`.

- On the demo page, the replacer's output equalled Datastar's own `filtered({exclude: /(^|\.)_/})` (its export, from
  the module the page loaded), which leaves out the footer's `_wire` and the `_ui` states. Where they would differ,
  an object holding only local signals (`{}` from the replacer, nothing from Datastar) and objects inside arrays (filtered by the replacer, a leaf to Datastar), no signal of the demo's is.
- In Chrome, under the demo's CSP, with `window.fetch` wrapped to record what the page posted: a click on a term in
  the raw-HTML card posted `{"signals":{"note":""},"value":{"term":"signal"}}`, and the card showed the definition. A
  term changed to one the passage doesn't have, in the DOM, was posted and ignored. A topic button posted
  `{"note":""}`, as before, and the page's signals were the same after the clicks as before them.
