# 2026-10-09 — A click soon after a load, in a hidden tab

Looked like a lost click, and wasn't. Reported from browser automation: in a tab reading `visibilityState` `hidden`,
a click on a button whose action opens an island's dialog, about a second after the page loaded, showed no dialog
1.5 s later and no error; a second click opened it. The page's resource timings had a fetch to its own URL about
every 10 s.

Datastar 1.0.4's `@get` takes `openWhenHidden`, false by default, and `stream-init` doesn't pass it. It starts the
fetch at once, hidden or not; on `visibilitychange` to hidden it aborts it, and it fetches again once the page is
shown. In the demo, in Chrome:

- Loaded and kept hidden, the page held its stream: no `visibilitychange` came. A click 1.6 s after the load opened
  the dialog.
- Hidden after the load: the stream closed. The click's action ran on the server, and the dialog appeared when the
  tab was shown and the stream resynced. Shown again after more than the session's grace (15 s), the session had
  closed, and the tab reconnected into a new one without the dialog open.
- So the report fits a page hidden and shown again by the automation, each hide closing the stream. The 10 s fetches
  are likely those reopenings; it wasn't reproduced that far.

Chrome hides a window behind others, and throttles its timers to about once a second. Launched with
`--disable-backgrounding-occluded-windows --disable-renderer-backgrounding --disable-background-timer-throttling`,
a window behind others, unfocused, read `visible`, and ten 100 ms timeouts took 1,008 ms. The flags don't reach a
minimized window or a tab that isn't its window's selected one: the automation's window had opened minimized, its
outer size 0×0, and read `hidden` until it was restored and the tab selected. Then a click 880 ms after a load
showed the dialog 26 ms later.

`stream-init`'s `:open-when-hidden` passes `openWhenHidden: true`, for automation in development. Production keeps
Datastar's default (design notes, Sessions).
