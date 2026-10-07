# Actions

Writes go the other way from renders: an island renders an action into the page, as a Datastar expression, and the
browser invokes it with a POST to the page's own URL, carrying the page's signals. The handler changes state, and the
islands that read that state render again and arrive over the stream.

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

- **Writes are capabilities.** `use-action` mints an unguessable token, bound to the user, the first time an island
  renders it, and revokes it once the island stops rendering it. A POST can only reach closures currently rendered for
  that user: another user's token gets a 403, a revoked one a 404. The token travels in a header, which a cross-site
  form can't set. The authority check happened when the island decided to render the action, and the closure carries
  it.
- **Arguments are untrusted.** Signals arrive from the client and must be validated. They come as JSON decodes them,
  with string keys. Signals whose names start with `_` are the page's own, and aren't sent.
- **Key an action by what it acts on.** A key is the action's identity across renders, and its handler is the latest
  render's. A key whose meaning changes, such as `[:pick i]` on a card paging through questions, lets a click on the
  previous render's element run the next render's handler; `[:pick question-id i]` doesn't, since the old token is
  revoked once its island stops rendering it. A row the island renders gets an action of its own, `[:remove id]`,
  whose token exists only for rows the server rendered, so the id needs no check.
- **A value from the browser, when there's no element to key by.** A block of HTML the island places whole, such as a
  document rendered elsewhere, has no element per reference for the island to render an action on, and a web component
  reports what the user did in a custom event. One action takes what the click knows instead, as a value its
  expression evaluates:

  ```clojure
  (let [open (use-action :open
               (fn [signals {:strs [id]}]
                 (when (contains? rendered-refs id) (open! id)))
               {:value "{id: m.dataset.refId}"})]
    [:div {:data-on:click (str "const m = evt.target.closest('[data-ref-id]'); m && " open)}
     (h/raw document-html)])
  ```

  The value travels in that action's body, with the signals, under the same cap (`:max-signals-bytes`): no signal
  holds it, and no other action posts it. It is untrusted, and since no token vouches for it, the handler checks it
  against what the island rendered.
- **Commands and queries.** A POST runs the command and answers 204, or JSON to patch signals. View updates arrive over
  the stream.
- **Signals after the answer.** Work that outlives its action, such as converting a pasted text with a model, answers at
  once and goes on in a background task, which tells the island how it goes through `use-state`. When its result
  belongs in signals, say a form's fields bound to them, `use-patch-signals` gives the island a `patch!` for it:

  ```clojure
  (let [patch! (use-patch-signals)
        import (use-action :import
                 (fn [{text "text"}]
                   (q/compel (q/task (patch! {:draft (convert text)})))
                   nil))]
    ...)
  ```

  The patch goes over the stream after the frame rendering what changed with it, so the fields are in the page by
  then, or once the tab is connected again. While it is away, patches are kept, merged where one patch does what they
  do in turn. One from an island no longer mounted is dropped. Signals are the page's: a patch overwrites what the user
  typed into a field bound to one.
- **A script's own requests.** `use-token` gives an action's token alone, for a script that posts to the page itself,
  with the token in the `Tropical-Action` header, and reads the answer: the handler gets the request's JSON, and a
  map it returns comes back as JSON. Uploads are built on it.
- **Navigation.** A handler that returns `(navigate path)` answers with a script that opens `path` as a page load,
  pushed onto the tab's history, or replacing its entry with `{:replace true}`. Datastar runs it, with the page's nonce
  in its CSP mode. With `{:signals m}`, the answer carries the signals the handler would return if it didn't navigate,
  merged from `cookie/patch`, `storage/patch` and `storage/forget`, ahead of the script: the page takes them, and its
  body keeps what they set, before the page it opens loads. `path` must be a path on the page's origin, starting with a
  single `/`; anything else throws, so a handler can't be made into an open redirect. A page elsewhere, such as a
  sign-in provider's, takes `{:external true}` and an absolute `http` or `https` URL: the handler's word that it built
  the URL, and didn't take it from the client.
- **The action's own request.** `(action/request)` in a handler returns the request the action came on, not the one
  that created the session, so what the page's own response set, such as a cookie, is on it. Anywhere else, a render
  included, it throws.
- **A page that belongs elsewhere.** An island whose page should be at another URL now, such as one showing an item
  that moved, renders `(action/navigate-element path {:replace true})`: an element that opens `path` once it is in the
  page, so every tab showing the item follows it, and Back doesn't return to the old URL. It runs as it arrives, in a
  page's first frame or patched in, and the same element in a later render doesn't run it again. In a page already
  at `path` it does nothing, so a path compared in another encoding doesn't reload the page in a loop. A page loaded
  at the old URL is better redirected by its route.
- **The race.** A token lives until the session's next render, normally within a frame, so a request can run a moment
  after its island stopped rendering the action. Writes that matter check again in the write path.
