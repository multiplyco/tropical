# 2026-10-15 — Failure runs and definition-owned trace caches

Consecutive failures of a mounted island now keep one reference while the exception class and known throw site
match. Repeats log at doubling counts, without repeating the stack trace; recovery, a different failure or unmounting
ends the run, with a count summary when it repeated. Each failure still reaches the error view and `:on-failure`.

The initial implementation used a process-wide atom of island names and failure fingerprints. Trace deduplication
now belongs to the island definition: `island-fn` creates a Java `TraceCache` in the descriptor shared by its Calls.
The cache holds at most 64 fingerprints, references and timestamps, with a one-minute deduplication window. A
synchronized `LinkedHashMap` makes expiry, reference selection and oldest-entry eviction one operation. A concurrent
map alone would not make the capacity check and eviction atomic. No logging runs under the monitor, and ordinary
repeats stay local to their session pump. Every new run still gets a line; the shared cache reduces duplicate traces,
not the number of newly failing instances that are logged. Retired definitions can be collected with their caches.

Java `Failure` owns the reference and counters, with independent attempts for the render and each error view.
Clojure `internal.failure` formats the decisions and sends them through tools.logging, preserving the application's
logging backend. Successful renders allocate no failure state. Bookkeeping stores the exception class and top stack
frame, not the thrown exception; the shared cache also retains no views or session contexts.

Two review probes exposed diagnostic gaps in the initial implementation. An error view first throwing on the third
failed render produced no log at all, and a view first failing in a second session lost its trace when that session's
primary render trace was deduplicated. Error views now have independent counts and trace decisions, reset on a
successful view render. Their first failures are logged under the island's reference even when its render repeat is
suppressed. Render and view fingerprints are separate in the shared cache.

A run that started without a stack trace also kept an unknown site forever: subsequent exceptions at two known sites
kept one reference and neither available trace was logged. The run now learns its first available site, preserving
the reference and logging or linking that trace immediately; a later different known site starts a new run.

Validation: 182 JVM tests with 1,127 assertions and all eight Node tests pass, along with lint, formatting and
reflection checks. Regressions cover both missing-trace directions, delayed view failures and recovery, failures in
other sessions, and separate definitions with the same name. A controlled monotonic clock checks expiry and capacity;
32 concurrent claimers select exactly one trace reference, both initially and after the window expires. Existing
ownership, aborted-render and immutable-frame tests also pass. Java changed, so the development JVM needs a restart.
