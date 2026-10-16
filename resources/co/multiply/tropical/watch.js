function watchSignals($, patch, {globalName}) {
  try {
    const state = globalThis[globalName] ??= {timers: {}, sent: {}};
    const has = (value, k) => value !== null && typeof value === 'object' && k in value;
    /* Read without creating a missing Datastar signal. */
    const get = (...steps) => {
      let value = $;
      for (const k of steps) {
        if (!has(value, k)) return undefined;
        value = value[k];
      }
      return value;
    };
    /* Whether the patch changes the signal at `path`, or replaces a value above it. A null above it is a signal
       cleared, as `_storageForget` is once handled. */
    const touches = path => {
      let value = patch;
      for (const k of path) {
        if (value === null) return false;
        if (typeof value !== 'object') return true;
        if (!(k in value)) return false;
        value = value[k];
      }
      return true;
    };
    /* A watch is [path, ms], or [path, ms, value] where the server rendered the page's value as `value`. What the
       server holds is what it was last sent, or that value: null where it isn't known. */
    const send = token => {
      clearTimeout(state.timers[token]);
      delete state.timers[token];
      const watch = get('_watch', token);
      if (!Array.isArray(watch)) return;
      const current = get(...watch[0]), value = current === undefined ? null : current, json = JSON.stringify(value);
      const held = token in state.sent ? state.sent[token] : watch.length > 2 ? JSON.stringify(watch[2]) : null;
      if (json === held) return;
      state.sent[token] = json;
      fetch(location.href, {
        method: 'POST',
        headers: {'Tropical-Action': token, 'Content-Type': 'application/json'},
        body: JSON.stringify({value}),
      }).then(response => {
        if (response.headers.has('Tropical-Reopen')) {
          state.sent[token] = null;
          dispatchEvent(new CustomEvent('tropical:reopen'));
        } else if (response.status === 403 || response.status === 404) {
          delete state.sent[token];
          $._watch[token] = null;
        } else if (!response.ok) state.sent[token] = null;
      }).catch(() => { state.sent[token] = null });
    };
    const later = (token, ms) => {
      clearTimeout(state.timers[token]);
      state.timers[token] = setTimeout(() => send(token), ms);
    };

    for (const [token, watch] of Object.entries(get('_watch') || {})) {
      if (!Array.isArray(watch)) continue;
      const [path, ms] = watch;
      /* A new watch sends at once, as does a forgotten storage entry, back at its default; a change, after `ms`. */
      if (has(patch._watch, token) || path[0] === '_storage' && touches(['_storageForget', ...path.slice(1)])) {
        later(token, 0);
      } else if (touches(path)) later(token, ms);
    }
  } catch (error) { console.error(error) }
}
