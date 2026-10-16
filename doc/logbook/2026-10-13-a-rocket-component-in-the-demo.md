# 2026-10-13 — A Rocket component in the demo

Datastar 1.0.4 moved Rocket, its web components, out of Datastar Pro, into a bundle of its own with Datastar,
`datastar-rocket.js` ("Datastar v1.0.4 + Rocket beta.2"). The demo now loads it in place of `datastar.js`, named
`datastar` in an import map, and has a Rocket component, `<star-rating>`, in a card of its own: props from the island,
local signals for the star under the pointer and an open breakdown, a `rate` event the island takes as an action's
value, and the island's label, with an action, in its children. Checked in Chrome, with the page's CSP (a nonce,
`'strict-dynamic'`, no `'unsafe-eval'`), from a tab kept streaming while hidden (`:open-when-hidden`, set from the
REPL):

- The component rendered in CSP mode, with no console errors. A click posted the action with `evt.detail` as its value,
  `{"stars": 4}`, and the island's patch changed the props, the summary and the label, while the breakdown stayed
  open. A rating from another user, set from the REPL, changed every prop the same way.
- A full resync, after `session/close-all!`, left the shadow root and the open breakdown as they were.
- The island's action in the component's children, `@post(...)`, arrived rewritten by Rocket to
  `@dispatchRocket("post", ...)`, and worked: the component defines no `post`, so it passed the call on to Datastar.
  It worked in the first frame, and patched in after a rating.
- Moving the element, with `moveBefore` as the morph moves one, or with `insertBefore`, closed the breakdown: the
  component defines no `connectedMoveCallback`, so it is disconnected, which drops its signals, and set up again.
- The morph removed `data-rocket-host`, which Rocket sets on the element as it connects, since the island doesn't
  render it; it keeps `data-scope-children`, which it knows. Rocket reads the marker to tell a component's children
  from those of one nested in them. The component's own children, and their actions, worked without it.
- The same component in `light` mode, for the test only: a rating changed the props, and the component rendered again
  with the label it had taken in when it started, "Rate the demo.", over the island's "You rated it 3". A resync, its
  props unchanged, removed what it drew, and left only the island's label.

Not exercised: a Rocket module first loaded over the stream, when an island using it is patched in, and a component
inside another's children.
