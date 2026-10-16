# 2026-10-10 — The first frame's scripts, twice

An app found each page's head with its island script twice: once from the page, and once appended over the stream after
it attached. By design: the session sent the first frame's scripts over the stream too, in case the page had left them
out, and a module runs once per URL, so nothing ran twice. Now `script/tags`, which `ring/scripts` calls, records that
the page has the first frame's scripts, and the session counts them as sent; a page without them still gets them over
the stream.

A test triggers a patch once the stream has attached, and checks whether the script came over the stream before it.
With the record disabled, the page with the scripts in its head got them again, and the test failed. In the demo, the
stream sent 9 element patches in 3 s, and not the head's script. In the app: one copy per page, its streams live, and
no errors in the console.
