(ns aws-xray-sdk-clj.protocols)

(defprotocol IEntityProvider
  (-start! [_ arg-map]
    "Start a new entity."))

(defprotocol IAutoCloseable
  "A closable abstraction."
  (-close! [_]))

(defprotocol IEntity
  "A segment or subsegment."
  (-set-exception! [_ arg-map])
  (-set-annotation! [_ arg-map])
  (-set-metadata! [_ arg-map]))

(defprotocol ITraceHeader
  (-root-trace-id [_])
  (-parent-id [_]))
