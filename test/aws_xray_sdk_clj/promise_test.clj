(ns aws-xray-sdk-clj.promise-test
  (:require [aws-xray-sdk-clj.core :as core]
            [aws-xray-sdk-clj.promise :refer [with-segment]]
            [clojure.test :refer [deftest is use-fixtures]]
            [cuid.core :refer [cuid]]
            [promesa.core :as promesa]
            [promesa.exec :as exec])
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

(deftest async-segment-test
  (let [data {"foo" "bar"}
        p (with-segment [segment (core/begin! recorder {:trace-id (trace-id)
                                                        :name     "foo"})]
            (-> (promesa/delay 10 "foobar")
              (promesa/finally (fn [_ _]
                                 (core/set-annotation! segment data))
                               exec/default-executor)))]
    (is (= "foobar" @p))
    (let [segment (first @segments)]
      (is (= "foo" (.getName segment)))
      (is (= 0 (count (subsegments segment))))
      (is (= data (annotations segment))))))

(deftest async-subsegment-test
  (let [data {"foo" "bar"}
        p (with-segment [segment (core/begin! recorder {:trace-id (trace-id)
                                                        :name     "bar"})]
            (-> (promesa/delay 10 "foobar")
              (promesa/then (fn [_]
                              (with-segment [subsegment (core/begin! segment {:name "baz"})]
                                (-> (promesa/delay 10 "baz")
                                  (promesa/finally (fn [_ _]
                                                     (core/set-annotation! subsegment data))
                                                   exec/default-executor))))
                            exec/default-executor)))]
    (is (= "baz" @p))
    (let [segment (first @segments)
          subsegments (subsegments segment)
          subsegment (first subsegments)]
      (is (= "bar" (.getName segment)))
      (is (= 1 (count subsegments)))
      (is (= data (annotations subsegment))))))

(defn random-subsegments [segment n]
  (map (fn [_]
         (-> (promesa/delay (rand-int 10) true)
           (promesa/then (fn [_]
                           (let [id (cuid)]
                             (with-segment [subsegment (core/begin! segment {:name id})]
                               (-> (promesa/delay (rand-int 10) true)
                                 (promesa/then (fn [_]
                                                 (core/set-annotation! subsegment {:id id})
                                                 (promesa/all (random-subsegments subsegment (int (Math/floor (/ n 2))))))
                                               exec/default-executor)))))
                         exec/default-executor)))
       (range n)))

(deftest random-subsegment-test
  @(with-segment [segment (core/begin! recorder {:trace-id (trace-id)
                                                 :name     "baz"})]
     (promesa/all (random-subsegments segment 6)))
  (is (= 1 (count @segments)))
  (let [segment (first @segments)
        subsegments (subsegments segment)]
    (is (= "baz" (.getName segment)))
    (is (< 1 (count subsegments)))))
