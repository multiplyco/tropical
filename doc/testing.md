# Testing islands

`co.multiply.tropical.test` renders an app's islands as a session does, on the test's own thread, with no server, stream
or browser:

```clojure
(deftest clicking-counts
  (with-open [h (test/harness #(app uid) {:uid uid})]
    (is (str/includes? (test/render! h) "Clicked 0 times"))
    ((test/action h "app/counter" :click) {})
    (is (str/includes? (test/render! h) "Clicked 1 times"))
    (is (empty? (test/failures h)))))
```

- **`harness`** holds a session's islands under a root: an island's call, such as `(app uid)`, or a fn of no arguments
  returning one, called for each render. Its options are the session's `:uid` (`"user"` by default) and `:tab`, an
  `:error-view` as the app's, and the `:request` that created the session, which `use-session` returns and the
  handler's `action/request` returns too. Without a request, `action/request` throws, as it does outside an action.
- **`render!`** renders what changed since the last render, and returns the HTML of the whole tree. While a read is
  pending, as an `observe` whose source hasn't emitted yet, it waits for it, up to `:wait-ms` (1 s by default), so a
  test sees what the read brought. `{:wait-ms 0}` renders once, for what the island shows while it waits.
- **`mounted`** lists the ids of the mounted islands, the paths of slots from the root.
- **`(action h path k)`** is the action the island at `path` renders under `k`, as a fn of the signals, and of a value
  for an action taking one. It is nil while the island doesn't render it, which is how a test sees a gate. The signals
  pass through JSON, as the browser's do, so the handler gets string keys.
- **`failures`** lists the renders that threw, which the page would show only as an error view: each the failure the
  view got, with the island's id under `:path` and the exception under `:error`, whichever view rendered it. An
  island failing again the same way repeats its `:ref`.
- **Closing the harness** unmounts its islands: their resources start to linger and their action tokens are revoked.
- **Renders run in the caller's scope.** Bind with [Scoped](https://github.com/multiplyco/scoped)'s `scoping` what the
  route's middleware would bind for a session.
- **Sources are the app's.** An island reading through the app's own function over `observe` reads what that function
  opens, so a test that shouldn't reach a database redefines the function, with `with-redefs`, to one over a source of
  the test's own.

Harnesses share an island definition's stack traces in the log, as sessions do ([Serving pages](serving.md#failures)).
So an island failing the same way in several tests within a minute may log a
line naming an earlier reference, rather than the exception. Assert on `test/failures`, which has each render's
exception.
