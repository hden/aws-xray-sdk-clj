(ns aws-xray-sdk-clj.promise
  (:require [aws-xray-sdk-clj.core :as core]
            [promesa.core :as promesa]))

(defn handler [entity]
  (fn [x ex]
    (when ex
      (core/set-exception! entity ex))
    (core/close! entity)
    x))

(defmacro with-segment
  "bindings => [name init ...]
  Evaluates body in a try expression with names bound to the values
  of the inits, and attach a finally handler that calls (close! name) on the
  name."
  [bindings & body]
  `(let ~(subvec bindings 0 2)
     (promesa/finally ~@body
                      (handler ~(bindings 0)))))
