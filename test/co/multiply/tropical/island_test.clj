(ns co.multiply.tropical.island-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.action :refer [use-action]]
    [co.multiply.tropical.island :as island :refer [defisland use-state use-watch]]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.test-util :refer [harness]]
    [dev.onionpancakes.chassis.core :as h])
  (:import
    [java.io StringReader StringWriter]
    [java.util ArrayList Collection Collections Random]
    [javax.xml.parsers DocumentBuilderFactory]
    [javax.xml.transform OutputKeys TransformerFactory]
    [javax.xml.transform.dom DOMSource]
    [javax.xml.transform.stream StreamResult]
    [javax.xml.xpath XPathConstants XPathFactory]
    [org.w3c.dom Document Node]
    [org.xml.sax InputSource]))


(def ^:private !title (atom "Hello"))
(def ^:private !data (atom {:count 1 :other 1}))


(defisland child
  []
  [:span "count " (use-watch !data :count)])


(defisland parent
  []
  [:section [:h1 (use-watch !title)] (child) [:p "footer"]])


(defn- reset-data!
  []
  (reset! !title "Hello")
  (reset! !data {:count 1 :other 1}))


(deftest emits-the-same-html-as-plain-rendering
  (reset-data!)
  (let [{:keys [step rt]} (harness parent)
        frame (step)]
    (is (= (h/html [:section {:id (island/element-id "parent")} [:h1 "Hello"]
                    [:span {:id (island/element-id "parent/child")} "count " 1] [:p "footer"]])
           (island/html frame)))
    (is (= (count (island/html frame)) (island/frame-chars frame)))
    (runtime/dispose! rt)))


(deftest renders-and-patches-only-what-changed
  (reset-data!)
  (let [{:keys [step rt]} (harness parent)
        f1 (step)]
    (testing "a child's change neither re-renders nor resends the parent"
      (swap! !data update :count inc)
      (let [f2 (step)]
        (is (= ["parent/child"] (map :id (:morph (island/patches f1 f2)))))
        (is (= {"parent" 1 "parent/child" 2} (runtime/render-counts rt)))))
    (testing "the parent's own change resends the parent, without re-rendering the child"
      (let [f2 (step)]
        (reset! !title "Bye")
        (let [f3 (step)]
          (is (= ["parent"] (map :id (:morph (island/patches f2 f3)))))
          (is (= {"parent" 2 "parent/child" 2} (runtime/render-counts rt))))))
    (testing "a change the selector maps to an equal value renders nothing"
      (let [f3 (step)]
        (swap! !data update :other inc)
        (is (identical? f3 (step)))
        (is (= {"parent" 2 "parent/child" 2} (runtime/render-counts rt)))))
    (testing "an unknown client state (nil) resyncs the root"
      (is (= ["parent"] (map :id (:morph (island/patches nil (step)))))))
    (runtime/dispose! rt)
    (is (empty? (clojure.lang.ARef/.getWatches !data)) "dispose removes every watch")))


(deftest equal-output-sends-nothing
  (let [!n     (atom 1)
        parity (island/island-fn "parity" nil (fn [] [:p (if (odd? (use-watch !n)) "odd" "even")]))
        {:keys [step rt]} (harness parity)
        f1     (step)]
    (swap! !n + 2)
    (let [f2 (step)]
      (is (= {"parity" 2} (runtime/render-counts rt)) "it rendered")
      (is (identical? f1 f2) "but nothing is sent"))
    (runtime/dispose! rt)))


(def ^:private !setter (atom nil))


(defisland counter
  []
  (let [[n set-n!] (use-state :n 0)]
    (reset! !setter set-n!)
    [:b n]))


(defisland counter-host
  [show?]
  [:div (when show? (counter))])


(deftest island-local-state
  (let [!show (atom true)
        {:keys [step rt]} (harness #(counter-host @!show))]
    (step)
    (@!setter 5)
    (let [frame (step)]
      (is (re-find #">5<" (island/html frame)))
      (is (= {"counter-host" 1 "counter-host/counter" 2} (runtime/render-counts rt)) "only the island re-renders"))
    (testing "state is dropped when the island unmounts"
      (reset! !show false)
      (step)
      (reset! !show true)
      (is (re-find #">0<" (island/html (step)))))
    (runtime/dispose! rt)))


(defisland row
  {:key :id}
  [{:keys [text]}]
  [:li text])


(deftest keyed-islands-render-once-each
  (let [!rows (atom [{:id 1 :text "a"}])
        rows  (island/island-fn "rows" nil (fn [] [:ul (map row (use-watch !rows))]))
        {:keys [step rt]} (harness rows)]
    (step)
    (swap! !rows conj {:id 2 :text "b"})
    (is (= (h/html [:ul {:id (island/element-id "rows")}
                    [:li {:id (island/element-id "rows/row.1")} "a"] [:li {:id (island/element-id "rows/row.2")} "b"]])
           (island/html (step))))
    (is (= {"rows" 2 "rows/row.1" 1 "rows/row.2" 1} (runtime/render-counts rt)))
    (testing "a removed row unmounts"
      (swap! !rows subvec 1)
      (step)
      (is (= #{"rows" "rows/row.2"} (runtime/mounted rt))))
    (runtime/dispose! rt)))


(defisland five-args
  {:key (fn [a & _] a)}
  [a b c d e]
  [:i a b c d e])


(deftest an-island-takes-any-number-of-arguments
  (let [p    (island/island-fn "p" nil (fn [] [:div (five-args 1 2 3 4 5) (five-args 2 3 4 5 6)]))
        {:keys [step rt]} (harness p)
        html (island/html (step))]
    (is (str/includes? html (str "<i id=\"" (island/element-id "p/five-args.1") "\">12345</i>")))
    (is (str/includes? html (str "<i id=\"" (island/element-id "p/five-args.2") "\">23456</i>")))
    (runtime/dispose! rt)))


(def ^:private !released (atom 0))


(defisland leaf
  []
  (island/use-hold :thing (fn [] {:value :thing :release #(swap! !released inc)}))
  [:i (use-watch !data :count)])


(defisland middle
  []
  [:div (leaf)])


(deftest unmounting-reaches-the-whole-subtree
  (reset-data!)
  (reset! !released 0)
  (let [!show (atom true)
        host  (island/island-fn "host" nil (fn [] [:div (when (use-watch !show) (middle))]))
        {:keys [step rt]} (harness host)]
    (step)
    (is (= #{"host" "host/middle" "host/middle/leaf"} (runtime/mounted rt)))
    (reset! !show false)
    (step)
    (is (= #{"host"} (runtime/mounted rt)))
    (is (= 1 @!released) "the grandchild's hold is released")
    (is (empty? (clojure.lang.ARef/.getWatches !data)) "and its watch removed")
    (runtime/dispose! rt)))


(deftest a-child-with-a-new-id-goes-in-the-old-ones-place
  (let [!which (atom :a)
        a      (island/island-fn "a" nil (fn [] [:i "A"]))
        b      (island/island-fn "b" nil (fn [] [:i "B"]))
        p      (island/island-fn "p" nil (fn [] [:div (if (= :a (use-watch !which)) (a) (b))]))
        {:keys [step rt]} (harness p)
        f1     (step)]
    (reset! !which :b)
    (let [ps (island/patches f1 (step))]
      (is (= [] (:morph ps)))
      (is (= [{:mode :after :target "p/a"}] (map #(dissoc % :frames) (:insert ps))))
      (is (= ["p/a"] (:remove ps))))
    (is (= #{"p" "p/b"} (runtime/mounted rt)))
    (runtime/dispose! rt)))


(def ^:private !left (atom 0))
(def ^:private !right (atom 0))


(defisland left
  []
  [:div (str "L" (use-watch !left)) (middle)])


(defisland right
  []
  [:div (str "R" (use-watch !right)) (middle)])


(deftest one-island-in-two-places-is-two-instances
  (reset-data!)
  (let [both (island/island-fn "both" nil (fn [] [:main (left) (right)]))
        {:keys [step rt]} (harness both)
        html (island/html (step))]
    (is (str/includes? html (str "id=\"" (island/element-id "both/left/middle/leaf") "\"")))
    (is (str/includes? html (str "id=\"" (island/element-id "both/right/middle/leaf") "\"")))
    (testing "each placement renders on its own"
      (swap! !right inc)
      (step)
      (is (= {"both"                   1
              "both/left"              1
              "both/left/middle"       1
              "both/left/middle/leaf"  1
              "both/right"             2
              "both/right/middle"      1
              "both/right/middle/leaf" 1}
             (runtime/render-counts rt))))
    (runtime/dispose! rt)))


(deftest keys-make-valid-distinct-slots
  (let [keyed (island/island-fn "k" {:key identity} (fn [_] nil))
        slot  #(island/call-slot (keyed %))]
    (is (= "k.17" (slot 17)))
    (is (= "k.ab-c" (slot :ab-c)))
    (is (= "k.ns_2Fname" (slot :ns/name)))
    (is (= "k.a_20b" (slot "a b")))
    (is (= "k.a_5F20b" (slot "a_20b")) "an escape in a key is itself escaped")
    (is (= "k.caf_C3_A9" (slot "café")))
    (is (thrown-with-msg? IllegalArgumentException #"Island name"
          (island/island-fn "two words" nil (fn [] nil))))))


(deftest failures-render-an-error-in-place
  (testing "a render that throws"
    (let [boom (island/island-fn "boom" nil (fn [] (throw (ex-info "kaboom" {}))))
          {:keys [step rt]} (harness boom)]
      (is (re-find #"kaboom" (island/html (step))))
      (runtime/dispose! rt)))
  (testing "a root element with its own id"
    (let [own (island/island-fn "own" nil (fn [] [:div {:id "mine"}]))
          {:keys [step rt]} (harness own)]
      (is (re-find #"don't set one" (island/html (step))))
      (runtime/dispose! rt)))
  (testing "two islands with one id"
    (let [twice (island/island-fn "twice" nil (fn [] [:div (row {:id 1 :text "a"}) (row {:id 1 :text "b"})]))
          {:keys [step rt]} (harness twice)]
      (is (re-find #"give them distinct :key values" (island/html (step))))
      (runtime/dispose! rt)))
  (testing "a hold asked for twice in one render"
    (let [twice (island/island-fn "twice" nil
                  (fn []
                    [:div
                     [:button {:data-on:click (use-action :go (fn [_] :first))} "1"]
                     [:button {:data-on:click (use-action :go (fn [_] :second))} "2"]]))
          {:keys [step rt]} (harness twice)]
      (is (re-find #"asks for the hold \[:co.multiply.tropical.action/action :go\] twice" (island/html (step))))
      (runtime/dispose! rt))))


(deftest the-error-view-is-the-apps
  (let [boom (island/island-fn "boom" nil (fn [] (throw (ex-info "secret" {}))))
        ctx  {:tab "tab" :uid "user"}
        html (fn [opts]
               (let [{:keys [step rt]} (harness boom ctx opts)]
                 (try
                   (island/html (step))
                   (finally
                     (runtime/dispose! rt)))))]
    (testing "the default doesn't show the exception's message, since it reaches the client"
      (let [out (html {})]
        (is (str/includes? out (str "id=\"" (island/element-id "boom") "\"")))
        (is (not (re-find #"secret" out)))))
    (testing "an app's view gets the island's id and the exception"
      (is (re-find #">boom: secret<" (html {:error-view (fn [id e] [:p id ": " (ex-message e)])}))))
    (testing "an app's view that throws falls back to the default"
      (let [out (html {:error-view (fn [_ _] (throw (ex-info "worse" {})))})]
        (is (str/includes? out (str "id=\"" (island/element-id "boom") "\"")))
        (is (not (re-find #"secret|worse" out)))))))


(deftest hooks-only-run-in-a-render
  (is (thrown-with-msg? IllegalStateException #"outside an island render" (use-watch !title))))


;; Patching a list ----------------------------------------------------------------
;;
;; A client, modelled as a DOM parsed from the frame it shows, patched the way
;; Datastar patches it: a morph replaces the element with the frame's id, an
;; insert goes after or before the element with its target id, and a removal
;; removes the element with the id. The markup here is well-formed XML.


(defn- parse
  ^Document [^String html]
  (-> (DocumentBuilderFactory/newInstance)
    (.newDocumentBuilder)
    (.parse (InputSource. (StringReader. html)))))


(defn- element
  ^Node [^Document doc id]
  (-> (XPathFactory/newInstance)
    (.newXPath)
    (.evaluate (str "//*[@id='" (island/element-id id) "']") doc XPathConstants/NODE)))


(defn- node
  "The element `frame` renders, as a node of `doc`."
  ^Node [^Document doc frame]
  (Document/.importNode doc (.getDocumentElement (parse (island/html frame))) true))


(defn- serialize
  [^Document doc]
  (let [out (StringWriter.)]
    (-> (TransformerFactory/newInstance)
      (.newTransformer)
      (doto (.setOutputProperty OutputKeys/OMIT_XML_DECLARATION "yes"))
      (.transform (DOMSource. doc) (StreamResult. out)))
    (str out)))


(defn- patch-client!
  "Applies `patches` to the client `doc`, as Datastar would."
  [^Document doc {:keys [morph insert] removed :remove}]
  (doseq [frame morph]
    (let [old (element doc (:id frame))]
      (Node/.replaceChild (.getParentNode old) (node doc frame) old)))
  (doseq [{:keys [mode target frames]} insert]
    (let [anchor (element doc target)
          parent (.getParentNode anchor)
          before (if (= :after mode) (.getNextSibling anchor) anchor)]
      (doseq [frame frames]
        (Node/.insertBefore parent (node doc frame) before))))
  (doseq [id removed]
    (let [old (element doc id)]
      (Node/.removeChild (.getParentNode old) old)))
  doc)


(defisland item
  {:key :id}
  [{:keys [text]}]
  [:li text])


(def ^:private !items (atom []))
(def ^:private !note (atom nil))


(defisland items
  []
  [:div [:ul (map item (use-watch !items))] (when-some [note (use-watch !note)] [:p note])])


(defn- texts
  [ids]
  (mapv (fn [id] {:id id :text (str "item " id)}) ids))


(defn- insert-summary
  [patches]
  (mapv (fn [{:keys [mode target frames]}] [mode target (mapv :id frames)]) (:insert patches)))


(deftest a-list-that-gains-or-loses-items-sends-only-those
  (reset! !items (texts [1 2 3]))
  (reset! !note nil)
  (let [{:keys [step rt]} (harness items)
        !sent   (atom (step))
        patch   (fn [f]
                  (f)
                  (let [sent @!sent]
                    (island/patches sent (reset! !sent (step)))))
        change! (fn [f] (patch #(swap! !items f)))]
    (testing "an item at the end goes after the last"
      (let [ps (change! #(conj % {:id 4 :text "item 4"}))]
        (is (= [] (:morph ps) (:remove ps)))
        (is (= [[:after "items/item.3" ["items/item.4"]]] (insert-summary ps)))))
    (testing "items at the start go, in order, before the first"
      (let [ps (change! #(into (texts [5 6]) %))]
        (is (= [[:before "items/item.1" ["items/item.5" "items/item.6"]]] (insert-summary ps)))))
    (testing "a removed item is removed, and a changed one morphed, in the same frame"
      (let [ps (change! #(-> (vec (remove (comp #{2} :id) %)) (assoc-in [0 :text] "changed")))]
        (is (= ["items/item.2"] (:remove ps)))
        (is (= ["items/item.5"] (map :id (:morph ps))))
        (is (= [] (:insert ps)))))
    (testing "a reorder morphs the list"
      (let [ps (change! (comp vec reverse))]
        (is (= ["items"] (map :id (:morph ps))))))
    (testing "so does a change to the list's own markup"
      (let [ps (patch #(reset! !note "a note"))]
        (is (= ["items"] (map :id (:morph ps))))))
    (runtime/dispose! rt)))


(def ^:private !set-edits (atom nil))


(defisland pane
  {:key :opened}
  [{:keys [text]}]
  (let [[edits set-edits!] (use-state :edits 0)]
    (reset! !set-edits set-edits!)
    [:section text " " edits]))


(def ^:private !opening (atom {:opened 1 :text "first"}))


(defisland document
  []
  [:article [:h1 "A long document"] [:p "Its body."] (pane (use-watch !opening)) [:footer "The end."]])


(deftest a-child-whose-key-changed-goes-in-its-place
  (reset! !opening {:opened 1 :text "first"})
  (let [{:keys [step rt]} (harness document)
        _      (step)
        _      (@!set-edits 3)
        f0     (step)
        client (parse (island/html f0))
        _      (swap! !opening assoc :opened 2 :text "second")
        f1     (step)
        ps     (island/patches f0 f1)]
    (testing "a new key is a new island, which starts over"
      (is (re-find #"first 3" (island/html f0)))
      (is (re-find #"second 0" (island/html f1))))
    (testing "and goes in the old one's place, sent without its parent"
      (is (= [] (:morph ps)))
      (is (= [[:after "document/pane.1" ["document/pane.2"]]] (insert-summary ps)))
      (is (= ["document/pane.1"] (:remove ps))))
    (testing "which brings the client to the frame"
      (patch-client! client ps)
      (is (= (serialize (parse (island/html f1))) (serialize client))))
    (runtime/dispose! rt)))


(deftest patching-a-list-brings-the-client-to-the-frame
  (let [rng     (Random. 42)
        shuffle #(let [l (ArrayList. ^Collection %)]
                   (Collections/shuffle l rng)
                   (vec l))
        pick    #(nth % (.nextInt rng (count %)))
        !next   (atom 0)
        fresh   #(let [id (swap! !next inc)] {:id id :text (str "item " id)})
        edits   [(fn [xs] (conj xs (fresh)))
                 (fn [xs] (into [(fresh) (fresh)] xs))
                 (fn [xs]
                   (let [i (.nextInt rng (inc (count xs)))]
                     (into (conj (subvec xs 0 i) (fresh) (fresh)) (subvec xs i))))
                 (fn [xs] (if (seq xs) (let [i (.nextInt rng (count xs))] (into (subvec xs 0 i) (subvec xs (inc i)))) xs))
                 (fn [xs] (if (seq xs) (update-in xs [(.nextInt rng (count xs)) :text] str "!") xs))
                 (fn [xs] (shuffle xs))
                 (fn [xs] (conj (shuffle xs) (fresh)))
                 (fn [xs] (shuffle (vec (rest xs))))
                 (fn [xs] (into (conj (pop (vec (or (seq xs) [(fresh)]))) (fresh)) []))
                 ;; Every key replaced: no kept sibling to insert beside.
                 (fn [xs] (mapv (fn [_] (fresh)) xs))
                 (fn [xs] (reset! !note (when (.nextBoolean rng) (str "note " (.nextInt rng 3)))) xs)]
        _       (reset! !items [])
        _       (reset! !note nil)
        {:keys [step rt]} (harness items)
        f0      (step)
        client  (parse (island/html f0))]
    (loop [n 0, sent f0, spliced 0]
      (if (< n 400)
        (do (swap! !items (pick edits))
          (let [frame   (step)
                patches (island/patches sent frame)]
            (patch-client! client patches)
            (if (= (serialize (parse (island/html frame))) (serialize client))
              (recur (inc n) frame (cond-> spliced (seq (concat (:insert patches) (:remove patches))) inc))
              (is false (str "step " n ": the client differs from the frame after " (pr-str patches))))))
        (is (< 100 spliced) "most edits sent only what changed")))
    (runtime/dispose! rt)))
