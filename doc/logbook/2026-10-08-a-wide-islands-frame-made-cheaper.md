# 2026-10-08 — A wide island's frame, made cheaper

A list island of 1,000 keyed rows, re-rendering with no row changed (its own attribute changed): the costliest frame
left. Measured in the dev JVM with one script after every step (`frame!` timed over 300 runs after 300 warm-up runs;
garbage from the thread's allocation counter, on a platform thread since evals run on virtual threads, over 100 runs).
Keys were longs, or UUID strings, which an app's ids likely are.

|                                        | UUID keys          | longs              |
|----------------------------------------|--------------------|--------------------|
| Before                                 | 2,119 µs, 3,374 KB | 1,075 µs, 2,191 KB |
| `key-string` in Java (`Slot/key`)      | 1,074 µs, 2,164 KB | 986 µs, 2,023 KB   |
| A template compiled into mutable lists | 976 µs, 1,448 KB   | 834 µs, 1,301 KB   |
| Fixed arities for an island's calls    | 821 µs, 929 KB     | 678 µs, 840 KB     |
| Kept children reconciled in place      | 461 µs, 335 KB     | 331 µs, 343 KB     |

- `key-string` checked each key a character at a time through a seq over the string: 1.2 MB of the frame with UUID
  keys. `Slot/key` scans the chars and returns the string itself when it needs no escaping, the usual case.
- `compile-island` built a pair of vectors per child and a set at the end to check slots: 882 KB. It now walks into an
  `ArrayList` and a `HashSet`, checking slots as it goes, and makes the parts a vector once: 160 KB. No Java class was
  needed.
- An island's fn took rest args, `apply`'d its key and `vec`'d its args, and joined its slot with `str`. Arities for
  zero to three args, and `String.concat`, took the calls from about 700 KB to about 225 KB.

- Reconciling the parent's children rebuilt, on every render of it, a new id string per child, the map of child
  frames, and a set of the placed children to find the unmounted: about 570 µs and 550 KB with UUID keys, though the
  children were the same. A render that places the same children in the same slots, in the same order, as the last
  now keeps their ids, starts from the last frame's map of child frames, changing it only where a child's frame
  changed, and skips the search for the unmounted. A render whose children changed takes the path it did before: a
  frame that adds a row to 1,000 measured 982 µs without this step, and 967, 1,056 and 992 µs with it, in three runs.

Against where it started, the frame takes 78% less time and leaves 90% less garbage with UUID keys. What remains is
creating the calls and compiling the parent's template, about 250 µs together, and one instance lookup per child.
