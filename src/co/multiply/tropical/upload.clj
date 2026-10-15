(ns co.multiply.tropical.upload
  "Files the browser sends straight to where the app keeps them, such as a
  bucket taking presigned PUTs: their bytes never pass through the app.

  An island takes files with `use-uploads`, which keeps their list and renders
  the island again as it changes:

      (let [{:keys [uploads start]}
            (use-uploads :attachments
              {:target (fn [{:keys [id name type size]} uploads]
                         (if (< (count (remove (comp #{:refused :failed} :state) uploads)) 10)
                           (let [key (str \"uploads/\" id)]
                             {:ref key :url (presign-put key type) :headers {\"Content-Type\" type}})
                           {:refused \"At most ten files.\"}))})]
        [:div
         [:input {:type \"file\" :multiple true :data-on:change (start \"evt.target.files\")}]
         (for [{:keys [id name state reason]} uploads]
           ...)])

  How a file goes:

  1. `start`'s expression is given files, from a file input or a drop. The
     browser posts their names, types and sizes, not their bytes.
  2. `:target` is called for each file, in order, with the list as it stands,
     the pick's earlier files included: one pick of the island at a time, so a
     limit holds. It returns where the bytes go, `{:ref :url :method :headers}`
     (`:method` PUT unless given), or `{:refused reason}`. The file joins the
     list either way, so the island shows it at once. `:ref` is the app's own
     reference, such as the storage key, and stays on the server: the browser
     knows the upload by its id.
  3. The browser sends the bytes to `:url`, at most three files at a time, its
     progress in a signal (`progress`).
  4. It reports how it went. An upload that arrived is passed to `:arrived`, if
     given, which can check it, as with a HEAD, and refuse it with
     `{:refused reason}`. One that failed gets the browser's reason.
  5. Those that arrived leave the list when the island takes them with
     `take!`, as a message being sent takes its files, or, once none is on
     its way, together to `:settled`, as a project takes in a pick as one
     batch.

  An upload is a map: `:id`, what the browser declared (`:name`, `:type`,
  `:size`), `:ref`, and its `:state`:

  - `:uploading` its bytes are on their way.
  - `:arrived`   the browser reported them sent, and `:arrived` took them.
  - `:refused`   `:target` or `:arrived` refused it, with its `:reason`.
  - `:failed`    sending failed, or `:arrived` or `:settled` threw, with a
                 `:reason` in English, for the log or the island.

  The list lives as long as the island. An island that unmounts, or a session
  that closes, forgets it, and reports for it are refused, as is a report
  naming an id the island didn't give, such as one of another session: its
  token reaches its own list only.

  What is left to the app:

  - What the browser declares, and its report that a file arrived, are
    untrusted. Sign the size and type into the target, or check the object in
    `:arrived`.
  - An object whose arrival is never reported, because the tab closed or the
    island unmounted, or that is removed or refused once it arrived, stays
    where it went. Upload under a prefix the store expires, and keep what the
    app takes.
  - The target must accept the browser's request: a store on another origin
    with CORS allowing the page's origin, the method and the headers, and a
    page whose CSP restricts `connect-src` allowing the store's origin."
  (:require
    [clojure.string :as str]
    [co.multiply.tropical.action :as action]
    [co.multiply.tropical.island :as island]
    [co.multiply.tropical.script :as script :refer [defscript]])
  (:import
    [java.util UUID]
    [java.util.concurrent.locks ReentrantLock]))


(defscript ^:no-doc uploader
  "The browser's half of uploads: it posts the files' descriptions, sends their
  bytes to their targets, and reports how it went."
  {:resource "co/multiply/tropical/upload.js"})


(def ^:private signal
  "The signal holding each upload's progress, by id: local, so no action posts it."
  "_upload")


(def ^:private max-reason
  "The longest reason a browser gives for a failure that is kept, in chars."
  200)


(defmacro ^:private with-lock
  [lock & body]
  `(let [lock# ~lock]
     (ReentrantLock/.lock lock#)
     (try
       ~@body
       (finally
         (ReentrantLock/.unlock lock#)))))


(defn- upload-id
  "A new upload's id: a name in a signal's path, unique on the page."
  []
  (format "u%016x" (UUID/.getLeastSignificantBits (random-uuid))))


(defn- described
  "The file the browser describes in `m`, or nil if it doesn't describe one."
  [m]
  (when (map? m)
    (let [{:strs [name type size]} m]
      (when (and (string? name) (string? type) (nat-int? size))
        {:name name :type type :size size}))))


(defn- targeted
  "The upload of `file` as `:target`'s `result` makes it, and the browser's
  answer for it."
  [{:keys [id] :as file} {:keys [ref url method headers] :as result}]
  (cond
    (and (map? result) (contains? result :refused))
    [(assoc file :state :refused :reason (:refused result))
     {:id id :refused true}]

    (and (map? result) (contains? result :ref) (string? url))
    [(assoc file :state :uploading :ref ref)
     {:id id :url url :method (or method "PUT") :headers (or headers {})}]

    :else
    (throw (IllegalArgumentException.
             (str "An upload's :target returns {:ref :url}, with :method and :headers if need be, "
               "or {:refused reason}; not " (pr-str result) ".")))))


(defn- target!
  "Answers the browser's description of `files`: each one's id, and where its
  bytes go or that it is refused. The described files join the list together,
  once `target` has answered for each."
  [{:keys [!uploads lock]} target files]
  (with-lock lock
    (let [[added answers]
          (reduce (fn [[added answers] m]
                    (if-some [file (described m)]
                      (let [file (assoc file :id (upload-id))
                            [upload answer] (targeted file (target file (into @!uploads added)))]
                        [(conj added upload) (conj answers answer)])
                      [added (conj answers {:refused true})]))
            [[] []]
            files)]
      (swap! !uploads into added)
      {:targets answers})))


(defn- uploading
  "The upload `id` in `uploads`, if it is uploading."
  [uploads id]
  (some #(when (and (= id (:id %)) (= :uploading (:state %))) %) uploads))


(defn- settle
  "`uploads` with the upload `id`, if it is uploading, merged with `outcome`."
  [uploads id outcome]
  (mapv #(if (and (= id (:id %)) (= :uploading (:state %))) (merge % outcome) %) uploads))


(defn- arrived?
  [upload]
  (= :arrived (:state upload)))


(defn- settled!
  "Once nothing is uploading, passes the uploads that arrived to `settled`, if
  given, and takes them from the list; if it throws, they fail. Called under
  the lock, after an upload stopped being on its way."
  [{:keys [!uploads]} settled]
  (when settled
    (let [uploads @!uploads]
      (when (and (not-any? #(= :uploading (:state %)) uploads) (some arrived? uploads))
        (let [batch (filterv arrived? uploads)
              ids   (into #{} (map :id) batch)
              fail  #(cond-> % (ids (:id %)) (assoc :state :failed :reason "The upload couldn't be taken in."))]
          (try
            (settled batch)
            (catch Throwable e
              (swap! !uploads #(mapv fail %))
              (throw e)))
          (swap! !uploads (fn [uploads] (filterv #(not (ids (:id %))) uploads))))))))


(defn- ended!
  "Settles the upload `id` with `outcome`, then the pick, if it was the last on
  its way: `settled!`. Rethrows `thrown`, what `:arrived` threw, once it has."
  [{:keys [!uploads] :as held} settled id outcome thrown]
  (swap! !uploads settle id outcome)
  (if thrown
    (do
      (try
        (settled! held settled)
        (catch Throwable e
          (when-not (identical? thrown e)
            (Throwable/.addSuppressed thrown e))))
      (throw thrown))
    (settled! held settled)))


(defn- arrive!
  "Takes the upload `id` as arrived, if it is uploading, unless `arrived`
  refuses it."
  [{:keys [!uploads lock] :as held} {:keys [arrived settled]} id]
  (with-lock lock
    (when-some [upload (uploading @!uploads id)]
      (let [[outcome thrown] (try
                               (let [verdict (when arrived (arrived upload))]
                                 [(if (and (map? verdict) (contains? verdict :refused))
                                    {:state :refused :reason (:refused verdict)}
                                    {:state :arrived})])
                               (catch Throwable e
                                 [{:state :failed :reason "The upload couldn't be checked."} e]))]
        (ended! held settled id outcome thrown)))))


(defn- fail!
  "Takes the upload `id` as failed, if it is uploading, for the browser's `reason`."
  [{:keys [!uploads lock] :as held} {:keys [settled]} id reason]
  (with-lock lock
    (when (uploading @!uploads id)
      (ended! held settled id
        {:state  :failed
         :reason (if (string? reason)
                   (subs reason 0 (min max-reason (count reason)))
                   "The upload failed.")}
        nil))))


(defn- remove!
  "Drops the upload `id` from the list. If it was the last on its way, the
  pick is settled."
  [{:keys [!uploads lock] :as held} {:keys [settled]} id]
  (with-lock lock
    (let [[before] (swap-vals! !uploads (fn [uploads] (filterv #(not= id (:id %)) uploads)))]
      (when (uploading before id)
        (settled! held settled))
      nil)))


(defn- take!
  "Takes the uploads that arrived from the list, and returns them."
  [{:keys [!uploads lock]}]
  (with-lock lock
    (let [[before] (swap-vals! !uploads (fn [uploads] (filterv (complement arrived?) uploads)))]
      (filterv arrived? before))))


(defn- handle
  "The answer to the browser's request `body`: targets for the files it
  describes, or nothing for its report on one."
  [held {:keys [target] :as opts} body]
  (when (map? body)
    (cond
      (contains? body "files")
      (let [files (get body "files")]
        (target! held target (if (sequential? files) files [])))

      (contains? body "arrived")
      (do
        (arrive! held opts (get body "arrived"))
        nil)

      (contains? body "failed")
      (do
        (fail! held opts (get body "failed") (get body "reason"))
        nil))))


(defn use-uploads
  "Files the browser sends straight to the targets that `:target` names, kept
  under `k` in this island (see the namespace docstring). The island renders
  again as the list changes. Returns a map:

  - `:uploads` the list, in the order the files were given.
  - `:start`   `(start files)`: the expression that uploads `files`, a
               JavaScript expression for a FileList or an array of Files, such
               as `evt.target.files` in an input's `change`, or
               `evt.dataTransfer.files` in a `drop`. It reads them as it runs,
               so the input can be cleared after it.
  - `:remove!` `(remove! id)`: drops an upload from the list. One on its way
               still arrives where it went, and its report is refused. The
               last one on its way ends the batch, as its report would.
  - `:take!`   `(take!)`: removes the uploads that arrived and returns them,
               at once, as a message being sent takes its files.

  Options:

  - `:target`  `(fn [file uploads] ...)`: `file` is `{:id :name :type :size}`,
               and `uploads` the list as it stands. Returns
               `{:ref :url :method :headers}` or `{:refused reason}`.
  - `:arrived` optional, `(fn [upload] ...)`: called once the browser reports
               the upload's bytes sent. Returns `{:refused reason}` to refuse
               it, anything else to take it.
  - `:settled` optional, `(fn [arrived] ...)`: called once nothing is
               uploading, with the uploads that arrived, which it takes, as
               `take!` does: a pick taken in whole, with any picked while it
               was on its way. One refused or failed stays in the list, and
               doesn't hold back the rest. It isn't called when none arrived,
               and if it throws, those it was given fail.

  Each runs on the thread of the request it answers, `:settled` on that of
  the report or the action that ended the last upload, one at a time per
  island and `k`: a report waits for the one before, so `:settled` starts
  long work rather than doing it. They are replaced by each render's, as an
  action's handler is."
  [k {:keys [target arrived settled]}]
  (when-not (ifn? target)
    (throw (IllegalArgumentException. "use-uploads takes a :target fn, which names where each file goes.")))
  (let [opts    {:target target :arrived arrived :settled settled}
        {:keys [!uploads] :as held} (island/use-hold [::uploads k]
                                      (fn [] {:value {:!uploads (atom []) :lock (ReentrantLock.)}}))
        uploads (island/use-watch !uploads)
        token   (action/use-token [::token k] #(handle held opts %))
        url     (script/use uploader)]
    (run! (fn [{:keys [id size state]}]
            (when (= :uploading state)
              (island/declare-signal [signal id] {:loaded 0 :total size})))
      uploads)
    {:uploads uploads
     ;; Names its drop area (`drop-area`) by its token, unique on the page.
     :start   (with-meta
                (fn [files]
                  (str "((files) => import('" url "').then((m) => m.start($, '" token "', files)))"
                    "(Array.from(" files " ?? []))"))
                {::area (format "a%08x" (hash token))})
     :remove! #(remove! held opts %)
     :take!   #(take! held)}))


(defn progress
  "A JavaScript expression for how much of the upload `id` the browser has
  sent, from 0 to 1, for an expression such as a bar's width:
  `(str \"(100 * \" (progress id) \") + '%'\")`. It reads the upload's signal,
  `_upload.<id>`, which holds `{loaded, total}` in bytes, and which the island
  declares while the upload is `:uploading`."
  [id]
  (let [s (str "$" signal "." id)]
    (str "(" s ".total ? " s ".loaded / " s ".total : 0)")))


;; Drop areas -------------------------------------------------------------------


(def ^:private drop-signal
  "The signal naming the drop area files are dragged over, by its id, or empty:
  local, so no action posts it. Datastar makes it, empty, as an expression
  first reads it."
  "_uploadDrop")


(def ^:private files?
  "Whether a drag event carries files, rather than text or a link."
  "evt.dataTransfer?.types.includes('Files')")


(defn- area
  "The id of the drop area for `start`, which `use-uploads` returned."
  [start]
  (or (::area (meta start))
    (throw (IllegalArgumentException. "A drop area takes the `:start` that `use-uploads` returns."))))


(defn drop-area
  "Attributes that make an element a drop area for `start`, the `:start` that
  `use-uploads` returns, added to the element's own `attrs`. Files dragged
  over it are the area's, and a drop starts uploading them; over an area
  inside it, they are the inner one's alone. A drag of text or a link isn't
  the area's, and goes on as if it weren't there.

      [:div (upload/drop-area {:class [:relative]} start)
       content
       [:div (ui/attr {:class [:absolute :inset-0]} :hidden true (str \"!\" (upload/dropping start)))
        \"Drop files to add them\"]]

  The browser fires `dragover` again and again at the element under the
  pointer while files are dragged: the innermost area around it takes the
  drag there, and keeps it from the areas around that. Leaving the area, or
  the window, or calling the drag off, lets it go. A drop outside every area
  is refused by the page's body (`co.multiply.tropical.ui/page-attrs`), so
  the browser doesn't open the file in the tab.

  An attribute of the element's own that one of these would replace throws.
  Two areas for one `start` are one area to `dropping`: dragging over either
  shows both."
  ([start]
   (drop-area {} start))
  ([attrs start]
   (let [id  (area start)
         mark (fn [v] (str "$" drop-signal " = '" v "'"))
         own {:data-on:dragover  (str files? " && (evt.preventDefault(), evt.stopPropagation(), "
                                   "evt.dataTransfer.dropEffect = 'copy', " (mark id) ")")
              ;; `relatedTarget` is what the pointer went on to: outside the
              ;; area, or nothing, past the window's edge or as the drag is
              ;; called off.
              :data-on:dragleave (str "$" drop-signal " === '" id "' && !el.contains(evt.relatedTarget) && ("
                                   (mark "") ")")
              :data-on:drop      (str files? " && (evt.preventDefault(), evt.stopPropagation(), " (mark "") ", "
                                   (start "evt.dataTransfer.files") ")")}]
     (when-some [taken (seq (filter #(contains? attrs %) (keys own)))]
       (throw (IllegalArgumentException.
                (str "drop-area sets " (str/join ", " (map name taken)) " on the element, which has its own."))))
     (merge attrs own))))


(defn dropping
  "A JavaScript expression, true while files are dragged over the drop area for
  `start` (`drop-area`), for the app's own overlay."
  [start]
  (str "($" drop-signal " === '" (area start) "')"))


(defn drop-guard
  "Attributes for the page's body, part of `co.multiply.tropical.ui/page-attrs`:
  files dragged anywhere outside a drop area are refused, so a drop there
  does nothing, where the browser would open the file in the tab, and no area
  shows them. A file input still takes files dropped on it."
  []
  (let [outside (str files? " && !(evt.target instanceof HTMLInputElement && evt.target.type === 'file')")]
    {:data-on:dragover__window (str outside " && (evt.preventDefault(), evt.dataTransfer.dropEffect = 'none', $"
                                 drop-signal " = '')")
     :data-on:drop__window     (str outside " && (evt.preventDefault(), $" drop-signal " = '')")}))
