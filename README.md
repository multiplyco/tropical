# Tropical

[![Clojars Project](https://img.shields.io/clojars/v/co.multiply/tropical.svg)](https://clojars.org/co.multiply/tropical)
[![cljdoc](https://cljdoc.org/badge/co.multiply/tropical)](https://cljdoc.org/d/co.multiply/tropical)

Server-rendered islands for [Datastar](https://data-star.dev). An island is a function returning hiccup, defined with
`defisland`. Each browser tab has a session on the server, which renders the tab's islands again when something they
read changes, and sends them over one SSE stream for Datastar to morph into the page.

Three guarantees come with the rendering:

- **Resources live exactly as long as the view that uses them.** A subscription, a poll loop or a query belongs to the
  islands that ask for it, and is released when the last of them stops asking. Readers of the same call site and
  arguments share one, across sessions, and it lingers briefly after its last reader leaves.
- **Access control is scope-based.** A subtree the user may not see is never rendered: its markup is never produced,
  its subscriptions are never opened, and its actions never exist.
- **A failure stays in its island.** A render that throws, an `assert` included, renders an error view in the island's
  place. Its parent never sees the exception, and the rest of the page goes on. The default view shows only a
  reference, which the exception is logged under, so nothing the exception says reaches the browser.

```clojure
co.multiply/tropical {:mvn/version "0.9.0"}
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

Every tab that shows the counter sees each click: `counter` read `!clicks`, so each session renders `counter` again,
without rendering `app`, and sends that one island.

## The model

- **Everything renders on the server.** There is no client component model and no client-side Clojure: the browser
  holds HTML, Datastar's signals, and the scripts the app chose to load. What changes without a round trip is a
  Datastar expression in an attribute.
- **Islands are functions of their arguments.** Calling one places it. The runtime renders it when it is new, when its
  arguments changed, or when something it read through a hook changed, and otherwise reuses its last output. Hooks
  are keyed by name, not by call order. ([Islands](doc/islands.md))
- **Reads from outside the page are shared resources.** `observe` opens a subscription, a poll loop or a query once for
  every reader of its call site and arguments, across sessions, and closes it after the last leaves. ([Reading from
  outside the page](doc/reading.md))
- **Writes are actions.** An island renders an action into the page as a token bound to the user, revoked when the
  island stops rendering it. The handler changes state, and the islands that read that state render again and go out
  over the stream. ([Actions](doc/actions.md))
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
- **Wrap the app in `ring/wrap-refused-streams` and `ring/wrap-scripts`**, outside the routes. The first reloads a page
  whose stream the route's middleware refused, as when its login expired, and the second serves the scripts islands
  declare. Body parsers let actions past (`ring/request-kind`).

[Serving pages](doc/serving.md) has the whole app map, the CSP, compression, and running it behind a load balancer.

## Guides

Writing an app:

- [Islands](doc/islands.md): writing them, their hooks, failures, and when to make one.
- [Reading from outside the page](doc/reading.md): `observe`, and how a resource lives.
- [Actions](doc/actions.md): writes, navigation, values from the browser, work that outlives its action.
- [State the browser owns](doc/browser-state.md): cookie and storage states, and the `ui` helpers.
- [State shared on the server](doc/shared-state.md): state across sessions, held while read.
- [Scripts the page needs](doc/scripts.md): ES modules, loaded once, for the islands that need them.
- [Components with Rocket](doc/rocket.md): web components made with Datastar's Rocket, their props, events and state.
- [Files from the browser](doc/uploads.md): uploads straight to the app's storage, and drop areas.
- [Bidirectional channels](doc/channels.md): an island-owned side channel, with ordered text and binary messages.
- [Testing islands](doc/testing.md): rendering islands and invoking their actions in a test.

Running it:

- [Serving pages](doc/serving.md): the app map, the page, error views, compression, running it.
- [Security](doc/security.md): what Tropical decides for an app, and what stays the app's.
- [How it works](doc/how-it-works.md): frames, signals and sessions.

## Editors and agents

The jar carries a [clj-kondo](https://github.com/clj-kondo/clj-kondo) config that lints `defisland` as `defn`
and `defcookie`, `defstorage`, `defscript` and `defshared` as `def`, so editors resolve islands, their params and
states. Import it into your project's `.clj-kondo` with
`clj-kondo --lint "$(clojure -Spath)" --dependencies --copy-configs --skip-lint`.

The repository also carries a skill for coding agents, `tropical`, in the open [Agent Skills](https://agentskills.io)
format. It gives an agent what it can't infer from the API when it writes islands: what renders when, what a resource
is shared by, what the browser is trusted with, and the mistakes a guess from React or Electric makes. An agent loads
it only when the work involves Tropical.

For Codex, Cursor, Gemini CLI, GitHub Copilot and other agents that read skills, the
[skills](https://github.com/vercel-labs/skills) CLI installs it in the project, or with `-g`, for yourself:

```
npx skills add multiplyco/tropical
```

Or copy its directory, `plugins/tropical/skills/tropical`, to the project's `.agents/skills/`, where Codex and several
other agents look for skills.

For [Claude Code](https://claude.com/claude-code), the repository is a plugin marketplace:

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
      "source": {
        "source": "github",
        "repo": "multiplyco/tropical"
      }
    }
  },
  "enabledPlugins": {
    "tropical@tropical": true
  }
}
```

## Development

`bb dev` serves a demo page that walks through what the library does, and `bb test` runs the tests:
[Development](doc/development.md) has the tasks, the demo and how the native libraries are built. For working on
Tropical itself, [the design notes](doc/design.md) record why it is built this way and the alternatives rejected, and
[the logbook](doc/logbook/README.md) has dated measurements and findings.

## License

Eclipse Public License 2.0. Copyright (c) 2026 Multiply. See [LICENSE](LICENSE).

The jar includes libzstd, built from [Zstandard](https://github.com/facebook/zstd)'s release source
(`native/build-zstd.sh`), under its BSD license: see [zstd-LICENSE](resources/co/multiply/tropical/native/zstd-LICENSE).
And it includes libbrotlienc, built from [Brotli](https://github.com/google/brotli)'s release source
(`native/build-brotli.sh`), under its MIT license: see
[brotli-LICENSE](resources/co/multiply/tropical/native/brotli-LICENSE).

Authored by [@kauppilainen](https://github.com/kauppilainen) and [@eneroth](https://github.com/eneroth)
