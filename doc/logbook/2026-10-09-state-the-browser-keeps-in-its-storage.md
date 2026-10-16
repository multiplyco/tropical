# 2026-10-09 — State the browser keeps in its storage

`co.multiply.tropical.storage`, beside the cookie's state, now `co.multiply.tropical.cookie`. Read from the Datastar
1.0.4 bundle before building it, and checked where noted:

- A server patch setting a signal to null deletes it through the store's proxy, whose delete makes no patch event, so
  `data-on-signal-patch` never hears it. An expression setting one to null does make one. So `forget` patches a
  signal of its own, `_storageForget`, and the handler clears it once done: patched to `true` again while still
  `true`, it would change nothing, and make no event.
- Reading a missing path through `$` creates it, as an empty string, with a patch event. The handler steps into
  `_storage` with `in` before reading.
- The patch a handler gets carries the current value at each changed path, not the patch's own: `data-on-signal-patch`
  filters the event's paths, then reads each from the store.
- A `data-bind` write patches the store as any write does, so the handler hears every keystroke.
- On load, Datastar applies `document.documentElement`, then every element in document order, so the body's handler
  listens before the first frame's islands declare their signals.
- The statement pattern Datastar uses before returning an expression's last statement takes `\"` for an escaped quote
  wherever it is, so the end of `"a\\"` doesn't end the string, and a `;` in a later string becomes a split. JSON in
  expressions now writes a backslash as `\`, and the entry's declaration has no `;` at all. A run of the port,
  in Node, of a declaration whose default held `"})(); x\"`: one statement.

The handler's code ran in Node first, over plain objects for the store and a map for `localStorage`: a value equal to
its declared default kept no entry, an edit did; a watch sent at once, and a change 300 ms after the last; forget
dropped the entry, set the value back to its default and told the watch, which a 404 then dropped; the start-up pass
kept only the user's live entries; and with storage full, a write dropped the oldest of Tropical's entries until it
fit.

The demo's Drafts card, in Chrome, in a tab in the background:

- Each row counted its draft on the server, "Not reworked yet", from the value the browser sent once the page had the
  row: the server rendered it, and nothing was stored.
- Rework opened the dialog with the defaults. A section typed into, and a chip picked, which added its line to the
  note and pressed itself: the row read "A draft of 21 words", and the entry held the edit.
- Closed and opened again, a reload, and a restart of the server with every session closed, the tab reconnecting:
  the fields were as typed and the chip pressed each time, and the rows counted 21 words in the new session.
- After the restart, the page held four watches: the closed session's two, and the new one's. Typing again sent to
  both on that entry, and the old one's 404 dropped it.
- Start posted the draft as its action's value: "Started “Plan the launch”, 24 words." The entry was gone, the signal
  back to its default, and `_storageForget` cleared.
- A second proposal, edited and closed, then dismissed from its row: the entry was gone. Brought back, it read "Not
  reworked yet".
- Entries planted by hand, then a reload: another user's, one older than 30 days, one of version 0, and one of a
  state never declared were dropped; the user's own, and a key not Tropical's, were kept, and the kept draft loaded.
- Folding the vault's messages wrote `{"demo_page__messages_open": false}` to the cookie, from `_cookie`, and
  unfolding left it empty.
- Post's request carried `note` alone: no `_storage` or `_cookie` signal goes with an action.
- No console errors.
