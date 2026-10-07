(ns co.multiply.tropical.compression-test
  "A compressed stream reaches the client as an uncompressed one does, frame
  by frame: what was sent so far decodes as it arrives."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.compression :as compression]
    [co.multiply.tropical.sse :as sse])
  (:import
    [co.multiply.tropical.sse BrotliEncoder EventStream ZstdEncoder]
    [com.github.luben.zstd ZstdInputStreamNoFinalizer]
    [java.io ByteArrayInputStream ByteArrayOutputStream FilterOutputStream IOException InputStream OutputStream]
    [java.nio.charset StandardCharsets]
    [java.util Arrays]
    [java.util.zip GZIPInputStream Inflater]
    [org.brotli.dec BrotliInputStream]))


(def ^:private encodings
  [:zstd :br :gzip])


(deftest an-encoding-is-chosen-by-what-the-request-accepts
  (is (= :zstd (compression/choose encodings "gzip, deflate, br, zstd")) "the app's first among equals")
  (is (= :br (compression/choose encodings "gzip, deflate, br")))
  (is (= :gzip (compression/choose encodings "gzip, deflate")))
  (is (= :gzip (compression/choose encodings "zstd;q=0.5, gzip")) "the request's weights first")
  (is (= :br (compression/choose encodings "zstd;q=0, br")) "a weight of 0 refuses")
  (is (= :br (compression/choose [:br :gzip] "*")) "any")
  (is (= :gzip (compression/choose encodings "*;q=0.1, gzip;q=0.5")))
  (is (= :zstd (compression/choose encodings " ZStd ; Q=1 ")) "codings and parameters ignore case")
  (is (nil? (compression/choose encodings "zstd;q=high")) "a weight that doesn't parse refuses")
  (is (nil? (compression/choose encodings "identity")))
  (is (nil? (compression/choose encodings nil)) "a request without the header isn't compressed")
  (is (nil? (compression/choose [] "gzip, zstd"))))


(deftest an-app-names-encodings-it-can-load
  (is (= encodings (compression/check! encodings)))
  (is (thrown? Exception (compression/check! [:deflate])))
  (is (thrown? Exception (compression/check! [:gzip :gzip])))
  (is (thrown? Exception (compression/check! :gzip))))


;; Decoders of a stream so far, as a client reads it: they decode what
;; arrived, without waiting for the end. brotli's is pure Java, so it runs on
;; musl too, and needs an end, which `unbrotli-so-far` supplies.

(defn- read-all
  ^bytes [^InputStream in]
  (with-open [in in]
    (InputStream/.readAllBytes in)))


(defn- inflate-so-far
  ^bytes [^bytes bs]
  ;; GZIPOutputStream's header is 10 bytes, without optional fields.
  (let [inflater (Inflater. true)
        out      (ByteArrayOutputStream.)
        buf      (byte-array 8192)]
    (Inflater/.setInput inflater bs 10 (- (alength bs) 10))
    (loop []
      (let [n (Inflater/.inflate inflater buf)]
        (when (pos? n)
          (ByteArrayOutputStream/.write out buf 0 n)
          (recur))))
    (Inflater/.end inflater)
    (ByteArrayOutputStream/.toByteArray out)))


(defn- unbrotli-so-far
  "What arrived, decoded by a decoder that requires the stream ended: a flush
  leaves it at the end of a metablock, at a byte's boundary, where an empty
  last metablock, 0x03, ends it. Bytes ending anywhere else don't decode."
  ^bytes [^bytes bs]
  (let [ended (Arrays/copyOf bs (int (inc (alength bs))))]
    (aset ended (alength bs) (byte 3))
    (read-all (BrotliInputStream. (ByteArrayInputStream. ended)))))


(defn- decoded-so-far
  [encoding ^bytes bs]
  (String. ^bytes (case encoding
                    :gzip (inflate-so-far bs)
                    :zstd (read-all (doto (ZstdInputStreamNoFinalizer. (ByteArrayInputStream. bs))
                                      (ZstdInputStreamNoFinalizer/.setContinuous true)))
                    :br (unbrotli-so-far bs))
    StandardCharsets/UTF_8))


(defn- decoded-whole
  "The stream decoded by a decoder that requires it ended: one that closed."
  [encoding ^bytes bs]
  (let [in (ByteArrayInputStream. bs)]
    (String. (read-all (case encoding
                         :gzip (GZIPInputStream. in)
                         :zstd (ZstdInputStreamNoFinalizer. in)
                         :br (BrotliInputStream. in)))
      StandardCharsets/UTF_8)))


(defn- open-stream
  [encoding out]
  (EventStream. out (compression/encoder encoding out)))


(defn- patch-event
  [html]
  (str "event: datastar-patch-elements\ndata: elements " html "\n\n"))


(deftest each-event-decodes-as-it-arrives
  (doseq [encoding encodings]
    (testing encoding
      (let [out    (ByteArrayOutputStream.)
            s      (open-stream encoding out)
            htmls  (concat (for [i (range 10)] (str "<p id=\"n\">" i "</p>"))
                     ;; Larger than every buffer on the way, and multi-byte.
                     [(str "<div id=\"big\">" (str/join (repeat 4000 "åäö 🌴 ")) "</div>")]
                     (for [i (range 10 20)] (str "<p id=\"n\">" i "</p>")))
            sent   (reductions str (map patch-event htmls))
            so-far (doall (for [html htmls]
                            (do (sse/patch! s [html])
                              (decoded-so-far encoding (ByteArrayOutputStream/.toByteArray out)))))]
        (is (= sent so-far) "each event decodes once sent, before the next")
        (sse/close! s)
        (is (= (last sent) (decoded-whole encoding (ByteArrayOutputStream/.toByteArray out)))
          "closing ends the encoding")))))


(deftest a-frame-repeating-an-earlier-one-costs-a-fraction-of-it
  (doseq [encoding encodings]
    (testing encoding
      (let [out    (ByteArrayOutputStream.)
            s      (open-stream encoding out)
            ;; An island of 20 KB, distinct within itself, as a table's rows are.
            island (str "<table id=\"t\">"
                     (str/join (for [i (range 400)] (str "<tr><td>" (* i 7919) "</td><td>" (Long/toString (* i 104729) 36)
                                                      "</td></tr>")))
                     "</table>")
            size   (fn [] (ByteArrayOutputStream/.size out))
            first  (do (sse/patch! s [island]) (size))
            again  (do (sse/patch! s [(str/replace-first island "<td>0</td>" "<td>changed</td>")]) (- (size) first))]
        (is (< again (/ first 10)) "a morph is sent as a reference to the last")
        (sse/close! s)))))


(defn- counting-flushes
  "An output stream into `out` that counts its flushes in `!flushes`."
  [out !flushes]
  (proxy [FilterOutputStream] [out]
    (write
      ;; write(int) and write(byte[]) alike.
      ([b] (if (bytes? b) (OutputStream/.write out ^bytes b) (OutputStream/.write out (int b))))
      ([b off len] (OutputStream/.write out ^bytes b (int off) (int len))))

    (flush [] (swap! !flushes inc))))


(deftest a-frame's-events-go-out-in-one-flush
  (doseq [encoding (cons nil encodings)]
    (testing (or encoding :none)
      (let [!flushes (atom 0)
            out      (ByteArrayOutputStream.)
            raw      (counting-flushes out !flushes)
            s        (if encoding (open-stream encoding raw) (EventStream. raw))
            frame    #(and (sse/patch! s ["<p id=\"a\">1</p>"])
                        (sse/remove! s "[id=\"b\"]")
                        (sse/signals! s {"a" 1}))]
        (reset! !flushes 0)
        (is (true? (sse/together s frame)))
        (is (= 1 @!flushes) "flushed once, when the frame was sent")
        (is (true? (sse/heartbeat! s)))
        (is (= 2 @!flushes) "outside a frame, an event is flushed as it is sent")
        (sse/close! s)
        (let [written (if encoding
                        (decoded-whole encoding (ByteArrayOutputStream/.toByteArray out))
                        (String. (ByteArrayOutputStream/.toByteArray out) StandardCharsets/UTF_8))]
          (is (str/starts-with? written (str (patch-event "<p id=\"a\">1</p>")
                                          "event: datastar-patch-elements\ndata: selector [id=\"b\"]\n"))))))))


(defn- leaving
  "An output stream to a client that leaves once `!left` is set: writing then
  fails. Counts its closes."
  [!left !closes]
  (proxy [OutputStream] []
    (write
      ([_] (when @!left (throw (IOException. "gone"))))
      ([_ _ _] (when @!left (throw (IOException. "gone")))))

    (close [] (swap! !closes inc))))


(def ^:private native-encoders
  "The encoders over a native library, and what each tells of the memory it
  holds."
  {:zstd {:open #(ZstdEncoder. % 3 18 15 14) :held ZstdEncoder/.nativeSize}
   :br   {:open #(BrotliEncoder. % 4 17)}})


(deftest a-native-encoder-lets-go-once-closed
  (doseq [[encoding {:keys [open held]}] native-encoders]
    (testing encoding
      (testing "closed after its stream ended"
        (let [!closes (atom 0)
              enc     (open (leaving (atom false) !closes))]
          (OutputStream/.write enc (.getBytes "data: elements <p>1</p>\n\n" StandardCharsets/UTF_8))
          (OutputStream/.flush enc)
          (when held (is (pos? (held enc))))
          (OutputStream/.close enc)
          (when held (is (zero? (held enc)) "freed"))
          (is (= 1 @!closes))
          (is (thrown? IOException (OutputStream/.write enc (byte-array 1))) "unusable once closed")
          (is (thrown? IOException (OutputStream/.flush enc)))
          (OutputStream/.close enc)
          (is (= 1 @!closes) "closing again does nothing")))
      (testing "closed after its client left"
        (let [!left   (atom false)
              !closes (atom 0)
              enc     (open (leaving !left !closes))]
          (OutputStream/.write enc (.getBytes "data: elements <p>1</p>\n\n" StandardCharsets/UTF_8))
          (reset! !left true)
          (is (thrown? IOException (OutputStream/.close enc)) "ending the stream failed")
          (when held (is (zero? (held enc)) "freed all the same"))
          (is (= 1 @!closes)))))))


(deftest a-client-that-left-closes-the-stream
  (doseq [encoding (cons nil encodings)]
    (testing (or encoding :none)
      (let [!left   (atom false)
            !closes (atom 0)
            raw     (leaving !left !closes)
            s       (if encoding (open-stream encoding raw) (EventStream. raw))]
        (reset! !left true)
        (is (false? (sse/together s #(sse/signals! s {"a" 1})))
          "a frame whose flush fails is not sent")
        (is (not (EventStream/.isOpen s)))
        (is (pos? @!closes) "the response's stream is closed, though ending the encoding failed")
        (is (false? (sse/heartbeat! s)))))))
