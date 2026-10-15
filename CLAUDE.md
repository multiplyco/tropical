# Tropical — working notes for Claude

## Layout

The library is `src/co/multiply/tropical`. The demo page that `bb dev` serves is `dev/demo`, on the `:dev` alias only,
with `dev/user.clj`. Keep them apart: nothing in `src` may require a `demo` namespace or know about the page, and the
demo uses only the library's public API. What the demo needs from a session goes through the app map (`:root`,
`:uid`, `:page`, `:patch-signals`, `:error-view`, `:first-frame-ms`), not through demo-specific code in the library.
Tests are of the library, and don't use the demo either.

The Java sources (`co.multiply.tropical.signal`, `co.multiply.tropical.sse`, `co.multiply.tropical.frame`) sit next to
the Clojure ones in `src` and compile into `target/classes`, which is on `:paths`. After changing one, run
`bb compile:java` and restart the JVM; `bb dev` and `bb test` compile first. The jar ships the classes, not the `.java`
files.

The jar also ships libzstd and libbrotlienc, for the zstd and brotli encodings (`co.multiply.tropical.sse.ZstdEncoder`,
`BrotliEncoder`), in `resources/co/multiply/tropical/native`. `bb native:zstd` and `bb native:brotli` build them from
their release source (`native/build-zstd.sh`, `native/build-brotli.sh`, on macOS, with Docker for Linux). To take up a
new release, check its signature (brotli signs none: check that the archive is the release commit), change the version
and checksum in the script, rebuild, and log the build.

Benchmarks are in `dev/bench` (`bench.signal`, `bench.frame`, `bench.sse`), run from the REPL under `bb dev`.

The wire is Datastar's SDK specification (`sdk/ADR.md` in the Datastar repository), implemented in-house in `sse` and
`ring`: the client and its spec are the contract, and no Datastar server SDK is a dependency.

## Where things are written down

- `README.md`: what the library is, an example, the model, and the index of the guides. Keep it short: the detail
  goes in a guide.
- `doc/*.md` other than the two below: the guides, one per subject (`islands`, `reading`, `actions`, `browser-state`,
  `shared-state`, `scripts`, `rocket`, `uploads`, `testing`, `serving`, `security`, `how-it-works`, `development`). A
  change to what an app writes goes in its guide, and one to what it trusts, in `security` too. `doc/cljdoc.edn` lists
  the pages cljdoc publishes, in order: add a new guide there and to the README's index. `rocket` follows the demo's
  `<star-rating>`: check both against a new Datastar release.
- `doc/design.md`: why it is built this way, and the alternatives rejected. Fold settled decisions in here.
- `doc/logbook.md`: dated records (measurements with how they were taken, smoke-test findings, things that looked like
  bugs and weren't), oldest first. Append; don't rewrite history.
- `plans/`, the maintainers' own and not in the repository: `backlog.md`, open questions and parked ideas, and
  `app-requirements.md`, what a first app asked of the library. Where it exists, read the backlog before changing the
  API or the resource lifecycle.

Tropical was extracted from a proof of concept, which went from a timer-driven full re-render, through a Missionary DAG
of islands, to the immediate islands it has now.

## Style notes

Prefer method values for Java interop. `(Class/.method …)` over `(.method ^Class …)`. Imports are vectors:
`[java.io File]`. Formatting is cljstyle's (`bb format:check`, `bb format:fix`). Linting is clj-kondo's (`bb lint`),
under the config the jar exports to apps, in `resources/clj-kondo.exports`.

Threads come from Quiescent (`q/task`, `q/compel`), not `future` or raw `Thread`s.

## Cutting a release

The version lives in **five places**, kept in sync by hand; `bb version:check` is the gate (and `bb deploy` runs it
first):

- `build.clj` — `(def version "…")`, the source of truth the check reads
- `README.md` — the `:mvn/version` coordinate
- `CHANGELOG.md` — a `## <version> ` heading
- `plugins/tropical/.claude-plugin/plugin.json` — `"version": "…"`
- `plugins/tropical/skills/tropical/SKILL.md` — the `:mvn/version` coordinate

## The Claude Code plugin

`plugins/tropical` is a Claude Code plugin, listed by `.claude-plugin/marketplace.json`, whose `tropical` skill
teaches an app's agent what it can't infer from the API: where a guess from React, Electric or a Datastar SDK goes
wrong. It is not an API reference, since the docstrings are. When a change alters what an app writes, such as a
hook's contract, a new trap or a renamed helper, update the skill with the guide. It follows the same public-repo
hygiene.

`bb deploy` tags (`v<version>`, pushed) and deploys to Clojars; credentials come from
`CLOJARS_DEPLOY_MAVEN_USERNAME` / `CLOJARS_DEPLOY_MAVEN_PASSWORD`. A maintainer runs the deploy, never an agent.

## Testing

`bb test` runs the JVM tests (kaocha) and the browser-state JavaScript tests (Node.js). `bb test:linux` runs the
JavaScript tests once on the host, then the JVM tests in Linux containers under JDK 25 (amd64 by default, or arm64,
musl, `all`), where the shipped libraries are what's tested. `bb test:browser` runs only the JavaScript tests.
Test philosophy: invariants over wording. Exact error messages and renderings drift by design — don't pin them.
Lifecycles (what is held, released, revoked), coalescing and what reaches the client are the contract.

Apps test their islands with `co.multiply.tropical.test`, which ships in the jar. The library's own tests use
`test_util.clj`: `harness`, which drives a runtime the way a session's pump does and gives them its frames;
`observe-source`, which reads a `(fn [emit!] undo)` under a key, as an app's own wrapper over `observe` would; and
`manual-source`, whose values the test emits.

## Public-repo hygiene

The repo is public-bound. Docs, docstrings, comments, commit messages and examples speak in general terms, never
Multiply's own setup: no environment names, no internal app or artifact names, no first-person infra claims ("our
deployment", "the production X already uses"). Reasoning about deployment shapes is fine; using ourselves as the
example is not.

## Method

- For the central, low-level operations, drop to Java (VarHandles, intrusive lock-free structures, `co.multiply.conc`),
  as Multiply's other libraries do. Less garbage pays off indirectly even when direct timings move little, and the
  better reason is that Java encodes the properties wanted directly. Lead with the properties a primitive guarantees;
  report performance honestly, including when the gain is small next to the total.
- Known behaviors that are not bugs, so don't raise them as such: Chrome keeps a left page's stream open for about a
  minute (bfcache), and a linger can expire between slow manual steps. See the logbook.
- In the browser, island ids, state signals and script URLs are digests (`co.multiply.tropical.names`). To read them
  while debugging, `bb dev:readable` starts the demo with them as written; `island/element-id` maps an island's path
  to its element id from the REPL.
- Prefer the in-process MCP eval tool (`mcp__tropical__*`, served by Remontoire under `bb dev`) for multi-line Clojure
  against the running system — never `clojure -M -e`.
