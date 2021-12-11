(ns aws-xray-sdk-clj.promise
  (:require [aws-xray-sdk-clj.core :as core]
            [promesa.core :as promesa]))

(defn handler [entity]
  (fn [_ ex]
    (when ex
      (core/set-exception! entity ex))
    (core/close! entity)))

(defmacro with-segment
  [bindings & body]
  `(let ~(subvec bindings 0 2)
     (promesa/finally ~@body
                      (handler ~(bindings 0)))))
