# How it works

What happens between a change on the server and a patch in the browser: how islands render into frames, how a change
reaches the sessions that read it, and how a session keeps its tab. How resources live is in
[Reading from outside the page](reading.md#a-resources-life), and how actions reach their handlers in
[Actions](actions.md). Why it is built this way, and what was rejected, is in [the design notes](design.md).

## Islands and frames (`island`, `runtime`, `internal.frame`)

- **Compile once, emit by appending.** A render is serialized once, by
  [Chassis](https://github.com/onionpancakes/chassis), into a template: strings, plus holes where child islands go.
  Emitting the page appends cached strings, so an unchanged island costs nothing per frame.
- **Reconciling.** A frame starts from the islands whose reads changed. It renders them and visits their ancestors on
  the way down; every other subtree is reused without being visited. Output equal to the previous render keeps the
  previous template, so nothing is sent. An island that places the same children as before, in the same order, keeps
  their ids and changes its map of child frames only where a child's frame changed.
- **Patching the topmost change.** The session compares the frame the client shows with the new one, top-down, by
  template identity, and sends each island whose own template changed, never also its descendants, for Datastar to
  morph into the page by its element's id. SSE is one ordered stream, so the server knows exactly what the client
  shows. The whole root is resent only when that is unknown: after a reconnect whose page may lack part of what was
  sent ([Sessions](#sessions-session-ring)).
- **Lists that grow send what they gain.** When an island's template changed only by children added or removed, the
  rest in order, the added children are inserted next to a kept sibling, or where a removed one was, and the removed
  ones removed, with Datastar's `before`, `after` and `remove` modes. Adding the 1,000th message to a conversation
  sends that message, not the conversation, and a child whose key changed sends itself, not its parent. Anything
  else, such as a reorder or a change to the island's own markup, resends the island. What that asks of a list's
  markup is in [Islands](islands.md#when-to-make-an-island).
- **Unmounting.** When an island renders without a child it placed before, that child and everything under it unmount:
  their holds are released, their subscriptions cancelled and their action tokens revoked.
- **Failures.** A render that throws renders an error view in its place, and the frame goes on without it
  ([Islands](islands.md#when-a-render-throws), [Serving pages](serving.md#failures)). A watch whose selector throws
  remains a read: a change to its source retries the render, and unmounting releases it just like a successful read.

## Signals (`signal`)

How a change reaches the sessions that read it: a few Java classes, on the path every change takes.

- **A signal tells its subscribers that it changed, not what to.** Each session reads the value when it gets to it. A
  `Cell` holds its value: a resource's `emit!` sets one. A `RefSignal` wraps an atom with one watch, however many
  sessions read it, removed with the last of them.
- **A subscription is in its inbox at most once.** A change marks it, and only the change that marks it pushes it into
  the session's `Inbox`. A session that falls behind has one entry per subscription to catch up on, and reads the
  latest value once, as with Missionary's continuous flows.
- **No change is lost.** The inbox is a lock-free stack threaded through the subscriptions themselves. The pump clears
  each mark before it reads the value, so a change after the clear marks it again.
- **Marking allocates nothing.** With 1,000 sessions reading one value, a change costs about 52 ns per session, and
  further changes before the session catches up about 8 ns.

## Sessions (`session`, `ring`)

- **One session per tab, not per connection.** A GET of the page creates the session and renders the page from its
  first frame. The tab's stream then attaches, and is sent only what changed since. The page is served `no-store`, so
  a duplicated or restored tab fetches a page, and a tab id, of its own.
- **One URL per page.** The stream and the actions are requests to the page's own URL, told apart by a header, so they
  pass the route's middleware as the page does, and a tab the server no longer knows is rebuilt from its route.
- **A cold first frame waits briefly.** With `:first-frame-ms` in the app, a session's first frame waits until no
  read of its islands is pending, or that many ms pass, and renders as reads land meanwhile, so an island that
  appears once an earlier read lands reads too. A page whose reads land in 20 ms arrives with them in about 23 ms.
  Later frames never wait ([Serving pages](serving.md#the-page)).
- **One page per session.** The stream carries an id made per page load, and the session belongs to the first load to
  attach. A copy of the page that shares the tab id is told to reload.
- **Reconnects are routine.** Datastar closes a hidden tab's stream and reopens it when the tab is shown, sending the
  last event id it read. A detached session keeps its islands, and so their resources, for its grace period
  ([Serving pages](serving.md#running-it)). Reattaching sends what the page lacks without reopening anything: what
  changed while it was away, if the last event id says the page has the rest, and otherwise the whole cached frame.
  After the grace, every island unmounts and everything they held is released. A tab the server doesn't know, because
  its session expired or the server restarted, gets a fresh session. An action taken in a hidden tab runs, and its
  result shows once the tab is shown again, within the grace.
- **One pump per session.** A Quiescent task on a virtual thread owns the session's state and renders on its behalf,
  never on the thread that made a change, so a shared subscription's loop never renders for anyone.
- **Backpressure.** Changes that arrive while a frame is being sent coalesce into the next one, `frame-ms` apart, and
  the latest state wins. A slow client gets fewer, later frames, never a backlog.
- **A frame is flushed once.** Its patches, inserts, removals and signals are written, then flushed together, so they
  reach the client at once, and a compressed stream encodes them as one.
- **Heartbeats.** A dead connection is only noticed on write, so a quiet session writes an SSE comment, which the
  client ignores ([Serving pages](serving.md#running-it)).
