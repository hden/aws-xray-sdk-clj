(ns aws-xray-sdk-clj.core-test
  (:refer-clojure :exclude [with-open])
  (:require
   [aws-xray-sdk-clj.core :as core]
   [aws-xray-sdk-clj.protocols :as protocol]
   [clojure.test :refer [deftest is]]))

(defn- receiving-consumer []
  (let [received (promise)]
    {:consumer (reify protocol/TraceConsumer
                 (consume! [_ trace]
                   (deliver received trace)))
     :received received}))

(deftest with-open-records-a-thrown-exception
  (let [{:keys [consumer received]} (receiving-consumer)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
          (core/with-open [root (core/start! consumer {:name "root"})]
            (throw (ex-info "boom" {})))))
    (let [^Throwable exception (:exception (deref received 1000 ::timeout))]
      (is (= "boom" (.getMessage exception))))))

(deftest updates-after-root-close-are-harmless
  (let [{:keys [consumer received]} (receiving-consumer)
        root (core/start! consumer {:name "root"})]
    (core/close! root)
    (is (identical? root (core/set-annotation! root {:late true})))
    (is (= "root" (:name (deref received 1000 ::timeout))))))
