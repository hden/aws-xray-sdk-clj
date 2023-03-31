(ns aws-xray-sdk-clj.core-test
  (:refer-clojure :exclude [with-open])
  (:require [aws-xray-sdk-clj.core :as core]
            [aws-xray-sdk-clj.test-util :as util]
            [clojure.core.async :as a :refer [<!!]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :refer [postwalk]]))

(def segments (atom []))
(def ^:const fixed-timestamp 1662905704)

(def mock-emitter (util/mock-emitter segments))
(def mock-clock (util/mock-clock fixed-timestamp))

(defn reset-fixtures! [f]
  (reset! segments [])
  (f))

(use-fixtures :each reset-fixtures!)

(def recorder (core/recorder {:emitter mock-emitter}))

(deftest set-exception-test
  (testing "set-exception!"
    (let [segment (core/start! recorder {:name "foo" :clock mock-clock})
          ex (ex-info "test" {:foo "bar"})]
      (core/set-exception! segment ex)
      (core/close! segment)
      (<!! (:done segment))
      (is (= "test" (get-in @segments [0 :cause :exceptions 0 :message]))))))

(deftest set-annotation-test
  (testing "set-annotation!"
    (let [segment (core/start! recorder {:name "foo" :clock mock-clock})
          annotations {:string  "string"
                       :number  123
                       :boolean true}]
      (core/set-annotation! segment annotations)
      (core/close! segment)
      (<!! (:done segment))
      (is (= annotations (get-in @segments [0 :annotations]))))))

(deftest set-metadata-test
  (testing "set-metadata!"
    (let [segment (core/start! recorder {:name "foo" :clock mock-clock})
          metadata {:foobar "baz"}]
      (core/set-metadata! segment metadata)
      (core/close! segment)
      (<!! (:done segment))
      (is (= metadata (get-in @segments [0 :metadata :default]))))))

(deftest trace-header-test
  (let [header "Sampled=?;Root=1-57ff426a-80c11c39b0c928905eb0828d;Parent=foo;Self=2;Foo=bar"]
    (testing "root-trace-id"
      (is (nil? (core/root-trace-id nil)))

      (is (= "1-57ff426a-80c11c39b0c928905eb0828d"
             (core/root-trace-id header))))

    (testing "parent-id"
      (is (nil? (core/parent-id nil)))

      (is (= "foo" (core/parent-id header))))))

(deftest with-open-close-test
  (testing "close"
    (let [done (atom nil)]
      (core/with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                      :clock    mock-clock
                                                      :name     "hoge"})]
        (core/set-annotation! segment {"foo" "bar"})
        (reset! done (:done segment)))
      (<!! @done)
      (is (= "hoge" (get-in @segments [0 :name]))))))

(deftest with-open-exception-handler-test
  (testing "exception handler"
    (let [done (atom nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Oops"
            (core/with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                            :clock    mock-clock
                                                            :name     "hoge"})]
              (core/set-annotation! segment {"foo" "bar"})
              (reset! done (:done segment))
              (throw (ex-info "Oops" {})))))
      (<!! @done)
      (is (= 1 (count @segments)))
      (is (= "Oops" (get-in @segments [0 :cause :exceptions 0 :message]))))))

(deftest segment-test
  (testing "begin-segment!"
    (let [done (atom nil)]
      (core/with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                      :clock    mock-clock
                                                      :name     "foo"})]
        (core/set-annotation! segment {"foo" "bar"})
        (reset! done (:done segment)))
      (<!! @done)
      (let [coll @segments]
        (is (= 1 (count coll)))
        (is (= "foo" (get-in coll [0 :name])))
        (is (= (double fixed-timestamp) (get-in coll [0 :start_time])))
        (is (= (double fixed-timestamp) (get-in coll [0 :end_time])))
        (is (nil? (seq (get-in coll [0 :subsegments]))))
        (is (= {:foo "bar"} (get-in coll [0 :annotations])))))))

(deftest subsegment-test
  (testing "begin-subsegment!"
    (let [done (atom nil)]
      (core/with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                      :clock    mock-clock
                                                      :name     "foo"})]
        (core/with-open [subsegment (core/start! segment {:name "bar"})]
          (core/set-annotation! subsegment {:foo "bar"})
          (reset! done (:done segment))))
      (<!! @done)
      (let [coll @segments]
        (is (= 1 (count coll)))
        (is (= "foo" (get-in coll [0 :name])))
        (is (= "bar" (get-in coll [0 :subsegments 0 :name])))
        (is (= {:foo "bar"} (get-in coll [0 :subsegments 0 :annotations])))))))

(deftest big-tree-test
  (testing "begin-subsegment!"
    (let [done (atom nil)]
      (core/with-open [root (core/start! recorder {:trace-id (util/trace-id recorder)
                                                   :clock    mock-clock
                                                   :name     "root"})]
        (reset! done (:done root))
        (core/with-open [a (core/start! root {:name "1"})]
          (core/with-open [b (core/start! a {:name "1-1"})]
            (core/set-annotation! b {:foo "bar"}))
          (core/with-open [c (core/start! a {:name "1-2"})]
            (core/with-open [d (core/start! c {:name "1-2-1"})]
              (core/set-annotation! d {:foo "bar"})))
          (core/with-open [d (core/start! a {:name "1-3"})]
            (core/with-open [e (core/start! d {:name "1-3-1"})]
              (core/set-annotation! e {:foo "bar"}))
            (core/with-open [f (core/start! d {:name "1-3-2"})]
              (core/set-annotation! f {:foo "bar"}))))
        (core/with-open [g (core/start! root {:name "2"})]
          (core/with-open [h (core/start! g {:name "2-1"})]
            (core/with-open [i (core/start! h {:name "2-1-1"})]
              (core/with-open [j (core/start! i {:name "2-1-1-1"})]
                (core/set-annotation! j {:foo "bar"}))))))
      (<!! @done)
      (let [coll @segments
            fixed-timestamp (double fixed-timestamp)
            expected [{:name "root"
                       :start_time fixed-timestamp
                       :end_time fixed-timestamp
                       :subsegments
                       [{:name "1"
                         :start_time fixed-timestamp
                         :end_time fixed-timestamp
                         :subsegments
                         [{:name "1-1"
                           :start_time fixed-timestamp
                           :end_time fixed-timestamp}
                          {:name "1-2"
                           :start_time fixed-timestamp
                           :end_time fixed-timestamp
                           :subsegments
                           [{:name "1-2-1"
                             :start_time fixed-timestamp
                             :end_time fixed-timestamp}]}
                          {:name "1-3"
                           :start_time fixed-timestamp
                           :end_time fixed-timestamp
                           :subsegments
                           [{:name "1-3-1"
                             :start_time fixed-timestamp
                             :end_time fixed-timestamp}
                            {:name "1-3-2"
                             :start_time fixed-timestamp
                             :end_time fixed-timestamp}]}]}
                        {:name "2"
                         :start_time fixed-timestamp
                         :end_time fixed-timestamp
                         :subsegments
                         [{:name "2-1"
                           :start_time fixed-timestamp
                           :end_time fixed-timestamp
                           :subsegments
                           [{:name "2-1-1"
                             :start_time fixed-timestamp
                             :end_time fixed-timestamp
                             :subsegments
                             [{:name "2-1-1-1"
                               :start_time fixed-timestamp
                               :end_time fixed-timestamp}]}]}]}]}]

            actual (postwalk (fn [x]
                               (if (map? x)
                                 (select-keys x [:name :subsegments :start_time :end_time])
                                 x))
                             coll)]
        (is (= expected actual))))))
