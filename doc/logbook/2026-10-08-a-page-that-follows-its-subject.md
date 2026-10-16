# 2026-10-08 — A page that follows its subject

The demo's topic page renders `navigate-element` with `{:replace true}` once its topic's page has moved (`demo.state/move!`), and the topic route redirects a page loaded at a moved topic's old URL. In Chrome, under the
demo's CSP, with two tabs on `/topic/alpha`, each marked with a global from the console:

- `(move! "alpha" "alpha-2")` from the REPL took both tabs to `/topic/alpha-2`, as page loads (the mark gone), with
  their history lengths unchanged: the entry was replaced. The new pages had no navigating element.
- Moving it back took both tabs home, each navigating 3 and 9 ms after `move!` returned, by
  `performance.timeOrigin`, so as soon as the frame arrived.
- Loading `/topic/alpha` while it was at `alpha-2` arrived at `/topic/alpha-2`, with one redirect.
- The element's script for `/topic/ö#x`, run on a page at `/topic/%C3%B6`, found the page already there and stayed:
  the mark survived.
- That a later render of the same element doesn't run it again is from reading Datastar 1.0.4, not observed: it
  applies an element's plugins when the element is added and when one of its `data-*` attributes changes, and its
  morph sets only attributes that differ.
