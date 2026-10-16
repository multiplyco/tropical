# Actions

Writes go the other way from renders. An island renders an action into the page as a Datastar `@post(...)`
expression, and when the browser invokes it, Datastar posts the page's signals to the page's own URL. The handler
changes state, and the islands that read that state render again and arrive over the stream.

```clojure
(defisland item-row
  {:key :id}
  [{:keys [id title]}]
  (let [{:keys [uid]} (use-session)
        archive (use-action [:archive id]
                  (fn [_signals]
                    (archive-item! uid id)
                    nil))]
    [:li title [:button {:data-on:click archive} "Archive"]]))
```

The list that renders the rows reads the items, so the archived one goes from every page showing it.

## Writing one

- **Key an action by what it acts on.** A key is the action's identity across renders, and its handler is the latest
  render's. A key whose meaning changes, such as `[:pick i]` on a card paging through questions, lets a click on the
  previous render's element run the next render's handler; `[:pick question-id i]` doesn't, since the old token is
  revoked once its island stops rendering it. A row the island renders gets an action of its own, `[:remove id]`,
  whose token exists only for rows the server rendered, so the id needs no check.
- **The handler gets the page's signals,** as JSON decodes them, with string keys: all but Datastar's local ones,
  under a key starting with `_`, which stay in the page.
- **It answers with nothing, signals, or a page.** nil answers 204. A map answers JSON, which Datastar patches into
  the page's signals. `(action/navigate path)` opens a page ([Navigation](#navigation)). What the handler changed in
  the view isn't in the answer: it arrives over the stream, from the islands that read it.
- **`(action/request)`** in a handler returns the request the action came on, not the one that created the session,
  so what the page's own response set, such as a cookie, is on it. Anywhere else, a render included, it throws.

## What an action trusts

- **Writes are capabilities.** `use-action` mints an unguessable token, bound to the user, the first time an island
  renders it, and revokes it once the island stops rendering it. A POST can only reach closures currently rendered for
  that user: another user's token gets a 403, a revoked one a 404. The token travels in a header, which a cross-site
  form can't set. The authority check happened when the island decided to render the action, and the closure carries
  it.
- **Signals are untrusted.** They arrive from the client, and are validated as any request's parameters are.
- **The race.** A token lives until the session's next render, normally within a frame, so a request can run a moment
  after its island stopped rendering the action. Writes that matter check again in the write path.

[Security](security.md) has the rest of what Tropical decides for an app, and what stays the app's.

## A value from the browser

A block of HTML the island places whole, such as a document rendered elsewhere, has no element per reference for the
island to render an action on, and a web component reports what the user did in a custom event. Then one action takes
what the click knows instead, as a value its expression evaluates when it is invoked:

```clojure
(let [open (use-action :open
             (fn [signals {:strs [id]}]
               (when (contains? rendered-refs id) (open! id)))
             {:value "{id: m.dataset.refId}"})]
  [:div {:data-on:click (str "const m = evt.target.closest('[data-ref-id]'); m && " open)}
   (h/raw document-html)])
```

The value travels in that action's body, with the signals, under the same cap (`:max-signals-bytes`): no signal holds
it, and no other action posts it. It is untrusted, and since no token vouches for it, the handler checks it against
what the island rendered.

## Signals after the answer

Work that outlives its action, such as converting a pasted text with a model, answers at once and goes on in a
background task, which tells the island how it goes through `use-state`. When its result belongs in signals, say a
form's fields bound to them, `use-patch-signals` gives the island a `patch!` for it:

```clojure
(let [patch! (use-patch-signals)
      import (use-action :import
               (fn [{text "text"}]
                 (q/compel (q/task (patch! {:draft (convert text)})))
                 nil))]
  ...)
```

- **After the view.** The patch goes over the stream after the frame rendering what changed with it, so the fields
  are in the page by then, or once the tab is connected again.
- **Kept while the tab is away.** Patches are kept, merged where one patch does what they do in turn. One from an
  island no longer mounted is dropped.
- **Signals are the page's.** A patch overwrites what the user typed into a field bound to one.

## Navigation

- **From a handler.** A handler that returns `(navigate path)` answers with a script that opens `path` as a page
  load, pushed onto the tab's history, or replacing its entry with `{:replace true}`. Datastar runs it, with the
  page's nonce in its CSP mode.
- **With signals first.** With `{:signals m}`, the answer carries the signals the handler would return if it didn't
  navigate, merged from `cookie/patch`, `storage/patch` and `storage/forget`, ahead of the script. The page takes
  them, and its body keeps what they set, before the page it opens loads.
- **On the page's origin.** `path` must be a path on the page's origin, starting with a single `/`; anything else
  throws, so a handler can't be made into an open redirect. A page elsewhere, such as a sign-in provider's, takes
  `{:external true}` and an absolute `http` or `https` URL: the handler's word that it built the URL, and didn't take
  it from the client.
- **From a render.** An island whose page should be at another URL now, such as one showing an item that moved,
  renders `(action/navigate-element path {:replace true})`: an element that opens `path` once it is in the page, so
  every tab showing the item follows it, and Back doesn't return to the old URL. It runs as it arrives, in a page's
  first frame or patched in, and the same element in a later render doesn't run it again. In a page already at `path`
  it does nothing, so a path compared in another encoding doesn't reload the page in a loop. A page loaded at the old
  URL is better redirected by its route.

## A script's own requests

`use-token` gives an action's token alone, for a script that posts to the page itself, with the token in the
`Tropical-Action` header, and reads the answer: the handler gets the request's JSON, and a map it returns comes back
as JSON. Uploads are built on it. An answer with a `Tropical-Reopen` header, a 409, says the request reached a server
that doesn't hold the tab's session: the script dispatches `tropical:reopen` on `window`, which reopens the page's
stream there, and drops the request.

## A dropped action

An action that reaches a server without the tab's session, as when a balancer's stickiness lapsed, is dropped, and the
page reopens its stream there ([Serving pages](serving.md#running-it)). A form still shows what the user typed, so a
dropped Save looks like one that worked: say that it didn't. The page dispatches `tropical:reopen` on `window` as it
reopens, and for nothing else, so a notice can follow it, shown by a signal, which survives the page's rebuild where an
element a script adds to an island doesn't:

```clojure
[:div {:data-on:tropical:reopen__window "$_dropped = true" :data-show "$_dropped" :style "display: none"}
 "That didn't go through. Try again."]
```
