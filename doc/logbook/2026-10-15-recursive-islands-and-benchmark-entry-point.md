# 2026-10-15 — Recursive islands and benchmark entry point

A recursive `defisland` called its named render function directly: that local name shadowed the island var. A tree
therefore rendered inline under one instance, ignoring child keys and sharing the parent's hook scope. A repeated
hold key failed on the second node. The render function now has a generated name with an island-specific prefix,
leaving self-calls to resolve to the island var and place child Calls. Generating the name also avoids capturing an
application helper named `<island>-render`.

The regression mounts a keyed tree, updates one leaf, removes a branch and disposes the remainder. It checks mounted
paths, independent renders, patch targets, retained frames, and exactly-once hold release. Before the fix it failed
eight of ten assertions; after it passes. A separate regression checks that a render helper's name is not captured.
The island guide, macro docstring and plugin now describe recursion. Existing island definitions need re-evaluation
to take up the macro change; no Java source changed.

`bb bench` now compiles Java and runs `bench.profile` in a fresh JVM, covering `measure`, `record` and `summarize`.
The development guide and working notes use that entry point, and the unused fixture `:stage` option is removed.
The changelog describes list-update improvements without promising that every sibling scan was eliminated.

Validation: all eight Node tests and 173 JVM tests with 1,081 assertions pass, along with lint, formatting and
reflection checks. The task was exercised with a sparse measurement, a one-second recording of the 128-source
workload, and a summary of that recording.
