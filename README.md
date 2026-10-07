# Tropical

[![Clojars Project](https://img.shields.io/clojars/v/co.multiply/tropical.svg)](https://clojars.org/co.multiply/tropical)
[![cljdoc](https://cljdoc.org/badge/co.multiply/tropical)](https://cljdoc.org/d/co.multiply/tropical)

Server-rendered islands for [Datastar](https://data-star.dev). An island is a function returning hiccup, defined with
`defisland`. Each browser tab has a session on the server that renders its islands, and only what changed is rendered
and sent: an island renders again when its arguments change, or when something it read changed, and Datastar morphs
each changed island into the page by id, over one SSE stream per tab.

Two guarantees come with the rendering:

- **Resources live exactly as long as the view that uses them.** A subscription, a poll loop or a query belongs to the
  islands that ask for it, and is released when the last of them stops asking. Readers of the same call site and
  arguments share one, across sessions, and it lingers briefly after its last reader leaves.
- **Access control is scope-based.** A subtree the user may not see is never rendered: its markup is never produced,
  its subscriptions are never opened, and its actions never exist.

```clojure
co.multiply/tropical {:mvn/version "0.1.0"}
```

Requires Clojure 1.12+ and JDK 25+. The wire follows Datastar's SDK specification, tested against the Datastar 1.0.4
client.

## An example

```clojure
(ns my.app
  (:require
    [co.multiply.tropical.action :refer [use-action]]
    [co.multiply.tropical.island :refer [defisland use-watch]]
    [co.multiply.tropical.ring :as ring]
    [dev.onionpancakes.chassis.core :as h]))


(defonce !clicks (atom 0))


(defisland counter
  []
  (let [n     (use-watch !clicks)
        click (use-action :click (fn [_] (swap! !clicks inc) nil))]
    [:p "Clicked " n " times " [:button {:data-on:click click} "+1"]]))


(defisland app
  [uid]
  [:main [:h1 "Hello, " uid] (counter)])


(defn page
  "The page, rendered from the session's first frame, which the body holds
  (`ring/mount`). Only its islands are patched from then on."
  [_]
  (h/html
    [h/doctype-html5
     [:html
      [:head [:script {:type "module"
                       :src  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js"}]]
      (ring/mount :body)]]))


(def tropical
  {:root (fn [{:keys [uid]}] (app uid))
   :page page
   ;; Your authentication: the user a request is made on behalf of, or nil.
   :uid  current-user})


(def routes
  ;; The page, the stream it opens and its actions, all at the page's URL.
  [["/" {:handler (ring/handler tropical)}]])
```

Every tab that shows the counter sees each click: `counter` read `!clicks`, so each session renders `counter` again and
sends that one island.

## The model

- **Everything renders on the server.** There is no client component model and no client-side Clojure: the browser
  holds HTML, Datastar's signals, and the scripts the app chose to load. What changes without a round trip is a
  Datastar expression in an attribute.
- **Islands are functions of their arguments.** Calling one places it; the runtime renders it when it is new, when its
  arguments changed, or when something it read through a hook changed, and reuses its last output otherwise. Hooks
  are keyed by name, not by call order. ([Islands](doc/islands.md))
- **Reads from outside the page are shared resources.** `observe` opens a subscription, a poll loop or a query, once
  for every reader of its call site and arguments, across sessions, and closes it after the last leaves.
  ([Reading from outside the page](doc/reading.md))
- **Writes are actions.** An island renders an action into the page as a token bound to the user, revoked when the
  island stops rendering it. Its handler changes state, and the islands reading that state arrive over the stream.
  ([Actions](doc/actions.md))
- **One session per tab, not per connection.** A tab's session renders its islands on a thread of its own, and
  outlives its stream for a grace period, so a reconnect resyncs from what it has. Changes coalesce, so a slow client
  gets fewer frames, never a backlog. ([How it works](doc/how-it-works.md))

## Mounting it

- **One handler per page, at the page's route**, for GET and POST: the page, the stream it opens and its actions are
  requests to the page's own URL, so the route's middleware covers all three. An app of many pages shares one app
  map, and gives each route its `:root`.
- **Authentication is the app's.** `:uid` returns the user a request is made on behalf of, or nil.
- **The page mounts the app with `(ring/mount :body attrs)`**: the element holds the session's first frame, opens the
  stream, and keeps the state the browser owns. Its head takes the islands' scripts with `(ring/scripts nonce)`.
- **Wrap the app in `ring/wrap-refused-streams` and `ring/wrap-scripts`**, outside the routes, and let actions past body
  parsers (`ring/request-kind`).

[Serving pages](doc/serving.md) has the whole app map, the CSP, compression, and running it behind a load balancer.

## Guides

- [Islands](doc/islands.md): writing them, their hooks, and when to make one.
- [Reading from outside the page](doc/reading.md): `observe`, and how a resource lives.
- [Actions](doc/actions.md): writes, navigation, values from the browser, work that outlives its action.
- [State the browser owns](doc/browser-state.md): cookie and storage states, and the `ui` helpers.
- [State shared on the server](doc/shared-state.md): state across sessions, held while read.
- [Scripts the page needs](doc/scripts.md): ES modules, loaded once, for the islands that need them.
- [Files from the browser](doc/uploads.md): uploads straight to the app's storage, and drop areas.
- [Testing islands](doc/testing.md): rendering islands and invoking their actions in a test.
- [Serving pages](doc/serving.md): the app map, the page, compression, running it.
- [Security](doc/security.md): what Tropical decides for an app, and what stays the app's.
- [How it works](doc/how-it-works.md): frames, signals and sessions.
- [The design notes](doc/design.md): why it is built this way, and the alternatives rejected.

## Editors and agents

The jar carries a [clj-kondo](https://github.com/clj-kondo/clj-kondo) config that lints `defisland` as `defn`
and `defcookie`, `defstorage`, `defscript` and `defshared` as `def`, so editors resolve islands, their params and
states. Import it into your project's `.clj-kondo` with
`clj-kondo --lint "$(clojure -Spath)" --dependencies --copy-configs --skip-lint`.

The repository is also a [Claude Code](https://claude.com/claude-code) plugin, whose `tropical` skill gives Claude
what it can't infer from the API when it writes islands: what renders when, what a resource is shared by, what the
browser is trusted with, and the mistakes a guess from React or Electric makes. It loads only when the conversation
is working with Tropical. From Claude Code:

```
/plugin marketplace add multiplyco/tropical
/plugin install tropical@tropical
```

That installs it for yourself. To offer it to everyone working in a project, commit this to the project's
`.claude/settings.json` instead; collaborators are asked to install it when they trust the repository. Both keys are
needed, since `enabledPlugins` alone names a marketplace a fresh clone has never heard of.

```json
{
  "extraKnownMarketplaces": {
    "tropical": {
      "source": { "source": "github", "repo": "multiplyco/tropical" }
    }
  },
  "enabledPlugins": {
    "tropical@tropical": true
  }
}
```

## Development

`bb dev` serves a demo page that walks through what the library does, and `bb test` runs the tests:
[Development](doc/development.md) has the tasks, the demo and how the native libraries are built. Dated measurements
and findings are in [the logbook](doc/logbook.md).

## License

Eclipse Public License 2.0. Copyright (c) 2026 Multiply. See [LICENSE](LICENSE).

The jar includes libzstd, built from [Zstandard](https://github.com/facebook/zstd)'s release source
(`native/build-zstd.sh`), under its BSD license: see
[zstd-LICENSE](resources/co/multiply/tropical/native/zstd-LICENSE). And it includes libbrotlienc, built from
[Brotli](https://github.com/google/brotli)'s release source (`native/build-brotli.sh`), under its MIT license: see
[brotli-LICENSE](resources/co/multiply/tropical/native/brotli-LICENSE).

Authored by [@kauppilainen](https://github.com/kauppilainen) and [@eneroth](https://github.com/eneroth)
