# 2026-10-08 — Uploads straight to a bucket

The demo's uploads card, in Chrome, under its CSP, against `demo.bucket`, which takes a presigned PUT and reads at
8 MB/s so progress shows on a local connection. Files were given to the input and dropped on the body from the
console, as `File`s in a `DataTransfer`, with `window.fetch` and `XMLHttpRequest` wrapped to record and tamper.

- A pick of a 40 MB and a 5-byte file posted one description of both, and no bytes. The small file arrived at once;
  the large one was at 27% after 1.5 s, and arrived with the size the bucket recorded, which `:arrived` checks.
- The expression imported the module by its URL, under the CSP's `'strict-dynamic'`, as the page had loaded it.
- A drop on the body was taken by the window's handler, its default prevented. Of four files, one whose PUT carried
  a forged signature got a 403 and was reported failed with that status; two arrived; the fourth was refused, the
  card's limit of five counting the 40 MB file, the small one and the one uploading.
- Send took the four that arrived and left the failed and the refused; removing one dropped its row.
- A second pick during a 40 MB upload re-rendered the island. The bar was the same element, and its width went on
  from 27.0% to 32.8% over 400 ms, never back: Datastar's `style` plugin re-applies its value when a morph changes the
  attribute.
- A fresh page that uploaded four 3 MB files logged no errors: no binding read a missing signal.
