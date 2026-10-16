# 2026-10-16 — `cookie/observe` in the demo

A smoke test of the demo under `bb dev`, at `127.0.0.1:8080`, with the changed namespaces reloaded into the running
JVM. Hiding the Server resources card set `hidden` and `aria-expanded` at once, and the table left the page a round
trip later. Showing it again, the element showed at once, and the table came back a round trip later. Each toggle sent
one action request, answered 204. A page loaded with the card folded had no table in its first frame, and its cookie
watch sent nothing as it appeared, since the server had rendered the value the page held. The two drafts' storage
watches each sent their value as they appeared, as before, now through the shared program.

The Chrome extension's network log listed every one of these POSTs as a 503. Wrapping the page's `fetch` showed 204
for each, and the server took the values: the log was wrong, not the endpoint.

Validation: 192 JVM tests with 6,210 assertions and 14 Node tests pass, along with lint and formatting checks. Linux
containers were not run, since no Java or native code changed.
