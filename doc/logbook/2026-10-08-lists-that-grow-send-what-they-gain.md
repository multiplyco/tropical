# 2026-10-08 — Lists that grow send what they gain

A conversation island with one keyed child island per message, each message about 430 chars, and one message added.
Before, the conversation's template gained a hole, so the conversation was the topmost change and went out whole. Now
the new message is inserted after the last. Measured in a fresh JVM on the test classpath: the runtime driven as the
pump drives it, `island/patches` timed over 2,000 runs after ten warm-up rounds, the list's HTML over 200, and one
frame once, for scale.

| Rows  | Sent before   | Sent now  | Patches, a row added | Patches, a row changed | HTML of the list | Frame    |
|-------|---------------|-----------|----------------------|------------------------|------------------|----------|
| 10    | 4,805 chars   | 434 chars | 0.9 µs               | 1.4 µs                 | 1 µs             | 107 µs   |
| 100   | 43,866 chars  | 435 chars | 5.2 µs               | 3.9 µs                 | 15 µs            | 308 µs   |
| 1,000 | 435,367 chars | 436 chars | 60.7 µs              | 41.0 µs                | 160 µs           | 1,490 µs |

Deciding the splice at 1,000 rows, in the dev JVM, as it went:

- 950 µs, first in Clojure. Most of it went to merging every kept child's empty patches, which also slowed the common
  case, a wide island with one child changed.
- 446 µs, skipping children that are the same frame.
- 254 µs, one pass over each slot map in place of sets built from the templates.
- 134 µs, the comparison in Java (`Splice`), one pass over both templates.
- 61 µs, taking a child at the same place in both as kept without asking either slot map, which leaves two lookups
  per kept child, to tell whether it changed (fresh JVM, the table above). The two JVMs agree on what this didn't
  touch: the list's HTML took 162 µs in the dev JVM and 160 µs in the fresh one, a changed row's patches 40 and
  41 µs.

The test that applies patches to a model of the client, a DOM parsed from the frame it shows, runs 400 random edits.
Breaking the slot check in `Splice` at first went unnoticed: no edit both reordered and added, and a reorder alone is no
splice. Edits that do both now catch it.

Live, in Chrome: five posts to the demo's messages, newest first and capped at five, each sent one insert of about
140 chars before the newest, and the fifth also a removal of the oldest by its id. The list island was never resent,
and the page's order matched after each post.
