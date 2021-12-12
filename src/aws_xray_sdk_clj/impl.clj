(ns aws-xray-sdk-clj.impl
  (:require [aws-xray-sdk-clj.protocols :as protocol]
            [camel-snake-kebab.core :as csk])
  (:import [com.amazonaws.xray AWSXRay AWSXRayRecorder AWSXRayRecorderBuilder]
           [com.amazonaws.xray.emitters Emitter]
           [com.amazonaws.xray.entities Entity Subsegment TraceID TraceHeader]
           [com.amazonaws.xray.plugins Plugin]
           [com.amazonaws.xray.strategy.sampling SamplingStrategy]))

(def ^AWSXRayRecorder global-recorder (AWSXRay/getGlobalRecorder))

(defn- ^AWSXRayRecorderBuilder apply-plugins! [^AWSXRayRecorderBuilder builder plugins]
  (doseq [^Plugin plugin plugins]
    (.withPlugin builder plugin))
  builder)

(defn ^AWSXRayRecorder recorder
  "Create an AWSXRayRecorder with options.
  Useful when you don't want to use the global recorder."
  ([] (recorder {}))
  ([{:keys [^Emitter emitter plugins ^SamplingStrategy sampling-strategy]}]
   (cond-> (AWSXRayRecorderBuilder/standard)
     emitter           (.withEmitter emitter)
     (seq plugins)     (apply-plugins! plugins)
     sampling-strategy (.withSamplingStrategy sampling-strategy)
     true              (.build))))

(defmacro ^:private when-let*
  [bindings & body]
  (if (seq bindings)
    `(when-let [~(first bindings) ~(second bindings)]
       (when-let* ~(drop 2 bindings) ~@body))
    `(do ~@body)))

(defrecord AEntity [^Entity entity]
  ;; Implement AutoCloseable by default so that this can work with 'open-with'
  java.lang.AutoCloseable
  (close [_]
    (protocol/-close! entity))

  protocol/IAutoCloseable
  (-close! [_]
    (protocol/-close! entity))

  protocol/IEntity
  (-set-exception! [this ex]
    (when entity
      (doto entity
        (.addException ex)
        (.setError true)))
    this)

  (-set-annotation! [this m]
    (when entity
      (doseq [[k v] m]
        (let [^String key (csk/->snake_case_string k)]
          (cond
            (boolean? v) (.putAnnotation entity key ^Boolean v)
            (number? v)  (.putAnnotation entity key ^Number v)
            :else        (.putAnnotation entity key (str v))))))
    this)

  (-set-metadata! [this m]
    (when entity
      (doseq [[k v] m]
        (.putMetadata entity (csk/->snake_case_string k) (str v))))
    this))

(extend-protocol protocol/IEntityProvider
  AWSXRayRecorder
  (-start! [^AWSXRayRecorder recorder arg-map]
    (let [^String name (get arg-map :name)
          ^String trace-id (get arg-map :trace-id)
          ^String parent-id (get arg-map :parent-id)]
      (-> (if trace-id
            (.beginSegment recorder name (TraceID/fromString trace-id) parent-id)
            (.beginSegment recorder name))
        (->AEntity))))

  AEntity
  (-start! [{:keys [^Entity entity]} arg-map]
    (let [^String name (get arg-map :name)
          ^AWSXRayRecorder recorder (.getCreator entity)
          result (atom nil)]
      (.run entity #(reset! result (.beginSubsegment recorder name)))
      (->AEntity @result)))

  nil
  (-start! [_ _]
    (->AEntity (.beginNoOpSegment global-recorder))))

(extend-protocol protocol/IAutoCloseable
  Subsegment
  (-close! [^Subsegment subsegment]
    (let [^AWSXRayRecorder recorder (.getCreator subsegment)]
      (.endSubsegment recorder subsegment)
      nil))

  Entity
  (-close! [^Entity entity]
    (.run entity #(.close entity))
    nil)

  nil
  (-close! [_]
    nil))

(extend-protocol protocol/ITraceHeader
  String
  (-root-trace-id [^String s]
    (when-let* [header s
                xray-trace-header (TraceHeader/fromString header)
                root-trace-id (.getRootTraceId xray-trace-header)]
      (.toString root-trace-id)))

  (-parent-id [^String s]
    (when-let* [header s
                xray-trace-header (TraceHeader/fromString header)]
      (.getParentId xray-trace-header))))
