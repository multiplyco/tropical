# Security

What Tropical decides for an app, and what stays the app's. Each point is covered where its subject is, and linked
from here.

## What a user can reach

- **Rendering is the access check.** A subtree the user may not see is never rendered: its markup is never produced,
  its subscriptions are never opened, and its actions never exist. The gate is `if`, in the island that decides
  ([Islands](islands.md)).
- **Actions are capabilities.** An action is an unguessable token, bound to the session's user, that exists while its
  island renders it. A POST with another user's token gets a 403, and one with a token never minted, or revoked since,
  a 404. The token travels in a header, which a cross-site form can't set, so a page elsewhere can't post an action
  on the user's behalf ([Actions](actions.md), [design](design.md#actions)).
- **A token outlives its island's last render by a moment.** It is revoked at the session's next render, so a request
  can arrive just after the island stopped rendering the action. Writes that matter check again in the write path.
- **An action per thing acted on needs no check that the thing is allowed.** An action keyed `[:remove id]`, rendered
  per row, exists only for the rows the server rendered for this user. One action taking a value from the browser
  vouches for less: the handler checks the value against what the island rendered.
- **A tab is its user's.** A session belongs to the user whose request created it. A stream for another user's tab is
  refused, and its page reloads.

## What the browser sends

Everything from the browser is the user's to change, whatever the page's script would send.

- **Signals** arrive as JSON, with string keys: validate them as any request's parameters.
- **An action's value** (`:value`) is checked against what the island rendered, since no token vouches for it.
- **Cookie states** are read from the request's cookie and checked with the state's `:valid?`, falling back to its
  default ([State the browser owns](browser-state.md)).
- **Storage states** come back through `storage/decode`, which returns nil unless the value passes `:valid?`.
- **An upload's arrival** is the browser's word. `:arrived` can check it, as with a HEAD on the stored object, and
  refuse it. The browser knows an upload by the id the island gave it, never by the app's reference, so it can't make
  the app take an object of its choosing, or another session's ([Files from the browser](uploads.md)).
- **An action's body** is read up to `:max-signals-bytes`, 1 MiB by default, and more gets a 413.

## Keys that decide who shares

- **A shared state's key is its access.** Every island reading a state with an equal key reads one value, in any
  session. Build the key from what the session vouches for, such as `use-session`'s `:uid`, never from signals or an
  action's value ([State shared on the server](shared-state.md)).
- **An `observe`'s `args` decide who shares its resource.** Data that differs per user needs the user, or their
  account, among the `args` ([Reading from outside the page](reading.md)).
- **Entries in the browser's storage are the user's.** An entry's key holds a digest of the session's user, and a page
  loaded for another user drops it. They are in the user's browser, not secret from the user.

## Sessions and authentication

- **Authentication is the app's.** `:uid` returns the user a request is made on behalf of, and a page or action
  without one gets a 403 ([Serving pages](serving.md)).
- **A session keeps the scope of the request that created it.** What the route's middleware bound when the page
  loaded, such as the account, is what every render of that session sees, for its life.
- **An open stream isn't checked again.** The route's middleware runs as a stream connects. A user who loses access
  while the page is open keeps the stream, and its updates, until it reconnects. Read access in the islands, as a read
  that follows it, so a render answers the change; a refused reconnect reloads the page into the app's own answer.
- **Signing a user out.** End the sign-in, then `(session/close-user! uid)`. It closes the user's sessions: their
  islands unmount, their actions are revoked, and their streams end. Each tab reconnects about a second later
  through the route's middleware, which refuses a user no longer signed in, and the page reloads into the app's own
  answer, such as its sign-in page (`ring/wrap-refused-streams`). In that order: a tab that reconnects before the
  sign-in ends gets a fresh session. So does a request that passed the middleware before then, and creates its
  session after the close.
- **On several servers, each closes its own.** `close-user!` reaches the sessions of the JVM it runs in, so the app
  tells every server, over what they share. Or the root island follows the user's standing, read from where every
  server reads it, such as the database:

  ```clojure
  ;; `user-standing` emits the user's standing as the database has it, such as `:active` or `:signed-out`.
  (defisland root
    [uid]
    (if (= :signed-out (observe uid user-standing))
      (action/navigate-element "/sign-in")
      (app uid)))
  ```

  Every server's sessions read the change, without being told, and a session created late reads it as it renders.
  Until the read lands, `observe` returns `pending`, and the app renders, as the request that created the session
  passed the middleware. Once the standing changes, the app's islands unmount, so their reads close and their actions
  are revoked, and the tab opens the sign-in page.

## What the page runs

- **Text and attributes are escaped.** Chassis escapes the strings an island renders, in content and attribute values.
  HTML placed with `h/raw` is the app's to make safe.
- **An expression is code.** A Datastar expression, in an `data-on:` or `data-attr:` attribute, runs as JavaScript.
  Escaping protects the attribute, not the JavaScript inside it, so a value spliced into an expression with `str` can
  become code. Build expressions from constants and the helpers' output (`use-action`, `ui/signal`, `ui/attr`). Data
  goes into the page as text or an attribute's value, and a value an expression needs goes in through `ui/json`, as a
  JavaScript literal, never as the string it is: `(str "$tab = " (ui/json tab))`.
- **The CSP needs no `'unsafe-eval'`.** With the page's nonce on `<html data-nonce=...>`, Datastar compiles each
  expression into a script carrying it, those patched in over the stream included, and a navigation's script runs with
  it. Scripts the islands declare get it too ([Serving pages](serving.md), [Scripts the page needs](scripts.md)).
- **Navigation stays on the page's origin.** `navigate` and `navigate-element` take a path on the page's origin and
  throw for anything else, so a handler passing on what a client sent can't become an open redirect. A page elsewhere
  takes `{:external true}` and an absolute `http` or `https` URL, never `javascript:`: the handler's word that it built
  the URL ([Actions](actions.md)).
- **The page doesn't show how the app is laid out.** Island ids, state signals and script URLs are digests of the
  app's names for them. They aren't secrets, since a name can be guessed and checked, but the page tells nothing of
  the app's namespaces, functions and states ([design](design.md#names-the-browser-sees)).
- **A failed island says only that it failed.** The default error view shows no exception message, which can carry
  what the user shouldn't see, only a reference made for the failure: random characters, which tell nothing of it. The
  exception goes to the log, with the reference. What an app's or an island's own view renders reaches the client.

## Compression

A compressed response carrying a secret and text an attacker can vary leaks the secret to someone watching its
encrypted size (BREACH), and a stream, compressed in one window and flushed per frame, suits that better than a page.
Tropical's own tokens in the stream are bound to their user, so one learned this way is no use without the user's
session. What the app renders is the app's to judge, which is why the stream's compression is opted into
([Serving pages](serving.md#compressing-the-stream), [design](design.md#compressing-the-stream)).

## What stays the app's

- Authentication, and the access decisions each render and each write path makes.
- Validating what the browser sends.
- The CSP and other security headers, and CORS on an upload store elsewhere.
- Limits on how often a user may act.
