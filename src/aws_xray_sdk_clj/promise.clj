(ns aws-xray-sdk-clj.promise
  (:refer-clojure :exclude [with-open])
  (:require
   [aws-xray-sdk-clj.core :as core]
   [promesa.core :as promesa]))

(defn handler [entity]
  (fn [x ex]
    (when ex
      (core/set-exception! entity ex))
    (core/close! entity)
    x))

(defmacro with-open
  "bindings => [name init ...]
  Evaluates body with promesa/do and closes the bound entity from its finalizer."
  [bindings & body]
  `(let ~(subvec bindings 0 2)
     (-> (promesa/do ~@body)
       (promesa/finally (handler ~(bindings 0))))))
