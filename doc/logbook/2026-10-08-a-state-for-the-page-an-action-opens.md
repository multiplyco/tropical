# 2026-10-08 — A state for the page an action opens, and a pick taken whole

`action/navigate`'s `:ui` writes UI states into the cookie before the page it opens loads, with the code the body's
handler runs, factored out to share it; `ui/patch` returns them as signals. `use-uploads`'s `:settled` gets a pick
once nothing is on its way. In Chrome, under the demo's CSP, against `demo.bucket`:

- The first shared version broke the body's handler, and every test passed. It had become `((patch) => {...})(patch)`,
  and Datastar 1.0.4, for an attribute whose expression returns a value, splits the expression into statements at
  each `;` outside strings, regex literals and an arrow function taking no arguments, called with none, then puts
  `return (` before the last. So it cut the function apart, and put `return (` inside the `while` that keeps the
  cookie under its cap. In CSP mode a function that fails to compile is reported as `GenerateExpression`, "Blocked by
  CSP.": the page compiles expressions in a script with its nonce, and the script didn't parse. The handler wrote no
  cookie, for the browser's own toggles as for patches. Restored to `void (() => {...})()`, reading the handler's
  `patch`; the navigation's script wraps the same statements in a function of its own. A test ports Datastar's
  statement regex and checks the handler is one statement.
- "…with its summary hidden" on `/`, with gamma picked, opened `/topic/gamma`, pushed onto the history (6 to 7). The
  cookie held `gamma` hidden and nothing else, and the page's HTML, fetched again with that cookie, rendered the
  summary hidden and its toggle reading "Show", as did the page itself.
- On a topic's page, Hide (every topic's, `ui/patch`) hid its summary within the action's response, and the cookie
  then held all three topics hidden; Show emptied it, every value its default; the page's own toggle then changed
  only its topic. `/topic/beta`, loaded after Hide, rendered its summary hidden.
- Three files dropped at once were taken in as one batch. Of three more, one whose PUT carried a forged signature got
  a 403 and stayed in the list, failed; the other two were taken in together. A 2 MB file dropped while a 12 MB one
  was uploading arrived first and waited for it: the two were one batch.
- Removing a 40 MB upload while a 2 MB one had arrived took the 2 MB one in at once. The list was the same 5 s later,
  time enough at the bucket's 8 MB/s for the 40 MB file to land and its report to be refused.
