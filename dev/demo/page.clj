(ns demo.page
  "The demo UI: two pages, `/` with every card, and `/topic/<topic>` with one
  topic's feed. The root island is rendered per session, so render counts are
  per tab. Each card demonstrates one claim from the README and its guides:

  - `feed-card`:      shared subscriptions and a one-off query (`observe`),
                      island-local state, and the linger. Its ticker reads the
                      feed through two hooks of its own, over one subscription.
  - `vault-card`:     scope-based ACL. Revoking stops rendering the panel, which
                      releases its resource and revokes its actions.
  - `messages`:       keyed islands. A new message renders one row; the others are reused.
                      Each tells how long ago it was posted through a web component,
                      `<time-ago>`, a script the rows declare (`script/use`).
  - `resources-card`: the registry, live: what is open, for whom, and what lingers.
  - `glossary-card`:  one action for every term in a block of raw HTML, passed
                      the clicked term as a value (`use-action`'s `:value`).
                      The definition is an island keyed by its term, which
                      starts over when another is picked.
  - `rating-card`:    a web component made with Datastar's Rocket,
                      `<star-rating>`: the island renders its props and takes
                      its events, and it keeps what only the browser changes.
  - `drafts-card`:    proposals reworked in a dialog, whose drafts the browser
                      keeps in its storage (`co.multiply.tropical.storage`),
                      through closing it, a reload and a server restart. Each
                      row counts its draft's words on the server, which reads
                      it from the browser (`storage/observe`); Start takes it as
                      its action's value, and it and Dismiss drop it; so does
                      one that opens another page, before that page loads.
  - `uploads-card`:   files picked or dropped, sent by the browser straight to a
                      stand-in bucket (`demo.bucket`) at URLs the island
                      presigned, with their progress, and taken in a pick at a
                      time (`use-uploads`). It is a drop area inside another,
                      the page's (`app`), which keeps a list of its own.
  - `bulk`:           a big static island. It is sent on full resyncs and never patched.

  The resources table and the vault's messages fold away, as state the browser
  owns and the server renders, kept in a cookie (`co.multiply.tropical.cookie`).
  The resources card's tabs switch in the browser, on a local signal, and the
  composer's Post button is enabled while there is something to post:
  attributes following an expression from the first paint (`ui/attr`). Its
  Suggest button answers at once, and fills the input a moment later from a
  background task (`use-patch-signals`).

  A topic's page takes its root from the route. Moving between pages is a page
  load and a new session, and its feed's subscription, lingering, is picked up
  again. Its \"Another topic\" button is an action that navigates
  (`action/navigate`). Its summary hides, a UI state per topic, which `/` can
  choose for the page it opens (`action/navigate`'s `:signals`), and a topic's
  page for every topic (`cookie/patch`). A topic's page moved to another URL
  from the REPL takes its open tabs there (`action/navigate-element`). The
  route refuses a topic that doesn't exist, and a tab whose topic goes away is
  reloaded into that answer when its stream reconnects
  (`ring/wrap-refused-streams`).

  Toasts are a state the server holds per user, shared by their tabs
  (`co.multiply.tropical.shared`): \"Notify my tabs\" shows one in each, and
  \"Another topic\" posts one that the page it opens shows.

  The footer shows what each patch cost (`wire-stats`)."
  (:require
    [clojure.string :as str]
    [co.multiply.quiescent :as q]
    [co.multiply.tropical.action :as action :refer [use-action]]
    [co.multiply.tropical.cookie :as cookie :refer [defcookie]]
    [co.multiply.tropical.hooks :as hooks :refer [observe]]
    [co.multiply.tropical.island :as island :refer [defisland use-state use-watch]]
    [co.multiply.tropical.resource :as resource]
    [co.multiply.tropical.ring :as ring]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.script :as script :refer [defscript]]
    [co.multiply.tropical.session :as session]
    [co.multiply.tropical.shared :as shared :refer [defshared use-shared]]
    [co.multiply.tropical.storage :as storage :refer [defstorage]]
    [co.multiply.tropical.ui :as ui]
    [co.multiply.tropical.upload :as upload :refer [use-uploads]]
    [demo.bucket :as bucket]
    [demo.state :as state]
    [dev.onionpancakes.chassis.core :as h]))


(def topic-names (vec (sort (keys state/topics))))


(def ^:private card
  [:rounded-xl :border :border-zinc-200 :bg-white :p-5 :shadow-sm :space-y-4
   :dark:border-zinc-800 :dark:bg-zinc-900])


(def ^:private muted
  [:text-sm :text-zinc-500 :dark:text-zinc-400])


(def ^:private button
  [:rounded-md :border :border-zinc-300 :px-3 :py-1 :text-sm :font-medium :cursor-pointer
   :hover:bg-zinc-100 :dark:border-zinc-700 :dark:hover:bg-zinc-800])


(def ^:private link
  [:text-sm :font-medium :text-indigo-600 :hover:underline :dark:text-indigo-400])


(def ^:private button-active
  [:rounded-md :border :border-indigo-600 :bg-indigo-600 :px-3 :py-1 :text-sm :font-medium :text-white])


(defn- heading
  [title & body]
  [:div
   [:h2 {:class [:text-lg :font-semibold]} title]
   (into [:p {:class muted}] body)])


(defn- reading-label
  [v]
  (str "reading #" (:n v) " at " (:at v)))


(defn- reading-view
  "A feed's latest `reading` with its `label`, or that it's connecting while
  `reading` is pending."
  [reading label]
  (if (= hooks/pending reading)
    [:div {:class (conj muted :h-16 :animate-pulse)} "Connecting…"]
    [:div {:class [:h-16]}
     [:div {:class [:font-mono :text-4xl :tabular-nums]} reading]
     [:p {:class muted} label]]))


;; Header -----------------------------------------------------------------------


;; Toasts -----------------------------------------------------------------------


(defshared toasts
  "The toasts a user's tabs show, oldest first. Per user, so every tab of
  theirs shows them, and the page a navigation opens still has them."
  {:init []})


(def ^:private toast-ms 6000)


(defn- dismiss!
  [uid id]
  (shared/swap! toasts uid (fn [ts] (filterv #(not= id (:id %)) ts))))


(defn- notify!
  "Shows `text` in every tab of `uid`'s that shows toasts, for `toast-ms`, the
  last three at most. Nothing if none does."
  [uid text]
  (let [id (random-uuid)]
    (when (shared/swap! toasts uid #(vec (take-last 3 (conj % {:id id :text text}))))
      (q/compel (q/sleep toast-ms #(dismiss! uid id))))
    nil))


(defisland toast-list
  "The user's toasts, over the footer (`co.multiply.tropical.shared`)."
  [uid]
  (let [[ts] (use-shared toasts uid)]
    [:div {:class [:fixed :right-4 :bottom-14 :z-10 :w-72 :space-y-2]}
     (for [{:keys [id text]} ts]
       [:div {:class [:flex :items-start :justify-between :gap-3 :rounded-lg :border :border-zinc-200 :bg-white :p-3
                      :text-sm :shadow-md :dark:border-zinc-700 :dark:bg-zinc-800]
              :role  "status"}
        [:span text]
        [:button {:class         [:text-zinc-400 :cursor-pointer :hover:text-zinc-700 :dark:hover:text-zinc-200]
                  :aria-label    "Dismiss"
                  :data-on:click (use-action [:dismiss id] (fn [_] (dismiss! uid id)))}
         "×"]])]))


(defisland header
  [tab uid]
  [:header {:class [:space-y-1]}
   [:h1 {:class [:text-2xl :font-bold]} "Tropical"]
   [:p {:class muted}
    "Tab " [:code (subs tab 0 8)] " · user " [:code (subs uid 0 8)]
    " · Open this page in several tabs, switch topics, revoke access, and hide a tab for "
    (quot session/grace-ms 1000) " s."]
   [:p {:class [:flex :flex-wrap :items-center :gap-2]}
    [:button {:class         button
              :data-on:click (use-action :notify (fn [_] (notify! uid (str "From tab " (subs tab 0 8) "."))))}
     "Notify my tabs"]
    [:span {:class muted}
     "A toast in every tab of this user's, a state they share on the server, which a page load keeps."]]])


;; Shared feed ------------------------------------------------------------------


(defn- feed-of
  "`topic`'s feed: one call site of `observe` for every hook reading it, as an
  app's own hook over `observe` is."
  [topic]
  (observe topic state/feed))


(defn- use-reading
  "A hook: `topic`'s latest reading, or pending."
  [topic]
  (let [v (feed-of topic)]
    (if (= hooks/pending v) v (:reading v))))


(defn- use-reading-label
  "A hook: which of `topic`'s readings is the latest, and when it came."
  [topic]
  (let [v (feed-of topic)]
    (when-not (= hooks/pending v) (reading-label v))))


(defisland ticker
  "Renders each reading of its topic's feed, from two hooks that each read the
  feed, with the same args, in one render: they share its one subscription.
  Given another topic, it holds the new subscription and releases the old one,
  which lingers."
  [topic]
  (reading-view (use-reading topic) (use-reading-label topic)))


(defisland topic-summary
  "A one-off read: it runs when the topic changes, not when the ticker ticks.
  Tabs on the same topic share it, and its result lingers like a subscription:
  switching back within the linger shows it at once."
  [topic]
  (let [info (observe topic state/describe-topic)]
    [:p {:class muted}
     (if (= hooks/pending info)
       "Looking up the topic…"
       (str (:about info) ": a reading every " (:period-ms info) " ms. Looked up at " (:looked-up-at info) "."))]))


(defcookie summary-open
  "Whether a topic's page shows the topic's summary, per topic."
  {:default true :valid? boolean?})


(defisland feed-card
  []
  (let [[topic set-topic!] (use-state :topic (first topic-names))]
    [:section {:class card}
     (heading "Shared feed"
       "Tabs on the same topic share one subscription. Switching moves the ticker to "
       "another; the old one lingers " (quot resource/linger-ms 1000) " s, then closes.")
     [:div {:class [:flex :gap-2]}
      (for [t topic-names]
        [:button {:class         (if (= t topic) button-active button)
                  :data-on:click (use-action [:select t] (fn [_] (set-topic! t)))}
         t])]
     (ticker topic)
     (topic-summary topic)
     [:div {:class [:flex :flex-wrap :gap-x-4 :gap-y-1]}
      [:a {:class link :href (str "/topic/" topic)} "Open " topic " on a page of its own →"]
      ;; A choice made here for a page this one doesn't render, in the cookie
      ;; before that page loads, so its first frame has it.
      [:button {:class         (conj link :cursor-pointer)
                :data-on:click (use-action [:open-hidden topic]
                                 (fn [_]
                                   (action/navigate (str "/topic/" (state/path-name @state/!paths topic))
                                     {:signals (cookie/patch [[summary-open topic false]])})))}
       "…with its summary hidden →"]]]))


;; Restricted vault -------------------------------------------------------------


(defisland vault-feed
  []
  (let [v (observe state/vault)]
    (reading-view (cond-> v (not= hooks/pending v) :reading) (reading-label v))))


(defscript time-ago
  "`<time-ago at=\"…\">`: how long ago a time in epoch ms was, kept current in the browser."
  {:resource "demo/time_ago.js"})


(defisland message
  {:key :n}
  [{:keys [uid text at]}]
  (script/use time-ago)
  [:li [:code {:class muted} (subs uid 0 8)] " " text " " [:time-ago {:class muted :at at}]])


(defisland messages
  []
  (let [posted (use-watch state/!messages)]
    [:ul {:class [:space-y-1 :text-sm]}
     (if (empty? posted)
       [:li {:class muted} "No messages yet."]
       (map message (rseq posted)))]))


(def ^:private suggestions
  ["Has anyone seen the latest numbers?" "Back in five." "This vault could use a tidy." "Lunch, anyone?"])


(def ^:private suggest-ms 1500)


(defisland composer
  "Holds the input, and reads nothing that ticks, so typing is never morphed
  away. Suggest answers at once, and its suggestion lands in the input a
  moment later, from a background task, through the island's signals: a render
  in between, or after, leaves what the user has typed."
  [uid]
  (let [[suggesting set-suggesting!] (use-state :suggesting false)
        patch!  (island/use-patch-signals)
        post    (use-action :post (fn [signals]
                                    (when (state/post-message! uid (get signals "note"))
                                      {:note ""})))
        suggest (use-action :suggest (fn [_]
                                       (set-suggesting! true)
                                       (q/compel (q/sleep suggest-ms
                                                   #(do (patch! {:note (rand-nth suggestions)})
                                                      (set-suggesting! false))))
                                       nil))]
    [:div {:class [:flex :gap-2]}
     [:input {:data-bind       "note"
              :data-on:keydown (str "evt.key === 'Enter' && " post)
              :placeholder     "Message the other permitted viewers"
              :class           [:flex-1 :rounded-md :border :border-zinc-300 :bg-transparent :px-2 :py-1
                                :text-sm :dark:border-zinc-700]}]
     [:button {:class (conj button :disabled:opacity-50) :disabled suggesting :data-on:click suggest}
      (if suggesting "Suggesting…" "Suggest")]
     ;; Enabled while there is something to post, from the first paint.
     [:button (ui/attr {:class (conj button :disabled:opacity-50 :disabled:cursor-not-allowed) :data-on:click post}
                :disabled true "!$note.trim()")
      "Post"]]))


(defn- fold-label
  "A label that reads `on` while the state `open` is true, and `off` while not."
  [open on off]
  [:span {:data-text (str (ui/signal open) " ? '" on "' : '" off "'")} (if (cookie/value open) on off)])


(defcookie messages-open
  "Whether the vault's messages are shown."
  {:default true :valid? boolean?})


(defisland vault-panel
  "Its messages fold away, and stay as the browser keeps them: when the panel
  mounts again, after access is granted again, the session's copy of the state
  is still the one from its start."
  [uid]
  (let [open (cookie/use-cookie messages-open)]
    [:div {:class [:space-y-4]}
     (vault-feed)
     [:button (ui/aria-expanded {:class (conj button :text-xs) :data-on:click (ui/toggle open)} open)
      (fold-label open "Hide messages" "Show messages")]
     [:div (ui/shown {:class [:space-y-4]} open)
      (messages)
      (composer uid)]]))


(defisland no-access
  []
  [:p {:class (conj muted :h-16)} "No access. Nothing under the gate is rendered: no subscription, no actions."])


(defisland vault-card
  [uid]
  (let [allowed (use-watch state/!acl #(state/allowed? % uid :vault))
        toggle  (use-action :toggle (fn [_] (state/toggle! uid :vault) nil))]
    [:section {:class card}
     [:div {:class [:flex :items-start :justify-between :gap-4]}
      (heading "Restricted"
        "Gated on your ACL, which is server state; every tab of yours follows it. "
        "Revoking stops rendering the panel: its subscription is released and its actions stop existing.")
      [:button {:class button :data-on:click toggle}
       (if allowed "Revoke" "Grant")]]
     (if allowed
       (vault-panel uid)
       (no-access))]))


;; Server resources -------------------------------------------------------------


(def ^:private cell [:py-1 :pr-4])


(defn- call-site
  "`demo.page/observe@84:22#1234` as `page.clj:84`."
  [site]
  (let [[_ line] (re-find #"@(\d+):" (name site))]
    (str (last (str/split (namespace site) #"\.")) ".clj" (when line (str ":" line)))))


(defn- resource-label
  "A resource's key: an `observe`'s call site and args, as `page.clj:84 [\"alpha\"]`,
  or a shared state's instance, as `shared [demo.page/toasts \"…\"]`."
  [[site & more]]
  (if (keyword? site)
    (str (name site) " " (pr-str (vec more)))
    (str (call-site site) " " (pr-str (first more)))))


(defcookie resources-open
  "Whether the resources table is shown."
  {:default true :valid? boolean?})


(def ^:private tab-button
  [:rounded-md :px-3 :py-1 :text-sm :font-medium :cursor-pointer :text-zinc-500 :dark:text-zinc-400
   :aria-selected:bg-indigo-600 :aria-selected:text-white])


(defn- on-tab
  "The expression that is true while the resources card's tab `t` is selected."
  [t]
  (str "$_resourcesTab === '" t "'"))


(defn- resources-tab
  "A tab of the resources card's, switched in the browser, by a local signal:
  selected from the first paint, which shows the first tab, and as the browser
  has it when the card renders again, with every change to the registry."
  [t label]
  [:button (-> {:type "button" :role "tab" :class tab-button :data-on:click (str "$_resourcesTab = '" t "'")}
             (ui/attr :aria-selected (= t "registry") (on-tab t))
             (ui/attr :tabindex (if (= t "registry") 0 -1) (str (on-tab t) " ? 0 : -1")))
   label])


(defn- count-row
  [label n]
  (list [:dt {:class muted} label] [:dd {:class [:font-mono :text-sm]} n]))


(defn- resources-table
  [entries]
  [:table (ui/attr {:role "tabpanel" :class [:w-full :text-left :text-sm]}
            :hidden false (str "!(" (on-tab "registry") ")"))
   [:thead
    [:tr {:class muted}
     [:th {:class cell} "Resource"] [:th {:class cell} "State"] [:th {:class cell} "Subscribers"]]]
   [:tbody {:class [:font-mono]}
    (if (empty? entries)
      [:tr [:td {:class (conj cell :text-zinc-500) :colspan 3} "None open."]]
      (for [[k {:keys [state subscribers]}] entries]
        [:tr
         [:td {:class cell} (resource-label k)]
         [:td {:class (conj cell (case state
                                   :live :text-emerald-600
                                   :connecting :text-amber-600
                                   :lingering :text-zinc-400))}
          (name state)]
         [:td {:class cell} subscribers]]))]])


(defisland resources-card
  []
  (let [open     (cookie/use-cookie resources-open)
        entries  (use-watch resource/!entries resource/summary)
        totals   (use-watch resource/!totals)
        sessions (use-watch session/!sessions session/summary)
        actions  (use-watch action/!actions count)
        queries  (use-watch state/!queries)]
    (island/declare-signal ["_resourcesTab"] "registry")
    [:section {:class card}
     [:div {:class [:flex :items-start :justify-between :gap-4]}
      (heading "Server resources"
        "The resource registry, live, across all sessions, and what else the server holds. The card is patched with "
        "every change to them; the table and its tabs stay as the browser has them.")
      [:button (ui/aria-expanded {:class button :data-on:click (ui/toggle open)} open)
       (fold-label open "Hide" "Show")]]
     [:div (ui/shown {:class [:space-y-3]} open)
      [:div {:role "tablist" :class [:flex :gap-1]}
       (resources-tab "registry" "Registry")
       (resources-tab "counts" "Counts")]
      [:dl (ui/attr {:role "tabpanel" :class [:grid :grid-cols-2 :gap-x-4 :gap-y-1]}
             :hidden true (str "!(" (on-tab "counts") ")"))
       (count-row "Resources opened" (:opened totals))
       (count-row "Resources closed" (:closed totals))
       (count-row "Sessions attached" (:attached sessions))
       (count-row "Sessions detached (in grace)" (:detached sessions))
       (count-row "Live action tokens" actions)
       (count-row "One-off queries started" (:started queries))
       (count-row "One-off queries completed" (:completed queries))
       (count-row "One-off queries cancelled" (:cancelled queries))]
      (resources-table entries)]]))


;; Raw HTML ---------------------------------------------------------------------


(def ^:private glossary
  {"island" "A function of its arguments, rendered on the server and patched into the page on its own."
   "signal" "State Datastar keeps in the browser. Every action posts the page's signals, all but the local ones."
   "token"  "What an action is reached by. It exists while an island renders the action, and for its user only."})


(def ^:private passage
  "One string of HTML, as a document rendered elsewhere arrives: the island
  places it whole, and renders none of its elements itself."
  (let [term #(str "<dfn data-term=\"" % "\" class=\"cursor-pointer underline decoration-dotted\">" %2 "</dfn>")]
    (str "<p>Each " (term "island" "island") " renders on the server. A click posts the page's "
      (term "signal" "signals") " to an action, which it reaches by its " (term "token" "token") ".</p>")))


(def ^:private examples
  {"island" "The vault card is one, and the panel it places another."
   "signal" "The message you type is one, bound to its input."
   "token"  "Revoke access, and the token behind the vault's Post button stops existing."})


(defisland definition
  "The definition of `term`, keyed by it: another term places a new island,
  which starts over, its example hidden again, and is sent in the old one's
  place, without the card and its passage."
  {:key identity}
  [term]
  (let [[example? set-example!] (use-state :example? false)
        show (use-action :example (fn [_] (set-example! true) nil))]
    [:p {:class muted}
     [:dfn {:class [:font-semibold]} term] ": " (glossary term)
     (if example?
       (str " " (examples term))
       (list " " [:button {:class link :data-on:click show} "An example?"]))]))


(defisland glossary-card
  []
  (let [[term set-term!] (use-state :term nil)
        define (use-action :define
                 (fn [_ {picked "term"}]
                   ;; Untrusted: a term the passage has, or nothing.
                   (when (contains? glossary picked)
                     (set-term! picked))
                   nil)
                 {:value "{term: t.dataset.term}"})]
    [:section {:class card}
     (heading "Raw HTML"
       "One action serves every term in a block of HTML the island doesn't render element by element. "
       "A click passes the term to it as a value, not through a signal.")
     [:div {:data-on:click (str "const t = evt.target.closest('[data-term]'); t && " define)}
      (h/raw passage)]
     (if term
       (definition term)
       [:p {:class muted} "Click a term."])]))


;; Rocket -----------------------------------------------------------------------


(defscript star-rating
  "`<star-rating>`: the ratings, as a Rocket component. It imports Rocket from
  `datastar`, the bundle the page's import map names."
  {:resource "demo/star_rating.js"})


(defn- rating-props
  "`<star-rating>`'s props, from every user's `ratings`, for `uid`."
  [ratings uid]
  (let [votes (vals ratings)]
    {:average (if (seq votes) (/ (double (reduce + votes)) (count votes)) 0)
     :votes   (count votes)
     :mine    (get ratings uid 0)
     :counts  (ui/json (for [n (range 1 6)] (count (filter #{n} votes))))}))


(defisland rating-card
  "Ratings drawn by a Rocket component, `<star-rating>`, whose props the island
  renders from every user's ratings: a rating patches every tab's. The
  component keeps the star under the pointer, and whether its breakdown is
  open, in signals of its own, which the patch leaves alone. A click is its
  `rate` event, which the island takes as an action's value. The label is the
  island's, an action included, in the component's children."
  [uid]
  (let [ratings (use-watch state/!ratings)
        mine    (get ratings uid)
        rate    (use-action :rate
                  (fn [_ {stars "stars"}]
                    (state/rate! uid stars)
                    nil)
                  {:value "evt.detail"})
        clear   (use-action :clear (fn [_] (state/unrate! uid) nil))]
    (script/use star-rating)
    [:section {:class card}
     (heading "A Rocket component"
       "A web component made with Datastar's Rocket. The island renders its props and takes its events; it keeps "
       "what only the browser changes. Open the breakdown, then rate in another tab: the props change, and it stays "
       "open.")
     [:star-rating (assoc (rating-props ratings uid) :data-on:rate rate)
      [:span {:class muted}
       (if mine
         (list "You rated it " mine ". Click a star to change it, or "
           [:button {:type "button" :class (conj link :cursor-pointer) :data-on:click clear} "clear it"] ".")
         "Rate the demo.")]]]))


;; Drafts -----------------------------------------------------------------------


(def ^:private proposals
  "Suggestions the app made, for the user to rework before starting one."
  [{:id       "launch"
    :title    "Plan the launch"
    :sections ["Pick a date that suits the support team." "Write the announcement, and who sends it."]
    :chips    ["Check the status page first." "Tell the partners a day ahead."]}
   {:id       "retro"
    :title    "Hold a retrospective"
    :sections ["Collect what went well, and what didn't." "Pick one thing to change before the next one."]
    :chips    ["Keep it to thirty minutes." "Someone new leads it."]}])


(defstorage proposal-edits
  "A proposal as the user reworks it: its sections by position, a note, and
  which chips were picked, each of which added a line to the note. The
  browser keeps it until the proposal is started or dismissed."
  {:default {} :valid? map?})


(defn- proposal-default
  "What a proposal's draft is before the user changes it."
  [{:keys [sections chips]}]
  {"sections" (into {} (map-indexed (fn [i s] [(str i) s])) sections)
   "note"     ""
   "picked"   (into {} (map-indexed (fn [i _] [(str i) false])) chips)})


(defn- words
  "How many words a draft's sections and note hold. It came from the browser,
  so anything but text counts for nothing."
  [draft]
  (->> (concat (vals (get draft "sections")) [(get draft "note")])
    (filter string?)
    (mapcat #(re-seq #"\S+" %))
    count))


(defisland proposal-row
  "A proposal in the list, with how far the browser's draft of it has come:
  the server renders the count, so it reads the draft (`storage/observe`),
  which the browser sends again a moment after the user stops typing."
  {:key (fn [proposal _ _] (:id proposal))}
  [{:keys [id title] :as proposal} rework dismiss]
  (let [draft (storage/use-storage proposal-edits id {:default (proposal-default proposal)})
        v     (storage/observe draft)]
    [:li {:class [:flex :flex-wrap :items-center :gap-3]}
     [:span {:class [:flex-1 :font-medium]} title]
     [:span {:class muted}
      (cond
        (= storage/pending v) "…"
        (= v (proposal-default proposal)) "Not reworked yet"
        :else (str "A draft of " (words v) " words"))]
     [:button {:class button :data-on:click rework} "Rework"]
     [:button {:class button :data-on:click dismiss} "Dismiss"]]))


(def ^:private field
  [:w-full :rounded-md :border :border-zinc-300 :bg-transparent :px-2 :py-1 :text-sm :dark:border-zinc-700])


(def ^:private chip
  [:rounded-full :border :border-zinc-300 :px-3 :py-0.5 :text-xs :cursor-pointer :dark:border-zinc-700
   :aria-pressed:border-indigo-600 :aria-pressed:text-indigo-600 :disabled:cursor-default])


(defn- rework-dialog
  "The dialog reworking `proposal`: its fields bound to the browser's draft,
  `draft`. Picking a chip adds its line to the note, in the browser."
  [{:keys [title sections chips]} draft start start-there close]
  (let [expr (ui/signal draft)
        note (str expr ".note")]
    [:div {:role "dialog" :aria-label (str "Rework: " title)
           :class [:space-y-3 :rounded-lg :border :border-indigo-300 :p-4 :dark:border-indigo-800]}
     [:h3 {:class [:font-semibold]} title]
     (for [i (range (count sections))]
       [:label {:class [:block :space-y-1]}
        [:span {:class muted} "Section " (inc i)]
        [:textarea {:class field :rows 2 :data-bind (ui/path draft "sections" i)}]])
     [:label {:class [:block :space-y-1]}
      [:span {:class muted} "Note"]
      [:textarea {:class field :rows 3 :data-bind (ui/path draft "note")}]]
     [:div {:class [:flex :flex-wrap :gap-2]}
      (for [[i line] (map-indexed vector chips)]
        (let [picked (str expr ".picked[\"" i "\"]")]
          [:button (-> {:type          "button"
                        :class         chip
                        :data-on:click (str picked " || (" note " = (" note " ? " note " + '\\n' : '') + "
                                         (pr-str line) ", " picked " = true)")}
                     (ui/attr :aria-pressed false picked)
                     (ui/attr :disabled false picked))
           "+ " line]))]
     [:div {:class [:flex :gap-2]}
      [:button {:class button-active :data-on:click start} "Start"]
      [:button {:class button :data-on:click start-there} "Start, then open " (first topic-names)]
      [:button {:class button :data-on:click close} "Close"]]]))


(defisland drafts-card
  "Proposals to rework in a dialog, whose drafts the browser keeps
  (`co.multiply.tropical.storage`): closing the dialog, a reload or a server
  restart leaves them. Start takes the draft as its action's value, and drops
  it; Dismiss drops it too, whether or not the dialog is open. \"Start, then
  open\" opens another page, and drops the draft before that page loads
  (`action/navigate`'s `:signals`), which tells of it in a toast."
  [uid]
  (let [[open set-open!] (use-state :open nil)
        [gone set-gone!] (use-state :gone #{})
        [started set-started!] (use-state :started [])
        shown    (remove (comp gone :id) proposals)
        proposal (some #(when (= open (:id %)) %) shown)
        draft    (when proposal
                   (storage/use-storage proposal-edits open {:default (proposal-default proposal)}))
        start    (when proposal
                   (use-action [:start open]
                     (fn [_ value]
                       ;; Untrusted: the browser's draft, as the browser sent it.
                       (when-some [v (storage/decode proposal-edits value)]
                         (set-started! (conj started (str "Started “" (:title proposal) "”, " (words v) " words.")))
                         (set-gone! (conj gone open))
                         (set-open! nil)
                         (storage/forget [[proposal-edits open]])))
                     {:value (ui/signal draft)}))
        there    (when proposal
                   (use-action [:start-there open]
                     (fn [_ value]
                       (when-some [v (storage/decode proposal-edits value)]
                         (notify! uid (str "Started “" (:title proposal) "”, " (words v) " words."))
                         (action/navigate (str "/topic/" (state/path-name @state/!paths (first topic-names)))
                           {:signals (storage/forget [[proposal-edits open]])})))
                     {:value (ui/signal draft)}))
        close    (use-action :close (fn [_] (set-open! nil) nil))
        again    (use-action :again (fn [_] (set-gone! #{}) nil))]
    [:section {:class card}
     (heading "Drafts the browser keeps"
       "Rework a proposal before starting it. Your edits stay in this browser's storage: close the dialog, reload, "
       "or restart the server, and they're still there. The server keeps none of them. It reads a draft to count "
       "its words, and takes it whole when you start.")
     (if (seq shown)
       [:ul {:class [:space-y-2 :text-sm]}
        (for [{:keys [id] :as p} shown]
          (proposal-row p
            (use-action [:rework id] (fn [_] (set-open! id) nil))
            (use-action [:dismiss id]
              (fn [_]
                (set-gone! (conj gone id))
                (when (= open id) (set-open! nil))
                (storage/forget [[proposal-edits id]])))))]
       [:p {:class muted} "No proposals left. " [:button {:class link :data-on:click again} "Bring them back"]])
     (when proposal
       (rework-dialog proposal draft start there close))
     (when (seq started)
       [:ul {:class (conj muted :space-y-1)}
        (for [line started] [:li line])])]))


;; Uploads ----------------------------------------------------------------------


(def ^:private max-files 5)


(def ^:private max-bytes (* 200 1000 1000))


(defn- size-label
  [n]
  (cond
    (< n 1000) (str n " B")
    (< n 1000000) (str (Math/round (/ n 1000.0)) " kB")
    :else (str (/ (Math/round (/ n 100000.0)) 10.0) " MB")))


(defn- to-bucket
  "`use-uploads`' options sending files to the bucket, at most `max-files` at a
  time, and taking a pick in whole into `!batches`, the last three."
  [!batches]
  {:target  (fn [{:keys [id size]} uploads]
              (cond
                (<= max-files (count (remove (comp #{:refused :failed} :state) uploads)))
                {:refused (str "At most " max-files " files at a time.")}

                (< max-bytes size)
                {:refused "Larger than 200 MB."}

                :else
                (let [key (str "uploads/" id)]
                  {:ref key :url (bucket/presign key)})))
   ;; What the browser says arrived, checked against the bucket.
   :arrived (fn [{:keys [ref size]}]
              (when-not (= size (:size (bucket/object ref)))
                {:refused "It didn't arrive whole."}))
   ;; Once none is on its way: the pick, taken in whole, as work over all of a
   ;; project's files would want it.
   :settled (fn [arrived]
              (swap! !batches #(vec (take-last 3 (conj % (mapv :name arrived))))))})


(defn- drop-overlay
  "What a drop area shows while files are dragged over it: `cover`'s classes
  place it over the area."
  [start cover text]
  [:div (ui/attr {:class (into [:pointer-events-none :z-40 :flex :items-center :justify-center :rounded-lg :border-2
                                :border-dashed :border-indigo-500 "bg-indigo-50/90" :text-sm :font-semibold
                                :text-indigo-700 "dark:bg-indigo-950/90" :dark:text-indigo-200]
                           cover)}
          :hidden true (str "!" (upload/dropping start)))
   text])


(defisland uploads-card
  []
  (let [!batches (island/use-hold :batches (fn [] {:value (atom [])}))
        batches  (use-watch !batches)
        {:keys [uploads start remove!]} (use-uploads :files (to-bucket !batches))]
    ;; A drop area inside the page's: files dropped here are this card's.
    [:section (upload/drop-area {:class (conj card :relative)} start)
     (drop-overlay start [:absolute :inset-0] "Drop to add them to this card")
     (heading "Uploads"
       "Files go from the browser straight to a bucket, at a URL the island presigned, and never through the "
       "server. Pick some, or drop them on this card: once none is on its way, those that arrived are taken in "
       "together. Dropped anywhere else on the page, they go to the page's own list.")
     [:input {:type           "file"
              :multiple       true
              :class          (conj muted :file:mr-3 :file:rounded-md :file:border :file:border-zinc-300 :file:px-3
                                :file:py-1 :file:cursor-pointer)
              :data-on:change (str (start "evt.target.files") "; evt.target.value = ''")}]
     (when (seq uploads)
       [:ul {:class [:space-y-2 :text-sm]}
        (for [{:keys [id name size state reason]} uploads]
          [:li
           [:div {:class [:flex :items-center :gap-3]}
            [:span {:class [:min-w-0 :flex-1 :truncate]} name]
            [:span {:class muted} (size-label size)]
            (case state
              :uploading [:div {:class [:h-2 :w-24 :overflow-hidden :rounded :bg-zinc-200 :dark:bg-zinc-800]}
                          [:div {:class            [:h-full :bg-indigo-600]
                                 :data-style:width (str "(100 * " (upload/progress id) ") + '%'")}]]
              :arrived [:span {:class [:w-24 :text-emerald-600]} "Arrived"]
              [:span {:class [:w-24 :text-red-600]} (if (= :refused state) "Refused" "Failed")])
            [:button {:class         [:text-zinc-400 :hover:text-zinc-700 :cursor-pointer]
                      :aria-label    (str "Remove " name)
                      :data-on:click (use-action [:remove id] (fn [_] (remove! id)))}
             "✕"]]
           (when reason
             [:p {:class [:text-xs :text-red-600]} reason])])])
     (if (seq batches)
       [:ul {:class [:space-y-1 :text-sm]}
        (for [names (rseq batches)]
          [:li "Took in " (when (next names) (str (count names) " together: ")) (str/join ", " names)])]
       [:p {:class muted} "Nothing taken in yet."])]))


;; Static bulk ------------------------------------------------------------------


(defisland bulk
  []
  [:section {:class card}
   [:details
    [:summary {:class [:cursor-pointer]}
     [:span {:class [:text-lg :font-semibold]} "Static bulk"]
     [:span {:class muted} " · 300 rows, rendered once per session: part of every full resync, never of a patch."]]
    [:table {:class [:mt-3 :w-full :font-mono :text-xs]}
     [:tbody
      (for [i (range 300)]
        [:tr [:td i] [:td (format "%08x" (hash i))] [:td (format "%08x" (hash (str i)))]])]]]])


;; Root and shell ---------------------------------------------------------------


(defisland app
  "The page, a drop area as a whole: files dropped anywhere on it but on the
  uploads card go to a list of the page's own."
  [tab uid]
  (let [!batches (island/use-hold :batches (fn [] {:value (atom [])}))
        batches  (use-watch !batches)
        {:keys [uploads start]} (use-uploads :page (to-bucket !batches))]
    [:div (upload/drop-area {:class [:min-h-screen]} start)
     (drop-overlay start [:fixed :inset-2] "Drop anywhere to add them to the page")
     [:main {:class [:mx-auto :max-w-4xl :space-y-6 :p-6 :pb-24]}
      (header tab uid)
      [:div {:class [:grid :gap-6 :md:grid-cols-2]}
       (feed-card)
       (vault-card uid)]
      (resources-card)
      (glossary-card)
      (rating-card uid)
      (drafts-card uid)
      (uploads-card)
      (when (or (seq uploads) (seq batches))
        [:section {:class card}
         (heading "Dropped on the page" "Files dropped anywhere but on the uploads card, sent the same way.")
         [:ul {:class [:space-y-1 :text-sm]}
          (for [upload uploads]
            [:li (:name upload) " · " (name (:state upload)) (some->> (:reason upload) (str " · "))])
          (for [names (rseq batches)]
            [:li "Took in " (str/join ", " names)])]])
      (bulk)
      (toast-list uid)]]))


(defisland topic-app
  "A topic's page. The same `ticker` and `topic-summary` as on `/`, so the
  same call sites and args: arriving from `/` within the linger, they show
  their resources at once. The route has checked that `topic` exists. `at` is
  the name in the page's URL: once the topic's page moves to another, the
  page follows it."
  [uid topic at]
  (let [paths (use-watch state/!paths)
        here  (state/path-name paths topic)
        open  (cookie/use-cookie summary-open topic)]
    [:main {:class [:mx-auto :max-w-4xl :space-y-6 :p-6 :pb-24]}
     (when (not= here at)
       ;; Every tab on the topic goes to its new URL, in place of this one.
       (action/navigate-element (str "/topic/" here) {:replace true}))
     [:header {:class [:space-y-1]}
      [:h1 {:class [:text-2xl :font-bold]} "Topic " topic]
      [:p {:class muted}
       "User " [:code (subs uid 0 8)] " · A page of its own, with its root from the route. Each link is a page load "
       "and a new session; the feed's subscription lingers " (quot resource/linger-ms 1000) " s, so a page within "
       "that picks it up without reconnecting. After a server restart, the stream rebuilds this page from its URL. "
       "Move it from the REPL, " [:code "(demo.state/move! \"" topic "\" \"" topic "-2\")"] ", and its tabs follow."]]
     [:nav {:class [:flex :flex-wrap :items-center :gap-2]}
      [:a {:class button :href "/"} "← All cards"]
      (for [t topic-names]
        [:a {:class (if (= t topic) button-active button) :href (str "/topic/" (state/path-name paths t))} t])
      [:button {:class         button
                :data-on:click (use-action :elsewhere
                                 (fn [_]
                                   (let [t (rand-nth (remove #{topic} topic-names))]
                                     ;; Posted before the page loads, and shown on the one it opens.
                                     (notify! uid (str "Picked " t " for you."))
                                     (action/navigate (str "/topic/" (state/path-name @state/!paths t))))))}
       "Another topic"]]
     [:section {:class (conj card :space-y-4)}
      (ticker topic)
      [:div {:class [:flex :flex-wrap :items-center :gap-2]}
       [:button (ui/aria-expanded {:class (conj button :text-xs) :data-on:click (ui/toggle open)} open)
        (fold-label open "Hide the summary" "Show the summary")]
       [:span {:class muted} "On every topic's page:"]
       ;; Signals for pages this one doesn't render too: the page's body keeps
       ;; them in the cookie.
       (for [shown [false true]]
         [:button {:class         (conj button :text-xs)
                   :data-on:click (use-action [:every shown]
                                    (fn [_] (cookie/patch (for [t topic-names] [summary-open t shown]))))}
          (if shown "Show" "Hide")])]
      [:div (ui/shown open)
       (topic-summary topic)]]
     (resources-card)
     (toast-list uid)]))


(defn not-found
  "The page for a topic that doesn't exist."
  [topic]
  (h/html
    [h/doctype-html5
     [:html {:lang "en"}
      [:head [:meta {:charset "utf-8"}] [:title "No such topic"]]
      [:body
       [:p "No topic named " [:code topic] ". " [:a {:href "/"} "All cards"]]]]]))


(defn error-view
  "What renders in place of an island that failed: the default view, plus the
  exception's message. A page for others shouldn't show it; this is a demo."
  [{:keys [error] :as failure}]
  (conj (island/error-view failure) " " (or (ex-message error) (str (class error)))))


(defn root
  "The root island of `/`."
  [{:keys [tab uid]}]
  (state/ensure-user! uid)
  (app tab uid))


(defn topic-root
  "The root island of `/topic/<topic>`: the topic the route found at its URL."
  [{:keys [uid request]}]
  (state/ensure-user! uid)
  (topic-app uid (:demo/topic request) (get-in request [:path-params :topic])))


(def ^:private wire-signals
  "{_wire: {frames: 0, total: 0, last: 0, page: 0, patched: '', renders: ''}}")


(defn- wire-footer
  []
  [:footer {:class [:fixed :inset-x-0 :bottom-0 :border-t :border-zinc-200 "bg-white/90" :px-6 :py-2 :font-mono
                    :text-xs :backdrop-blur :dark:border-zinc-800 "dark:bg-zinc-950/90"]}
   [:div {:class [:mx-auto :max-w-4xl :space-y-1]}
    [:div
     "frames " [:span {:data-text "$_wire.frames"}]
     " · last frame " [:span {:data-text "$_wire.last"}] " chars"
     " · full page " [:span {:data-text "$_wire.page"}] " chars"
     " · total sent " [:span {:data-text "$_wire.total"}] " chars"
     " · patched: " [:span {:data-text "$_wire.patched"}]]
    [:div {:class muted} "renders: " [:span {:data-text "$_wire.renders"}]]]])


(defn- last-slot
  "The last slot of the island id `id`: short enough for the footer."
  [id]
  (subs id (inc (or (str/last-index-of id "/") -1))))


(defn wire-stats
  "The footer's signals, sent with each patch: the app's `:patch-signals`."
  [{:keys [runtime frame patched chars frames total]}]
  {:_wire {:frames  frames
           :total   total
           :last    chars
           :page    (island/frame-chars frame)
           :patched (str/join ", " (map :id patched))
           :renders (->> (runtime/render-counts runtime)
                      (map (fn [[id n]] (str (last-slot id) " " n)))
                      (str/join " · "))}})


(def ^:private datastar-url
  ;; The client tropical is tested against, with Rocket: the bundle holds both.
  ;; CSP mode needs 1.0.3 or later.
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar-rocket.js")


(def ^:private import-map
  ;; Modules import Datastar, and Rocket, as `datastar`: the page's own copy, as
  ;; a second would be a second engine.
  (ui/json {:imports {:datastar datastar-url}}))


(defn shell
  "The page, server-side rendered from the session's first frame, which the
  body holds (`ring/mount`). Its islands are patched over the stream from then
  on; the shell itself never is. `nonce` is the CSP nonce: its scripts carry
  it, and Datastar compiles expressions with it (`data-nonce`, CSP mode)
  instead of with `Function()`."
  [nonce]
  (h/html
    [h/doctype-html5
     [:html {:lang "en" :data-nonce nonce}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:title "Tropical"]
       [:script {:nonce nonce :src "https://unpkg.com/@tailwindcss/browser@4"}]
       ;; Before the first module.
       [:script {:nonce nonce :type "importmap"} (h/raw import-map)]
       [:script {:nonce nonce :type "module" :src datastar-url}]
       ;; The scripts the first frame's islands declared.
       (ring/scripts nonce)]
      ;; The first frame, the stream, and the state the browser owns, in its
      ;; cookie and its storage. The footer is outside the islands.
      (ring/mount :body {:class        [:bg-zinc-50 :text-zinc-900 :dark:bg-zinc-950 :dark:text-zinc-100]
                         :data-signals wire-signals}
        (wire-footer))]]))
