(ns aws-xray-sdk-clj.core-test
  (:require [aws-xray-sdk-clj.core :as core]
            [clojure.test :refer [are deftest is testing use-fixtures]])
  (:import [com.amazonaws.xray.emitters Emitter]
           [com.amazonaws.xray.entities Entity TraceID]))

(def segments (atom []))
(def entity (atom {}))

(def mock-emitter
  (proxy [Emitter][]
    (sendSegment [x]
      (swap! segments conj x)
      true)

    (sendSubsegment [x]
      (swap! segments conj x)
      true)))

(def mock-entity
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
      (swap! entity assoc k v))))

(defn reset-fixtures! [f]
  (reset! segments [])
  (reset! entity {})
  (f))

(use-fixtures :each reset-fixtures!)

(def recorder (core/recorder {:emitter mock-emitter}))

(defn trace-id []
  (.toString (TraceID/create recorder)))

(defn annotations [x]
  (into {} (.getAnnotations x)))

(defn subsegments [segment]
  (into [] (.getSubsegments segment)))

(deftest entity-test
  (testing "set-exception!"
    (let [ex (ex-info "test" {:foo "bar"})]
      (core/set-exception! mock-entity ex)
      (is (= ex (get @entity :exception)))
      (is (= true (get @entity :error)))))

  (testing "set-annotation!"
    (are [x y]
      (do
        (core/set-annotation! mock-entity {x y})
        (= y (get @entity x)))

      "string"  "string"
      "number"  123
      "boolean" true))

  (testing "set-metadata!"
    (core/set-metadata! mock-entity {"foobar" "baz"})
    (is (= "baz" (get @entity "foobar")))))

(deftest trace-header-test
  (let [header "Sampled=?;Root=1-57ff426a-80c11c39b0c928905eb0828d;Parent=foo;Self=2;Foo=bar"]
    (testing "root-trace-id"
      (is (= "1-57ff426a-80c11c39b0c928905eb0828d"
             (core/root-trace-id header))))

    (testing "parent-id"
      (is (= "foo"
             (core/parent-id header))))))

(deftest segment-test
  (testing "begin-segment!"
    (let [data {"foo" "bar"}]
      (with-open [segment (core/begin! recorder {:trace-id (trace-id)
                                                 :name     "foo"})]
        (core/set-annotation! segment data))
      (is (= 1 (count @segments)))
      (let [segment (first @segments)]
        (is (= "foo" (.getName segment)))
        (is (= 0 (count (subsegments segment))))
        (is (= data (annotations segment)))))))

(deftest subsegment-test
  (testing "begin-subsegment!"
    (let [data {"foo" "bar"}]
      (core/with-open [segment (core/begin! recorder {:trace-id (trace-id)
                                                      :name     "bar"})]
        (core/with-open [subsegment (core/begin! segment {:name "baz"})]
          (core/set-annotation! subsegment data)))
      (is (= 1 (count @segments)))
      (let [segment (first @segments)
            subsegments (subsegments segment)
            subsegment (first subsegments)]
        (is (= "bar" (.getName segment)))
        (is (= 1 (count subsegments)))
        (is (= data (annotations subsegment)))))))
