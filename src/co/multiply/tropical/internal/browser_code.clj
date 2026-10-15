(ns ^:no-doc co.multiply.tropical.internal.browser-code
  "Browser programs embedded in Datastar attributes, with configuration
  passed as arguments. They run synchronously, including before navigation."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))


(defn program
  "Reads the function in the JavaScript resource `file`. These programs are
  embedded in expressions; use block comments."
  [file]
  (str/trim (slurp (io/resource (str "co/multiply/tropical/" file)) :encoding "UTF-8")))


(defn call
  "Statements invoking `program` with `args`, already written as JS expressions.
  The containing attribute must wrap them in Datastar's zero-argument IIFE."
  [program args]
  (str "(" program ")(" (str/join ", " args) ");"))
