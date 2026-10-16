# 2026-10-09 — Digests of the app's names

`co.multiply.tropical.names`: the page sees a SHA-256 digest of an island's id, a cookie or storage state's signal
name, and a script's name, unless the JVM property `co.multiply.tropical.readable-names` is `true`. The suite passed
both ways: as `bb test` runs it, with digests, its assertions on ids taken through `island/element-id`, and with
`-J-Dco.multiply.tropical.readable-names=true`.

The demo, on the reloaded JVM with its state registries cleared, fetched: every element id a digest (`t27d5211dc43eef66`), the script at `/tropical/t46b7d22850.<hash>.js`, and no namespace or var name in anything
Tropical wrote. What still reads `demo.page/toasts` is the Server resources table, which shows the registry's keys on
purpose. In Chrome:

- The ticker ticked, morphed by its element's digest. A posted message was inserted as a keyed row, with its
  `<time-ago>` defined from the digest's URL and reading "18 s ago". The definition in the Raw HTML card was replaced
  in place when another term was picked.
- Folding the vault's messages wrote the cookie under `t835c0c72ab`; a draft was stored under
  `tropical:["<user>","tc2c239d902","launch"]`, and its row counted it from the server.
