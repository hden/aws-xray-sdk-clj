(ns aws-xray-sdk-clj.impl
  (:require [aws-xray-sdk-clj.protocols :as protocol]
            [camel-snake-kebab.core :as csk]
            [datascript.core :as d])
  (:import [com.amazonaws.xray AWSXRay AWSXRayRecorder AWSXRayRecorderBuilder]
           [com.amazonaws.xray.emitters Emitter]
           [com.amazonaws.xray.entities Entity Subsegment TraceID TraceHeader Segment]
           [com.amazonaws.xray.plugins Plugin]
           [com.amazonaws.xray.strategy.sampling SamplingStrategy]
           [java.time Clock]))

(def ^AWSXRayRecorder global-recorder (AWSXRay/getGlobalRecorder))
(def ^Clock default-clock (Clock/systemUTC))

(defn- apply-plugins!
  ^AWSXRayRecorderBuilder
  [^AWSXRayRecorderBuilder builder plugins]
  (doseq [^Plugin plugin plugins]
    (.withPlugin builder plugin))
  builder)

(defn recorder
  "Create an AWSXRayRecorder with options.
  Useful when you don't want to use the global recorder."
  (^AWSXRayRecorder [] (recorder {}))
  (^AWSXRayRecorder [{:keys [^Emitter emitter plugins ^SamplingStrategy sampling-strategy]}]
   (cond-> (AWSXRayRecorderBuilder/standard)
     emitter           (.withEmitter emitter)
     (seq plugins)     (apply-plugins! plugins)
     sampling-strategy (.withSamplingStrategy sampling-strategy)
     true              (.build))))

(defmacro ^:private when-let*
  [bindings & body]
  (if (seq bindings)
    `(when-let [~(first bindings) ~(second bindings)]
       (when-let* ~(drop 2 bindings) ~@body))
    `(do ~@body)))

(def ^:private schema
  {:subsegments {:db/cardinality :db.cardinality/many
                 :db/valueType   :db.type/ref}})

(defn ^:private sanitize-keys [m]
  (into {}
        (map (fn [[k v]]
               [(csk/->snake_case_string k) v]))
        m))

(defn ^:private current-timestamp-seconds
  [^Clock clock]
  (double (/ (.millis clock) 1000)))

(defn ^:private add-attributes!
  ^Entity
  [^Entity entity {:keys [db eid]}]
  (let [{:keys [start-at end-at annotations exception metadata]}
        (d/pull db '[*] eid)]
    (when start-at
      (.setStartTime entity start-at))
    (when (map? annotations)
      (doseq [[^String key v] annotations]
        (cond
          (boolean? v) (.putAnnotation entity key ^Boolean v)
          (number? v)  (.putAnnotation entity key ^Number v)
          :else        (.putAnnotation entity key (str v)))))
    (when (map? metadata)
      (doseq [[^String key v] metadata]
        (.putMetadata entity key (str v))))
    (when exception
      (.addException entity exception)
      (.setError entity true))
    (when end-at
      (.setEndTime entity end-at))
    entity))

(defn ^:private create-segment!
  ^Segment
  [^AWSXRayRecorder recorder {:as arg-map :keys [db]}]
  (let [eid 1
        {:keys [name trace-id parent-id]}
        (d/pull db [:name :trace-id :parent-id] eid)
        segment (if trace-id
                  (.beginSegment recorder name (TraceID/fromString trace-id) parent-id)
                  (.beginSegment recorder name))]
    (add-attributes! segment (assoc arg-map :eid eid))
    segment))

(defn ^:private create-subsegment!
  ^Subsegment
  [^AWSXRayRecorder recorder {:as arg-map :keys [db eid]}]
  (let [{:keys [name]} (d/pull db [:name] eid)
        subsegment (.beginSubsegment recorder name)]
    (add-attributes! subsegment arg-map)
    subsegment))

(defn ^:private send-trace! [{:as arg-map
                              :keys [eid conn ^AWSXRayRecorder recorder]
                              :or {eid 1}}]
  (let [db @conn
        tree (d/pull db
                     '[[:db/id :as :eid]
                       {:subsegments 1}]
                     eid)]
    (if (= eid 1)
      (create-segment! recorder {:db db})
      (create-subsegment! recorder {:db db :eid eid}))
    (when-let [subsegments (seq (:subsegments tree))]
      (doseq [{:keys [eid]} subsegments]
        (send-trace! (assoc arg-map :eid eid))))
    (if (= eid 1)
      (.endSegment recorder)
      (.endSubsegment recorder))))

(defrecord AEntity [eid conn ^Clock clock ^AWSXRayRecorder recorder]
  ;; Implement AutoCloseable by default so that this can work with 'open-with'
  java.lang.AutoCloseable
  (close [entity]
    (protocol/-close! entity))

  protocol/IAutoCloseable
  (-close! [{:as entity :keys [eid conn]}]
    (let [current-timestamp (current-timestamp-seconds clock)]
      (d/transact! conn [{:db/id eid :end-at current-timestamp}]))
    (when (= 1 eid)
      (send-trace! entity))
    nil)

  protocol/IEntity
  (-set-exception! [{:as entity :keys [eid conn]} ex]
    (d/transact! conn [{:db/id eid :exception ex}])
    entity)

  (-set-annotation! [{:as entity :keys [eid conn]} m]
    (let [annotations (sanitize-keys m)]
      (d/transact! conn [{:db/id eid :annotations annotations}])
      entity))

  (-set-metadata! [{:as entity :keys [eid conn]} m]
    (let [metadata (sanitize-keys m)]
      (d/transact! conn [{:db/id eid :metadata metadata}])
      entity)))

(extend-protocol protocol/IEntityProvider
  AWSXRayRecorder
  (-start! [^AWSXRayRecorder recorder {:as arg-map
                                       :keys [clock]
                                       :or {clock default-clock}}]
    (let [conn (d/create-conn schema)
          current-timestamp (current-timestamp-seconds clock)
          segment (merge (select-keys arg-map [:name :trace-id :parent-id])
                         {:db/id 1 :start-at current-timestamp})]
      (d/transact! conn [segment])
      (->AEntity 1 conn clock recorder)))

  AEntity
  (-start! [{:keys [eid conn clock recorder]} {:keys [name]}]
    (let [{:keys [tempids]} (d/transact! conn [{:db/id -1  :name  name}
                                               {:db/id eid :subsegments [-1]}])]
      (->AEntity (get tempids -1) conn clock recorder))))

(extend-protocol protocol/ITraceHeader
  String
  (-root-trace-id [^String s]
    (when-let* [header s
                xray-trace-header (TraceHeader/fromString header)
                root-trace-id (.getRootTraceId xray-trace-header)]
      (.toString root-trace-id)))

  (-parent-id [^String s]
    (when-let* [header s
                xray-trace-header (TraceHeader/fromString header)]
      (.getParentId xray-trace-header))))
