(ns co.multiply.tropical.internal.channel-wire
  "Version 2 channel envelopes. Control frames are small JSON arrays; application
  messages use a nine-byte binary header (type, uint64 sequence) and opaque bytes."
  (:require [s-exp.oda :as json])
  (:import
    [co.multiply.tropical.channel Buffers]
    [java.nio ByteBuffer ByteOrder]
    [java.nio.charset Charset CharsetDecoder CodingErrorAction StandardCharsets]))


(defn counter?
  [n]
  (and (integer? n) (<= 0 n Buffers/MAX)))


(defn payload
  [value max-bytes]
  (let [^bytes bytes (cond
                       (string? value) (String/.getBytes value StandardCharsets/UTF_8)
                       (bytes? value) (aclone ^bytes value)
                       (instance? ByteBuffer value) (let [copy (byte-array (ByteBuffer/.remaining value))]
                                                      (ByteBuffer/.get (ByteBuffer/.duplicate value) copy)
                                                      copy)
                       :else (throw (IllegalArgumentException. "A channel message is a string, byte array or ByteBuffer.")))]
    (when (> (alength bytes) max-bytes)
      (throw (ex-info "Channel message exceeds its byte limit." {:type :message-too-large})))
    [(if (string? value) value (ByteBuffer/wrap bytes)) (alength bytes)]))


(defn control
  [v]
  (json/write-str v))


(defn data-frame
  [sequence value]
  (let [text? (string? value)
        data (if text? (ByteBuffer/wrap (String/.getBytes value StandardCharsets/UTF_8))
                 (ByteBuffer/.duplicate ^ByteBuffer value))
        frame (ByteBuffer/allocate (+ 9 (ByteBuffer/.remaining data)))]
    (ByteBuffer/.put frame (byte (if text? 1 2)))
    (ByteBuffer/.putLong frame sequence)
    (ByteBuffer/.put frame data)
    (ByteBuffer/.flip frame)))


(defn read-frame
  [frame max-bytes]
  (if (string? frame)
    (do
      (when (> (count frame) 1024) (throw (ex-info "Oversized channel control frame." {})))
      (let [v (json/parse frame)]
        (when-not (and (vector? v) (#{"hello" "welcome" "ready" "ack"} (first v)))
          (throw (ex-info "Invalid channel control frame." {})))
        v))
    (let [data (ByteBuffer/.order (ByteBuffer/.duplicate ^ByteBuffer frame) ByteOrder/BIG_ENDIAN)
          n (- (ByteBuffer/.remaining data) 9)]
      (when (or (neg? n) (> n max-bytes)) (throw (ex-info "Invalid channel data frame size." {})))
      (let [kind (ByteBuffer/.get data)
            sequence (ByteBuffer/.getLong data)
            copy (byte-array n)]
        (when-not (and (#{1 2} kind) (counter? sequence) (pos? sequence))
          (throw (ex-info "Invalid channel data frame header." {})))
        (ByteBuffer/.get data copy)
        ["data" sequence
         (if (= kind 1)
           (str (CharsetDecoder/.decode (doto (Charset/.newDecoder StandardCharsets/UTF_8)
                                          (CharsetDecoder/.onMalformedInput CodingErrorAction/REPORT)
                                          (CharsetDecoder/.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap copy)))
           (ByteBuffer/wrap copy)) n]))))
