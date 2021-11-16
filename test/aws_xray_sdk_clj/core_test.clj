(ns aws-xray-sdk-clj.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [aws-xray-sdk-clj.core :as core])
  (:import [com.amazonaws.xray.plugins EC2Plugin]
           [com.amazonaws.xray.strategy.sampling AllSamplingStrategy]))

(deftest tracer-provider-test
  (testing "the recorder-builder should reuse a shared emitter."
    (let [tracer (core/tracer (core/tracer-provider))]
      (is (= core/default-emitter
             (.getEmitter tracer)))))

  (testing "plugins"
    (is (core/tracer (core/tracer-provider {:plugins [(new EC2Plugin)]}))))

  (testing "sampling strategy"
    (is (core/tracer (core/tracer-provider {:sampling-strategy (new AllSamplingStrategy)})))))
