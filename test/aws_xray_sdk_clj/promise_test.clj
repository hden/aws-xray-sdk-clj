(ns aws-xray-sdk-clj.promise-test
  (:refer-clojure :exclude [with-open])
  (:require [aws-xray-sdk-clj.core :as core]
            [aws-xray-sdk-clj.promise :refer [with-open]]
            [aws-xray-sdk-clj.test-util :as util]
            [clojure.test :refer [deftest is use-fixtures]]
            [cuid.core :refer [cuid]]
            [promesa.core :as promesa]
            [promesa.exec :as exec]))

(def segments (atom []))
(def ^:const fixed-timestamp 1662905704)

(def mock-emitter (util/mock-emitter segments))
(def mock-clock (util/mock-clock fixed-timestamp))

(defn reset-fixtures! [f]
  (reset! segments [])
  (f))

(use-fixtures :each reset-fixtures!)

(def recorder (core/recorder {:emitter mock-emitter}))

(deftest ex-handler-test
  (let [p (with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                     :clock    mock-clock
                                                     :name     "foo"})]
            (promesa/future
              (core/set-annotation! segment {"foo" "bar"})
              (throw (ex-info "Oops" {"foo" "bar"}))))]
    (is (thrown-with-msg? java.util.concurrent.ExecutionException #"Oops" @p))
    (let [coll @segments]
      (is (= 1 (count coll)))
      (is (= "clojure.lang.ExceptionInfo: Oops {\"foo\" \"bar\"}"
             (get-in @segments [0 :cause :exceptions 0 :message]))))))

(deftest async-segment-test
  (let [p (with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                     :clock    mock-clock
                                                     :name     "foo"})]
            (core/set-annotation! segment {:foo "bar"})
            (promesa/delay 10 "foobar"))]
    (is (= "foobar" @p))
    (let [coll @segments]
      (is (= "foo" (get-in coll [0 :name])))
      (is (nil? (seq (get-in coll [0 :subsegments]))))
      (is (= {:foo "bar"} (get-in coll [0 :annotations]))))))

(deftest async-subsegment-test
  (let [p (with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                     :clock    mock-clock
                                                     :name     "bar"})]
            (-> (promesa/delay 10 "foobar")
                (promesa/then (fn [_]
                                (with-open [subsegment (core/start! segment {:name "baz"})]
                                  (core/set-annotation! subsegment {:foo "bar"})
                                  (promesa/delay 10 "baz")))
                              exec/default-executor)))]
    (is (= "baz" @p))
    (let [coll @segments]
      (is (= "bar" (get-in coll [0 :name])))
      (is (= 1 (count (get-in coll [0 :subsegments]))))
      (is (= "baz" (get-in coll [0 :subsegments 0 :name])))
      (is (= {:foo "bar"} (get-in coll [0 :subsegments 0 :annotations]))))))

(defn random-subsegments [segment n]
  (map (fn [_]
         (-> (promesa/delay (rand-int 10) true)
             (promesa/then (fn [_]
                             (let [id (cuid)]
                               (with-open [subsegment (core/start! segment {:name id})]
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
  @(with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                              :clock    mock-clock
                                              :name     "baz"})]
     (promesa/all (random-subsegments segment 50)))
  (is (<= 1 (count @segments))))
