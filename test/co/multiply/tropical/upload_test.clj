(ns co.multiply.tropical.upload-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.tropical.island :as island :refer [defisland]]
    [co.multiply.tropical.ring :as ring]
    [co.multiply.tropical.runtime :as runtime]
    [co.multiply.tropical.script :as script]
    [co.multiply.tropical.test-util :refer [harness]]
    [co.multiply.tropical.upload :as upload :refer [use-uploads]]
    [s-exp.oda :as json])
  (:import
    [java.io ByteArrayInputStream]))


(def ^:private handler
  (ring/handler {:uid  #(get-in % [:headers "x-uid"])
                 :root (fn [_] nil)
                 :page (fn [_] "")}))


(defn- post
  "Posts `body` to `token` as `uid`, as the browser's half does: the answer's
  JSON, or its status if it has none."
  [token uid body]
  (let [resp (handler {:request-method :post
                       :uri            "/page"
                       :headers        {"x-uid" uid "tropical-action" token "content-type" "application/json"}
                       :body           (ByteArrayInputStream. (.getBytes ^String (json/write-str body) "UTF-8"))})]
    (if (= 200 (:status resp))
      (json/parse (:body resp))
      (:status resp))))


(defisland files
  "Takes files, and gives the test what `use-uploads` returned through `!out`."
  [!out opts]
  (let [{:keys [uploads start] :as u} (use-uploads :files opts)]
    (reset! !out u)
    [:div {:data-on:change (start "evt.target.files")}
     (for [{:keys [id]} uploads]
       [:p {:data-style:width (upload/progress id)} id])]))


(defn- page
  "An island taking files for `uid` with `opts`: `{:step :rt :token :uploads}`,
  `uploads` the list as the island last rendered it."
  [uid opts]
  (let [!out (atom nil)
        {:keys [step rt]} (harness #(files !out opts) {:tab (str (random-uuid)) :uid uid})
        html (island/html (step))]
    {:step    step
     :rt      rt
     :html    html
     :token   (re-find #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" html)
     :out     !out
     :uploads #(:uploads @!out)}))


(defn- target
  "A `:target` referring to each file by its id, recording the list it was
  shown into `!shown`. It refuses executables, and more than `n` files."
  [!shown n]
  (fn [{:keys [id type]} uploads]
    (swap! !shown conj (mapv :name uploads))
    (cond
      (= "application/x-msdownload" type) {:refused "No executables."}
      (<= n (count uploads)) {:refused "Too many."}
      :else {:ref     (str "ref:" id)
             :url     (str "https://store.test/" id "?sig=x")
             :headers {"Content-Type" type}})))


(defn- file
  [name type size]
  {"name" name "type" type "size" size})


(deftest files-go-where-the-island-says
  (let [!shown (atom [])
        {:keys [rt step html token uploads]} (page "alice" {:target (target !shown 3)})]
    (testing "the page loads the browser's half, which the expression imports with the files"
      (is (some #{(script/url upload/uploader)} (runtime/scripts rt)))
      (is (str/includes? html (script/url upload/uploader)))
      (is (str/includes? html "evt.target.files")))
    (let [{answers "targets"} (post token "alice"
                                {"files" [(file "a.pdf" "application/pdf" 10)
                                          (file "setup.exe" "application/x-msdownload" 20)
                                          (file "b.png" "image/png" 30)]})
          [a exe b] answers]
      (is (= 3 (count answers)) "an answer for each file, in order")
      (is (every? #(get % "id") answers))
      (is (= (str "https://store.test/" (get a "id") "?sig=x") (get a "url")))
      (is (= "PUT" (get a "method")) "PUT unless the target says otherwise")
      (is (= {"Content-Type" "image/png"} (get b "headers")))
      (is (get exe "refused"))
      (is (not (get exe "url")))
      (is (not (str/includes? (json/write-str answers) "ref:")) "the app's reference stays on the server")
      (is (= [[] ["a.pdf"] ["a.pdf" "setup.exe"]] @!shown) "each file sees the pick's earlier ones")
      (step)
      (is (= [["a.pdf" :uploading] ["setup.exe" :refused] ["b.png" :uploading]]
             (mapv (juxt :name :state) (uploads))))
      (is (= "No executables." (:reason (second (uploads)))))
      (is (= (str "ref:" (get a "id")) (:ref (first (uploads)))))
      (testing "the island declares the progress of each upload on its way"
        (let [declared (->> (island/html (step)) (re-find #"data-signals__ifmissing=\"([^\"]*)\"") second)]
          (is (str/includes? declared (get a "id")))
          (is (str/includes? declared (get b "id")))
          (is (not (str/includes? declared (get exe "id")))))))
    (testing "a limit holds across picks"
      (let [{[c] "targets"} (post token "alice" {"files" [(file "c.txt" "text/plain" 1)]})]
        (is (get c "refused"))))
    (testing "what doesn't describe a file is refused, and doesn't reach the island"
      (let [before (count @!shown)]
        (is (= {"targets" [{"refused" true} {"refused" true}]}
               (post token "alice" {"files" [{"name" "x"} "y"]})))
        (is (= {"targets" []} (post token "alice" {"files" "nope"})))
        (is (= before (count @!shown)))))
    (runtime/dispose! rt)))


(deftest a-target-of-another-shape-throws
  (let [{:keys [rt step token uploads]} (page "alice" {:target (fn [_ _] {:url "https://store.test/x"})})]
    (is (thrown? IllegalArgumentException (post token "alice" {"files" [(file "a" "text/plain" 1)]}))
      "no :ref")
    (step)
    (is (empty? (uploads)) "the pick took no files")
    (runtime/dispose! rt)))


(defn- pick!
  "Picks a file in `p`, returning its id."
  [{:keys [token]} uid]
  (get-in (post token uid {"files" [(file "a.pdf" "application/pdf" 10)]}) ["targets" 0 "id"]))


(deftest a-report-reaches-only-the-island's-own-uploads
  (let [!arrived (atom [])
        opts     {:target  (target (atom []) 10)
                  :arrived #(swap! !arrived conj (:ref %))}
        alice    (page "alice" opts)
        other    (page "alice" opts)
        mine     (pick! alice "alice")
        theirs   (pick! other "alice")
        state    (fn [{:keys [step uploads]} id]
                   (step)
                   (some #(when (= id (:id %)) (:state %)) (uploads)))]
    (is (= 204 (post (:token alice) "alice" {"arrived" theirs})))
    (is (= :uploading (state other theirs)) "another session's id, named to this island")
    (is (= 204 (post (:token alice) "alice" {"arrived" "u0000000000000000"})) "never given")
    (is (= 403 (post (:token alice) "mallory" {"arrived" mine})) "another user")
    (is (= :uploading (state alice mine)))
    (is (= [] @!arrived) "none reached :arrived")
    (post (:token alice) "alice" {"arrived" mine})
    (is (= :arrived (state alice mine)))
    (is (= [(str "ref:" mine)] @!arrived) ":arrived gets the app's reference")
    (post (:token alice) "alice" {"arrived" mine})
    (post (:token alice) "alice" {"failed" mine "reason" "late"})
    (is (= :arrived (state alice mine)) "an upload settles once")
    (is (= 1 (count @!arrived)))
    (post (:token other) "alice" {"failed" theirs "reason" "The target answered 403."})
    (is (= :failed (state other theirs)))
    (runtime/dispose! (:rt alice))
    (is (= 404 (post (:token alice) "alice" {"arrived" mine})) "the island unmounted")
    (runtime/dispose! (:rt other))))


(deftest arrived-can-refuse-an-upload
  (let [!fail (atom false)
        p     (page "alice" {:target  (target (atom []) 10)
                             :arrived (fn [_]
                                        (if @!fail
                                          (throw (ex-info "store down" {}))
                                          {:refused "Not whole."}))})
        state (fn [id]
                ((:step p))
                (some #(when (= id (:id %)) %) ((:uploads p))))
        a     (pick! p "alice")
        b     (pick! p "alice")]
    (post (:token p) "alice" {"arrived" a})
    (is (= [:refused "Not whole."] ((juxt :state :reason) (state a))))
    (reset! !fail true)
    (is (thrown? Exception (post (:token p) "alice" {"arrived" b})))
    (is (= :failed (:state (state b))) "one that couldn't be checked failed")
    (runtime/dispose! (:rt p))))


(deftest a-message-takes-what-arrived
  (let [{:keys [rt step token uploads out] :as p} (page "alice" {:target (target (atom []) 10)})
        [a b c d] (repeatedly 4 #(pick! p "alice"))]
    (post token "alice" {"arrived" a})
    (post token "alice" {"arrived" b})
    (post token "alice" {"failed" d "reason" "x"})
    (step)
    (is (= [a b] (mapv :id ((:take! @out)))))
    (is (= [] ((:take! @out))) "taken once")
    (step)
    (is (= [c d] (mapv :id (uploads))) "the rest stay")
    ((:remove! @out) c)
    (post token "alice" {"arrived" c})
    (step)
    (is (= [d] (mapv :id (uploads))) "a removed upload's report changes nothing")
    (runtime/dispose! rt)))


(defn- picks!
  "Picks `n` files in one pick in `p`, returning their ids."
  [{:keys [token]} n]
  (mapv #(get % "id")
    (get (post token "alice" {"files" (vec (repeat n (file "a.pdf" "application/pdf" 10)))}) "targets")))


(deftest a-pick-settles-whole
  (let [!batches (atom [])
        !refuse  (atom #{})
        {:keys [rt step token uploads out] :as p}
        (page "alice" {:target  (target (atom []) 100)
                       :arrived (fn [{:keys [id]}] (when (@!refuse id) {:refused "Not whole."}))
                       :settled (fn [batch] (swap! !batches conj (mapv :id batch)))})
        states   (fn []
                   (step)
                   (mapv (juxt :id :state) (uploads)))]
    (testing "once nothing is uploading, with what arrived, which it takes"
      (let [[a b c] (picks! p 3)]
        (post token "alice" {"arrived" a})
        (post token "alice" {"failed" b "reason" "The target answered 403."})
        (is (= [] @!batches) "not while one is on its way")
        (post token "alice" {"arrived" c})
        (is (= [[a c]] @!batches) "one that failed doesn't hold back the rest")
        (is (= [[b :failed]] (states)) "which stays in the list")
        (is (= [] ((:take! @out))) "taken")))
    (testing "a pick made while another is on its way joins its batch"
      (let [[d] (picks! p 1)
            [e f] (picks! p 2)]
        (post token "alice" {"arrived" e})
        (post token "alice" {"arrived" d})
        (is (= 1 (count @!batches)))
        (post token "alice" {"arrived" f})
        (is (= [d e f] (last @!batches)) "in the list's order")))
    (testing "the last one on its way ends the batch however it ends"
      (let [[g h] (picks! p 2)]
        (post token "alice" {"arrived" g})
        (swap! !refuse conj h)
        (post token "alice" {"arrived" h})
        (is (= [g] (last @!batches)) "refused by :arrived"))
      (let [[i j] (picks! p 2)]
        (post token "alice" {"arrived" i})
        ((:remove! @out) j)
        (is (= [i] (last @!batches)) "removed")))
    (testing "not called when none arrived"
      (let [n (count @!batches)
            [k l] (picks! p 2)]
        (post token "alice" {"failed" k})
        ((:remove! @out) l)
        (is (= n (count @!batches)))))
    (runtime/dispose! rt)))


(deftest a-batch-that-cant-be-taken-in-fails
  (let [!fail (atom true)
        {:keys [rt step token uploads] :as p}
        (page "alice" {:target  (target (atom []) 100)
                       :settled (fn [_] (when @!fail (throw (ex-info "store down" {}))))})
        [a b] (picks! p 2)]
    (post token "alice" {"arrived" a})
    (is (thrown? Exception (post token "alice" {"arrived" b})) "the report that ended it gets the exception")
    (step)
    (is (= [[a :failed] [b :failed]] (mapv (juxt :id :state) (uploads))) "neither is lost, nor taken in again")
    (reset! !fail false)
    (let [[c] (picks! p 1)]
      (post token "alice" {"arrived" c})
      (step)
      (is (= [:failed :failed] (mapv :state (uploads))) "the next batch is c alone"))
    (runtime/dispose! rt)))


(defisland nested-areas
  "A drop area for one list of files, holding one for another, giving the test
  their `start`s through `!out`."
  [!out]
  (let [outer (:start (use-uploads :page {:target (target (atom []) 10)}))
        inner (:start (use-uploads :card {:target (target (atom []) 10)}))]
    (reset! !out [outer inner])
    [:div (upload/drop-area {:class "page"} outer)
     [:section (upload/drop-area inner) "card"]]))


(deftest a-drop-area-takes-files-for-its-uploads
  (let [!out (atom nil)
        {:keys [step rt]} (harness #(nested-areas !out) {:tab (str (random-uuid)) :uid "alice"})
        _    (step)
        [outer inner] @!out
        area (fn [start] (second (re-find #"'([^']+)'" (upload/dropping start))))]
    (is (not= (area outer) (area inner)) "each list's area is its own, so dragging over one shows its overlay alone")
    (doseq [start [outer inner]]
      (let [attrs (upload/drop-area {:class "c"} start)]
        (is (= "c" (:class attrs)) "added to the element's own")
        (is (str/includes? (:data-on:dragover attrs) (str "'" (area start) "'"))
          "a drag of files over it names the area `dropping` reads")
        (is (str/includes? (:data-on:drop attrs) (start "evt.dataTransfer.files")) "a drop uploads the files")))
    (is (thrown? IllegalArgumentException (upload/drop-area {:data-on:drop "mine"} inner))
      "an attribute of the element's own it would replace")
    (is (thrown? IllegalArgumentException (upload/drop-area (fn [files] files))) "only `use-uploads`'s start")
    (runtime/dispose! rt)))
