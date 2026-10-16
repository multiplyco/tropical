# 2026-10-15 — Internal boundaries and frame ownership

`39ebd25` separated frame compilation and patch planning, session delivery, and signal-patch composition into
internal namespaces. Browser-state naming and validation share helpers, as does the hashing used for names and
revisions. Cookie and storage persistence moved into JavaScript resources, still embedded and run synchronously,
with direct Node.js tests. The internal `Call`, `Hole`, `Template` and `Frame` types moved to `internal.model`;
taking up that change in this repository's running development JVM required a restart.

A review found an ownership gap above `abandon-render!`: when a child aborted a frame, its ancestors had already
acquired their new holds and released their old ones, but the runtime still held their old instances. Disposal
leaked the new holds and released the old ones twice. The regression covers two ancestors, both on a first frame
and after a successful frame, with a separate reader's claim left intact. Reconciliation now records each rendered
instance before visiting its children, so disposal sees the holds that instance actually owns.

The storage loader and persistence handler now take their global's name from one definition. The JavaScript tests
use a different configured name, so a literal name in the handler cannot silently bypass their defaults. Both
`bb test` and `bb test:linux` run these tests on the host; the Linux JVM matrix remains in its containers.

The review's Chrome smoke test found cookie persistence, storage observation, reload and dismiss working, with no
console errors. Its signal-patch attribute retained its newlines, so the earlier explanation about HTML folding
them was wrong. The resources keep block comments and explicit statement endings as an embedding convention.
