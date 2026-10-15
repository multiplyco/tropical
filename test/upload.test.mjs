import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'

const program = readFileSync(new URL('../resources/co/multiply/tropical/upload.js', import.meta.url), 'utf8')

function browser(failAt) {
  const reports = [], transfers = [], pending = []
  let active = 0, peak = 0
  const context = vm.createContext({
    location: {href: 'https://example.test/page'},
    console,
    fetch: async (_, {body}) => {
      const request = JSON.parse(body)
      if (request.files) {
        return {ok: true, headers: new Map(), json: async () => ({targets: request.files.map((_, i) =>
          ({id: `u${i}`, url: `https://upload.test/${i}`, method: 'PUT', headers: {'Content-Type': 'text/plain'}}))})}
      }
      reports.push(request)
      return {ok: true, headers: new Map()}
    },
    XMLHttpRequest: class {
      upload = {}
      open(_, url) {
        this.id = Number(url.split('/').at(-1))
        if (this.id === 0 && failAt === 'open') throw new Error('bad URL')
      }
      setRequestHeader() {
        if (this.id === 0 && failAt === 'header') throw new Error('bad header')
      }
      send() {
        if (this.id === 0 && failAt === 'send') throw new Error('cannot send')
        transfers.push(this.id)
        peak = Math.max(peak, ++active)
        pending.push(() => {
          active--
          this.status = 200
          this.onload()
        })
      }
    },
  })
  vm.runInContext(program.replace('export async function start', 'async function start'), context)
  return {
    reports, transfers,
    peak: () => peak,
    start: (signals, files) => context.start(signals, 'token', files),
    async finish() {
      for (let i = 0; i < 10; i++) {
        pending.splice(0).forEach(done => done())
        await new Promise(resolve => setImmediate(resolve))
      }
    },
  }
}

for (const stage of ['open', 'header', 'send']) {
  test(`an upload that throws during ${stage} is reported and the queue continues`, async () => {
    const b = browser(stage)
    const files = Array.from({length: 6}, (_, i) => ({name: `${i}.txt`, type: 'text/plain', size: 42}))
    await b.start({_upload: {}}, files)
    await b.finish()
    assert.equal(b.reports.length, 6)
    assert.equal(b.reports.filter(r => r.failed === 'u0').length, 1)
    assert.equal(b.reports.filter(r => r.arrived).length, 5)
    assert.deepEqual(b.transfers, [1, 2, 3, 4, 5])
    assert.ok(b.peak() <= 3)
  })
}
