# Bidirectional channels

An island can declare a bidirectional channel for a browser component's own protocol. The application sends strings
or bytes and handles incoming messages in order. Tropical buffers messages, reconnects and resumes behind that API.
Datastar still carries HTML over SSE; channel traffic and connection status never trigger rendering themselves.

A **generation** is one continuous conversation. Its queues and application state survive socket replacements.
If either endpoint cannot resume the conversation, Tropical closes the old endpoint, waits for application
processing and cleanup, and asks the application to initialize a fresh generation. The app keeps its durable
state outside that peer initialization.

## Declare a channel

`use-channel` declares the connection under an island key. Its callback receives a **duplex endpoint for one
logical generation**, implementing core.async `ReadPort`, `WritePort` and `Channel`. Taking reads messages from
the browser; putting sends messages to it. `nil` is reserved for EOF. The callback runs on a compelled Quiescent
task and can run its processing loop directly or return the task that runs it:

```clojure
(require '[clojure.core.async :as async]
         '[co.multiply.quiescent :as q]
         '[co.multiply.tropical.channel :as channel]
         '[co.multiply.tropical.script :as script])

(script/defscript editor {:resource "app/editor.js"})

(defisland document-editor [document]
  (let [connection
        (channel/use-channel [:document document]
          (fn [{:keys [channel reason generation]}]
            (q/task
              ;; Keep the document; create fresh per-generation protocol state.
              (let [peer (connect-peer! document channel reason)]
                (try
                  (loop []
                    (when-some [message (async/<!! channel)]
                      ;; This application's receive function returns a task.
                      @(receive-peer! peer message)
                      (recur)))
                  (finally
                    @(disconnect-peer! peer)))))))]
    (script/use editor)
    [:document-editor (channel/attrs connection)]))
```

The context is `{:channel endpoint :reason :initial|:continuity-lost :generation id}`. The endpoint stays identical
through ordinary reconnects. Continuity loss closes it, waits for the callback's task to finish including cleanup,
then calls the callback with a fresh endpoint. An old endpoint stays closed; stale code cannot put into its replacement.
Initialization and processing run in the Scoped scope of the request establishing that generation.

A stable key retains the declaration through renders. Rerenders update the callback for future generations;
existing work remains unchanged. Change the key when the subject changes. Several channels can be declared
under separate keys in one island. Each declaration has one active browser peer; a different peer starts a new
generation. A shared document can outlive all of them ([Reading](reading.md)).

The callback's task is the whole peer lifetime, rather than an initialization task returning handlers. It must
finish after processing and cleanup. Do not launch a detached worker and return immediately. Tropical neither
waits for this lifetime to finish before exchanging messages nor cancels it on socket loss or unmount. A task can
await resource acquisition before starting its receive loop; accepted input stays queued in the meantime.
The callback may also return a core.async completion channel, such as the result of `go`; Tropical waits for its
result or closure. That channel must signal only after the peer's work and cleanup finish.

The endpoint works with `take!`, `put!`, `<!!`, `>!!`, `<!`, `>!` and `alts!`/`alts!!`. Use `<!`/`>!` inside a
core.async `go` block; use blocking operations in a Quiescent virtual-thread task. Process one message before
taking the next if application processing must remain ordered. Multiple readers distribute messages; they do not
broadcast them or establish processing order.

## Putting and backpressure

A completed put establishes **local admission**. The message is retained for retransmission until the peer
acknowledges it or this generation retires. Byte arrays and buffers are copied when submitted, including pending puts.

When the outgoing queue is full, `>!!` blocks, `>!` parks, and callback-based `put!` leaves a pending operation.
Tropical retains those exact bytes in submission order. `put!` returning immediately does not prove admission;
its completion callback does. `offer!` tries immediate admission and returns a falsey result when it cannot.

Pending puts have a separate bounded budget using the same byte and message limits as the retained outgoing
queue. Exceeding that budget throws `ex-info` with `:type :backpressure`. There is no sliding or dropping buffer.
Awaiting each put naturally bounds a producer; applications submitting many pending puts must also bound their
producers. If message generation advances protocol state, retain and retry a message refused by `offer!` or by
these limits. Logging refusal and continuing would lose that message.

Do not wait for outgoing capacity while holding a shared document gate: one offline peer must not prevent other
peers or backend updates from using it. Submissions can happen in order inside the gate, with completion awaited
after releasing it. As with any bounded duplex protocol, consumption must continue when writers are backpressured.

`(channel/flush! endpoint)` returns a Quiescent task for peer receipt of puts committed before the call. Await put
completion first to include a pending put. Receipt does not establish processing or durable storage. Cancelling a
flush wait does not retract messages; retirement fails unsettled flush waits. Simultaneous waits are bounded by
the message limit. Callers needing delivery before voluntary closure can flush first, with their own deadline.

## Browser helper

`channel/attrs` puts the descriptor in `data-tropical-channel`. The descriptor contains `token`, `path` and `module`.
The hook declares that module through the normal [script mechanism](scripts.md), with CSP and content versioning.
The component imports it and mounts a channel:

```javascript
const descriptor = JSON.parse(element.getAttribute('data-tropical-channel'))
const {mount} = await import(descriptor.module)
const channel = mount(element, {
  open: async ({send, flush, reason, generation, signal}) => {
    // reason is 'initial' or 'continuity-lost'. The documentReplica is owned
    // outside this callback; only its peer state is initialized here.
    const peer = await connectPeer(documentReplica, send, signal)
    return {
      receive: message => peer.receive(message),
      close: info => peer.dispose(info),
    }
  },
  status: ({state}) => showConnectionStatus(state),
})
```

`open` returns `{receive, close?}` or a promise of that map. It runs once per generation. The server and browser
initialize before application messages are exchanged. Initializers may enqueue messages, but must not wait for
remote replies or `flush` before returning their handlers: the other side is waiting for initialization to finish.

`send` admits into the local outgoing queue synchronously, copying bytes, or throws. `flush()` returns a promise
for peer receipt of messages admitted before the call. Both functions are also available on the returned handle.
Status is optional and browser-local: `connecting`, `open`, `retrying`, `recovering`, `paused`, `failed` or `closed`.
The returned handle can queue sends before the first connection and while its transport is offline.

`mount` follows descriptor changes after an SSE session rebuild. A change of backend can result in
`continuity-lost`; the application reestablishes synchronization from its retained document. The helper can also
resume an unchanged descriptor without a DOM mutation. `connect(descriptor, options)` supports descriptors held
elsewhere and adds `update(nextDescriptor)` to the same interface.

Call `dispose()` when the component leaves the document. It returns a promise that settles after accepted input,
cleanup and the bounded outgoing flush. A custom element can defer removal handling by a microtask and check
`isConnected`, to distinguish removal from a morph moving the element. Guard asynchronous imports with
`isConnected` too. See `dev/demo/socket.js` for a complete component. Draw into a shadow root so ordinary patches
preserve the component's state ([Scripts](scripts.md)). `pagehide` suspends only the transport and `pageshow` resumes
it; a hidden tab's channel remains active.

## Delivery and continuity

- Strings remain text; JVM byte arrays/`ByteBuffer`s and browser `ArrayBuffer`s/typed arrays remain binary. Receives
  get a String or owned ByteBuffer on the JVM, and a string or Uint8Array in the browser. Tropical adds its own
  envelope without interpreting the application payload; no application codec or base64 conversion is required.
- Messages have generation-local sequence numbers. A sender retains them until the peer acknowledges admission
  into its receive queue. Interrupted writes and lost acknowledgements are resolved by the resume handshake;
  retransmitted messages are suppressed before application delivery.
- Peer credits bound queued bytes and message counts. On the JVM, taking a message returns its credit and transfers
  ownership to application code. Work already taken, or prefetched into an application queue, is outside this budget.
  The browser callback helper returns credit after its receive handler settles, including active work in its bound.
- While both endpoints retain their generation state, reconnect resumes the same ordered stream and endpoint.
  Connection-attempt timeouts do not reset it. Ordinary network outages are retried with bounded exponential backoff.
- If an endpoint has lost the state needed to resume, the handshake selects a new generation. The old JVM endpoint
  closes, and its processing task must finish before the new callback runs with `:continuity-lost`. In the browser,
  accepted input drains and `close` finishes before its new `open` callback. Old outgoing bytes are discarded and
  never cross into the new generation. The app resynchronizes its retained document with fresh peer state.

These buffers and counters are in memory. This supplies continuity through transport interruptions while the
logical state is retained. Process loss, browser destruction or expiration can require a reset. Surviving backend
crashes with an acceptance guarantee requires durable admission and progress in the application; neither receipt
acknowledgements nor a reconnect provide that storage guarantee. A reset indicates uncertain delivery, rather than
proof that every outstanding operation failed.

## Release and draining

On the JVM, unmount, revocation, continuity loss or `async/close!` closes both admissions:

1. Accepted incoming messages remain readable in order, followed by `nil`.
2. New outgoing puts complete with `false`.
3. Buffered outgoing messages are discarded. Pending puts are consumed into a discard sink and complete with
   `true`, following ordinary core.async put semantics. This completion establishes no remote delivery.
4. The application keeps processing its accepted input and performs cleanup in `finally`. Tropical awaits the
   whole returned task before providing a replacement generation.

Closing the endpoint and finishing the application task are different events. EOF means the stream has no more
messages, including messages already handed to another reader. A single serial loop that awaits each operation
before its next take supplies the processing order and a natural cleanup point. The receive loop must not exit
just because a reply put returns `false`: there may still be accepted input to process.

An unreachable peer cannot prevent retirement from releasing parked puts or the application from consuming its
remaining input. There is no receive-processing timeout imposed by Tropical. If the application task throws,
Tropical logs the failure and retires its generation; it cannot complete failed application work or restart its
loop. Catch recoverable errors inside the application's loop if remaining messages must still be processed.

Resources needed for processing must live through the task's cleanup. An outer `observe` may own a subscription
that can stop independently; it must not shut down workers or clients still needed by peer tasks. Keeping an object
reachable does not undo explicit shutdown of its resources. A callback that acquires resources asynchronously
owns their cleanup even if its endpoint closes during acquisition.

The browser helper retains its callback API. `dispose()` drains accepted receives and cleanup, then bounds its
outgoing flush by `drainTimeout`. Its generation `signal` is not aborted on socket loss; it is aborted after input
drain, before cleanup. Initialization may be aborted earlier and must clean up partial acquisition.

Explicit sign-out/session revocation stops communication immediately. Accepted input remains available under the
application's existing authorization policy; apply any stricter domain revocation rules in that processing loop.
A normal hidden-tab or network disconnect can keep the session for `:resume-ms`, after which the ordinary session
grace applies. Live SSE can retain it longer. Unused declarations alone never keep a session alive.

## Limits and serving

Server options are the third argument to `use-channel`:

| Option | Default |
|---|---:|
| `:max-message-bytes` | 1 MiB |
| `:max-queued-bytes` | 4 MiB per queue, including a separate pending-put budget |
| `:max-queued-messages` | 64 per queue, including pending puts |
| `:init-timeout-ms` | 10,000 per transport handshake |
| `:send-timeout-ms` | 10,000 per transport write |
| `:ping-ms` / `:pong-timeout-ms` | 30,000 / 10,000 |
| `:resume-ms` | 60,000 |

Pending takes are also bounded by `:max-queued-messages`.

The browser has `maxMessageBytes`, `maxQueuedBytes` and `maxQueuedMessages` with the same defaults, plus
`retryDelay: 500`, `retryMaxDelay: 10000`, `connectTimeout: 10000`, `recoveryTimeout: 10000`, `recoveryLimit: 3`, and
`drainTimeout: 5000`. Recovery limits cover finding a replacement capability through SSE, not ordinary network
outages. Refused authentication, malformed protocol and application failures are terminal. Control pings/pongs are
internal; configure the ping interval comfortably below the server and proxy idle timeouts.

On each endpoint, the maximum message size must not exceed its queued-byte limit; inconsistent options are
rejected, including when one limit is left at its default. Limits count payload bytes (UTF-8 for text), excluding
channel framing. Messages exceeding the local maximum are refused at admission. If an admitted message exceeds
the peer's advertised maximum, the channel fails and outstanding flush waits fail, even when peer credits are
exhausted. Waiting for credit only applies to messages the peer can eventually accept.

The page's `ring/handler` serves capability checks and upgrades on the same route and query as the page, through
its normal middleware. `ring/request-kind` reports `:channel-check` and `:channel`. Checks use `Tropical-Channel`;
upgrades offer `tropical.channel.v2` and `tropical.cap.<token>` subprotocols, selecting the former. Tokens need not
appear in URLs. Every upgrade independently checks user, route, capability and Origin.

Origin is compared with Ring's scheme, server-name and server-port, or with the app's `:channel-origin` function
returning its public HTTP origin. Behind a proxy, use trusted origin information. Allow the corresponding socket
origin in CSP `connect-src` when restricted. Access changes must revoke the declaration or session; an open channel
does not rerun route middleware for every message ([Security](security.md)). Sticky routing remains useful; a server
without the session asks SSE to rebuild it and deliver a new descriptor ([Serving pages](serving.md#running-it)).

Use a Ring adapter supporting `Socket` and `AsyncSocket`. The demo and real integration tests use
`ring/ring-jetty-adapter` 1.15.5. Configure its binary message limit for the largest payload **plus nine bytes** of
channel framing. Control frames are small JSON arrays, capped at 1,024 characters. The native browser socket uses
`binaryType = 'arraybuffer'`. JSON frames only carry handshake and acknowledgement controls; application data must
use the binary envelope, which determines its type, size and sequence number.
