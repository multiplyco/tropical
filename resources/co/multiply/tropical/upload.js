// The browser's half of `co.multiply.tropical.upload`. It posts the files'
// descriptions to the island's token, sends each file's bytes to the target
// the island named, its progress in a signal, and reports how it went. The
// bytes go to the target only.

const parallel = 3
const queue = []
let running = 0

function post(token, body) {
  return fetch(location.href, {
    method: 'POST',
    headers: {'Content-Type': 'application/json', 'Tropical-Action': token},
    body: JSON.stringify(body),
  })
}

// `$` is Datastar's signals. Reading one that doesn't exist makes it "", and
// setting an object replaces the keys of the one there, so `_upload` is set
// whole only while it isn't one.
function progress($, id, loaded, total) {
  const uploads = $._upload
  if (uploads && typeof uploads === 'object') uploads[id] = {loaded, total}
  else $._upload = {[id]: {loaded, total}}
}

// Sends `file` to its target, resolving to why it failed, or null.
function send($, file, {id, url, method, headers}) {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest()
    xhr.open(method, url)
    for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v)
    // `fetch` can't tell how much of a body it has sent.
    xhr.upload.onprogress = (e) => progress($, id, e.loaded, e.lengthComputable ? e.total : file.size)
    xhr.onload = () => resolve(xhr.status >= 200 && xhr.status < 300 ? null : `The target answered ${xhr.status}.`)
    xhr.onerror = () => resolve("The target couldn't be reached, or didn't allow the request.")
    xhr.onabort = () => resolve('The upload was aborted.')
    xhr.send(file)
  })
}

function next() {
  while (running < parallel && queue.length) {
    const job = queue.shift()
    running++
    job().finally(() => {
      running--
      next()
    })
  }
}

export async function start($, token, files) {
  if (!files.length) return
  const res = await post(token, {files: files.map((f) => ({name: f.name, type: f.type, size: f.size}))})
  if (!res.ok) throw new Error(`Tropical: the island took no files (${res.status}).`)
  const {targets} = await res.json()
  targets.forEach((target, i) => {
    if (!target.url) return
    const file = files[i]
    progress($, target.id, 0, file.size)
    queue.push(async () => {
      const reason = await send($, file, target)
      if (!reason) progress($, target.id, file.size, file.size)
      try {
        await post(token, reason ? {failed: target.id, reason} : {arrived: target.id})
      } catch (e) {
        console.warn('Tropical: reporting an upload failed.', e)
      }
    })
  })
  next()
}
