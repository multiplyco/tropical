function keepStorage($, patch, {owner, states, prefix, globalName}) {
  try {
    const state = globalThis[globalName] ??= {defaults: {}};
    const key = (signal, item) => prefix + JSON.stringify([owner, signal, item]);
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
    const put = (k, value) => {
      try { localStorage.setItem(k, value); return } catch (_) {}
      /* Full: evict the oldest entries first. */
      const ours = [];
      for (let n = 0; n < localStorage.length; n++) {
        const candidate = localStorage.key(n);
        if (candidate && candidate !== k && candidate.startsWith(prefix)) {
          let at = 0;
          try { at = JSON.parse(localStorage.getItem(candidate)).at } catch (_) {}
          ours.push([at, candidate]);
        }
      }
      ours.sort((a, b) => a[0] - b[0]);
      for (const [, candidate] of ours) {
        localStorage.removeItem(candidate);
        try { localStorage.setItem(k, value); return } catch (_) {}
      }
    };
    const keep = (signal, item) => {
      const definition = states[signal];
      if (!definition) return;
      const k = key(signal, item), value = get('_storage', signal, item);
      if (value === undefined || value === null || JSON.stringify(value) === state.defaults[k]) {
        localStorage.removeItem(k);
      } else {
        put(k, JSON.stringify({version: definition[0], at: Date.now(), value}));
      }
    };
    for (const [signal, items] of Object.entries(patch._storage || {})) {
      if (items === null || typeof items !== 'object') continue;
      for (const item of Object.keys(items)) keep(signal, item);
    }
    if (patch._storageForget) {
      for (const [signal, items] of Object.entries(patch._storageForget)) {
        if (items === null || typeof items !== 'object') continue;
        for (const item of Object.keys(items)) {
          const k = key(signal, item), defaultValue = state.defaults[k];
          localStorage.removeItem(k);
          if (get('_storage', signal, item) !== undefined) {
            $._storage[signal][item] = defaultValue === undefined ? null : JSON.parse(defaultValue);
          }
        }
      }
      $._storageForget = null;
    }
  } catch (error) { console.error(error) }
}
