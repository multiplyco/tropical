# 2026-10-09 — What a morph leaves to Datastar's bindings

Asked for: attributes and text that follow the browser from the first paint, on the premise that a render puts back
the server's text under `data-text`, which an app had worked around with a preserved attribute shown through CSS
`content: attr(...)`. Measured in Chrome against Datastar 1.0.4, on the demo page, by dispatching the
`datastar-fetch` event its patch watcher listens for, with an element as the server would send it:

- `data-text` and `data-attr` each watch what they set, and set it again when a morph changes it. A note bound with
  `data-text` read "Copied" after the browser set its signal, and still did after a morph sending the server's empty
  span, and after one restructuring the elements around it. `hidden` bound with `data-attr` was the browser's after
  the morph too. So the premise was wrong: text needs no helper.
- Without `data-preserve-attr`, the morph does write the server's value: `hidden` was set right after the patch, and
  the binding's observer removed it again before anything painted. A field focused inside the panel stayed focused.
  With it, the morph wrote nothing at all, so preserving stays, for an element no other observer sees flip.
- `data-attr:aria-selected` with an expression that's `true` writes `aria-selected=""`, which counts as no state, so
  `aria-selected:` styles don't apply: the trap the app fell into. `ui/attr`'s expression turned `true`, `false`, a
  string and `null` into `"true"`, `"false"`, the string, and no attribute, and a morph changed none of them.
- A signal set to `null` is deleted, and an expression reading only it stops following it: later values reached a
  `data-text` that also read another signal, but not a `data-attr` reading that one alone. So nil is for an
  expression to return, not for a signal to hold.

The demo, after a restart for the Java change (`Splice`), in a tab of its own:

- The resources card's first paint, fetched: Registry selected (`aria-selected="true"`, `tabindex="0"`), Counts not (`"false"`, `-1`), the counts' list hidden and the table not, each preserving both attributes; Post disabled.
- Counts, clicked: selected, styled by `aria-selected:` (the indigo background), its list shown. The card, then
  patched twice from the REPL, changed 8 text nodes and no attribute: the tabs stayed as clicked.
- Post was enabled by "hello", disabled again by spaces and by an empty message, all in the browser.
- Suggest showed "Suggesting…", disabled, with what was typed kept through that render; the suggestion then replaced
  it, and Post was enabled. In a tab in the foreground, over three runs, "Suggesting…" showed 12 to 34 ms after the
  click and the suggestion landed 1,562 to 1,592 ms after it, against the server's 1,500 ms wait. In a tab in the
  background, Chrome holds timers to about a second, so the page's own timings there say nothing: a first try read
  the suggestion as landing within 350 ms.
- The definition in the Raw HTML card, an island keyed by its term: the first term sent the card once, since
  "Click a term." became an island; "An example?" sent the definition alone, 283 chars; another term was inserted
  after the old definition, which was then removed, starting over with its example hidden. The card wasn't sent, and
  the passage was the same node throughout.
