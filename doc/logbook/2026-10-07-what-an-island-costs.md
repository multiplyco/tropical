# 2026-10-07 — What an island costs

1,000 small list items, `[:li [:span "item " i] [:span label]]`, in one session. They were rendered either as keyed
islands placed by a root island or as plain functions called inside it. The runtime was driven directly, as the pump
drives it, in the dev JVM, averaged over 50 to 200 runs after warm-up. Memory is the heap used after two GCs, before and
after building 40 sessions and rendering each once, divided per session.

|                                           | Islands                | Plain functions            |
|-------------------------------------------|------------------------|----------------------------|
| First render                              | 4.9 ms                 | 0.6 ms                     |
| Root re-renders, items unchanged          | 1.2 ms                 | 0.5 ms                     |
| One item changes, read by the item itself | 0.59 ms, 71 chars sent | 0.55 ms, 55,909 chars sent |
| Retained per session                      | ~1.4 MB                | ~0.14 MB                   |

- **An island costs per instance, per session:** about 1.3 KB kept, and a first render about seven times a plain
  function's.
- **It pays back in the patch.** One changed item went out as 71 chars as an island, and as the whole 55,909-char list
  inside the root. With the root reading every label and re-rendering, the patch was still 70 chars: the root's template
  was unchanged, so patching went down into the one island that changed.
- **Server time for one changed item was a wash.** The root is on the path to the changed island, so it checks all 1,000
  children either way.
- So islands are boundaries, for what reads, holds or changes on its own, and presentational pieces stay plain
  functions.
