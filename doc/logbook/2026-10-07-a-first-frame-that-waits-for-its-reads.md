# 2026-10-07 — A first frame that waits for its reads

Page latency, before and after `:first-frame-ms`. A `ring/handler` page whose root is one island reading a source
that emits once, a set time after it opens, under a fresh key each time so every read is cold. Timed around the sync
handler call in the dev JVM, 15 runs each after 20 to warm up.

| Wait   | Read lands after | Median   | Max      | In the page         |
|--------|------------------|----------|----------|---------------------|
| 0      | 20 ms            | 0.5 ms   | 3.7 ms   | pending, 15 of 15   |
| 150 ms | 20 ms            | 23.5 ms  | 25.9 ms  | the value, 15 of 15 |
| 0      | 2 s              | 0.3 ms   | 3.5 ms   | pending, 15 of 15   |
| 150 ms | 2 s              | 152.5 ms | 155.7 ms | pending, 15 of 15   |

- A read that lands within the wait costs its own time and about 3 ms more, the wake-up of the pump after the value is
  emitted, and the render.
- One that doesn't costs the wait and about 2.5 ms more.

The demo waits 150 ms, and alpha's feed now connects in 50 ms. With no session holding anything, `/topic/alpha` took
156 ms with curl and arrived with the feed's first reading and the 700 ms summary still pending. With gamma's connect
time set to 50 ms in the REPL, `/topic/gamma` did the same in 154 ms. In Chrome, a cold `/topic/alpha` had its first
byte at 155 ms; the summary followed over the stream, and the ticker went on ticking.

Reads in one frame load side by side. A render is synchronous, but `observe` returns `pending` at once and opens its
resource on a virtual thread of its own, so one render starts every read in the frame. Measured the same way, with a
1 s wait and reads of 50 ms, the median of 10 cold pages:

| Page                                                 | Median | Landed |
|------------------------------------------------------|--------|--------|
| One island, one read                                 | 55 ms  | 1 of 1 |
| Five keyed islands side by side, a read each         | 54 ms  | 5 of 5 |
| An island placed only once another's read has landed | 109 ms | 1 of 1 |

Reads are sequential only where the structure makes them so: a child placed once its parent's read lands starts its
read in the render after. A child whose read doesn't need the parent's data can be placed regardless, showing its own
pending state, and its read starts with the first render.
