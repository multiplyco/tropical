# Scripts the page needs

Some of a page has to run in the browser: an outline that follows the reader as they scroll, a chart, a clock.
`co.multiply.tropical.script` loads such code as an ES module, once per page, for the islands that need it.

```clojure
(script/defscript outline
  "The page's outline, which follows the reader as they scroll."
  {:resource "app/outline.js"})

(defisland article-outline
  []
  (script/use outline)
  [:nav [:page-outline]])
```

- **Declared where it's used.** An island takes a script up with `script/use` during its render. The page loads it
  from the first frame in which an island declares it: in the page's head if the first frame has it, or appended to it
  over the stream when such an island is patched in, with the page's nonce, which Datastar gives patched script tags.
  A module runs once per page, however many islands use it.
- **Mounting.** Wrap the app in `ring/wrap-scripts`, outside the routes and the session middleware. It serves each
  script under `/tropical/`, at a URL named by its var and the hash of its content, which browsers keep for good. The
  page puts the first frame's scripts in its head with the CSP nonce: `[:head … (ring/scripts nonce)]`, or
  `(script/tags scripts nonce)` given the `:scripts` its `:page` gets, and the stream doesn't send them again. A page
  that leaves them out still gets them over the stream, a little later.
  One that renders them without the nonce breaks them: a module blocked once stays failed for the page.
- **Don't draw into the island.** The island's next render matches its elements' children to the server's, undoing
  what a script put there. A web component draws into its shadow root, which the morph doesn't reach.
- **A page keeps what it loaded.** An edited script gets a new URL, but `customElements.define` won't redefine a name,
  so an edited component shows on the next page load. In development, a script whose resource is a file is read again
  when the file changes.
