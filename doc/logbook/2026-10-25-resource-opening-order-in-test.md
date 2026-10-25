# 2026-10-25 — Resource opening order in a test

The [Brotli 1.2.1 validation](2026-10-25-brotli-1.2.1.md) caught a failure in
`hooks-test/hooks-reading-the-same-thing-in-one-render-share-it`: its opening log was `[5 4]`, where it expected
`[4 5]`.

`observe` registers each distinct resource through `resource/acquire!`, which serializes registry updates. Its
subject runs separately in `resource/start`, on a Quiescent virtual thread. Registering the resource for `4` first
does not order its subject's effects before those of `5`. Independent subjects may connect in either order, as the
[reading contract](../reading.md#opening-one) permits.

A controlled reproduction held the subject for `4` until the subject for `5` had published. The original test then
failed only its vector comparison: the rendered `40 40 50`, single subscription for `4`, and release assertions
all passed. This was a test assumption about scheduling, not evidence of broken sharing or cleanup.

The test now exercises both orders, releasing each subject explicitly and waiting for its publication before
releasing the other. It checks exact opening counts by argument, one subscriber per resource, and one cleanup per
resource after unmounting. Publication and cleanup promises replace the fixed sleeps; waits have a two-second
failure bound. A `finally` releases the gates, disposes the runtime and waits for cleanup even if an assertion's
surrounding code throws. No library code changed.

Validation:

- The hooks namespace passed on macOS arm64 with JDK 27: 10 tests, 61 assertions. All 15 browser JavaScript tests
  also passed.
- In fresh JVMs on macOS arm64 and Linux x86-64 musl (JDK 25), the corrected test ran 100 times each. Each run
  exercised both orders: 2,000 assertions, no failures or errors, and no resources left registered.
- `bb test:linux amd64-musl` passed the full suite: 197 tests, 6,259 assertions, no failures; all 15 browser
  JavaScript tests passed too.
- `bb format:check`, `bb lint` and `git diff --check` passed. Babashka's process-cleanup hooks on macOS reported
  sandbox-denied `sysctl` calls; the commands exited successfully.
