import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'

const programs = Object.fromEntries(['cookie-keep', 'storage-keep', 'storage-prune', 'watch'].map(name =>
  [name, readFileSync(new URL(`../resources/co/multiply/tropical/${name}.js`, import.meta.url), 'utf8')]))

class Storage {
  entries = new Map()
  capacity = Infinity
  get length() { return this.entries.size }
  key(n) { return [...this.entries.keys()][n] ?? null }
  getItem(key) { return this.entries.get(key) ?? null }
  setItem(key, value) {
    if (!this.entries.has(key) && this.length >= this.capacity) throw new Error('full')
    this.entries.set(key, value)
  }
  removeItem(key) { this.entries.delete(key) }
}

function browser() {
  const localStorage = new Storage(), timers = new Map(), requests = [], events = []
  let nextTimer = 0
  const context = vm.createContext({
    localStorage,
    document: {cookie: ''},
    location: {href: 'https://example.test/page'},
    Date: {now: () => 1000},
    console: {error: error => { throw error }},
    setTimeout: (run, ms) => { const id = ++nextTimer; timers.set(id, {run, ms}); return id },
    clearTimeout: id => timers.delete(id),
    fetch: async (url, options) => {
      requests.push({url, ...options})
      return context.response
    },
    response: {status: 204, ok: true, headers: new Map()},
    CustomEvent: class { constructor(type) { this.type = type } },
    dispatchEvent: event => events.push(event.type),
  })
  return {
    context, localStorage, timers, requests, events,
    run(name, ...args) {
      return vm.runInContext(`(${programs[name]})`, context)(...args)
    },
    async tick() {
      const pending = [...timers.values()]
      timers.clear()
      for (const {run} of pending) run()
      await new Promise(resolve => setImmediate(resolve))
    },
  }
}

const config = {owner: 'alice', states: {draft: [1, 500]}, prefix: 'tropical:', globalName: 'storageForTest'}
const key = (signal = 'draft', item = '_', owner = 'alice') => 'tropical:' + JSON.stringify([owner, signal, item])
const entry = (value, at = 900, version = 1) => JSON.stringify({version, at, value})
const watchConfig = {globalName: 'watchForTest'}
const draftWatch = () => [['_storage', 'draft', '_'], 300]

test('cookies merge other tabs, discard removed states, and keep false', () => {
  const b = browser()
  b.context.document.cookie = 'tropical-ui=' + encodeURIComponent(JSON.stringify({'open/other': false, gone: 4}))
  b.run('cookie-keep', {_cookie: {open: {here: false}}}, {defaults: {open: true}, name: 'tropical-ui', maxChars: 3500})
  const read = () => JSON.parse(decodeURIComponent(b.context.document.cookie.split(';')[0].split('=')[1]))
  assert.deepEqual(read(), {'open/other': false, 'open/here': false})
  b.run('cookie-keep', {_cookie: {open: {here: true}}}, {defaults: {open: true}, name: 'tropical-ui', maxChars: 3500})
  assert.deepEqual(read(), {'open/other': false})
})

test('cookie overflow drops the oldest values', () => {
  const b = browser()
  b.run('cookie-keep', {_cookie: {text: {old: 'x'.repeat(80), new: 'saved'}}},
    {defaults: {text: ''}, name: 'tropical-ui', maxChars: 70})
  const encoded = b.context.document.cookie.split(';')[0].split('=')[1]
  assert.ok(encoded.length <= 70)
  assert.deepEqual(JSON.parse(decodeURIComponent(encoded)), {'text/new': 'saved'})
})

test('storage persists the complete signal synchronously and removes defaults', () => {
  const b = browser()
  const signals = {_storage: {draft: {_: {text: 'edited', unchanged: 1}}}}
  b.context[config.globalName] = {defaults: {[key()]: JSON.stringify({text: 'initial'})}, timers: {}}
  b.run('storage-keep', signals, {_storage: {draft: {_: {text: 'edited'}}}}, config)
  assert.deepEqual(JSON.parse(b.localStorage.getItem(key())).value, {text: 'edited', unchanged: 1})
  signals._storage.draft._ = {text: 'initial'}
  b.run('storage-keep', signals, {_storage: {draft: {_: {text: 'initial'}}}}, config)
  assert.equal(b.localStorage.getItem(key()), null)
})

test('quota eviction removes the oldest owned entry and keeps unrelated data', () => {
  const b = browser()
  b.localStorage.setItem(key('draft', 'old'), entry('old', 100))
  b.localStorage.setItem(key('draft', 'recent'), entry('recent', 800))
  b.localStorage.setItem('another-app', 'keep')
  b.localStorage.capacity = 3
  b.run('storage-keep', {_storage: {draft: {_: false}}}, {_storage: {draft: {_: false}}}, config)
  assert.equal(b.localStorage.getItem(key('draft', 'old')), null)
  assert.ok(b.localStorage.getItem(key('draft', 'recent')))
  assert.equal(b.localStorage.getItem('another-app'), 'keep')
  assert.equal(JSON.parse(b.localStorage.getItem(key())).value, false)
})

test('observations coalesce, read the latest value, and revoke gone tokens', async () => {
  const b = browser(), signals = {_storage: {draft: {_: 'first'}}, _watch: {token: draftWatch()}}
  b.run('watch', signals, {_watch: signals._watch}, watchConfig)
  assert.equal([...b.timers.values()][0].ms, 0)
  for (const value of ['second', false]) {
    signals._storage.draft._ = value
    b.run('watch', signals, {_storage: signals._storage}, watchConfig)
  }
  assert.equal(b.timers.size, 1)
  assert.equal([...b.timers.values()][0].ms, 300)
  b.context.response = {status: 404, ok: false, headers: new Map()}
  await b.tick()
  assert.equal(b.requests.length, 1)
  assert.equal(b.requests[0].headers['Tropical-Action'], 'token')
  assert.deepEqual(JSON.parse(b.requests[0].body), {value: false})
  assert.equal(signals._watch.token, null)
})

test('an observation on another server asks the page to reopen', async () => {
  const b = browser(), signals = {_watch: {token: draftWatch()}}
  b.context.response = {status: 409, ok: false, headers: new Map([['Tropical-Reopen', 'true']])}
  b.run('watch', signals, {_watch: signals._watch}, watchConfig)
  await b.tick()
  assert.deepEqual(b.events, ['tropical:reopen'])
  assert.deepEqual(JSON.parse(b.requests[0].body), {value: null})
})

test('forget removes storage and restores the default before navigation', async () => {
  const b = browser(), signals = {_storage: {draft: {_: 'edited'}}, _watch: {token: draftWatch()}}
  const patch = {_storage: {draft: {_: 'edited'}}, _storageForget: {draft: {_: true}}}
  b.context[config.globalName] = {defaults: {[key()]: JSON.stringify('initial')}}
  b.localStorage.setItem(key(), entry('edited'))
  b.run('storage-keep', signals, patch, config)
  b.run('watch', signals, patch, watchConfig)
  assert.equal(b.localStorage.getItem(key()), null)
  assert.equal(signals._storage.draft._, 'initial')
  assert.equal(signals._storageForget, null)
  assert.equal([...b.timers.values()][0].ms, 0, 'at once, though the patch changed the entry too')
  await b.tick()
  assert.deepEqual(JSON.parse(b.requests[0].body), {value: 'initial'})
})

test('a cookie observation sends at once, and only what the server does not hold', async () => {
  const b = browser(), signals = {_cookie: {open: true}, _watch: {token: [['_cookie', 'open'], 0, true]}}
  const change = value => {
    signals._cookie.open = value
    b.run('watch', signals, {_cookie: {open: value}}, watchConfig)
  }
  b.run('watch', signals, {_cookie: signals._cookie, _watch: signals._watch}, watchConfig)
  await b.tick()
  assert.equal(b.requests.length, 0, 'the server rendered the value the page holds')
  change(false)
  assert.equal([...b.timers.values()][0].ms, 0)
  await b.tick()
  assert.deepEqual(JSON.parse(b.requests[0].body), {value: false})
  change(false)
  await b.tick()
  assert.equal(b.requests.length, 1, 'nor what it was last sent')
  change(true)
  await b.tick()
  assert.deepEqual(JSON.parse(b.requests[1].body), {value: true})
})

test('a cookie observation of a page that has moved on sends its value as it appears', async () => {
  const b = browser(), signals = {_cookie: {open: {a: false, b: true}}, _watch: {token: [['_cookie', 'open', 'a'], 0, true]}}
  b.run('watch', signals, {_watch: signals._watch}, watchConfig)
  await b.tick()
  assert.deepEqual(JSON.parse(b.requests[0].body), {value: false})
  b.run('watch', signals, {_cookie: {open: {b: false}}}, watchConfig)
  assert.equal(b.timers.size, 0, 'another item is another watch\'s')
})

test('a value whose send failed is sent again', async () => {
  const b = browser(), signals = {_cookie: {open: false}, _watch: {token: [['_cookie', 'open'], 0, true]}}
  b.context.response = {status: 503, ok: false, headers: new Map()}
  b.run('watch', signals, {_cookie: {open: false}}, watchConfig)
  await b.tick()
  b.context.response = {status: 204, ok: true, headers: new Map()}
  b.run('watch', signals, {_cookie: {open: false}}, watchConfig)
  await b.tick()
  assert.equal(b.requests.length, 2)
  assert.deepEqual(JSON.parse(b.requests[1].body), {value: false})
})

test('pruning checks owner, age, version and declaration while preserving other apps', () => {
  const b = browser()
  b.localStorage.setItem(key('draft', 'keep'), entry('keep'))
  b.localStorage.setItem(key('draft', 'expired'), entry('expired', 0))
  b.localStorage.setItem(key('draft', 'version'), entry('version', 900, 2))
  b.localStorage.setItem(key('draft', 'owner', 'bob'), entry('owner'))
  b.localStorage.setItem(key('removed'), entry('removed'))
  b.localStorage.setItem('tropical:broken', 'broken')
  b.localStorage.setItem('another-app', 'keep')
  b.run('storage-prune', config)
  assert.deepEqual([...b.localStorage.entries.keys()], [key('draft', 'keep'), 'another-app'])
})
