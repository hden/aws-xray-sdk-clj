(ns aws-xray-sdk-clj.promise-test
  (:require [aws-xray-sdk-clj.core :as core]
            [aws-xray-sdk-clj.promise :refer [with-segment]]
            [aws-xray-sdk-clj.test-util :as util]
            [clojure.test :refer [deftest is use-fixtures]]
            [cuid.core :refer [cuid]]
            [promesa.core :as promesa]
            [promesa.exec :as exec]))

(def segments (atom []))
(def entity (atom {}))

(def mock-emitter (util/mock-emitter segments))

(defn reset-fixtures! [f]
  (reset! segments [])
  (reset! entity {})
  (f))

(use-fixtures :each reset-fixtures!)

(def recorder (core/recorder {:emitter mock-emitter}))

(deftest ex-handler-test
  (let [p (with-segment [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                        :name     "foo"})]
            (promesa/future
              (core/set-annotation! segment {"foo" "bar"})
              (throw (ex-info "Oops" {"foo" "bar"}))))]
    (is (thrown-with-msg? java.util.concurrent.ExecutionException #"Oops" @p))
    (is (= 1 (count @segments)))
    (let [segment (first @segments)]
      (is (:error segment)))))

(deftest async-segment-test
  (let [p (with-segment [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                        :name     "foo"})]
            (core/set-annotation! segment {"foo" "bar"})
            (promesa/delay 10 "foobar"))]
    (is (= "foobar" @p))
    (let [segment (first @segments)]
      (is (= "foo" (:name segment)))
      (is (nil? (seq (:subsegments segment))))
      (is (= "bar" (get-in segment [:annotations :foo]))))))

(deftest async-subsegment-test
  (let [p (with-segment [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                        :name     "bar"})]
            (-> (promesa/delay 10 "foobar")
              (promesa/then (fn [_]
                              (with-segment [subsegment (core/start! segment {:name "baz"})]
                                (core/set-annotation! subsegment {"foo" "bar"})
                                (promesa/delay 10 "baz")))
                            exec/default-executor)))]
    (is (= "baz" @p))
    (let [segment (first @segments)
          subsegments (:subsegments segment)
          subsegment (first subsegments)]
      (is (= "bar" (:name segment)))
      (is (= 1 (count subsegments)))
      (is (= "bar" (get-in subsegment [:annotations :foo]))))))

(defn random-subsegments [segment n]
  (map (fn [_]
         (-> (promesa/delay (rand-int 10) true)
           (promesa/then (fn [_]
                           (let [id (cuid)]
                             (with-segment [subsegment (core/start! segment {:name id})]
                               (-> (promesa/delay (rand-int 10) true)
                                 (promesa/then (fn [_]
                                                 (core/set-annotation! subsegment {:id id})
                                                 (promesa/all (random-subsegments subsegment (int (Math/floor (/ n 2))))))
                                               exec/default-executor)))))
                         exec/default-executor)))
       (range n)))

;; Ensure we don't stumble on a deadlock due to synchronized blocks.
;; https://github.com/aws/aws-xray-sdk-java/issues/303
(deftest random-subsegment-test
  @(with-segment [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                 :name     "baz"})]
     (promesa/all (random-subsegments segment 50)))
  (is (<= 1 (count @segments))))
