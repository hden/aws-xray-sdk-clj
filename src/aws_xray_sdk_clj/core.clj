(ns aws-xray-sdk-clj.core
  (:import [com.amazonaws.xray AWSXRayRecorderBuilder]
           [com.amazonaws.xray.plugins Plugin]))

(def default-recorder (AWSXRayRecorderBuilder/defaultRecorder))
(def default-emitter (.getEmitter default-recorder))

(defn- apply-plugins! [^AWSXRayRecorderBuilder builder plugins]
  (doseq [^Plugin plugin plugins]
    (.withPlugin builder plugin))
  builder)

;; Reuse emitter to prevent file descriptor leakage.
;; See https://github.com/aws/aws-xray-sdk-java/pull/310
(defn tracer-provider
  ([] (tracer-provider {}))
  ([{:keys [emitter plugins sampling-strategy]
     :or {emitter default-emitter}}]
   (cond-> (AWSXRayRecorderBuilder/standard)
     emitter (.withEmitter emitter)
     (seq plugins) (apply-plugins! plugins)
     sampling-strategy (.withSamplingStrategy​ sampling-strategy))))

(defn tracer [^AWSXRayRecorderBuilder provider]
  (.build provider))
