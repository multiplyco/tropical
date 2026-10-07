// <time-ago at="…">: how long ago `at`, in epoch ms, was, kept current in the
// browser. It draws into a shadow root, which the island's morph leaves alone.
class TimeAgo extends HTMLElement {
  static observedAttributes = ['at'];

  constructor() {
    super();
    this.attachShadow({mode: 'open'});
  }

  connectedCallback() {
    this.render();
    this.timer = setInterval(() => this.render(), 1000);
  }

  disconnectedCallback() {
    clearInterval(this.timer);
  }

  attributeChangedCallback() {
    this.render();
  }

  render() {
    const s = Math.max(0, Math.round((Date.now() - Number(this.getAttribute('at'))) / 1000));
    this.shadowRoot.textContent =
      s < 60 ? `${s} s ago` : s < 3600 ? `${Math.floor(s / 60)} min ago` : `${Math.floor(s / 3600)} h ago`;
  }
}

if (!customElements.get('time-ago')) customElements.define('time-ago', TimeAgo);
