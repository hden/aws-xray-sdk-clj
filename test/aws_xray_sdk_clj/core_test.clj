(ns aws-xray-sdk-clj.core-test
  (:refer-clojure :exclude [with-open])
  (:require [aws-xray-sdk-clj.core :as core]
            [aws-xray-sdk-clj.impl :as impl]
            [aws-xray-sdk-clj.protocols :as protocols]
            [aws-xray-sdk-clj.test-util :as util]
            [clojure.test :refer [are deftest is testing use-fixtures]])
  (:import [com.amazonaws.xray.entities Entity]))

(def segments (atom []))
(def entity (atom {}))

(def mock-emitter (util/mock-emitter segments))

(def mock-entity
  (impl/->AEntity
    (reify Entity
      (addException [_ ex]
        (swap! entity assoc :exception ex))

      (setError [_ x]
        (swap! entity assoc :error x))

      (^void putAnnotation [_ ^String k ^String v]
        (swap! entity assoc k v))

      (^void putAnnotation [_ ^String k ^Number v]
        (swap! entity assoc k v))

      (^void putAnnotation [_ ^String k ^Boolean v]
        (swap! entity assoc k v))

      (putMetadata [_ k v]
        (swap! entity assoc k v)))))

(defn reset-fixtures! [f]
  (reset! segments [])
  (reset! entity {})
  (f))

(use-fixtures :each reset-fixtures!)

(def recorder (core/recorder {:emitter mock-emitter}))

(deftest entity-test
  (testing "set-exception!"
    (let [ex (ex-info "test" {:foo "bar"})]
      (is (nil? (core/set-exception! nil ex)))
      (core/set-exception! mock-entity ex)
      (is (= ex (get @entity :exception)))
      (is (= true (get @entity :error)))))

  (testing "set-annotation!"
    (is (nil? (core/set-annotation! nil {})))
    (are [x y]
      (do
        (core/set-annotation! mock-entity {x y})
        (= y (get @entity x)))

      "string"  "string"
      "number"  123
      "boolean" true))

  (testing "set-metadata!"
    (is (nil? (core/set-metadata! nil {})))
    (core/set-metadata! mock-entity {"foobar" "baz"})
    (is (= "baz" (get @entity "foobar")))))

(deftest trace-header-test
  (let [header "Sampled=?;Root=1-57ff426a-80c11c39b0c928905eb0828d;Parent=foo;Self=2;Foo=bar"]
    (testing "root-trace-id"
      (is (nil? (core/root-trace-id nil)))

      (is (= "1-57ff426a-80c11c39b0c928905eb0828d"
             (core/root-trace-id header))))

    (testing "parent-id"
      (is (nil? (core/parent-id nil)))

      (is (= "foo"
             (core/parent-id header))))))

(deftest with-open-test
  (testing "exception handler"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Oops"
          (core/with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                          :name     "hoge"})]
            (core/set-annotation! segment {"foo" "bar"})
            (throw (ex-info "Oops" {})))))
    (is (= 1 (count @segments)))
    (let [segment (first @segments)]
      (is (:error segment)))))

(deftest segment-test
  (testing "begin-segment!, nil"
    (core/with-open [segment (core/start! nil {:trace-id (util/trace-id recorder)
                                               :name     "foo"})]
      (is (satisfies? protocols/IEntity segment))
      (is (satisfies? protocols/IEntityProvider segment))))

  (testing "begin-segment!"
    (core/with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                    :name     "foo"})]
      (core/set-annotation! segment {"foo" "bar"}))
    (is (= 1 (count @segments)))
    (let [segment (first @segments)]
      (is (= "foo" (:name segment)))
      (is (nil? (seq (:subsegments segment))))
      (is (= "bar" (get-in segment [:annotations :foo]))))))

(deftest subsegment-test
  (testing "begin-subsegment!, nil"
    (core/with-open [segment (core/start! nil {:trace-id (util/trace-id recorder)
                                               :name     "foo"})]
      (core/with-open [subsegment (core/start! segment {:name "bar"})]
        (is (satisfies? protocols/IEntity subsegment))
        (is (satisfies? protocols/IEntityProvider subsegment)))))

  (testing "begin-subsegment!"
    (core/with-open [segment (core/start! recorder {:trace-id (util/trace-id recorder)
                                                    :name     "bar"})]
      (core/with-open [subsegment (core/start! segment {:name "baz"})]
        (core/set-annotation! subsegment {"foo" "bar"})))
    (is (= 1 (count @segments)))
    (let [segment (first @segments)
          subsegments (:subsegments segment)
          subsegment (first subsegments)]
      (is (= "bar" (:name segment)))
      (is (= 1 (count subsegments)))
      (is (= "bar" (get-in subsegment [:annotations :foo]))))))
