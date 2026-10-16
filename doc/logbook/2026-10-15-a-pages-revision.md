# 2026-10-15 — A page's revision

Datastar 1.0.4 copies a request's headers once, as `@get` starts it. In the bundle the demo loads, a retry rebuilds
the URL, with the signals in its query, and sends the headers as they were, `last-event-id` added, as does a hidden
page shown again. So what a page sends with its stream can't change after it opens it: a list of the scripts in its
head, read as the stream opens, would lack one patched in later on the reconnect after a restart. The revision
covers every script instead.

Cost of `ring/revision-of`, from the REPL in the dev JVM, warm, over 20000 calls:

- 15 µs a call while it rendered tropical's code in the page on each: about 10 µs rendering it, 2 µs joining the
  parts, 3 µs the SHA-256 of their 6 KB. Joined with `pr-str` before that, 430 µs, as it prints a string a character
  at a time.
- 2 µs with that code's digest kept until the cookie and storage states declared change.

Checked in Chrome on the home page. With what the demo's head loads changed from the REPL, Tailwind's URL given a
query, `session/close-all!` reloaded the tab once, into a page of the new revision, whose stream attached. With
nothing changed, `close-all!` rebuilt it in place, and a value set on `window` before stayed.

`sse-test/await-returns-once-closed` failed once in a full run: the stream still open after the task in `await` was
cancelled (line 125). It passed in three runs of its namespace after. It checks as soon as `cancel` settles, while
the interrupted thread closes the stream: a race in the test, not in the stream.

Then an action that reaches a server of a new release, made from the REPL as in the 2026-10-13 entries: for the test
tab only, `action/invoke!` answered `[:gone]` and `session/held?` false, and the head's URL changed. A click was
answered with the reopen, the reopened stream with the reload, and the page loaded into the new revision, whose
stream attached: four requests, the click dropped, as a reopen drops it.

The extension's network log showed the two POSTs a page of the demo makes as it loads as 503. The server answered
both with 204, by a wrapper over the handler, and the page's resource timing has 204 for both: the log mislabels
them.
