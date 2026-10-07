# Files from the browser

A message takes attachments, a project its files. `co.multiply.tropical.upload` has the browser send a file's bytes
straight to where the app keeps them, such as a bucket taking presigned PUTs, so they never pass through the app, while
the island renders the files and their progress.

```clojure
(defisland composer
  []
  (let [{:keys [uploads start remove! take!]}
        (upload/use-uploads :attachments
          {:target  (fn [{:keys [id type size]} uploads]
                      (if (< size max-size)
                        (let [key (str "pending/" id)]
                          {:ref key :url (presign-put key) :headers {"Content-Type" type}})
                        {:refused "Too large."}))
           :arrived (fn [{:keys [ref size]}]
                      (when-not (= size (stored-size ref))
                        {:refused "It didn't arrive whole."}))})
        send (use-action :send (fn [{:strs [text]}] (post-message! text (map :ref (take!)))))]
    [:form {:data-on:submit send}
     [:textarea {:data-bind "text"}]
     [:input {:type "file" :multiple true :data-on:change (start "evt.target.files")}]
     (for [{:keys [id name state reason]} uploads]
       [:div name
        (case state
          :uploading [:div {:data-style:width (str "(100 * " (upload/progress id) ") + '%'")}]
          :arrived "Attached"
          reason)
        [:button {:type "button" :data-on:click (use-action [:remove id] (fn [_] (remove! id)))} "Remove"]])]))
```

- **The bytes go to the target only.** The browser posts each file's name, type and size. `:target` answers where its
  bytes go, a URL, a method (PUT unless given) and headers, or refuses it. The browser sends them there, three files
  at a time, and reports how it went.
- **The server keeps the reference.** `:target` also returns the app's own reference, such as the storage key, which
  stays on the server: the browser knows an upload by an id the island gave it. Its reports go to a token bound to the
  user and revoked with the island, and reach that island's uploads only, so a client can't make the app take an
  object of its choosing, or another session's. That a file arrived is still the browser's word: `:arrived` can
  check, as with a HEAD, and refuse it.
- **The island keeps the list.** A file joins it as soon as `:target` has answered, so the island shows it at once,
  and the island renders again as the list changes. An upload is `:uploading`, `:arrived`, `:refused` or `:failed`,
  the last two with a `:reason`. `:target` sees the list as it stands, the pick's earlier files included, one pick of
  the island at a time, so a limit holds. `take!` removes the uploads that arrived and returns them, as a message
  being sent takes its files, and `remove!` drops one.
- **Or taken in whole.** Where files go in as soon as they arrive, a project's say, and each batch starts work over
  all of them, `:settled` gets a pick at once: called once nothing is uploading, with the uploads that arrived, which
  it takes. A pick made while another is on its way joins its batch, and one that failed or was refused stays in the
  list without holding back the rest. It runs under the hook's lock, so it starts long work rather than doing it.
- **Progress stays in the browser.** Bytes sent are in a local signal per upload, which `upload/progress` reads, so a
  bar moves without a round trip.
- **Started from an expression.** `(start js)` uploads the files a JavaScript expression gives, read as it runs:
  `evt.target.files` in an input's `change`, after which the input can be cleared (`"; evt.target.value = ''"`), or
  `evt.dataTransfer.files` in a drop.
- **Drop areas.** `(upload/drop-area attrs start)` makes an element a drop area for `start`: files dragged over it are
  its own, and a drop uploads them. The innermost area under the pointer takes a drag, so an area can hold another, or
  be the whole page, and a drag of text or a link passes. `(upload/dropping start)` is an expression, true while files
  are over the area, for the app's own overlay:

  ```clojure
  [:div (upload/drop-area {:class [:relative]} start)
   content
   [:div (ui/attr {:class [:absolute :inset-0]} :hidden true (str "!" (upload/dropping start)))
    "Drop files to add them"]]
  ```

  The element the app mounts in (`ring/mount`) refuses files dropped outside every area, which the browser would
  open in the tab; a file input still takes them. Two areas for one `start` show their overlays together.
- **What the store must allow.** The browser's request goes to the target's origin: a store elsewhere needs CORS
  allowing the page's origin, the method and the headers, and a CSP restricting `connect-src` must allow it. The
  browser's half is a script, served by `wrap-scripts`.
- **What stays behind.** An object whose arrival is never reported, because the tab closed or the island unmounted,
  or that was removed or refused once it arrived, stays in the store. Upload under a prefix the store expires, and
  keep what the app takes.
