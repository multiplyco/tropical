# 2026-10-10 — A closing resource is forgotten before it tears down

`close-if-idle!` cancelled a resource's task, then removed its entry. Cancelling starts the teardown on other threads:
the subject's thread is interrupted and runs its `finally`, and the `q/finally` in `start` calls the cleanup fn. So a
teardown could find the entry still registered. `resource_test`'s `closing-undoes-the-subject`, which waits on its
subject's `finally` and then looks, failed that way once on amd64 under emulation (the entry above). `acquire!`,
`release!` and closing exclude each other under the registry's lock; only what reads without it could see the entry:
`summary`, `cell` and through it `shared/swap!`, and the teardown itself.

Now the entry goes first, then the cancel. Removing it only once torn down, in a `q/finally` of the cancel, would
leave a cancelled resource registered meanwhile, for an `acquire!` to find and subscribe to.

A new test, `a-resource-is-forgotten-before-it-tears-down`, makes closing wait for the teardown, with `cancel!`
redefined to deref `q/cancel`'s task, which settles once its target has torn down. The old order then shows every
time: against it, the subject's `finally` found its entry as `:lingering` in three runs of three; against the new, in
none of three.

Closing still doesn't wait for the teardown, so a resource opened again soon after can run beside the last as it tears
down (backlog).

`bb test`: 128 tests, 714 assertions.
