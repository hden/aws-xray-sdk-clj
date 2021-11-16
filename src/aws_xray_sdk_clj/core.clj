(ns aws-xray-sdk-clj.core
  (:import [com.amazonaws.xray AWSXRayRecorderBuilder]
           [com.amazonaws.xray.plugins Plugin]
           [com.amazonaws.xray.strategy.sampling SamplingStrategy]))

(def default-recorder (AWSXRayRecorderBuilder/defaultRecorder))
(def default-emitter (.getEmitter default-recorder))

(defn- ^AWSXRayRecorderBuilder apply-plugins! [^AWSXRayRecorderBuilder builder plugins]
  (doseq [^Plugin plugin plugins]
    (.withPlugin builder plugin))
  builder)

;; Reuse emitter to prevent file descriptor leakage.
;; See https://github.com/aws/aws-xray-sdk-java/pull/310
(defn tracer-provider
  ([] (tracer-provider {}))
  ([{:keys [emitter plugins ^SamplingStrategy sampling-strategy]
     :or {emitter default-emitter}}]
   (cond-> (AWSXRayRecorderBuilder/standard)
     emitter (.withEmitter emitter)
     sampling-strategy (.withSamplingStrategy sampling-strategy)
     (seq plugins) (apply-plugins! plugins))))

(defn tracer [^AWSXRayRecorderBuilder provider]
  (.build provider))
