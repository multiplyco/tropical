(ns co.multiply.tropical.names
  "What the browser sees of the app's own names: an island's element id, the
  signal of a cookie or storage state, a script's URL.

  By default each is an opaque digest of the app's name for it, so the page
  tells nothing of how the app is laid out: its namespaces, its functions, its
  states. The server keeps the names as written, in its logs, its REPL and the
  errors it throws. A digest is deterministic, so every server gives a name
  the same one: a tab reconnecting into another server is patched in place,
  and what the browser keeps under a state's name, in its cookie or storage,
  is found again after a deploy.

  With the JVM property `co.multiply.tropical.readable-names` set to `true`,
  read once as this namespace loads, the browser sees the names as written,
  for reading them while debugging. A state's names change with it, so what
  the browser kept under the other ones reads as the default. Every server of
  an app runs one way.

  A name is the app's own only in its code: what reaches the page goes
  through `co.multiply.tropical.ui` (`signal`, `path`) and the like. CSS, a
  script or an expression that names an island's element or a state's signal
  by hand would hold only while names are readable."
  (:import
    [java.nio.charset StandardCharsets]
    [java.security MessageDigest]
    [java.util HexFormat]))


(def ^:no-doc readable?
  "Whether the browser sees the app's names as written: the JVM property
  `co.multiply.tropical.readable-names`."
  (Boolean/getBoolean "co.multiply.tropical.readable-names"))


(defn ^:no-doc opaque
  "What the browser sees of the name `s`: `s` while names are readable, else
  `t` and the first `n` hex digits of a SHA-256 of `s`, which a CSS
  selector, a Datastar signal path and a URL all take as they are."
  [^String s n]
  (if readable?
    s
    (let [digest (MessageDigest/.digest (MessageDigest/getInstance "SHA-256")
                   (String/.getBytes (str "co.multiply.tropical/" s) StandardCharsets/UTF_8))]
      (str "t" (subs (HexFormat/.formatHex (HexFormat/of) digest) 0 n)))))
