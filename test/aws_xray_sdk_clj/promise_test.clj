(ns aws-xray-sdk-clj.promise-test
  (:refer-clojure :exclude [with-open])
  (:require
   [aws-xray-sdk-clj.core :as core]
   [aws-xray-sdk-clj.promise :refer [with-open]]
   [aws-xray-sdk-clj.protocols :as protocol]
   [clojure.test :refer [deftest is]]
   [promesa.core :as promesa]))

(defn- receiving-consumer []
  (let [received (promise)]
    {:consumer (reify protocol/TraceConsumer
                 (consume! [_ trace]
                   (deliver received trace)))
     :received received}))

(deftest with-open-closes-a-root-after-its-promise-resolves
  (let [{:keys [consumer received]} (receiving-consumer)
        result (with-open [root (core/start! consumer {:name "root"})]
                 (core/set-annotation! root {:request-id "abc"})
                 (promesa/resolved :value))]
    (is (= :value @result))
    (is (= {:request-id "abc"}
           (:annotations (deref received 1000 ::timeout))))))

(deftest with-open-does-not-schedule-its-own-task
  (let [{:keys [consumer received]} (receiving-consumer)
        calling-thread (Thread/currentThread)
        body-thread (atom nil)
        result (with-open [root (core/start! consumer {:name "root"})]
                 (reset! body-thread (Thread/currentThread))
                 "value")]
    (is (= "value" @result))
    (is (identical? calling-thread @body-thread))
    (is (= "root" (:name (deref received 1000 ::timeout))))))
