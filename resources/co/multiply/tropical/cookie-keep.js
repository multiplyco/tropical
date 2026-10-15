function keepCookie(patch, {defaults, name, maxChars}) {
  if (!patch._cookie) return;
  /* Read the current cookie so a change in another tab is kept. */
  const raw = document.cookie.split('; ').find(c => c.startsWith(name + '='));
  let values = {};
  try { values = raw ? JSON.parse(decodeURIComponent(raw.slice(name.length + 1))) : {} } catch (_) {}
  if (values === null || typeof values !== 'object' || Array.isArray(values)) values = {};

  /* Drop states no longer declared: renamed, or gone. */
  for (const key of Object.keys(values)) {
    if (!(key.split('/')[0] in defaults)) delete values[key];
  }
  for (const [signal, value] of Object.entries(patch._cookie)) {
    const entries = value !== null && typeof value === 'object'
      ? Object.entries(value).map(([item, v]) => [signal + '/' + item, v])
      : [[signal, value]];
    for (const [key, v] of entries) {
      delete values[key];
      if (signal in defaults && v !== null && v !== undefined && v !== defaults[signal]) values[key] = v;
    }
  }

  let encoded = encodeURIComponent(JSON.stringify(values));
  while (encoded.length > maxChars) {
    delete values[Object.keys(values)[0]];
    encoded = encodeURIComponent(JSON.stringify(values));
  }
  document.cookie = name + '=' + encoded + '; path=/; max-age=31536000; samesite=lax';
}
