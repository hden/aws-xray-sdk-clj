(ns aws-xray-sdk-clj.xray
  (:require
   [aws-xray-sdk-clj.protocols :as protocol]
   [camel-snake-kebab.core :as csk])
  (:import
   (com.amazonaws.xray AWSXRayRecorder AWSXRayRecorderBuilder)
   (com.amazonaws.xray.emitters Emitter)
   (com.amazonaws.xray.entities Entity TraceID)
   (com.amazonaws.xray.plugins Plugin)
   (com.amazonaws.xray.strategy.sampling SamplingStrategy)))

(defn- apply-plugins! [^AWSXRayRecorderBuilder builder plugins]
  (doseq [^Plugin plugin plugins]
    (.withPlugin builder plugin))
  builder)

(defn xray-trace-consumer
  "Creates an AWS X-Ray TraceConsumer."
  (^AWSXRayRecorder []
   (xray-trace-consumer {}))
  (^AWSXRayRecorder [{:keys [^Emitter emitter plugins ^SamplingStrategy sampling-strategy]}]
   (let [^AWSXRayRecorderBuilder builder (AWSXRayRecorderBuilder/standard)]
     (when emitter
       (.withEmitter builder emitter))
     (when (seq plugins)
       (apply-plugins! builder plugins))
     (when sampling-strategy
       (.withSamplingStrategy builder sampling-strategy))
     (.build builder))))

(defrecord CapturedTraceConsumer [traces]
  protocol/TraceConsumer
  (consume! [_ trace]
    (swap! traces conj trace)))

(defn captured-trace-consumer []
  (->CapturedTraceConsumer (atom [])))

(defn- ^String xray-key [key]
  (csk/->snake_case_string key))

(defn- add-attributes! [^Entity entity trace]
  (when-let [start-at (:start-at trace)]
    (.setStartTime entity start-at))
  (doseq [[key value] (:annotations trace)]
    (let [^String key (xray-key key)]
      (cond
        (boolean? value) (.putAnnotation entity key ^Boolean value)
        (number? value)  (.putAnnotation entity key ^Number value)
        :else            (.putAnnotation entity key (str value)))))
  (doseq [[key value] (:metadata trace)]
    (.putMetadata entity (xray-key key) (str value)))
  (when-let [exception (:exception trace)]
    (.addException entity exception)
    (.setError entity true))
  (when-let [end-at (:end-at trace)]
    (.setEndTime entity end-at))
  entity)

(defn- consume-entity! [^AWSXRayRecorder recorder trace root?]
  (let [entity (if root?
                 (if-let [trace-id (:trace-id trace)]
                   (.beginSegment recorder
                                  (:name trace)
                                  (TraceID/fromString trace-id)
                                  (:parent-id trace))
                   (.beginSegment recorder (:name trace)))
                 (.beginSubsegment recorder (:name trace)))]
    (add-attributes! entity trace)
    (doseq [subsegment (:subsegments trace)]
      (consume-entity! recorder subsegment false))
    (if root?
      (.endSegment recorder)
      (.endSubsegment recorder))))

(extend-protocol protocol/TraceConsumer
  AWSXRayRecorder
  (consume! [recorder trace]
    (consume-entity! recorder trace true)))
