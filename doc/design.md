# Design

Why Tropical is built the way it is. The README and the guides say what it does and how; this is the reasoning behind
it, and the alternatives that were rejected. Measurements behind these claims are in [logbook.md](logbook.md).

## The two guarantees

Tropical exists to keep two guarantees that Electric gives a Datastar application, without a reactive graph:

- **Resources live exactly as long as the view that uses them.** Neither torn down while in use, nor kept when unused.
  The registry (`resource`) enforces it: one physical resource per key, opened by the first subscriber, lingering after
  the last. Closing forgets it, then cancels it, without waiting for its teardown, so one opened again soon after can
  run beside the last as it tears down: an open question.
- **Access control is scope-based.** A subtree the user may not see is never rendered, so its markup is never produced,
  its subscriptions never opened and its actions never minted. That is stronger than Electric, which ships gated code in
  the bundle and merely doesn't mount it.

## Immediate mode, not a reactive graph

An earlier version built the islands as a Missionary DAG. It worked and was as fast, but page code became explicit flow
wiring: declared inputs, slots and gates. Ordinary functions plus a small runtime give the same guarantees, with page
code that reads as plain Clojure and plain stack traces, and islands that render in a test harness with no flows
involved. Libraries considered:

- **Missionary** is the best retained-graph library in Clojure, and the earlier version used it.
- **Signaali** tracks dependencies on a process-global mutable stack, so one graph runs at a time. Sessions render
  concurrently.
- **Javelin** has no automatic teardown, which is the lifecycle needed.
- **Spindel** keeps running a spin that its parent no longer creates, and leaves teardown to garbage collection.
  Teardown must be deterministic.
- **partial-cps** gives async code without a scheduler, through a CPS transform. On the JVM, virtual threads and
  Quiescent already let code block cheaply.

What this gives up: Missionary's operators, such as time-based ones, and its supervision semantics. Scoped lifetimes
and coalescing come from the runtime and the session pump instead, and are covered by the tests.

## Rendering

- **Hooks keyed, not ordered.** Each hook names what it holds, so conditionals and loops around hooks are safe, unlike
  React's call order.
- **Ids scoped by parent.** A page-wide uniqueness rule can't be checked locally, since two parents rendered in
  different frames never meet. Scoping makes ids unique by construction, and keys only need to be unique among
  siblings. Moving an island between parents remounts it, as in React.
- **Plain hiccup, serialized by Chassis.** Building hiccup is nearly free; serializing a small island takes about
  1.5 µs. Chassis's `cc/compile` pre-serializes static parts at macroexpansion and halves that, with the same syntax,
  below an island's root (which must stay a vector, so the runtime can put the id on it). A second, Electric-shaped
  syntax over the same output was considered and rejected.
- **Patching the topmost change.** SSE is one ordered stream, so the server knows what the client shows and can diff
  frames by template identity. The whole root is resent only when that is unknown.
- **Inserting what a list gains.** A list island's template holds a hole per child, so a new child changes the
  template, and the topmost change was the whole list, every child included. When the templates differ only by
  holes added or removed, the rest in order, the added children go out with Datastar's `before`/`after` modes next to
  a kept sibling's id, and the removed ones with `remove`; anything else resends the island, which is correct by
  construction. Added children with no kept sibling beside them, but taking the place of removed ones, with no text
  between, go in after the last of those, which are removed after them: a child whose key changed, amid its parent's
  markup. Placeholders, the parent resent with empty stand-ins for children the client keeps, were rejected:
  Datastar's `data-ignore-morph` skips an element only when both the element in the page and the incoming one carry
  it. The server side still renders the parent over the whole list; a differential `for-by` over a source's diffs is
  parked.
- **Starting over is a new key.** A key is an island's identity, as in React: a new key is a new island, whose state
  starts at its initial values. That was costly while a child whose key changed resent its parent, which is why a
  `use-state` option resetting a state when a value it belongs to changes, `(use-state :nav nil {:for opened-at})`, was
  asked for. Sending the new child in the old one's place made it cheap instead, so there is one way to start over,
  and it resets what else the island holds, its actions and holds, with its state.
- **Deciding a splice in Java.** Whether two templates are a splice is decided on every frame in which a wide island
  changed, so it is one pass in Java (`co.multiply.tropical.frame.Splice`) over both templates. It compares their
  strings as one text, across the holes left out, without joining them, and takes a child at the same place in both
  as kept without asking either slot map. At 1,000 rows it costs 61 µs, against 950 µs for the first version in
  Clojure (logbook).

## Reading

- **Reads go through `observe`.** Rendering is cheap; a database query is not. A query in a render runs on every
  re-render, its cost lands on the database where it is hard to trace back to the view, and caching it by hand means
  inventing invalidation at every call site. `observe` makes the rule structural: what is read from outside the page is
  a resource, opened once for every reader of the same call site and arguments, and streamed.
- **One primitive; the shapes are the app's.** A subscription to a path in a database, a one-off query and a poll loop
  are all a resource that emits values: a query emits once. The library owns how a read lives (shared, lingering,
  failing), and an app writes what it reads as a function or macro over `observe`. An earlier `<-` took a database's
  shape (a name, a path, an open fn), and a separate `?` ran one-off queries per island.
- **Missionary's `observe`.** The open fn is called with an `emit!` fn and returns what undoes it, the shape callback
  APIs, loops and queries all fit. It returns a cleanup fn or a task. A cleanup fn suits a callback API. A task carries
  its failure to the registry, where a cleanup fn returned for a loop would hide it: readers would stay on the crashed
  loop's last value, and nothing would reopen it. Unlike Missionary's, `emit!` keeps only the latest value, as signals
  do.
- **Keyed by call site and arguments, with nothing captured.** The key decides who shares a resource, so it must cover
  everything the open fn depends on. An explicit key fails unsafe: a dependency left out of it serves one reader's
  resource to another, such as one account's data to another account, silently. Keying on the call site and the
  arguments, and refusing at compile time an open fn that closes over any other local of the render, can only fail by
  keying on too much: a value made anew each render reopens the resource each render, which is costly and visible in
  the registry, but never shares one wrongly. The call site is unique per macro expansion, so two never share, even in
  the expansions of a macro that carries no positions, and reloaded code opens resources of its own.
- **Read twice in a render, held once.** A hold is asked for once per render, since two call sites sharing one is a
  bug where the hold is the call site's own: two buttons sharing an action token, two widgets an upload list. A read is
  keyed by what it reads, so asking again is a second reader of the same thing, as two `use-watch`es of one atom are.
  An app wraps `observe` in hooks of its own, whose reads all share the wrapper's call site, and two hooks asking of the
  same data in one render made the same call twice, which threw; an argument added only to tell them apart opened a
  resource per question over the same data. So `use-hold` takes `:idempotent`, which `observe` and `use-shared` set: a
  repeat returns what the first ask got. One primitive with a flag, not two, since the two kinds differ only there:
  acquired on the first ask, released after the first render that doesn't ask, alike. Strict stays the default, as
  the one that catches a bug. The caller's call site in the key instead would take a macro at every hook over
  `observe`, and would only hold one resource twice.
- **An empty scope for resources; the creating request's for sessions.** A resource belongs to no single reader, so it
  sees no reader's scope: inherited, a fetch that reads the account from scope would run as whoever opened it, for
  everyone sharing it. A scoped value is read in the render and passed as an argument, which puts it in the key. A
  session's renders run in the scope of the request that created the session, so an app's request context reaches
  renders without being threaded through.
- **Off the render, off the lock.** The open fn runs on a virtual thread of its own. One that blocks while it connects
  holds up neither the session that opened it nor anyone opening another resource, which it would if it ran under the
  registry's lock, as an open fn once did.
- **One resource per distinct read, shared by every session.** Load on the database scales with the distinct reads
  being viewed, not with the number of viewers. What stays per session is the fan-out: each new value notifies every
  session that reads it. A shared resource doesn't know who reads it, so access control stays at the island, and data
  that differs per user needs the user among the arguments. A one-off query is cached the same way: identical queries
  run once, and the result lives as long as its readers, plus the linger.
- **The linger.** Without it, every remount (a topic switched back, access granted again, a reconnect) would close and
  reopen a resource, and the churn would land on the database.

## Signals

A change needs to reach every session that read it, and a session cares only about the latest value. So a signal pushes
invalidation and the session pulls the value: a change marks the session's subscription dirty, at most once until the
session takes it, and the session reads the current value when it renders. This is the protocol of Missionary's
continuous flows (notify once, read on transfer).

It is written in Java (`co.multiply.tropical.signal`) because it is on the path every change takes, and Java states the
properties directly: a CAS on a dirty flag decides which change pushes, an intrusive Treiber stack threaded through the
subscriptions bounds the inbox by the number of subscriptions, and `LockSupport` parks and wakes the pump. It allocates
nothing per change, which matters less for time than for garbage: the cost is paid on the publishing thread, once per
reading session. The invariants (drain order, clear-before-read, wake on empty to non-empty) are documented in the
sources.

Atoms remain the user-facing state: `RefSignal` gives each atom one watch, however many sessions read it.

## Sessions

- **One session per tab, not per connection.** Datastar drops streams routinely (a hidden tab closes its stream), so a
  connection is too short-lived to own islands and their resources. A grace period absorbs reconnects.
- **A hidden page's stream closes.** Datastar's default, kept: a stream per background tab would hold a connection, a
  session and, compressed, an encoder, for changes nobody sees, and a click needs a page that is shown. What an action
  taken while hidden changed is sent when the page is shown again. The app's `:open-when-hidden` is for
  development, where browser automation can drive a page its browser keeps hidden: there the closed stream made a click
  right after a load look lost (logbook, 2026-10-09). A page left behind lets go of its stream all the same. Chrome
  freezes a left page in its back/forward cache, `no-store` or not, where the stream holds its connection for about a
  minute, and HTTP/1.1 allows six to a host: clicking from page to page, the sixth waited a minute. So with the option,
  the stream is cancelled as the page is left, and opened again if the page is restored (logbook, 2026-10-10).
- **One pump per session.** Rendering happens on the session's own virtual thread, never on the thread that made a
  change, so a shared subscription's loop never renders on anyone's behalf, and a slow session slows only itself.
- **Coalescing, not queueing.** The latest state wins. A slow client gets fewer, later frames, never a backlog.
- **No scheduler of our own.** Everything concurrent is a Quiescent task on a virtual thread: pumps, resources and the
  linger timer. Rendering happens on change, never on a timer.
- **A cold first frame waits briefly for its reads.** The first frame a client sees from a session, the page or the
  root a rebuilt tab resyncs from, often reads what nothing holds yet, and sent at once it shows pending states that
  the data replaces a moment later: on a cold load, and in every open tab after a restart, where the whole root is
  morphed into them. Reads that land within an app's budget are worth waiting for, so the first frame waits until
  none is pending, or the budget passes. Pending is what a render read, so islands that appear once an earlier read
  lands are waited for too, within the same budget. A read that never emits costs the budget and no more. Only the
  first frame waits: after it, a client is shown something, and a pending state is an honest update.
- **One URL per page.** The page, its stream and its actions are requests to the page's own URL, served by one handler
  and told apart by method and a header. All three pass the route's middleware, so what it binds from the path or the
  login (a workspace, an account) is the same for each, and a tab the server no longer knows, as after any restart, is
  rebuilt from the route it was on. Fixed `/stream` and `/act/<token>` paths carried no route: a resync could only
  rebuild whatever one app the stream handler had, and middleware binding context from the path had nothing to bind.
  Carrying the page's URL in a query parameter would have let the root route, but not the middleware run. Headers,
  not query parameters, keep the route's query the app's. Datastar cancels a request in flight when another goes to
  the same method and URL, which every action on a page now shares, so actions turn that off.
- **The app's middleware asks tropical what a request is.** Middleware that runs before routing, refusing or parsing
  bodies, has to let actions through, and `request-kind` is the handler's own dispatch, so the two can't disagree. An
  action needs a JSON body as well as its header: the header is the client's to send, and a filter admitting any body
  that carries it would hand that body to the form and multipart parsers it exists to keep bodies from. Those ignore
  JSON, so an admitted action body reaches only the handler, which reads it after finding the user, and at most
  `:max-signals-bytes` of it.
- **A refused stream reloads its page.** The stream passes the route's middleware each time it connects, and the
  middleware may refuse it: the login expired, or what the page shows is gone. Datastar retries a refusal (under
  `retry: 'always'`, every status but 200, 204 and 3xx), then gives up, and it follows a redirect to a response it
  can't use as a stream, so the tab stays stale with nothing to say so. Only the page request gets the app's real
  answer, so a refused stream becomes a stream that reloads the page. That takes middleware outside the app's, since
  the refusals happen before the handler. A 5xx is left to the retries, since a deploy or a restart gives one for a
  moment. A page that answers while its stream is refused would reload forever, so the reload sets a short-lived
  cookie naming the page and the load told to reload, and a refusal from a later load of the page gets a 204, which
  ends Datastar's retries. The load told to reload is exempt because Datastar retries a finished stream after a
  second, and a slow reload lets that retry out first. An open stream isn't asked again: what the page shows going
  away while it is open, access included, is for the islands to read and render.
- **A failed pump closes its stream.** A session whose own loop throws is closed with its stream, so the client
  reconnects into a new session, instead of staying attached to one that will never send.
- **Sync Ring streams by holding the thread.** A sync server ends a response once its body is written, so a sync
  stream request holds its thread until the stream closes, as any blocking server streams. On virtual threads that
  costs a parked thread. Requiring async Ring instead would demand the async arity of every middleware in an app's
  stack.

## The page

The page is the app's, and the root island is the session's. The page renders once, and opens the stream that patches
the islands, so what sets the session up can't live in an island: a patch morphs an island back to its render, and
would strip the attributes that opened the stream. `ring/mount` is the session's part of the page: the element the app
mounts in, holding the first frame, opening the stream and keeping the state the browser owns. It reads the tab and
the frame from the page being rendered, as `ui/page-attrs` reads the page's user, so `:page` needs only its request,
for what is the app's own: the head, its Datastar and the CSP nonce.

- **The placeholder is the library's.** Without a first frame, the page holds an element with the root island's id,
  which a fresh session's root is patched into. The id is a digest of the root's name, so an id the app wrote by hand
  never matched (logbook, 2026-10-10): `mount` takes it from `:root`.
- **A page that can't update fails as it is served.** One that opens no stream would render, never update, and leave
  its session to wait out its grace, with nothing logged. One without `ui/page-attrs` works, so it is logged, once: an
  app with no state the browser owns and no drop areas loses only the drop guard.
- **`:open-when-hidden` is the app's, not the page's.** It is a choice for a deployment, development's browser
  automation, and every page of the app makes it alike.
- **The session's context has the request that created it.** An island deep in the page reads the page's path from
  `use-session`, where an app would otherwise bind it itself, around its handler. The request is the page's, or for a
  tab rebuilt after a restart its stream's, to the same URL: its path and query are the page's either way, and its
  headers the request's.

## Actions

Actions are capabilities: an unguessable token, bound to the user, that exists while an island renders it. The
authority check is the island's decision to render the action, and the closure carries it. A request can race a
revocation by up to a frame, as in Electric, so writes that matter check again in the write path.

An action navigates through its response: a handler returns `(navigate path)`, and the response is a script, which
Datastar runs, giving it the page's nonce in CSP mode. A signal the page watches, the alternative, needs an element
declaring the effect to stay mounted until the response lands, and makes page state of something that isn't. An event
over the stream would arrive only while the stream is up, apart from the click that caused it. The target must be a
path on the page's origin, checked when the handler builds it, so a handler passing on what a client sent can't become
an open redirect. A page on another origin, such as a sign-in provider's, takes an explicit `{:external true}`: the
option is where the handler says it built the URL, and it is easy to find in review. Only `http` and `https` pass,
since the script assigns the URL to `location`, where `javascript:` would run in the page. The alternative, an app
route redirecting to the provider, is one every app wanting it would write, with the URL looked up again from an id.

An action's handler reads the request it came on with `request`, a scoped value bound around the handler's call, so
its arguments stay what the client sent. It is the action's own request, not the session's. A render sees the request
that created its session, fixed for its life, and the two differ where it matters: a cookie the page's own response
set is on every later request but not on the page's. Outside an action it is unbound, and `request` throws, rather
than falling back to the session's request: a render that read it, with a handler using what it read, would hand the
action the session's request with nothing to show for it. Middleware binding what it reads from each request also
reaches the handler, which runs on its request's stack, but it binds for the page's request too, where renders read it.

A page whose subject moved, such as an item moved into another group, goes to its new URL through an element the
island renders, `navigate-element`, which runs its script with `data-init`. That the page is at the wrong URL is
state, not an event, so it is rendered as the rest of the island's state is: a tab gets it in a patch, in the first
frame of a page, or in the first frame of a session its stream reconnected into, where an event sent once over the
stream would miss a tab whose stream was down. Datastar runs an element's `data-init` when the element is added, or
when that attribute changes, and the morph sets only the attributes that differ, so later renders of the same element
don't run it again. The script compares the path with the page's URL first, both as the browser resolves them, since
an island comparing a canonical path with the request's in another encoding would otherwise reload the page in a loop.

Signals reach the page outside an action through the island, `use-patch-signals`, for work that lands after its
action answered. Setting a signal is an event, not state, so it goes over the stream once, as an action's answer would
have carried it, where the way before rendered the result into a hidden element's `data-signals`: state the island had
to keep, applied whenever Datastar applies that attribute, again when it changes. A `patch!` posts to the session's
inbox, as a state change marks it, and the pump sends the patch after the frame rendering what changed with it, so the
elements bound to its signals are in the page first. One from an island no longer mounted, as the instance that made
it, is dropped, as a late `set-value!` changes nothing anyone sees. While the tab is away, patches wait for it, so a
result landing during a blip isn't lost, and they don't pile up: a patch is merged into the one before when one patch
does both, as Datastar applies merge patches, which is always but where the first replaces a value and the second
merges a map into it. Then the two go in turn.

A value only the browser knows when an action is invoked, such as which reference in a block of HTML was clicked,
travels in that action's body, beside its signals. Datastar's `payload` replaces the signals in the body, so the
expression puts back the ones `@post` would have sent: all but those under a key starting with `_`, as Datastar
filters them, read peeking, as `@post` reads them. The alternatives:

- **A signal**, filled by the expression before it posts, is how it was done without support. It makes page state of
  something that isn't: it outlives the click, and every later action on the page posts it. A local signal, which
  `@post` leaves out, would leave out this action too. Signals are shared by the whole page, so every instance of the
  island fills the same one.
- **A header** has a limit of a few KB, shared with the cookies, and takes only ASCII.
- **The URL** would put the value in access logs.

A value-taking action vouches for less than an action per element: its token says the island rendered it, not that
the island rendered what the value names, so the handler checks that.

## State the browser owns

The browser owns some state: it changes without a round trip, and outlasts a page load. Two kinds differ in what the
server must know of it. Whether a section is open is small, and the server renders it in the first frame, with no flash
of the default. A draft the user is writing is large, and the server needn't render it, only take it when the user acts.

- **Two stores, named for where they keep it.** The first goes in a cookie, which every request carries; the second in
  `localStorage`, which none does. The store decides when the server sees a value (as the session begins, or never
  unasked), how much it holds, and what travels with each request, and a developer reads all three off its name. So
  each is a namespace, `cookie` and `storage`, whose hooks say which, rather than an option on one `defstate`: under
  that, the server's read would have meant two things, a value from the session's request for one, and a value pending
  until the browser answers for the other. What works over any signal, `attr` and the helpers over a handle, stays in
  `ui`, as does `page-attrs`, since an element takes one signal-patch handler and one effect.
- **Signals carry it, not the server's copy.** The server reads a cookie, but only as the request that created the
  session carried it: its copy is stale as soon as the user changes anything. A cookie read by middleware, with
  `data-preserve-attr` keeping the browser's attribute through morphs, was the way before: it protects an element that
  stays in the page, but an island that unmounts and mounts again renders the stale copy. A signal outlives any element,
  so a bound attribute (`data-attr`) takes the browser's value wherever and whenever the element appears. The server's
  value matters only for the first paint, before Datastar runs, and stays in the attribute for that.
- **`data-attr` keeps it right; `data-preserve-attr` spares churn.** Datastar's attribute binding watches the attribute
  it sets and sets it again when something else changes it, before the page paints. Without `data-preserve-attr`, each
  patch of the island would write the stale value and have it undone; with it, the morph leaves the attribute alone.
- **One primitive over any expression.** `attr` renders an attribute's first-paint value, binds it to an expression
  and preserves it; `shown` and `aria-expanded` are it over a state's handle, and an app's own signals use it directly,
  with the first-paint value the server works out. It takes the element's attributes and returns them with its own:
  `data-preserve-attr` is one attribute listing every name, so two helpers' maps merged would keep one list. An `aria-*`
  state is a word, where Datastar writes `true` as an empty value, which no state reads as true; the expression's
  booleans are turned into words in the browser, and a nil still removes the attribute, which `String(...)` would
  write as `"null"`. Text needs none of it: `data-text` sets the text again when a morph changes it, as `data-attr`
  does an attribute (logbook, 2026-10-09).
- **A var is the identity.** `defcookie` and `defstorage` declare a state where the code using it lives, next to one
  island or in a namespace several share, decoupled from both the island tree and the app. Declared in the app map, a
  state sits far from the islands using it; declared on an island, as its `:key` is, a state can't be shared by an
  island elsewhere. An item, such as a group's id, gives a state per item, so one declaration covers states that depend
  on data. An item's name can't hold a `.`, which Datastar reads as a step into the signal.
- **Declared where it's used, if missing.** Every island using a state declares its signal on its root, with
  `data-signals__ifmissing`, which Datastar applies per leaf. So whichever renders first declares it, and a later
  declaration, a remount or a patch carrying the stale copy changes nothing: no island owns the state, and none has to
  come first.

### In a cookie

- **A handler keeps the cookie, merging.** One handler on the page writes each change of a `_cookie` signal into the
  cookie, merged with what it holds, where writing the whole of `_cookie` would let each tab undo another's changes.
  Declarations patch signals too, so it drops values equal to their state's default, which also keeps the cookie to
  what the user changed; it drops states no longer declared, and the oldest values past a size well under the 4 KB a
  browser keeps. It is an arrow function taking no arguments, called with none: Datastar splits an expression that
  returns a value into statements at each `;` outside strings and such a function, and returns the last, so any other
  shape is cut apart and fails to compile (logbook, 2026-10-08).
- **The cookie is the client's.** A value that isn't valid for its state reads as the default. Values are booleans,
  numbers and strings.
- **An action sets a state as a signal.** A handler returns a state's signals (`patch`), checked as it builds them:
  the islands using the state follow, and the body's handler keeps it, as it keeps the browser's own changes. That
  covers an item the page doesn't render too, whose signal the patch creates.
- **For the page an action opens, signals ahead of the navigation.** A handler that opens a page with a state chosen
  for it, such as a new item's, or that uses up a draft, has its store written before that page loads. Signals patched
  over the session's stream would race the action's answer, which opens the page; so the answer carries both, as
  events Datastar handles in order: `navigate`'s `:signals`, then the script. The body's handler keeps what they set
  as they land, for either store, so `navigate` takes the map a handler returns rather than an option per store and
  operation. It used to put a copy of the cookie's writer into its script, for cookie states alone. `Set-Cookie` on
  the action's response would write the cookie from the server, as a second writer of its format, in Clojure, to keep
  in step with the merge, the defaults and the cap, and couldn't reach the storage. `navigate` refuses an option it
  doesn't take, so one left over from a rename fails loudly.

### In the browser's storage

- **The server stores none of it.** A draft should outlast a reload, a restart and a deploy, in the one browser. The
  session's memory loses it on a restart, and a store of the app's own is more than one browser needs. So the browser
  keeps it, and the server sees it when an action passes it as its value, which `decode` reads back, untrusted.
- **Only edits are kept.** A declaration patches its signal, as the user's change does, and the handler can't tell the
  two apart. So the declaration records, in the page, the default it declared the value with, and the handler keeps an
  entry only while the value differs from it. Keeping the default would store an entry for every value merely shown,
  and hide a default the server revised behind its first version.
- **Declared from the entry.** The declaration is an expression, `island/js`: the browser's entry, under the state's
  version and young enough, else the default. Datastar applies it per leaf, which for a map would bring back a key the
  user removed from a stale copy; but the copy it reads is the browser's own entry, the signal's mirror, which has no
  such key. A server's copy would bring the key back, which is why maps in a cookie state are parked.
- **Shown by bindings, read on request.** The first frame renders the default: no request carries `localStorage`, so
  the server can't render the browser's value before the page's script runs. Most of what shows a draft is a binding,
  which needs no server. What only the server can render from it, a row per item or a count, reads it with `observe`:
  a hold that mints a token bound to the user, as an action's is, and declares `_storageWatch.<token>` naming the
  entry. The handler sends the entry to the token as the watch appears, and a moment after each change. When the
  island stops reading, the token is revoked, and the next send's 404 drops the watch. Its `pending` isn't a
  resource's, so the first frame doesn't wait for it: the browser can't answer before it has the page.
- **Forgetting is a signal of its own.** Datastar deletes a signal patched to null without the patch event a handler
  hears (logbook, 2026-10-09). So `forget` patches `_storageForget`, which the body's handler hears whether or not
  anything on the page uses the entry: it drops the entry, sets a declared value back to its default, and clears the
  signal, so the next forget is a change again.
- **The user's own, and bounded.** An entry's key holds a digest of the session's user, so the id itself isn't in the
  browser, and a page loaded for another user drops the entry. Clearing on sign-out would need Tropical to see sign-outs,
  which are the app's. The same pass, as the page starts, drops entries older than their state's age, stored under
  another version, or of a state no longer declared; a write the storage has no room for drops the oldest first.
- **JSON, not Transit.** The browser changes the value itself, in expressions and bindings, so it has to be one the
  browser reads. `:encode` and `:decode` map Clojure values at the server's edges, and the declaration checks that the
  default comes back as it went. A nil, which Datastar takes for deleting a signal, and a key holding a `.`, which it
  reads as a step, are refused.
- **Expressions Datastar can't split.** The declaration is one expression without a `;`, and JSON in an expression
  writes a backslash as `\u005c`: Datastar's statement pattern reads the end of `"a\\"` as an escaped quote, and
  would go on to cut an expression at a `;` in a later string (logbook, 2026-10-09).

## State shared on the server

Every route is a page load and a session of its own, so state that should outlive a page, such as a user's toasts,
which every tab of theirs shows and a navigation keeps, had no home: an island's state ends with its page, and a `def`
never ends. In Electric a session lasts a tab's whole life, so its session-locals covered most of this, and globals
were a `def` there too. An app keeping such state in a `def`'d atom does the bookkeeping a resource's lifecycle
already does: who still reads it, and when to drop it. So shared state takes that lifecycle.

- **The var is the identity, the key picks the instance.** Keyed by the key alone, every feature keyed by a user's id
  would share one state. Keyed by the call site, as `observe` is, nothing outside the render could name it, and an
  action, a resource or a task of the app's own has to change it. `defshared` declares it, as `defcookie` does, with
  one `:init` for every reader.
- **The resource registry holds it.** An instance is a resource under its state and key: held while read, lingering
  after, under the registry's one lock. It opens holding `:init`, live, so the render that creates it has its value,
  where a resource's subject runs on a thread of its own and its first reader sees `pending`. Its linger is its
  state's, since state meant to outlast a page may want longer than a subscription's churn needs.
- **A change with no instance is dropped.** No island reads it, so no one would see it, and an instance created for
  it would only linger and go. `swap!` returns whether it found one.
- **`swap!` runs its fn once, under the instance's lock,** where an atom retries it: a quick fn costs the same, and
  one with a side effect isn't run twice.
- **In memory, by intent.** It is the stand-in for a durable pipeline, one that delivers to every channel and every
  server, and suits what can be lost harmlessly: a restart loses it, and each server behind a balancer holds its own.
- **Not per tab across page loads.** Only `sessionStorage` names a tab across its page loads, and a page load can't
  carry it, so a first frame couldn't render such state. A user's id or a browser's cookie is on every request.

## Scripts

Some of a page has to run in the browser, such as a web component that tracks scrolling. An island declares the script
it needs, as it declares a signal, and the page loads it once, whether the island is in the first frame or patched in
later (measurements in the logbook, 2026-10-08).

- **Modules, which the browser runs once.** A page keeps an ES module per URL for its life, so any number of tags for
  one URL run it once, and declaring a script needs no bookkeeping in the page. A classic script runs again for each
  tag.
- **Sent by the session, not in the island's HTML.** A tag inside the island would go out with every patch of it, and
  sit among its children. The session sends each script once, appended to the head, ahead of the first frame that
  needs it, and a page load keeps what it loaded, so a reconnect sends nothing again. Datastar gives every script tag
  that arrives in a patch the page's nonce.
- **The first frame's scripts go once.** The page puts them in its head with the nonce (`ring/scripts`), so they load
  alongside Datastar, and the stream sends them only to a page that didn't. Tropical doesn't render the page, so the
  helper records that it ran, as `mount` does; a page that left them out still works, its scripts loading once the
  stream connects. They went both ways before, which cost a second tag for each, for a module that runs once
  (logbook, 2026-10-10).
- **A URL of its own, outside the routes.** Served under the page's URL, a script would be fetched again for each page
  that uses it, and pass the route's middleware. `wrap-scripts` serves it under one path, named by its var and the
  hash of its content, so it is kept for good and a change is a new URL. A page from before the change, asking for the
  old version, gets the current one, revalidated, rather than a 404.
- **A blocked module stays blocked.** A page that fetches a module without the nonce has it blocked, and the browser
  keeps that failure for the URL: a later tag with the nonce doesn't run it. So the page's own tags need the nonce,
  which only the page knows.

## Uploads

A file's bytes go from the browser straight to where the app keeps them, such as a bucket taking presigned PUTs. Through
the app, a large file would hold a request and its memory twice over, once arriving and once going on to storage.
Datastar binds a file input into signals as base64, which an action's body cap rules out for all but small files. The
shape is Phoenix LiveView's external uploads: a fn per file names the target, the browser sends the bytes there, and
the server takes the files when it needs them.

- **The island keeps the list, not the app.** Were the app to record files and arrivals from handlers of its own,
  every page taking files would repeat that bookkeeping, with a third handler for failures. The hook holds the list
  and renders the island as it changes, so a refused or failed file shows without a handler, and `take!` reads and
  removes what arrived at once, where a send action reading the render's list could miss a file arriving as it ran.
  `:target` runs one pick of the island at a time and sees the list with the pick's earlier files, so a limit it
  checks holds against two picks at once.
- **A batch once nothing is on its way.** Work over all of a project's files wants a pick taken in whole, not a file
  at a time. Taken in a render that sees none uploading, the batch puts a side effect in the render, which holds only
  while every change to the list is rendered. Every change to the list is made under the hook's lock, so the hook
  sees when the last upload ends, by a report, `:arrived` refusing it or `remove!`, and passes what arrived to
  `:settled` then, still under the lock, so no report or `take!` falls between reading the batch and taking it. One
  failed or refused doesn't hold back the rest. If `:settled` throws, its uploads fail, as when `:arrived` throws:
  taking them would lose them, and leaving them arrived would hand them over again with the next batch, to work that
  may have started.
- **The server takes an id from the browser, never a reference.** `:target` returns the app's reference, such as the
  storage key, and the browser reports on an upload by the id the island gave it, to the island's token. So a client
  can't make the app take an object of its choosing, or another session's. The key is no secret: it is in the
  presigned URL's path. The report that an upload arrived is still the browser's word, so `:arrived` can check.
- **Drop areas by `dragover`.** While files are dragged, the browser fires `dragover` at the element under the pointer
  again and again, and it bubbles: the innermost area around that element takes the drag and stops it there, so an area
  inside another needs no counting of the `dragenter` and `dragleave` each child fires, and only the area that would
  take a drop shows that it would. A drag that reaches the window is over no area. The page's body refuses it there,
  since the browser would open a dropped file in the tab, and clears what an area showed: `ui/page-attrs` carries that,
  as it carries the browser's stores, since the document around the root island is the app's, and tropical renders from
  the root island down. An area is named by its uploads' token, so `drop-area` and `dropping` take the `start` that
  `use-uploads` returns, and two areas for one list are one area to `dropping`.
- **The requests are an action's.** The description of the files and the reports post to the page's URL with a token
  in `Tropical-Action`, minted bound to the user and revoked with the island, so they pass the route's middleware and
  need no route of their own. `action/use-token` is the primitive: the handler gets the body, and a map it returns
  goes back as JSON, which the script reads.
- **Progress stays in the browser.** Bytes sent are in a local signal per upload, so a bar moves without a round trip;
  what the server needs, whether an upload arrived, it hears once. `XMLHttpRequest` sends the bytes, since `fetch`
  can't tell how much of a body it has sent. The island declares each signal only if missing, since the frame
  showing an upload can reach the page before the browser has read its target, and a binding reading a missing
  nested signal throws.
- **The script reaches the signals through the expression.** The expression imports the module by its URL and passes
  it Datastar's `$`, the live signals. Importing Datastar from the module would need the URL the app loaded it from,
  and a second copy would be a second store; a global would be one more name on the page. Reading a signal that
  doesn't exist makes it `""`, and setting an object replaces the keys of the one there, so the script sets the
  signal holding every upload whole only while it isn't an object yet.

Left out: aborting a PUT when its file is removed, which the script could do by id; resuming or splitting large
uploads; and retrying a failed one, which the user can do by picking it again.

## Names the browser sees

What the page needs to name, it names after the app: an island's element is found by its id, the path of the
functions that placed it; a cookie or storage state's signal by its var, namespace and all; a script's URL by its var.
None of it is a secret, but none of it is the page's business either, and a script running in the page, or anyone
reading it, learns the app's layout from it.

- **Digests at the edge.** Each of these names is a SHA-256 of the app's name for it, cut to a few hex digits after a
  letter, which a CSS selector, a Datastar path and a URL all take as they are. Only what reaches the browser
  changes: the runtime keys islands by their paths, logs them, and counts their renders by them, and a state's var
  stays its identity. So the server reads as before, and a failed island's log line gives its element id beside its
  path, to find what a user saw. The default error view stopped naming the island.
- **Deterministic, without a salt.** The digest of a name is the same on every server and in every version: a tab
  reconnecting into another server after a deploy is morphed in place, and what the browser keeps under a state's
  name, in its cookie or its storage, is found again. A salt would have to be configured alike on every server, for
  good, to keep that; without one, a name can be guessed and checked, which is more than the opacity is for.
- **Lengths by what a collision costs.** An island's element id takes 16 hex digits: two islands under one id would
  patch each other, silently. A state's signal takes 10, since a collision throws as the second is declared, and the
  cookie holds every name it keeps. Ids are shorter than most paths, so patches are smaller too.
- **Readable on request, never by default.** The JVM property `co.multiply.tropical.readable-names` shows the names as
  written, for debugging. A mode is a trap: what names an element or a signal by hand works in it and fails without
  it. So the safe direction is the default, everywhere, `bb dev` included: readable names are something a developer
  asks for, with `bb dev:readable`, not what they work in. Code names them through the helpers (`ui/signal`,
  `ui/path`), which follow either way. A server's setting renames every state, so every server of an app runs one
  way.

## Failures and logging

- **The error view is the app's.** What renders in place of a failed island reaches the client, and an exception's
  message can carry what the user shouldn't see: a query, an id, another service's error. So the default says only
  that a part of the page failed, without naming the island, and an app that wants more, in development, gives its
  own `:error-view`.
- **`clojure.tools.logging`, not a backend.** A library shouldn't pick where logs go. tools.logging is the facade most
  Clojure apps already route, to SLF4J, Telemere or others, and exceptions go with their stack traces. A hook in the
  app map was the alternative: it fits a session's events, but not the resource registry's, which belong to no app.

## The wire

- **Datastar's specification, not its server SDK.** The contract is Datastar's client and its SDK specification. What
  tropical sends is small (two event types and a comment), so it implements them itself, pinned by tests to the
  specification byte for byte. Owning the wire makes the stream's lifecycle the response body's own (attach, then hold
  or return, then close), lets the heartbeat be an SSE comment that touches no client state, and keeps the write path
  free of garbage. The SDK it replaced was a release candidate trailing the client, and allocated about 10 KB per
  event.
- **Transport in Java, vocabulary in Clojure.** `EventStream` guarantees whole, ordered events, a final close, and
  closing on a failed write. `sse` says which events tropical sends and what they carry.
- **JSON by Oda.** Signals go out as JSON and actions' signals come in as JSON, per event and per action. Oda writes
  and parses straight on UTF-8 bytes with no dependencies, and is several times faster and lighter than the
  alternatives measured (see the logbook). It needs JDK 25, which is tropical's floor.

## Compressing the stream

- **In tropical, not the server.** A tab's stream is one response that never ends, written a frame at a time, and a
  server's compressor can't encode it. Jetty 12.1's zstd and brotli encoders, read from their source, flush only at a
  response's last write, so the frames would wait in them until the stream ended; its gzip flushes on each flush only
  with sync flush turned on. Tropical knows where a frame ends, so it encodes the stream itself, flushes the encoder
  once per frame, and works the same on any Ring server. The page and the actions are responses that end, which a
  server's compression handles, and are left to it.
- **One encoder for the stream's life.** What the stream sent stays in the encoder's window, so a frame repeating an
  earlier one, as a morph of an island mostly repeats its last, is sent as a reference to it. On the demo's stream, a
  frame went from about 700 bytes to 43–52, whichever encoding. A 32 KB island re-sent with one row changed went to
  40 bytes with brotli and 140 with zstd, but 3.4 KB with gzip, whose 32 KiB window doesn't reach its last copy.
  Compressing each frame on its own would lose that, which is most of the gain.
- **A frame is flushed once.** Its patches, inserts, removals and signals are written, then flushed together. Each
  flush ends a block of the encoding and costs bytes: a flush per event sent 6–8 % more on the demo's stream. An
  uncompressed stream writes less often for it too.
- **The settings are tropical's.** Level, window and table sizes trade memory per open stream, and CPU per frame, for
  how small a frame gets. They were picked by measuring streams of frames (see the logbook), and an app names only the
  encodings, in its order. Exposing them would make each library's parameters part of tropical's API, and the level
  gains little: the window is what pays. One budget for every encoding, gzip's 0.25 MB, was tried: windows that small
  left zstd no better than gzip on a large island. If a deployment needs to spend less, the setting to add is memory
  per stream, which tropical would map to each encoding.
- **zstd's library is tropical's.** It was the app's at first, zstd-jni, as brotli's was. But an app whose
  dependencies pin an older zstd-jni can't have a newer one beside it, and zstd-jni can't be shaded: its native
  methods are bound by name to its package, 149 functions named after `com.github.luben.zstd`, with no registration
  that a relocated class could use. A classloader of tropical's own could hold a second copy, but its jar would have to
  sit outside the app's classpath, wherever the app is deployed. And the one pure-Java zstd at hand doesn't flush:
  aircompressor's `ZstdOutputStream` holds everything until it closes. So tropical ships libzstd itself and calls it
  through the JDK's foreign function API, which needs no glue code, only zstd's own library: built from zstd's signed
  release source by `native/build-zstd.sh`, compression only, single-threaded, exporting zstd's API alone and binding
  its own calls to itself. It needs nothing of libc but six memory functions, so one Linux build runs on glibc from
  2.14 (2.17 on aarch64) and on musl. Its output is zstd-jni's, byte for byte, at the same version and settings. Each
  write is copied into native memory, where zstd-jni pins the Java array for the call instead, which would hold off
  the garbage collector for as long as a call compresses: about a tenth more CPU per frame (logbook).
- **brotli's library is tropical's too.** It was the app's: brotli4j, added with an artifact per platform and reached by
  reflection. That left brotli open to what zstd-jni ran into, a brotli4j of another version among the app's
  dependencies, and brotli4j builds no library for musl. So tropical ships brotli's encoder as it does libzstd, built by
  `native/build-brotli.sh` from brotli's release source: the encoder and the code it shares with the decoder, exporting
  brotli's API alone and binding its own calls to itself. Of libc it needs the six memory functions libzstd does and
  `exit`, and of libm `log2`, which glibc 2.29 gave a new version; the build binds the first, so the library runs on
  glibc from 2.14 (2.17 on aarch64) and on musl, as libzstd does. brotli signs no release: the source is GitHub's
  archive of the release tag, checked against the release commit, and it is the source brotli4j carries, file for file.
  Its output is brotli4j's, byte for byte, at the same version and settings. Like every build of brotli's own, and
  brotli4j's, the encoder ends the process if malloc fails: brotli's alternative, cleaning up and returning an error, is
  built by none of them, and tracks at most 128 live allocations, unchecked outside debug builds. On Linux, malloc
  failing is rarer than the kernel ending the process for its memory.
- **An encoding is loaded when the app names it.** The native libraries load when the handler is created, from the
  encodings the app names, so an app that names none loads none. An encoding the app names that tropical can't load
  fails the handler's creation, not the first stream.
- **The request chooses among the app's.** A stream is encoded with the app's first encoding among those its request
  weighs highest, or not at all. zstd is in every engine now (Chrome 123, Firefox 126, Safari 26.3), brotli in every
  current browser, and gzip in all.
- **Its size can leak a secret (BREACH).** A compressed response carrying a secret and text an attacker can vary leaks
  the secret, a byte at a time, to someone who watches its encrypted size. A stream suits that better than a page: it
  is compressed in one window for its life, and flushed per frame, so each frame's size shows on its own. Tropical's
  own tokens in the stream are bound to their user, so one learned this way is no use without the user's session. What
  the app renders is the app's to judge, which is why compression is opted into.
