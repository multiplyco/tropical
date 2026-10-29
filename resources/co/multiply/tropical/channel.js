// A channel generation owns protocol state and queues. A socket only carries
// envelopes. Neither a write callback nor a socket close acknowledges a message.
const encoder = new TextEncoder()
const decoder = new TextDecoder('utf-8', {fatal: true})
const integer = n => Number.isSafeInteger(n) && n >= 0
const uuid = s => typeof s === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s)
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return {promise, resolve, reject}
}
function payload(value, limit) {
  const text = typeof value === 'string'
  let bytes
  if (text) bytes = encoder.encode(value)
  else if (value instanceof ArrayBuffer) bytes = new Uint8Array(value).slice()
  else if (ArrayBuffer.isView(value)) bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength).slice()
  else throw new TypeError('A channel message is a string, ArrayBuffer or typed array')
  if (bytes.byteLength > limit) throw new RangeError('Channel message exceeds its byte limit')
  return {text, bytes}
}
function frame(entry) {
  const bytes = new Uint8Array(9 + entry.bytes.byteLength)
  bytes[0] = entry.text ? 1 : 2
  new DataView(bytes.buffer).setBigUint64(1, BigInt(entry.sequence))
  bytes.set(entry.bytes, 9)
  return bytes
}
function decode(raw, limit) {
  if (typeof raw === 'string') {
    if (raw.length > 1024) throw new Error('Oversized channel control')
    const v = JSON.parse(raw)
    if (!Array.isArray(v) || !['hello', 'welcome', 'ready', 'ack'].includes(v[0])) throw new Error('Invalid channel control')
    return v
  }
  const bytes = new Uint8Array(raw)
  if (bytes.length < 9 || bytes.length - 9 > limit || ![1, 2].includes(bytes[0])) throw new Error('Invalid channel frame')
  const sequence = Number(new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getBigUint64(1))
  if (!integer(sequence) || !sequence) throw new Error('Invalid channel sequence')
  const data = bytes.slice(9)
  return ['data', sequence, bytes[0] === 1 ? decoder.decode(data) : data, data.length]
}

export function connect(descriptor, options = {}) {
  const policy = {
    maxMessageBytes: 1048576, maxQueuedBytes: 4194304, maxQueuedMessages: 64,
    retryDelay: 500, retryMaxDelay: 10000, connectTimeout: 10000,
    recoveryTimeout: 10000, recoveryLimit: 3, drainTimeout: 5000,
    ...options,
  }
  for (const key of ['maxMessageBytes', 'maxQueuedBytes', 'maxQueuedMessages', 'retryDelay',
    'retryMaxDelay', 'connectTimeout', 'recoveryTimeout', 'recoveryLimit', 'drainTimeout']) {
    if (!integer(policy[key]) || !policy[key]) throw new TypeError(`Invalid channel option: ${key}`)
  }
  if (policy.maxMessageBytes > policy.maxQueuedBytes) {
    throw new RangeError('Channel maxMessageBytes must not exceed maxQueuedBytes')
  }
  const client = crypto.randomUUID()
  const newGeneration = id => ({
    id, out: [], outBytes: 0, sequence: 0, totalBytes: 0, acknowledged: 0, sentThrough: 0,
    incoming: [], inBytes: 0, received: 0, processed: 0, processedBytes: 0, waiters: [],
    abort: new AbortController(), initializing: true, started: false, drained: deferred(), finished: deferred(),
  })
  let generation = newGeneration(null), link, timer, recovery, failures = 0, recoveries = 0
  let transition = Promise.resolve()
  let disposed = false, stopped = false, paused = false, state = 'connecting'
  const notify = (next, detail = {}) => {
    state = next
    try { options.status?.({state: next, ...detail}) }
    catch (error) { console.error('Channel status handler failed', error) }
  }
  const current = l => !!l && link === l && !l.ended
  const clearRetry = () => { clearTimeout(timer); timer = undefined }
  const clearRecovery = () => { clearTimeout(recovery?.deadline); recovery = undefined }
  const receipt = g => [g.received, policy.maxQueuedMessages + g.processed, policy.maxQueuedBytes + g.processedBytes]
  const endedError = reason => Object.assign(new Error('Channel generation ended'), {type: 'channel-ended', reason})
  function abandon(g, reason) {
    g.abandoned = true; g.out = []; g.outBytes = 0
    for (const waiter of g.waiters) waiter.reject(endedError(reason))
    g.waiters = []
  }
  function send(g, value) {
    if (!g || g.abandoned || g.sealedOut) throw endedError(g?.reason)
    const data = payload(value, policy.maxMessageBytes)
    if (g.out.length >= policy.maxQueuedMessages || g.outBytes + data.bytes.length > policy.maxQueuedBytes) {
      throw Object.assign(new Error('Channel outgoing queue is full'), {type: 'backpressure'})
    }
    if (!integer(g.sequence + 1) || !integer(g.totalBytes + data.bytes.length)) throw new Error('Channel counters exhausted')
    g.outBytes += data.bytes.length; g.totalBytes += data.bytes.length
    g.out.push({...data, sequence: ++g.sequence, totalBytes: g.totalBytes})
    pump(link)
  }
  function flush(g) {
    if (!g || g.abandoned) return Promise.reject(endedError(g?.reason))
    if (g.acknowledged === g.sequence) return Promise.resolve()
    if (g.waiters.length >= policy.maxQueuedMessages) return Promise.reject(new Error('Too many channel flush waiters'))
    const wait = deferred()
    g.waiters.push({...wait, target: g.sequence})
    return wait.promise
  }
  function acknowledge(g, n) {
    if (!integer(n) || n < g.acknowledged || n > g.sentThrough) throw new Error('Invalid channel acknowledgement')
    g.acknowledged = n
    while (g.out.length && g.out[0].sequence <= n) g.outBytes -= g.out.shift().bytes.length
    const ready = g.waiters.filter(w => w.target <= n)
    g.waiters = g.waiters.filter(w => w.target > n)
    for (const w of ready) w.resolve()
  }
  async function drain(g) {
    if (g.initializing || g.processing || !g.handlers) return
    g.processing = true
    try {
      while (g.incoming.length) {
        const entry = g.incoming.shift()
        try { await g.handlers.receive(entry.value) }
        catch (error) {
          console.error('Channel receive failed', error)
          if (!g.closing) terminal({reason: 'Application receive failed', error})
        } finally {
          g.processed++; g.processedBytes += entry.size; g.inBytes -= entry.size
          pump(link)
        }
      }
    } finally {
      g.processing = false
      if (g.closing) await cleanup(g)
    }
  }
  async function cleanup(g) {
    if (g.cleaned || g.initializing || g.processing || g.incoming.length) return
    g.cleaned = true
    g.abort.abort()
    try { await g.handlers?.close?.({reason: g.reason}) }
    catch (error) { console.error('Channel cleanup failed', error) }
    g.drained.resolve()
  }
  function retire(g, reason, discardOutput) {
    if (!g) return Promise.resolve()
    if (discardOutput) abandon(g, reason)
    if (g.closing) return g.finished.promise
    g.closing = true; g.reason = reason
    if (!g.started) g.initializing = false
    // There is no accepted input before initialization. Its signal can safely
    // cancel partial acquisition; otherwise it stays live through the drain.
    if (g.initializing) g.abort.abort()
    void drain(g)
    void cleanup(g)
    void (async () => {
      await g.drained.promise
      g.sealedOut = true
      let deadline
      try {
        if (!g.abandoned) await Promise.race([
          flush(g),
          new Promise((_, reject) => { deadline = setTimeout(() => reject(endedError(reason)), policy.drainTimeout) }),
        ])
      } catch (_) { abandon(g, reason) }
      finally { clearTimeout(deadline); g.finished.resolve() }
    })()
    return g.finished.promise
  }
  async function initialize(id, reason, previous, allowed) {
    if (previous?.id) {
      retire(previous, 'continuity-lost', true)
      await previous.drained.promise
    }
    if (disposed || stopped || !allowed()) return null
    const g = previous && !previous.id && !previous.closing ? previous : newGeneration(id)
    g.id = id; g.started = true
    generation = g
    g.initialized = (async () => {
      try {
        g.handlers = await options.open({send: v => send(g, v), flush: () => flush(g),
          reason, generation: id, signal: g.abort.signal})
        if (!g.handlers || typeof g.handlers.receive !== 'function') throw new TypeError('open returns {receive, close?}')
      } catch (error) {
        terminal({reason: 'Application initialization failed', error})
      } finally {
        g.initializing = false
        if (g.closing) await cleanup(g)
      }
    })()
    await g.initialized
    return g
  }
  function terminateLink(l, code = 4000) {
    if (!l || l.ended) return
    l.ended = true
    clearTimeout(l.deadline); clearTimeout(l.writeTimer)
    l.abort.abort()
    try { l.socket?.close(code) } catch (_) {}
  }
  function terminal(info) {
    stopped = true; clearRetry(); clearRecovery()
    terminateLink(link, 4004)
    retire(generation, info.reason, true)
    notify('failed', info)
  }
  function schedule(info = {}) {
    if (disposed || stopped || paused) return
    const delay = Math.min(policy.retryMaxDelay, policy.retryDelay * 2 ** Math.min(failures++, 20)) * (0.8 + Math.random() * 0.4)
    notify(recovery ? 'recovering' : 'retrying', {...info, delay})
    if (!disposed && !stopped && !paused) { clearRetry(); timer = setTimeout(start, delay) }
  }
  function disconnect(l, info = {}) {
    if (!l || l.ended) return
    terminateLink(l)
    if (link === l) schedule(info)
  }
  function pump(l) {
    const g = generation
    if (!l || l.ended || l.phase !== 'open' || l.gen !== g || l.pumping) return
    l.pumping = true
    try {
      if (l.socket.bufferedAmount > policy.maxQueuedBytes) {
        if (!l.writeTimer) l.writeTimer = setTimeout(() => { l.writeTimer = undefined; pump(l) }, 20)
        return
      }
      const position = receipt(g), signature = position.join(':')
      if (l.receipt !== signature) {
        l.socket.send(JSON.stringify(['ack', ...position])); l.receipt = signature
      }
      let next
      while ((next = g.out.find(e => e.sequence > l.cursor))) {
        if (next.bytes.length > l.maximum) { terminal({reason: 'Message exceeds peer capacity'}); return }
        if (next.sequence > l.messageGrant || next.totalBytes > l.byteGrant) break
        if (l.socket.bufferedAmount > policy.maxQueuedBytes) {
          if (!l.writeTimer) l.writeTimer = setTimeout(() => { l.writeTimer = undefined; pump(l) }, 20)
          break
        }
        g.sentThrough = Math.max(g.sentThrough, next.sequence)
        l.socket.send(frame(next)); l.cursor = next.sequence
      }
    } catch (error) { disconnect(l, {reason: 'Transport write failed', error}) }
    finally { l.pumping = false }
  }
  function position(l, n, messages, bytes) {
    if (![n, messages, bytes].every(integer) || n > messages || messages < l.messageGrant || bytes < l.byteGrant) {
      throw new Error('Invalid channel credit')
    }
    acknowledge(generation, n)
    l.messageGrant = messages; l.byteGrant = bytes
  }
  async function welcome(l, message) {
    const [, id, outcome, n, messages, bytes, maximum] = message
    if (l.phase !== 'hello' || message.length !== 7 || !uuid(id) ||
      !['initial', 'resumed', 'continuity-lost'].includes(outcome) ||
      ![n, messages, bytes, maximum].every(integer) || !maximum || n > messages) throw new Error('Invalid channel welcome')
    l.phase = 'initializing'
    const setup = transition.then(async () => {
      if (!current(l) || disposed || stopped) return
      const previous = generation
      if (previous?.id === id && !previous.closing) {
        if (n < previous.acknowledged || n > previous.sentThrough) throw new Error('Invalid channel resume')
        await previous.initialized
      } else {
        if (outcome === 'resumed' || n !== 0) throw new Error('Unknown channel generation')
        await initialize(id, previous?.id ? 'continuity-lost' : 'initial', previous, () => current(l))
      }
    })
    transition = setup.catch(() => {})
    await setup
    if (!current(l) || disposed || stopped || generation?.closing) return
    const g = generation
    l.gen = g; l.maximum = maximum; l.messageGrant = messages; l.byteGrant = bytes
    acknowledge(g, n)
    l.cursor = n; l.phase = 'open'
    try { l.socket.send(JSON.stringify(['ready', id, ...receipt(g)])) }
    catch (error) { disconnect(l, {reason: 'Transport write failed', error}); return }
    clearTimeout(l.deadline); clearRecovery(); failures = 0; recoveries = 0
    notify('open')
    pump(l)
  }
  function receive(l, raw) {
    if (!current(l)) return
    try {
      const message = decode(raw, policy.maxMessageBytes), [kind, ...args] = message
      if (kind === 'welcome') {
        void welcome(l, message).catch(error => { if (current(l)) terminal({reason: 'Invalid channel handshake', error}) })
        return
      }
      if (l.phase !== 'open') throw new Error('Channel is not ready')
      const g = generation
      if (kind === 'ack' && message.length === 4) { position(l, ...args); pump(l); return }
      if (kind !== 'data') throw new Error('Unknown channel frame')
      const [sequence, value, size] = args
      if (sequence <= g.received) { l.receipt = undefined; pump(l); return }
      if (sequence !== g.received + 1) throw new Error('Channel sequence gap')
      if (g.closing) return
      if (g.received - g.processed >= policy.maxQueuedMessages || g.inBytes + size > policy.maxQueuedBytes) {
        throw new Error('Peer exceeded channel credit')
      }
      if (!integer(g.processedBytes + g.inBytes + size + policy.maxQueuedBytes) ||
        !integer(sequence + policy.maxQueuedMessages)) throw new Error('Channel counters exhausted')
      g.received = sequence; g.inBytes += size; g.incoming.push({value, size})
      pump(l)
      void drain(g)
    } catch (error) { terminal({reason: 'Invalid channel protocol', error}) }
  }
  async function start() {
    clearRetry()
    if (disposed || stopped || paused || current(link)) return
    const l = {abort: new AbortController(), phase: 'checking'}
    link = l
    notify('connecting')
    if (!current(l) || disposed) return
    l.deadline = setTimeout(() => disconnect(l, {reason: 'Connection timed out'}), policy.connectTimeout)
    try {
      const url = new URL(descriptor.path, location.href)
      if (url.origin !== location.origin) throw new Error('Channel must be on the page origin')
      const response = await fetch(url, {headers: {'Tropical-Channel': descriptor.token},
        credentials: 'same-origin', cache: 'no-store', redirect: 'manual', signal: l.abort.signal})
      if (!current(l) || disposed) return
      if (response.status === 409 && response.headers.get('Tropical-Reopen')) {
        terminateLink(l)
        if (!recovery) window.dispatchEvent(new CustomEvent('tropical:reopen'))
        else schedule({reason: 'Session rebuilding'})
        return
      }
      if (response.status !== 204) {
        if (response.status >= 500 || [408, 429].includes(response.status) || (response.status === 404 && recovery)) {
          disconnect(l, {reason: 'Endpoint unavailable', status: response.status})
        } else terminal({reason: 'Endpoint refused', status: response.status})
        return
      }
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
      const socket = new WebSocket(url, ['tropical.channel.v2', `tropical.cap.${descriptor.token}`])
      l.socket = socket; socket.binaryType = 'arraybuffer'
      socket.onopen = () => {
        if (!current(l) || disposed) { socket.close(); return }
        l.phase = 'hello'
        const g = generation && !generation.closing ? generation : null
        try {
          socket.send(JSON.stringify(['hello', client, g?.id ?? null,
            ...(g ? receipt(g) : [0, policy.maxQueuedMessages, policy.maxQueuedBytes]), policy.maxMessageBytes]))
        } catch (error) { disconnect(l, {reason: 'Transport write failed', error}) }
      }
      socket.onmessage = event => receive(l, event.data)
      socket.onerror = () => disconnect(l, {reason: 'Transport failed'})
      socket.onclose = event => {
        if (!current(l)) return
        if ([1000, 1001, 1006, 1012, 1013, 4000, 4001, 4003].includes(event.code)) disconnect(l, {code: event.code})
        else terminal({reason: 'Channel ended', code: event.code})
      }
    } catch (error) { if (current(l)) disconnect(l, {reason: 'Connection failed', error}) }
  }
  const onReopen = () => {
    if (disposed || paused || recovery) return
    terminateLink(link); clearRetry()
    if (recoveries++ >= policy.recoveryLimit) { terminal({reason: 'Session recovery exhausted'}); return }
    const cycle = {}; recovery = cycle
    cycle.deadline = setTimeout(() => {
      if (recovery !== cycle) return
      clearRecovery(); terminateLink(link); clearRetry()
      terminal({reason: 'Session recovery timed out'})
    }, policy.recoveryTimeout)
    stopped = false; notify('recovering')
    if (recovery === cycle && !disposed && !paused) timer = setTimeout(start, policy.retryDelay)
  }
  const onHide = () => { paused = true; clearRecovery(); clearRetry(); terminateLink(link); notify('paused') }
  const onShow = () => { if (paused) { paused = false; void start() } }
  window.addEventListener('tropical:reopen', onReopen)
  window.addEventListener('pagehide', onHide)
  window.addEventListener('pageshow', onShow)
  queueMicrotask(start)
  return {
    get state() { return state },
    send(value) { if (disposed || stopped) throw endedError('closed'); send(generation, value) },
    flush() { return flush(generation) },
    update(next) {
      const same = next.token === descriptor.token && next.path === descriptor.path
      if (disposed || (same && (!recovery || current(link)))) return
      terminateLink(link); clearRetry()
      descriptor = next; stopped = false
      void start()
    },
    dispose() {
      if (disposed) return generation?.finished.promise ?? Promise.resolve()
      disposed = true; clearRetry(); clearRecovery()
      window.removeEventListener('tropical:reopen', onReopen)
      window.removeEventListener('pagehide', onHide)
      window.removeEventListener('pageshow', onShow)
      // Keep the current socket only to acknowledge and flush already accepted
      // work. receive() rejects new input once this generation is closing.
      const done = retire(generation, 'disposed', false)
      void done.then(() => { terminateLink(link, 4004); notify('closed') })
      return done
    },
  }
}

export function mount(element, options) {
  const read = () => JSON.parse(element.getAttribute('data-tropical-channel'))
  const channel = connect(read(), options)
  const observer = new MutationObserver(() => {
    try { channel.update(read()) }
    catch (error) { void channel.dispose(); console.error('Invalid channel descriptor', error) }
  })
  observer.observe(element, {attributes: true, attributeFilter: ['data-tropical-channel']})
  return {get state() { return channel.state }, send: channel.send, flush: channel.flush,
    dispose() { observer.disconnect(); return channel.dispose() }}
}
