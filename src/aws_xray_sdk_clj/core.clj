(ns aws-xray-sdk-clj.core
  (:refer-clojure :exclude [with-open])
  (:require [camel-snake-kebab.core :as csk])
  (:import [com.amazonaws.xray AWSXRay AWSXRayRecorder AWSXRayRecorderBuilder]
           [com.amazonaws.xray.emitters Emitter]
           [com.amazonaws.xray.entities Entity Segment Subsegment TraceID TraceHeader]
           [com.amazonaws.xray.plugins Plugin]
           [com.amazonaws.xray.strategy.sampling SamplingStrategy]))

(def global-recorder (AWSXRay/getGlobalRecorder))

(defmacro ^:private when-let*
  [bindings & body]
  (if (seq bindings)
    `(when-let [~(first bindings) ~(second bindings)]
       (when-let* ~(drop 2 bindings) ~@body))
    `(do ~@body)))

(defn set-exception!
  [^Entity entity ^Throwable ex]
  (when entity
    (doto entity
      (.addException ex)
      (.setError true))))

(defn set-annotation!
  [^Entity entity m]
  (when entity
    (doseq [[k v] m]
      (let [^String key (csk/->snake_case_string k)]
        (cond
          (boolean? v) (.putAnnotation entity key ^Boolean v)
          (number? v)  (.putAnnotation entity key ^Number v)
          :else        (.putAnnotation entity key (str v)))))
    entity))

(defn set-metadata!
  [^Entity entity m]
  (when entity
    (doseq [[k v] m]
      (.putMetadata entity (csk/->snake_case_string k) (str v)))
    entity))

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

(defn ^String root-trace-id
  "Get the root trace ID from a HTTP Header string."
  [^String s]
  (when-let* [header s
              xray-trace-header (TraceHeader/fromString header)
              root-trace-id (.getRootTraceId xray-trace-header)]
    (.toString root-trace-id)))

(defn ^String parent-id
  "Get the parent ID from a HTTP Header string."
  [^String s]
  (when-let* [header s
              xray-trace-header (TraceHeader/fromString header)]
    (.getParentId xray-trace-header)))

(defn- ^Segment begin-segment! [^AWSXRayRecorder recorder arg-map]
  (let [^String name (get arg-map :name)
        ^String trace-id (get arg-map :trace-id)
        ^String parent-id (get arg-map :parent-id)]
    (if trace-id
      (.beginSegment recorder name (TraceID/fromString trace-id) parent-id)
      (.beginSegment recorder name))))

(defn- ^Subsegment begin-subsegment! [^Entity entity arg-map]
  (let [^String name (get arg-map :name)
        ^AWSXRayRecorder recorder (.getCreator entity)
        result (atom nil)]
    (.run entity #(reset! result (.beginSubsegment recorder name)))
    @result))

(defprotocol SegmentProvider
  (begin! [provider arg-map]))

(extend-protocol SegmentProvider
  AWSXRayRecorder
  (begin! [provider arg-map]
    (begin-segment! provider arg-map))

  Entity
  (begin! [provider arg-map]
    (begin-subsegment! provider arg-map))

  nil
  (begin! [_ _]
    (.beginNoOpSegment global-recorder)))

(defprotocol AutoCloseable
  (close! [entity]))

(extend-protocol AutoCloseable
  Subsegment
  (close! [subsegment]
    (let [^AWSXRayRecorder recorder (.getCreator subsegment)]
      (.endSubsegment recorder subsegment)))

  Entity
  (close! [entity]
    (.run entity #(.close entity)))

  nil
  (close! [_]
    nil))

(defmacro with-open
  "bindings => [name init ...]
  Evaluates body in a try expression with names bound to the values
  of the inits, and a finally clause that calls (close! name) on each
  name in reverse order."
  [bindings & body]
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                (with-open ~(subvec bindings 2) ~@body)
                                (finally
                                  (close! ~(bindings 0)))))
    :else (throw (IllegalArgumentException.
                   "with-open only allows Symbols in bindings"))))
