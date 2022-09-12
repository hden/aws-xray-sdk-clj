(ns aws-xray-sdk-clj.test-util
  (:require [clojure.data.json :as json])
  (:import [com.amazonaws.xray.emitters Emitter]
           [com.amazonaws.xray.entities Entity Subsegment TraceID]
           [java.time Clock Instant ZoneId]))

(defprotocol Serializable
  (serialize [entity]))

(extend-protocol Serializable
  Subsegment
  (serialize [subsegment]
    (.streamSerialize subsegment))

  Entity
  (serialize [entity]
    (.serialize entity)))

(defn- entity->map [entity]
  (-> (serialize entity)
    (json/read-str :key-fn keyword)))

(defn mock-emitter [storage]
  (proxy [Emitter][]
    (sendSegment [x]
      (swap! storage conj (entity->map x))
      true)

    (sendSubsegment [x]
      (swap! storage conj (entity->map x))
      true)))

(defn trace-id [recorder]
  (.toString (TraceID/create recorder)))

(defn mock-clock [x]
  (Clock/fixed (Instant/ofEpochSecond x)
               (ZoneId/of "UTC")))
