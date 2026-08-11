(ns aws-xray-sdk-clj.fact-test
  (:require
   [aws-xray-sdk-clj.core :as core]
   [aws-xray-sdk-clj.protocols :as protocol]
   [clojure.test :refer [deftest is]]
   [clojure.test.check :as check]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop])
  (:import
   (java.time Clock Instant ZoneOffset)))

(defn- advancing-clock [millis]
  (proxy [Clock] []
    (getZone [] ZoneOffset/UTC)
    (withZone [_] this)
    (instant [] (Instant/ofEpochMilli @millis))))

(deftest root-close-gives-one-completed-trace-to-its-consumer
  (let [received (promise)
        consumer (reify protocol/TraceConsumer
                   (consume! [_ trace]
                     (deliver received trace)))
        root (core/start! consumer {:name "root"})]
    (core/set-annotation! root {:request-id "abc"})
    (core/close! root)
    (is (= {:name        "root"
            :annotations {:request-id "abc"}}
           (select-keys (deref received 1000 ::timeout)
                        [:name :annotations])))))

(deftest root-close-gives-its-child-to-the-same-consumer
  (let [received (promise)
        consumer (reify protocol/TraceConsumer
                   (consume! [_ trace]
                     (deliver received trace)))
        root (core/start! consumer {:name "root"})
        child (core/start! root {:name "child"})]
    (core/set-metadata! child {:request-id "abc"})
    (core/close! child)
    (core/close! root)
    (let [trace (deref received 1000 ::timeout)]
      (is (= "root" (:name trace)))
      (is (= "child" (get-in trace [:subsegments 0 :name])))
      (is (= {:request-id "abc"}
             (get-in trace [:subsegments 0 :metadata]))))))

(deftest updates-after-child-close-do-not-change-the-completed-trace
  (let [received (promise)
        consumer (reify protocol/TraceConsumer
                   (consume! [_ trace]
                     (deliver received trace)))
        root (core/start! consumer {:name "root"})
        child (core/start! root {:name "child"})]
    (core/close! child)
    (core/set-annotation! child {:late true})
    (core/close! root)
    (is (nil? (get-in (deref received 1000 ::timeout)
                      [:subsegments 0 :annotations])))))

(deftest a-trace-recorder-bounds-its-stored-roots
  (let [received (promise)
        consumer (reify protocol/TraceConsumer
                   (consume! [_ trace]
                     (deliver received trace)))
        recorder (core/trace-recorder consumer {:max-stored-roots 1})
        first-root (core/start! recorder {:name "first"})
        second-root (core/start! recorder {:name "second"})]
    (core/close! first-root)
    (core/close! second-root)
    (is (= "first" (:name (deref received 1000 ::timeout))))
    (core/shutdown! recorder)))

(deftest a-trace-recorder-expires-an-unclosed-root
  (let [millis (atom 0)
        received (promise)
        consumer (reify protocol/TraceConsumer
                   (consume! [_ trace]
                     (deliver received trace)))
        recorder (core/trace-recorder
                   consumer
                   {:clock (advancing-clock millis)
                    :root-ttl-seconds 1})]
    (core/start! recorder {:name "expired"})
    (reset! millis 1001)
    (is (= "expired" (:name (deref received 1000 ::timeout))))
    (core/shutdown! recorder)))

(deftest a-trace-recorder-bounds-entities-in-one-trace
  (let [received (promise)
        consumer (reify protocol/TraceConsumer
                   (consume! [_ trace]
                     (deliver received trace)))
        recorder (core/trace-recorder consumer {:max-entities-per-trace 2})
        root (core/start! recorder {:name "root"})
        first-child (core/start! root {:name "first"})
        second-child (core/start! root {:name "second"})]
    (core/close! first-child)
    (core/close! second-child)
    (core/close! root)
    (is (= ["first"]
           (mapv :name (:subsegments (deref received 1000 ::timeout)))))
    (core/shutdown! recorder)))

(deftest a-blocking-consumer-does-not-block-fact-recording
  (let [started (promise)
        release (promise)
        received (promise)
        traces (atom [])
        consumer (reify protocol/TraceConsumer
                   (consume! [_ trace]
                     (deliver started true)
                     @release
                     (let [completed (swap! traces conj trace)]
                       (when (= 2 (count completed))
                         (deliver received completed)))))
        recorder (core/trace-recorder consumer
                                      {:consumer-thread-count 1
                                       :completed-trace-capacity 1})
        first-root (core/start! recorder {:name "first"})]
    (core/close! first-root)
    (is (true? (deref started 1000 false)))
    (is (not= ::timeout
              (deref (future
                       (let [second-root (core/start! recorder {:name "second"})
                             third-root (core/start! recorder {:name "third"})]
                         (core/close! second-root)
                         (core/close! third-root)
                         true))
                     1000
                     ::timeout)))
    (deliver release true)
    (is (= ["first" "second"]
           (mapv :name (deref received 1000 ::timeout))))
    (core/shutdown! recorder)))

(defn- start-tree! [parent branching depth]
  (when (pos? depth)
    (let [children (doall
                     (for [index (range branching)]
                       (future
                         (let [child (core/start! parent {:name (str depth "-" index)})]
                           (start-tree! child branching (dec depth))
                           (core/close! child)))))]
      (doseq [child children]
        @child))))

(deftest generated-trees-complete-without-hanging
  (let [result (check/quick-check
                 25
                 (prop/for-all [branching (gen/choose 0 3)
                                depth (gen/choose 0 3)]
                   (let [received (promise)
                         consumer (reify protocol/TraceConsumer
                                    (consume! [_ trace]
                                      (deliver received trace)))
                         root (core/start! consumer {:name "root"})]
                     (start-tree! root branching depth)
                     (core/close! root)
                     (not= ::timeout (deref received 5000 ::timeout)))))]
    (is (true? (:result result)) (pr-str result))))
