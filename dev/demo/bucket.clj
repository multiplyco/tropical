(ns demo.bucket
  "A stand-in for an object store taking presigned PUTs, for the uploads card.
  `presign` gives a URL that takes a PUT of one key until it expires, signed
  with a secret only the server knows, as a bucket's presigned URL is.
  `wrap-bucket` serves it outside the app's middleware, as a store on an origin
  of its own would be.

  It keeps each object's size, not its bytes, and reads slowly, so a file's
  progress shows on a local connection."
  (:require
    [clojure.string :as str])
  (:import
    [java.io InputStream]
    [java.nio.charset StandardCharsets]
    [java.security MessageDigest SecureRandom]
    [java.util HexFormat]
    [javax.crypto Mac]
    [javax.crypto.spec SecretKeySpec]))


(def ^:private path "/bucket/")


(def ^:private expires-ms
  "How long a presigned URL takes a PUT."
  (* 5 60 1000))


(def ^:private bytes-per-ms
  "How fast the bucket reads: 8 MB a second."
  8000)


(defonce ^:private secret
  (let [bs (byte-array 32)]
    (SecureRandom/.nextBytes (SecureRandom.) bs)
    bs))


;; key -> {:size}, for each object that arrived whole.
(defonce !objects (atom {}))


(defn- signature
  [key expires]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (Mac/.init mac (SecretKeySpec. ^bytes secret "HmacSHA256"))
    (HexFormat/.formatHex (HexFormat/of)
      (Mac/.doFinal mac (String/.getBytes (str key "\n" expires) StandardCharsets/UTF_8)))))


(defn presign
  "The URL that takes a PUT of the object `key` for the next five minutes."
  [key]
  (let [expires (+ (System/currentTimeMillis) expires-ms)]
    (str path key "?expires=" expires "&signature=" (signature key expires))))


(defn object
  "The object `key`, `{:size}`, if it arrived whole."
  [key]
  (get @!objects key))


(defn- signed?
  "Whether `req` carries a signature, not yet expired, for the key it names."
  [req key]
  (let [[_ expires sig] (re-matches #"expires=(\d+)&signature=([0-9a-f]+)" (str (:query-string req)))]
    (and expires
      (< (System/currentTimeMillis) (parse-long expires))
      (MessageDigest/isEqual (String/.getBytes ^String sig StandardCharsets/US_ASCII)
        (String/.getBytes ^String (signature key expires) StandardCharsets/US_ASCII)))))


(defn- read-slowly!
  "Reads `in` to its end at `bytes-per-ms`, returning how many bytes it had."
  [^InputStream in]
  (let [buf (byte-array 65536)]
    (loop [total 0]
      (let [n (InputStream/.read in buf)]
        (if (neg? n)
          total
          (do
            (Thread/sleep (long (/ n bytes-per-ms)))
            (recur (+ total n))))))))


(defn- response
  [req]
  (let [key (subs (:uri req) (count path))]
    (cond
      (not= :put (:request-method req))
      {:status 405 :headers {"Allow" "PUT"}}

      (not (signed? req key))
      {:status 403 :body "Not signed, or expired."}

      :else
      (let [size (read-slowly! (:body req))]
        (swap! !objects assoc key {:size size})
        {:status 200}))))


(defn wrap-bucket
  "Serves the bucket under `/bucket/`, passing other requests to `handler`."
  [handler]
  (fn [req]
    (if (str/starts-with? (:uri req) path)
      (response req)
      (handler req))))
