(ns aws-xray-sdk-clj.impl
  (:require [aws-xray-sdk-clj.protocols :as protocol]
            [camel-snake-kebab.core :as csk]
            [clojure.core.async :as a :refer [<!]]
            [datascript.core :as d]
            [diehard.core :as dh]
            [diehard.bulkhead :refer [bulkhead]])
  (:import [com.amazonaws.xray AWSXRay AWSXRayRecorder AWSXRayRecorderBuilder]
           [com.amazonaws.xray.emitters Emitter]
           [com.amazonaws.xray.entities Entity Subsegment TraceID TraceHeader Segment]
           [com.amazonaws.xray.plugins Plugin]
           [com.amazonaws.xray.strategy.sampling SamplingStrategy]
           [java.time Clock]))

(def ^:const root-eid 1)
(def ^AWSXRayRecorder global-recorder (AWSXRay/getGlobalRecorder))
(def ^Clock default-clock (Clock/systemUTC))
(def default-throttle (bulkhead {:concurrency 1}))

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
  (double (/ (.millis (or clock default-clock))
             1000)))

(defn ^:private add-attributes!
  ^Entity
  [^Entity entity {:keys [^Clock clock db eid]}]
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
    (let [end-at (or end-at (current-timestamp-seconds clock))]
      (.setEndTime entity end-at))
    entity))

(defn ^:private create-segment!
  ^Segment
  [^AWSXRayRecorder recorder {:as arg-map :keys [db]}]
  (let [eid root-eid
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
                              :keys [^Clock clock conn eid ^AWSXRayRecorder recorder]
                              :or {eid root-eid}}]
  (let [db @conn
        tree (d/pull db
                     '[[:db/id :as :eid]
                       {:subsegments 1}]
                     eid)]
    (if (= eid root-eid)
      (create-segment! recorder {:clock clock :db db})
      (create-subsegment! recorder {:clock clock :db db :eid eid}))
    (when-let [subsegments (seq (:subsegments tree))]
      (doseq [{:keys [eid]} subsegments]
        (when-not (= eid root-eid)
          (send-trace! (assoc arg-map :eid eid)))))
    (if (= eid root-eid)
      (.endSegment recorder)
      (.endSubsegment recorder))))

(defrecord AEntity [^Clock clock conn done eid queue]
  ;; Implement AutoCloseable by default so that this can work with 'open-with'
  java.lang.AutoCloseable
  (close [entity]
    (protocol/-close! entity))

  protocol/IAutoCloseable
  (-close! [{:keys [eid queue]}]
    (let [current-timestamp (current-timestamp-seconds clock)]
      (a/offer! queue [{:db/id eid :end-at current-timestamp}]))
    (when (= eid root-eid)
      (a/close! queue)))

  protocol/IEntity
  (-set-exception! [{:as entity :keys [eid queue]} ex]
    (a/offer! queue [{:db/id eid :exception ex}])
    entity)

  (-set-annotation! [{:as entity :keys [eid queue]} m]
    (let [annotations (sanitize-keys m)]
      (a/offer! queue [{:db/id eid :annotations annotations}])
      entity))

  (-set-metadata! [{:as entity :keys [eid queue]} m]
    (let [metadata (sanitize-keys m)]
      (a/offer! queue [{:db/id eid :metadata metadata}])
      entity)))

(extend-protocol protocol/IEntityProvider
  AWSXRayRecorder
  (-start! [^AWSXRayRecorder recorder {:as arg-map
                                       :keys [clock throttle queue]
                                       :or {clock default-clock
                                            throttle default-throttle
                                            queue (a/chan (a/dropping-buffer 1000))}}]
    (let [conn (d/create-conn schema)
          current-timestamp (current-timestamp-seconds clock)
          eid root-eid
          segment (merge (select-keys arg-map [:name :trace-id :parent-id])
                         {:db/id eid :start-at current-timestamp})
          ;; async worker
          done (a/go-loop []
                 (if-let [tx-data (<! queue)]
                   (do
                     (d/transact! conn tx-data)
                     (recur))
                   ;; try send the root segment if possible
                   (try
                     (dh/with-bulkhead throttle
                       (send-trace! {:clock clock :conn conn :recorder recorder}))
                     ;; no-op
                     (catch Throwable _))))]
      (a/offer! queue [segment])
      (->AEntity clock conn done eid queue)))

  AEntity
  (-start! [{:as entity :keys [eid conn]} {:keys [name]}]
    (let [{:keys [tempids]} (d/transact! conn [{:db/id -1  :name  name}
                                               {:db/id eid :subsegments [-1]}])]
      (assoc entity :eid (get tempids -1)))))

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
