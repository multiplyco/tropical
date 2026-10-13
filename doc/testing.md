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

- **`render!`** renders what changed since the last render, and returns the HTML of the whole tree. While a read is
  pending, as an `observe` whose source hasn't emitted yet, it waits for it, up to `:wait-ms` (1 s by default), so a
  test sees what the read brought. `{:wait-ms 0}` renders once, for what the island shows while it waits.
- **`action`** is the action an island renders under a key, found by the island's id, the path of slots from the root
  that `mounted` lists, as a fn of the signals, and of a value for an action taking one. It is nil while the island
  doesn't render it, which is how a test sees a gate. The signals pass through JSON, as the browser's do, so the
  handler gets string keys. With `:request` in the harness's options, `use-session` has it as the request that
  created the session, and the handler's `action/request` returns it.
- **`failures`** lists the renders that threw, which the page would show only as an error view: each the failure the
  view got, with the island's id under `:path` and the exception under `:error`, whichever view rendered it.
- **Closing the harness** unmounts its islands: their resources start to linger and their action tokens are revoked.
- **Renders run in the caller's scope.** Bind with `scoping` what the route's middleware would bind for a session.
- **Sources are the app's.** An island reading through the app's own function over `observe` reads what that function
  opens, so a test that shouldn't reach a database redefines the function, with `with-redefs`, to one over a source of
  the test's own.
