(ns aws-xray-sdk-clj.core
  (:refer-clojure :exclude [with-open])
  (:require [aws-xray-sdk-clj.protocols :as protocol]
            [aws-xray-sdk-clj.impl :as impl])
  (:import [com.amazonaws.xray AWSXRayRecorder]))

(def ^AWSXRayRecorder global-recorder impl/global-recorder)

(defn recorder
  [arg-map]
  (impl/recorder arg-map))

(defn ^String root-trace-id
  "Get the root trace ID from a HTTP Header string."
  [^String s]
  (when s
    (protocol/-root-trace-id s)))

(defn ^String parent-id
  "Get the parent ID from a HTTP Header string."
  [^String s]
  (when s
    (protocol/-parent-id s)))

(defn set-annotation! [entity arg-map]
  (when entity
    (protocol/-set-annotation! entity arg-map)))

(defn set-exception! [entity ex]
  (when entity
    (protocol/-set-exception! entity ex)))

(defn set-metadata! [entity arg-map]
  (when entity
    (protocol/-set-metadata! entity arg-map)))

(defn start! [entity-provider arg-map]
  (protocol/-start! entity-provider arg-map))

(defn close! [entity]
  (protocol/-close! entity))

(defmacro with-open
  "Evaluates body in the scope of a generated entity.

  binding => [entity-sym entity-init]"
  [binding & body]
  (let [sym (binding 0)
        entity (binding 1)]
    `(clojure.core/with-open [entity# ~entity]
       (try
         (let [~sym entity#]
           ~@body)
         (catch RuntimeException ex#
           (when entity#
             (protocol/-set-exception! entity# ex#))
           (throw ex#))))))
