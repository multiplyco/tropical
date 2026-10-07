(ns co.multiply.tropical.compression
  "Compressing a tab's stream, which an app opts into with `:compression`
  (`co.multiply.tropical.ring`): the encodings, how a request's is chosen, and
  the encoder each opens over the response.

  - `:zstd`  Zstandard, through the libzstd tropical ships
             (`co.multiply.tropical.sse.ZstdEncoder`).
  - `:br`    Brotli, through the libbrotlienc tropical ships
             (`co.multiply.tropical.sse.BrotliEncoder`).
  - `:gzip`  gzip, from the JDK.

  A stream's encoder lives as long as the stream, and keeps what it sent
  before as its window: a frame repeating what an earlier one sent, as a
  morph of an island mostly does, is sent as a reference to it. The frame's
  events are flushed through the encoder at once, so the client decodes the
  frame as it arrives.

  The settings are tropical's, measured on streams of frames (see the
  logbook), not the app's to tune. Each is a trade of memory for how far back
  the window reaches, held for every open stream:

  - `:zstd`  level 3, a 256 KiB window: about 0.7 MB a stream.
  - `:br`    quality 4, a 128 KiB window: about 0.9 MB a stream.
  - `:gzip`  the JDK's default level, and gzip's 32 KiB window: about 0.25 MB
             a stream. A morph of an island larger than the window is
             compressed on its own, without reference to its last.

  The native libraries are tropical's: an encoding is loaded when the app
  names it, and `ring/handler` throws for one it can't load."
  (:require
    [clojure.string :as str])
  (:import
    [co.multiply.tropical.sse BrotliEncoder ZstdEncoder]
    [java.io ByteArrayOutputStream OutputStream]
    [java.util.zip GZIPOutputStream]))


(defn- open-zstd
  [^OutputStream out]
  ;; Its flush ends the block, leaving the frame open; close ends the frame.
  ;; Level 3, a 2^18 window, and tables of 2^15 and 2^14 entries.
  (ZstdEncoder. out 3 18 15 14))


(defn- open-brotli
  [^OutputStream out]
  ;; Its flush ends the metablock; close ends the stream. Quality 4, and a 2^17
  ;; window.
  (BrotliEncoder. out 4 17))


(defn- open-gzip
  [^OutputStream out]
  ;; A sync flush on each flush; close writes the trailer.
  (GZIPOutputStream. out 4096 true))


(def ^:private encodings
  {:zstd {:open  open-zstd
          :needs (str "tropical's libzstd, for macOS, or for Linux on x86-64 or aarch64, or the system property "
                   ZstdEncoder/LIBRARY " naming one")}
   :br   {:open  open-brotli
          :needs (str "tropical's libbrotlienc, for macOS, or for Linux on x86-64 or aarch64, or the system "
                   "property " BrotliEncoder/LIBRARY " naming one")}
   :gzip {:open open-gzip}})


(defn ^:no-doc encoder
  "An encoder for `encoding` over the response's output stream `out`, as
  `EventStream` takes it."
  ^OutputStream [encoding out]
  ((:open (encodings encoding)) out))


(defn ^:no-doc check!
  "Returns `names`, the encodings an app names for `:compression`, in its
  order of preference, after loading each. Throws for a name that isn't an
  encoding, or one whose library isn't there or doesn't load."
  [names]
  (when-not (and (sequential? names) (every? keyword? names) (apply distinct? ::none names))
    (throw (ex-info "`:compression` is a vector of distinct encodings, in order of preference." {:compression names})))
  (doseq [k names]
    (when-not (encodings k)
      (throw (ex-info (str "No such encoding: " k ". The encodings are " (str/join ", " (keys encodings)) ".")
               {:encoding k})))
    (try
      (OutputStream/.close (encoder k (ByteArrayOutputStream.)))
      (catch Throwable t
        (throw (ex-info (str "The " k " encoding didn't load: it needs " (:needs (encodings k)) ".") {:encoding k} t)))))
  names)


(defn- weights
  "The codings of an Accept-Encoding header, lower-cased, and their weights.
  A weight that doesn't parse is 0."
  [header]
  (into {}
    (keep (fn [part]
            (let [[coding & params] (str/split (str/trim part) #"\s*;\s*")]
              (when-not (str/blank? coding)
                [(str/lower-case coding)
                 (if-some [[_ q] (some #(re-matches #"(?i)q\s*=\s*(.*)" %) params)]
                   (or (parse-double q) 0.0)
                   1.0)]))))
    (str/split header #",")))


(defn ^:no-doc choose
  "The encoding of `names`, the app's in its order of preference, for a
  request whose Accept-Encoding header is `accept`: the one it weighs
  highest, the app's first among equals. Nil if it accepts none, or sent no
  header."
  [names accept]
  (when (and accept (seq names))
    (let [w      (weights accept)
          weight #(get w (name %) (get w "*" 0.0))]
      (reduce (fn [best k]
                (if (> (double (weight k)) (if best (double (weight best)) 0.0)) k best))
        nil
        names))))
