# 2026-10-09 — Signals ahead of a navigation

`navigate`'s `:signals`: an action that opens a page answers with events, `datastar-patch-signals` with the signals,
then a script appended to the body, which opens the page and removes itself, in place of a `text/javascript` answer.
Datastar handles an answer's events in order, and the body's signal-patch handler runs as the patch lands, so what it
writes is written before the script runs. In Chrome, in the demo:

- The Drafts dialog's "Start, then open alpha", with a section edited and its entry stored: the tab was on
  `/topic/alpha` with the toast "Started “Plan the launch”, 12 words.", and `localStorage` held no entry. Back on `/`,
  the row read "Not reworked yet", and the dialog showed the proposal's own text.
- "…with its summary hidden", its cookie state now in `:signals` (`cookie/patch`) rather than `:cookie`: alpha's page
  showed "Show the summary", and its HTML, fetched again with the tab's cookie, had the summary hidden in the first
  frame. So the cookie was written before the page loaded.
