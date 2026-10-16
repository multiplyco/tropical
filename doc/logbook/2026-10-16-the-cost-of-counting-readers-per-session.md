# 2026-10-16 — The cost of counting a resource's readers per session

Measured before deciding against it, for a view of the resources a user's tabs read. In the dev JVM, warm, from the
REPL: a map shaped as the registry's, of 2,000 entries with three readers each, and an acquire then a release of one
entry with other readers, each a `swap!` under a lock as `resource/acquire!` and `release!` do, a million times after
two warm-up runs. Today's update of `:subscribers` alone took about 390 ns per pair. Also keeping a count per
`[uid tab]` in the entry took about 720 ns: some 165 ns more per acquire or release, under the registry's one lock,
and paid only as a resource gains or loses a reader, never as it emits. A micro-benchmark, not a fresh-JVM run under
`bb bench`: a magnitude, not a figure to hold to.

The views that shipped need none of it: `resource/use-summary` of the server reads the registry as it is, and of a
session matches the session's holds against it (design notes).

In the demo, with `/` and `/topic/beta` open as one user, the beta tab's This tab view listed its feed, its summary
and the user's toasts, the toasts counting one reader, while its Registry view listed both tabs' reads, the toasts
counting two.
