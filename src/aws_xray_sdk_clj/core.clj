(ns aws-xray-sdk-clj.core
  (:refer-clojure :exclude [with-open])
  (:require
   [aws-xray-sdk-clj.impl :as impl]
   [aws-xray-sdk-clj.protocols :as protocol]))

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

(defn trace-recorder
  ([consumer]
   (impl/trace-recorder consumer))
  ([consumer options]
   (impl/trace-recorder consumer options)))

(defn close! [entity]
  (protocol/-close! entity))

(defn shutdown!
  ([]
   (impl/shutdown!))
  ([recorder]
   (impl/shutdown! recorder)))

(defmacro with-open
  "Evaluates body in the scope of a generated entity.

  binding => [entity-sym entity-init]"
  [binding & body]
  (let [sym (binding 0)
        entity (binding 1)]
    `(clojure.core/with-open [^java.lang.AutoCloseable entity# ~entity]
       (try
         (let [~sym entity#]
           ~@body)
         (catch RuntimeException ex#
           (set-exception! entity# ex#)
           (throw ex#))))))
