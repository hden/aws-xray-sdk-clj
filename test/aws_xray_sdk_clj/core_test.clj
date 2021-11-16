(ns aws-xray-sdk-clj.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [aws-xray-sdk-clj.core :as core]))

(deftest shared-emitter-test
  (testing "The recorder-builder should reuse a shared emitter."
    (let [recorder (core/tracer (core/tracer-provider))]
      (is (= core/default-emitter
             (.getEmitter recorder))))))
