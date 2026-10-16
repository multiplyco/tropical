# 2026-10-08 — Squint, spiked

Could Datastar expressions be written as Clojure forms, and a page's script as a Squint file? Tried in the dev JVM with
Squint 0.14.211 (from Clojars, added at runtime), on a page served next to the demo under its CSP (nonce,
`'strict-dynamic'`, no `'unsafe-eval'`) and Datastar 1.0.4, in Chrome.

- A `js` macro read its forms as data, as the JVM reads any quoted form, and compiled them with
  `squint.compiler/compile*` (`:context :expr`, imports elided) when it expanded: about 85 µs a form, once. Several
  forms were joined as statements; `~x` became a hole filled with `x` as JSON at render, or as it was if marked raw.
- What ran in the page: `(set! $n (inc $n))`; `str`, as a template literal; `if`, through Squint's core;
  `and`, `let` and `do`, each a function called in place, which Datastar's statement splitter keeps whole;
  `data-show` and `data-attr`; `el` and `evt`; a server value through `~`, following the server when it changed; and
  `(set! $busy true) ~(raw post)`, whose button stayed disabled until the reply set `$busy` back.
- Squint's core (`squint/core.js` in its jar, 128 KB, 33 KB gzipped) was served from the page's origin, and a module
  ahead of Datastar's imported it and set the global, `squint_core`, that compiled expressions refer to. It was there
  for the first `data-init`.
- A web component in Squint (`defclass` extending `HTMLElement`, a scroll listener throttled by animation frames)
  compiled in 0.8 ms to an ES module. `:import-maps` points its import of core at the served URL, the same one the
  global came from, so the page held one copy of core, and needed no import map.
- `#js` doesn't read on the JVM, and wasn't needed: Squint's maps are JS objects. `if`, `when`, `and` and `or` follow
  Clojure's truthiness, so a signal holding 0 or `""` is true to them, where it is false to Datastar's own JS.

Delivering the component's module, for the script support to follow:

- A `<script type="module">` in a patched island got the page's nonce from Datastar and ran. Three tags for one URL
  ran the module once, and removing the islands and patching them back in ran it no more: the browser keeps a module
  per URL for the life of the page. A module that first arrived by a patch also upgraded the element already in the
  page.
- The same tag in the first frame, without the nonce, was blocked, and so was every later tag for its URL, though
  those carried the nonce: a module that failed to load stays failed for the page. The first page's scripts need the
  nonce, from the page itself.
- A re-render of the island holding the component emptied it: the morph matched its children to the server's empty
  element. Drawing into a shadow root, which the morph doesn't reach, kept it through two re-renders, the element
  itself kept.
- Editing the component gave its module a new URL, which loaded, but a name stays defined as it first was (`customElements.define` throws on a second definition), so a change shows on the next page load.
