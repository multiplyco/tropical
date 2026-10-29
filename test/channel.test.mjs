import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import {webcrypto} from 'node:crypto'
const program = readFileSync(new URL('../resources/co/multiply/tropical/channel.js', import.meta.url), 'utf8')
const flush = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)) }
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return {promise, resolve, reject} }
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const descriptor = token => ({token, path: '/page?document=one', module: '/helper.js'})
function dataFrame(sequence, value) {
  const text = typeof value === 'string', data = text ? new TextEncoder().encode(value) : new Uint8Array(value)
  const result = new Uint8Array(9 + data.length); result[0] = text ? 1 : 2
  new DataView(result.buffer).setBigUint64(1, BigInt(sequence)); result.set(data, 9)
  return result.buffer
}
function readData(frame) {
  const b = new Uint8Array(frame.buffer ?? frame, frame.byteOffset ?? 0, frame.byteLength)
  return {sequence: Number(new DataView(b.buffer, b.byteOffset).getBigUint64(1)),
    value: b[0] === 1 ? new TextDecoder().decode(b.slice(9)) : Array.from(b.slice(9)), size: b.length - 9}
}
function browser(fetchImpl = async () => ({status: 204, headers: new Map()})) {
  const sockets = [], requests = [], timers = new Map(), observers = [], errors = [], window = new EventTarget()
  let nextTimer = 0
  const remote = {id: id(1), received: 0, values: [], autoAck: true, messageGrant: 64, byteGrant: 4194304, maximum: 1048576}
  class Socket {
    static OPEN = 1
    readyState = 0; bufferedAmount = 0; sent = []; throwOn = null
    constructor(url, protocols) { this.url = url; this.protocols = protocols; sockets.push(this) }
    open() { this.readyState = 1; this.onopen?.() }
    send(value) {
      const control = typeof value === 'string' ? JSON.parse(value) : null
      if (this.throwOn === (control?.[0] ?? 'data')) throw new Error('write failed')
      this.sent.push(value)
      if (control?.[0] === 'hello') {
        this.hello = control
        const resumed = control[2] === remote.id, receipt = resumed ? remote.received : 0
        if (!resumed) remote.received = 0
        this.control(['welcome', remote.id, resumed ? 'resumed' : control[2] ? 'continuity-lost' : 'initial',
          receipt, remote.messageGrant, remote.byteGrant, remote.maximum])
      } else if (!control) {
        const message = readData(value)
        if (message.sequence > remote.received) {
          assert.equal(message.sequence, remote.received + 1)
          remote.received = message.sequence; remote.values.push(message.value)
        }
        if (remote.autoAck) this.control(['ack', remote.received, remote.messageGrant, remote.byteGrant])
      }
    }
    control(value) { queueMicrotask(() => this.onmessage?.({data: JSON.stringify(value)})) }
    message(sequence, value) { this.onmessage?.({data: dataFrame(sequence, value)}) }
    drop(code = 1006) { this.readyState = 3; this.onclose?.({code, reason: ''}) }
    close(code) { this.readyState = 3; this.closeCode = code; this.closeCount = (this.closeCount || 0) + 1 }
  }
  const context = vm.createContext({window, location: new URL('https://example.test/page'), WebSocket: Socket,
    URL, TextEncoder, TextDecoder, ArrayBuffer, Uint8Array, DataView, AbortController, CustomEvent, crypto: webcrypto,
    queueMicrotask, console: {error: (...args) => errors.push(args)},
    fetch: (url, opts) => { requests.push({url, opts}); return fetchImpl(url, opts) },
    setTimeout: (fn, ms) => { timers.set(++nextTimer, {fn, ms}); return nextTimer }, clearTimeout: n => timers.delete(n),
    MutationObserver: class {
      constructor(fn) { this.changed = fn; observers.push(this) }
      observe() {}
      disconnect() { this.disconnected = true }
    },
  })
  vm.runInContext(program.replaceAll('export function', 'function'), context)
  return {sockets, requests, timers, observers, window, remote, errors,
    connect: (d, opts) => context.connect(d, opts), mount: (e, opts) => context.mount(e, opts),
    async open() { await flush(); sockets.at(-1).open(); await flush() },
    async retry() {
      for (const [n, t] of [...timers]) if (timers.has(n) && t.ms < 10000) { timers.delete(n); t.fn() }
      await flush()
    },
    async runTimer(ms) {
      for (const [n, t] of [...timers]) if (timers.has(n) && t.ms === ms) { timers.delete(n); t.fn() }
      await flush()
    },
  }
}
const empty = () => ({receive() {}})

test('text and bytes retain their type and socket replacement preserves the initializer', async () => {
  const b = browser(), opens = [], seen = []
  const c = b.connect(descriptor('one'), {open: ctx => { opens.push(ctx); return {receive: v => seen.push(v)} }})
  await b.open(); assert.equal(c.state, 'open'); assert.equal(b.sockets[0].binaryType, 'arraybuffer')
  assert.deepEqual(Array.from(b.sockets[0].protocols), ['tropical.channel.v2', 'tropical.cap.one'])
  b.sockets[0].message(1, 'hello λ'); b.sockets[0].message(2, [0, 255]); await flush()
  c.send('text'); c.send(new Uint8Array([3, 4])); await c.flush()
  assert.deepEqual(b.remote.values, ['text', [3, 4]])
  assert.equal(seen[0], 'hello λ'); assert.deepEqual(Array.from(seen[1]), [0, 255])
  b.sockets[0].drop(); c.send('offline'); await b.retry(); await b.open()
  assert.equal(opens.length, 1); assert.equal(opens[0].reason, 'initial'); assert.equal(b.sockets[1].hello[3], 2)
  await c.flush(); assert.equal(b.remote.values.at(-1), 'offline'); await c.dispose(); assert.equal(b.timers.size, 0)
})

test('text control frames cannot impersonate application data', async () => {
  for (const value of [{unexpected: 'object'}, 'text', [0, 1, 2]]) {
    const b = browser(), seen = []
    const c = b.connect(descriptor('one'), {open: () => ({receive: v => seen.push(v)})})
    try {
      await b.open(); b.sockets[0].control(['data', 1, value, 0]); await flush()
      assert.equal(c.state, 'failed')
      assert.deepEqual(seen, [])
      assert.ok(b.sockets[0].sent.filter(v => typeof v === 'string').map(v => JSON.parse(v))
        .every(v => v[0] !== 'ack' || v[1] === 0))
    } finally { await c.dispose() }
    assert.deepEqual(seen, [])
    assert.equal(b.timers.size, 0)
  }
})

test('message limit must fit the queue', async () => {
  const b = browser()
  for (const options of [{maxMessageBytes: 4, maxQueuedBytes: 3}, {maxQueuedBytes: 3}, {maxMessageBytes: 4194305}]) {
    assert.throws(() => b.connect(descriptor('one'), {...options, open: empty}), {name: 'RangeError'})
  }
  const c = b.connect(descriptor('one'), {maxMessageBytes: 3, maxQueuedBytes: 3, open: empty})
  try {
    await b.open()
    assert.deepEqual(b.sockets[0].hello.slice(5), [3, 3])
    assert.throws(() => c.send('éé'), {name: 'RangeError'})
  } finally { await c.dispose() }
})

test('oversized output fails before waiting for peer credit', async () => {
  for (const value of ['éé', new Uint8Array([0, 1, 2, 3])]) {
    for (const [messageGrant, byteGrant] of [[0, 3], [1, 3], [1, 4]]) {
      const b = browser(); Object.assign(b.remote, {messageGrant, byteGrant, maximum: 3})
      const c = b.connect(descriptor('one'), {open: empty})
      c.send(value)
      const waiting = c.flush()
      waiting.catch(() => {})
      try {
        await b.open()
        assert.equal(c.state, 'failed')
        await assert.rejects(waiting, {type: 'channel-ended'})
        assert.deepEqual(b.remote.values, [])
      } finally {
        const closing = c.dispose(); await flush(); await b.runTimer(5000); await closing
      }
      assert.equal(b.timers.size, 0)
    }
  }
})

test('lost acknowledgements retain output and resume removes received messages without replay', async () => {
  const b = browser(); b.remote.autoAck = false
  const c = b.connect(descriptor('one'), {open: empty}); await b.open(); c.send('one')
  let flushed = false; const waiting = c.flush().then(() => { flushed = true }); assert.equal(flushed, false)
  b.sockets[0].drop(); await b.retry(); await b.open(); await waiting
  assert.equal(flushed, true); assert.deepEqual(b.remote.values, ['one'])
  assert.equal(b.sockets[1].sent.filter(v => typeof v !== 'string').length, 0); await c.dispose()
})

test('unreceived output replays in order after a failed write', async () => {
  const b = browser(), c = b.connect(descriptor('one'), {open: empty})
  await b.open(); b.sockets[0].throwOn = 'data'; c.send('one'); c.send('two'); assert.equal(c.state, 'retrying')
  await b.retry(); await b.open(); await c.flush(); assert.deepEqual(b.remote.values, ['one', 'two']); await c.dispose()
})

test('duplicate incoming messages are acknowledged without a second invocation', async () => {
  const b = browser(), seen = [], c = b.connect(descriptor('one'), {open: () => ({receive: v => seen.push(v)})})
  await b.open(); b.sockets[0].message(1, 'one'); b.sockets[0].message(1, 'one'); await flush()
  b.sockets[0].drop(); await b.retry(); await b.open()
  b.sockets[1].message(1, 'one'); b.sockets[1].message(2, 'two'); await flush()
  assert.deepEqual(seen, ['one', 'two']); await c.dispose()
})

test('peer credits pause sending and local queue limits refuse without eviction', async () => {
  const b = browser(); b.remote.messageGrant = 1; b.remote.byteGrant = 3
  const c = b.connect(descriptor('one'), {maxQueuedMessages: 1, open: empty})
  await b.open(); c.send('abc'); await flush(); c.send('def'); assert.throws(() => c.send('ghi'), /queue is full/)
  assert.deepEqual(b.remote.values, ['abc']); b.remote.messageGrant = 2; b.remote.byteGrant = 6
  b.sockets[0].control(['ack', 1, 2, 6]); await flush(); await c.flush()
  assert.deepEqual(b.remote.values, ['abc', 'def']); assert.equal(c.state, 'open'); await c.dispose()
})

test('outgoing binary buffers are owned at admission', async () => {
  const b = browser(), c = b.connect(descriptor('one'), {open: empty}); await b.open(); b.sockets[0].drop()
  const input = new Uint8Array([1, 2]); c.send(input); input[0] = 99
  await b.retry(); await b.open(); await c.flush(); assert.deepEqual(b.remote.values, [[1, 2]]); await c.dispose()
})

test('accepted input drains after disposal without an available server', async () => {
  const b = browser(), gate = deferred(), seen = [], cleaned = []
  const c = b.connect(descriptor('one'), {open: () => ({receive: v => { seen.push(v); if (v === 'one') return gate.promise }, close: () => cleaned.push(true)})})
  await b.open(); b.sockets[0].message(1, 'one'); b.sockets[0].message(2, 'two'); b.sockets[0].drop()
  const closing = c.dispose(); await b.runTimer(5000); assert.deepEqual(seen, ['one']); assert.deepEqual(cleaned, [])
  gate.resolve(); await closing; assert.deepEqual(seen, ['one', 'two']); assert.deepEqual(cleaned, [true]); assert.equal(b.timers.size, 0)
})

test('reset drains old input and cleanup before initializing the new peer', async () => {
  const b = browser(), gate = deferred(), events = []
  const c = b.connect(descriptor('one'), {open: ({reason}) => {
    events.push(['open', reason]); return {receive: v => { events.push(['receive', v]); if (v === 'one') return gate.promise }, close: () => events.push(['close'])}
  }})
  await b.open(); b.sockets[0].message(1, 'one'); b.sockets[0].message(2, 'two'); b.sockets[0].drop(); b.remote.id = id(2)
  await b.retry(); await b.open(); assert.deepEqual(events, [['open', 'initial'], ['receive', 'one']])
  gate.resolve(); await flush()
  assert.deepEqual(events, [['open', 'initial'], ['receive', 'one'], ['receive', 'two'], ['close'], ['open', 'continuity-lost']])
  assert.equal(c.state, 'open'); await c.dispose()
})

test('reset fails delivery waits and never replays old-generation output', async () => {
  const b = browser(); b.remote.autoAck = false
  const c = b.connect(descriptor('one'), {open: empty}); await b.open(); c.send('uncertain')
  const failed = assert.rejects(c.flush(), /generation ended/)
  b.sockets[0].drop(); b.remote.id = id(2); await b.retry(); await b.open(); await failed
  assert.equal(b.sockets[1].sent.filter(v => typeof v !== 'string').length, 0); await c.dispose()
})

test('asynchronous initialization survives physical reconnect and gates receives', async () => {
  const b = browser(), gate = deferred(), opened = [], seen = []
  const c = b.connect(descriptor('one'), {open: ctx => { opened.push(ctx); return gate.promise }})
  await b.open(); assert.equal(opened.length, 1); b.sockets[0].drop(); await b.retry(); await b.open()
  gate.resolve({receive: v => seen.push(v)}); await flush(); assert.equal(opened.length, 1); assert.equal(c.state, 'open')
  b.sockets[1].message(1, 'after init'); await flush(); assert.deepEqual(seen, ['after init']); await c.dispose()
})

test('a reset superseded while draining cannot initialize a stale generation', async () => {
  const b = browser(), gate = deferred(), reasons = []
  const c = b.connect(descriptor('one'), {open: ({reason}) => { reasons.push(reason); return {receive: () => gate.promise} }})
  await b.open(); b.sockets[0].message(1, 'old'); b.sockets[0].drop(); b.remote.id = id(2)
  await b.retry(); await b.open(); b.sockets[1].drop(); await b.retry(); await b.open()
  gate.resolve(); await flush(); assert.deepEqual(reasons, ['initial', 'continuity-lost']); assert.equal(c.state, 'open'); await c.dispose()
})

for (const command of ['hello', 'ready']) {
  test(`a thrown ${command} write is retryable without losing generation state`, async () => {
    const b = browser(), reasons = [], c = b.connect(descriptor('one'), {open: ({reason}) => { reasons.push(reason); return empty() }})
    await flush(); b.sockets[0].throwOn = command; b.sockets[0].open(); await flush(); assert.equal(c.state, 'retrying')
    await b.retry(); await b.open(); assert.equal(c.state, 'open'); assert.deepEqual(reasons, ['initial']); await c.dispose()
  })
}

test('SSE reopen resumes an unchanged descriptor without a DOM mutation', async () => {
  const b = browser(), opened = [], c = b.mount({getAttribute: () => JSON.stringify(descriptor('one'))}, {open: ctx => { opened.push(ctx); return empty() }})
  await b.open(); b.window.dispatchEvent(new CustomEvent('tropical:reopen')); await b.retry(); await b.open()
  assert.equal(c.state, 'open'); assert.equal(opened.length, 1); await c.dispose(); assert.equal(b.observers[0].disconnected, true)
})

test('server migration uses a new descriptor and explicitly resets the application', async () => {
  let missing = false
  const b = browser(async () => missing ? {status: 409, headers: new Map([['Tropical-Reopen', 'true']])} : {status: 204, headers: new Map()})
  const reasons = [], c = b.connect(descriptor('one'), {open: ({reason}) => { reasons.push(reason); return empty() }})
  await b.open(); b.sockets[0].drop(); missing = true; await b.retry(); assert.equal(c.state, 'recovering')
  missing = false; b.remote.id = id(2); c.update(descriptor('two')); await b.open()
  assert.deepEqual(reasons, ['initial', 'continuity-lost']); assert.equal(b.requests.at(-1).opts.headers['Tropical-Channel'], 'two'); await c.dispose()
})

test('network outages retain sends and keep retrying with bounded backoff', async () => {
  let unavailable = false
  const b = browser(async () => unavailable ? {status: 503, headers: new Map()} : {status: 204, headers: new Map()})
  const c = b.connect(descriptor('one'), {retryDelay: 1, retryMaxDelay: 2, open: empty})
  await b.open(); unavailable = true; b.sockets[0].drop(); c.send('delayed')
  for (let i = 0; i < 12; i++) await b.retry()
  assert.equal(c.state, 'retrying'); unavailable = false; await b.retry(); await b.open(); await c.flush()
  assert.deepEqual(b.remote.values, ['delayed']); await c.dispose()
})

test('malformed sequences and acknowledgements stop admission but drain accepted input', async () => {
  for (const invalid of ['gap', 'ack']) {
    const b = browser(), gate = deferred(), seen = [], c = b.connect(descriptor('one'), {open: () => ({receive: v => { seen.push(v); return gate.promise }})})
    await b.open(); b.sockets[0].message(1, 'accepted')
    if (invalid === 'gap') b.sockets[0].message(3, 'gap'); else b.sockets[0].control(['ack', 100, 200, 1000])
    await flush(); assert.equal(c.state, 'failed'); gate.resolve(); await c.dispose(); assert.deepEqual(seen, ['accepted'])
  }
})

for (const status of [0, 302, 401, 403, 404]) {
  test(`endpoint refusal ${status} is terminal`, async () => {
    const b = browser(async () => ({status, headers: new Map()})), c = b.connect(descriptor('one'), {open: empty})
    await flush(); assert.equal(c.state, 'failed'); await c.dispose(); assert.equal(b.timers.size, 0)
  })
}

test('page suspension preserves queues and handlers; disposal removes listeners', async () => {
  const b = browser(), opened = [], c = b.connect(descriptor('one'), {open: ctx => { opened.push(ctx); return empty() }})
  await b.open(); b.window.dispatchEvent(new Event('pagehide')); c.send('while suspended')
  b.window.dispatchEvent(new Event('pageshow')); await b.open(); await c.flush()
  assert.equal(opened.length, 1); assert.deepEqual(b.remote.values, ['while suspended'])
  await c.dispose(); b.window.dispatchEvent(new Event('pageshow')); await flush(); assert.equal(b.sockets.length, 2); assert.equal(b.timers.size, 0)
})

test('messages submitted before first connection are admitted and flushed in order', async () => {
  const b = browser(), reasons = []
  const c = b.connect(descriptor('one'), {open: ({reason, send}) => { reasons.push(reason); send('from initializer'); return empty() }})
  c.send('before connection')
  let received = false; const delivered = c.flush().then(() => { received = true })
  assert.equal(received, false)
  await b.open(); await delivered
  assert.deepEqual(reasons, ['initial'])
  assert.deepEqual(b.remote.values, ['before connection', 'from initializer'])
  await c.dispose()
})

test('disposing before initial connection does not initialize a peer or leave timers', async () => {
  const b = browser(), c = b.connect(descriptor('one'), {open: () => { throw new Error('must not initialize') }})
  await c.dispose(); await flush()
  assert.equal(b.requests.length, 0)
  assert.equal(b.timers.size, 0)
})
