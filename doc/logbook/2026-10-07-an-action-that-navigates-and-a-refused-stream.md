# 2026-10-07 — An action that navigates, and a refused stream

Read in Datastar 1.0.4's bundle, not run:

- A `text/javascript` response to an action is run as a script, with attributes from a `datastar-script-attributes`
  header. In CSP mode, Datastar gives that script, and every `<script>` it patches in, the page's nonce.
- Under `retry: 'always'`, the fetch retries every status but 200, 204 and 3xx, with backoff, up to `retryMaxCount`
  (10). A 200 stream that ends is retried too, after `retryInterval` (1 s) at first. This is why the reload guard spares
  the page load it told to reload: a reload slower than a second lets that load retry first.
- `data-ignore-morph` skips an element only when both the element in the page and the incoming one carry it. A patch
  can't keep an existing child by sending a placeholder for it, so a list sends added children with Datastar's
  `after`/`before` modes instead.

Live, in Chrome, under the demo's CSP without `'unsafe-eval'`:

- "Another topic" opened another topic's page, pushed onto the tab's history.
- On `/topic/gamma`, with gamma removed from the topics and the tab's session closed, the stream reconnected, the
  topic route refused it with a 404, and the tab reloaded (`performance` navigation type `reload`) into the not-found
  page.
- With the handler swapped in the REPL for one refusing only `/topic/alpha`'s stream, the page reloaded once, then the
  guard held. Direct fetches confirmed it: the load told to reload got the reload stream again, another load a 204.

Looked like a bug, wasn't: the extension's network list reported the guard's 204 as 503, as it did with actions in the
`request-kind` entry. A direct fetch got the 204.
