class SocketRoom extends HTMLElement {
  constructor() {
    super()
    this.attachShadow({mode: 'open'})
    this.shadowRoot.innerHTML = `
      <style>
        :host { display:block; font:14px system-ui; color:#27272a }
        form { display:flex; flex-wrap:wrap; gap:8px; margin:12px 0 }
        input { flex:1; min-width:120px; border:1px solid #d4d4d8; border-radius:6px; padding:8px }
        button { border:1px solid #d4d4d8; border-radius:6px; padding:8px; background:white; cursor:pointer }
        button:disabled { opacity:.5; cursor:default }
        output { color:#71717a; font-size:12px }
        ul { padding-left:20px; max-height:160px; overflow:auto }
        .status[data-state="open"] { color:#059669 }
      </style>
      <output class="status">Connecting…</output>
      <form><input aria-label="Room message" placeholder="A message for every open tab">
        <button type="submit" disabled>Send text</button>
        <button type="button" class="binary" disabled>Send bytes</button>
      </form>
      <ul aria-label="Received messages"></ul>`
    this.shadowRoot.querySelector('form').onsubmit = event => { event.preventDefault(); this.send(false) }
    this.shadowRoot.querySelector('.binary').onclick = () => this.send(true)
  }

  async connectedCallback() {
    if (this.connection || this.loading) return
    this.loading = true
    try {
      const descriptor = JSON.parse(this.getAttribute('data-tropical-channel'))
      const {mount} = await import(descriptor.module)
      if (!this.isConnected) return
      this.connection = mount(this, {
        open: ({reason}) => {
          this.initializations = (this.initializations || 0) + 1
          this.initialized = true
          this.lastReason = reason
          return {receive: value => {
          const row = document.createElement('li')
          row.textContent = typeof value === 'string' ? `Text: ${value}` : `Bytes: ${new TextDecoder().decode(value)}`
          const list = this.shadowRoot.querySelector('ul')
          list.append(row)
          while (list.children.length > 20) list.firstElementChild.remove()
          }}
        },
        status: ({state}) => {
          const status = this.shadowRoot.querySelector('.status')
          status.textContent = `${state} · peer initializations ${this.initializations || 0}${this.lastReason ? ` · ${this.lastReason}` : ""}`; status.dataset.state = state
          for (const button of this.shadowRoot.querySelectorAll('button')) button.disabled = !this.initialized || ['failed', 'closed'].includes(state)
        },
      })
    } finally { this.loading = false }
  }

  disconnectedCallback() {
    // A morph may move the same element within the document.
    queueMicrotask(() => {
      if (this.isConnected) return
      this.connection?.dispose()
      this.connection = undefined
    })
  }

  send(binary) {
    const input = this.shadowRoot.querySelector('input')
    if (!input.value) return
    try {
      this.connection.send(binary ? new TextEncoder().encode(input.value) : input.value)
      input.value = ''
    } catch (error) { this.shadowRoot.querySelector('.status').textContent = error.message }
  }
}

if (!customElements.get('socket-room')) customElements.define('socket-room', SocketRoom)
