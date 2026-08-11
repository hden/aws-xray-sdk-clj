(ns aws-xray-sdk-clj.xray-test
  (:require
   [aws-xray-sdk-clj.protocols :as protocol]
   [aws-xray-sdk-clj.xray :as xray]
   [clojure.test :refer [deftest is]]))

(deftest captured-trace-consumer-keeps-completed-traces-for-assertions
  (let [consumer (xray/captured-trace-consumer)
        trace {:name "root"}]
    (protocol/consume! consumer trace)
    (is (= [trace] @(:traces consumer)))))
