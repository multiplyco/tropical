function pruneStorage({owner, states, prefix}) {
  try {
    const now = Date.now();
    for (let n = localStorage.length - 1; n >= 0; n--) {
      const key = localStorage.key(n);
      if (!key || !key.startsWith(prefix)) continue;
      let kept = false;
      try {
        const [entryOwner, signal] = JSON.parse(key.slice(prefix.length));
        const entry = JSON.parse(localStorage.getItem(key)), definition = states[signal];
        kept = entryOwner === owner && !!definition && entry.version === definition[0]
          && now - entry.at <= definition[1];
      } catch (_) {}
      if (!kept) localStorage.removeItem(key);
    }
  } catch (_) {}
}
