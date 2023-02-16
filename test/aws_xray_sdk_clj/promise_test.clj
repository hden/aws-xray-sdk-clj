(ns aws-xray-sdk-clj.promise-test
  (:refer-clojure :exclude [with-open])
  (:require [aws-xray-sdk-clj.core :as core]
            [aws-xray-sdk-clj.promise :refer [with-open]]
            [aws-xray-sdk-clj.test-util :as util]
            [clojure.core.async :as a :refer [<!!]]
            [clojure.test :refer [deftest is use-fixtures]]
            [cuid.core :refer [cuid]]
            [promesa.core :as promesa])
  (:import java.util.concurrent.ForkJoinPool))

(def default-executor (ForkJoinPool/commonPool))
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
  (let [done (atom nil)
        p (with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                     :clock    mock-clock
                                                     :name     "foo"})]
            (reset! done (:done segment))
            (promesa/future
              (core/set-annotation! segment {"foo" "bar"})
              (throw (ex-info "Oops" {"foo" "bar"}))))]
    (is (thrown-with-msg? java.util.concurrent.ExecutionException #"Oops" @p))
    (<!! @done)
    (let [coll @segments]
      (is (= 1 (count coll)))
      (is (= "clojure.lang.ExceptionInfo: Oops {\"foo\" \"bar\"}"
             (get-in @segments [0 :cause :exceptions 0 :message]))))))

(deftest async-segment-test
  (let [done (atom nil)
        p (with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                     :clock    mock-clock
                                                     :name     "foo"})]
            (core/set-annotation! segment {:foo "bar"})
            (reset! done (:done segment))
            (promesa/delay 10 "foobar"))]
    (is (= "foobar" @p))
    (<!! @done)
    (let [coll @segments]
      (is (= "foo" (get-in coll [0 :name])))
      (is (nil? (seq (get-in coll [0 :subsegments]))))
      (is (= {:foo "bar"} (get-in coll [0 :annotations]))))))

(deftest async-subsegment-test
  (let [done (atom nil)
        p (with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                     :clock    mock-clock
                                                     :name     "bar"})]
            (reset! done (:done segment))
            (-> (promesa/delay 10 "foobar")
                (promesa/then (fn [_]
                                (with-open [subsegment (core/start! segment {:name "baz"})]
                                  (core/set-annotation! subsegment {:foo "bar"})
                                  (promesa/delay 10 "baz")))
                              default-executor)))]
    (is (= "baz" @p))
    (<!! @done)
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
                                                 default-executor)))))
                           default-executor)))
       (range n)))

;; Ensure we don't stumble on a deadlock due to synchronized blocks.
;; https://github.com/aws/aws-xray-sdk-java/issues/303
(deftest random-subsegment-test
  (let [done (atom nil)]
    @(with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                :clock    mock-clock
                                                :name     "baz"})]
       (reset! done (:done segment))
       (promesa/all (random-subsegments segment 50)))
    (<!! @done)
    (is (<= 1 (count @segments)))))
