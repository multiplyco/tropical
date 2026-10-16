# 2026-10-08 — UI state the browser owns: a prototype

`demo.ui`: signals under `_ui` declared on the page's body from a cookie, an effect on the body writing them back,
and islands rendering the plain attribute for the first paint while binding it to the signal (`data-attr`). The
demo's Server resources table and the vault panel's messages fold away. Tried in Chrome, under the demo's CSP:

- The effect writes the cookie, `JSON.stringify($_ui)` reading through Datastar's signals, and the next page load
  was server-rendered folded, with `_ui` declared from the cookie.
- Folded against the session's own copy, the resources table stayed folded through every patch of its card (49
  mutations in the card over two topic switches), its button reading "Show" with `aria-expanded` false.
- The case `data-preserve-attr` alone can't hold: folded, then access revoked and granted again, the messages came
  back as a new element, folded, though the session's copy said open.
- What keeps an attribute right is `data-attr`: Datastar's attribute binding watches the attribute it sets and sets
  it again when something else changes it. Without `data-preserve-attr`, seven patches set the stale `hidden` on the
  table 14 times, each undone before the page settled; with it, none. So `data-preserve-attr` only spares the churn.
- Validation: a value that isn't valid for its key reads as the default, and a key not declared is dropped.
