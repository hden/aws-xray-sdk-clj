(ns aws-xray-sdk-clj.impl
  (:require
   [aws-xray-sdk-clj.protocols :as protocol]
   [cuid.core :refer [cuid]]
   [datascript.core :as d])
  (:import
   (aws_xray_sdk_clj.protocols TraceConsumer)
   (java.time Clock)
   (java.util.concurrent ArrayBlockingQueue Executors RejectedExecutionException ThreadFactory ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit)))

(def ^:private default-limits
  {:fact-mailbox-capacity    1024
   :completed-trace-capacity 64
   :consumer-thread-count    2
   :max-stored-roots         1024
   :max-entities-per-trace   1024
   :max-stored-entities      16384
   :root-ttl-seconds         300
   :clock                    (Clock/systemUTC)})

(def ^:private schema
  {:entity-key {:db/unique :db.unique/identity}
   :children   {:db/cardinality :db.cardinality/many
                :db/valueType   :db.type/ref}})

(defn- daemon-thread-factory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix (swap! counter inc)))
          (.setDaemon true))))))

(defrecord TraceRuntime [conn fact-mailbox fact-executor consumer-executor stopping? limits])

(defrecord TraceRecorder [runtime consumer])

(defn- current-timestamp-seconds [^Clock clock]
  (double (/ (.millis (or clock (Clock/systemUTC))) 1000)))

(defn- entity [db entity-key]
  (d/entity db [:entity-key entity-key]))

(defn- root-eids [db]
  (d/q '[:find [?eid ...]
         :where
         [?eid :entity-key ?key]
         [?eid :trace-key ?key]]
       db))

(defn- entity-count [db]
  (count (d/q '[:find [?eid ...]
                :where [?eid :entity-key]]
           db)))

(defn- trace-entity-count [db trace-key]
  (count (d/q '[:find [?eid ...]
                :in $ ?trace-key
                :where [?eid :trace-key ?trace-key]]
           db
           trace-key)))

(defn- entity-eids [db eid]
  (let [children (:children (d/pull db [:children] eid))]
    (cons eid (mapcat #(entity-eids db (:db/id %)) children))))

(defn- snapshot-entity [db eid]
  (let [{:keys [name trace-id parent-id start-at end-at annotations metadata
                exception children]}
        (d/pull db [:name :trace-id :parent-id :start-at :end-at
                    :annotations :metadata :exception :children]
                eid)
        trace (cond-> {:name     name
                       :start-at start-at
                       :end-at   end-at}
                trace-id (assoc :trace-id trace-id)
                parent-id (assoc :parent-id parent-id)
                annotations (assoc :annotations annotations)
                metadata (assoc :metadata metadata)
                exception (assoc :exception exception))]
    (cond-> trace
      (seq children) (assoc :subsegments (mapv #(snapshot-entity db (:db/id %)) children)))))

(defn- submit-completed-trace! [runtime consumer trace]
  (try
    (.execute ^ThreadPoolExecutor
              (:consumer-executor runtime)
              ^Runnable
              (reify Runnable
                (run [_]
                  (try
                    (protocol/consume! consumer trace)
                    (catch Throwable _)))))
    true
    (catch RejectedExecutionException _
      false)))

(defn- remove-trace! [runtime db root-eid]
  (let [root (d/pull db [:consumer] root-eid)
        trace (snapshot-entity db root-eid)
        retractions (mapv (fn [eid] [:db/retractEntity eid])
                          (entity-eids db root-eid))]
    (d/transact! (:conn runtime) retractions)
    (submit-completed-trace! runtime (:consumer root) trace)))

(defn- expire-traces! [runtime]
  (let [{:keys [clock root-ttl-seconds]} (:limits runtime)
        expires-before (- (current-timestamp-seconds clock) root-ttl-seconds)
        db @(:conn runtime)]
    (doseq [root-eid (root-eids db)
            :let [root (d/pull db [:start-at] root-eid)]
            :when (<= (:start-at root) expires-before)]
      (remove-trace! runtime @(:conn runtime) root-eid))))

(defn- apply-fact! [runtime fact]
  (let [db @(:conn runtime)
        {:keys [kind trace-key entity-key parent-key]} fact]
    (case kind
      :root-started
      (when-not (entity db entity-key)
        (when (and (< (count (root-eids db))
                     (:max-stored-roots (:limits runtime)))
                   (< (entity-count db)
                     (:max-stored-entities (:limits runtime))))
          (let [root-fact (select-keys fact [:trace-key :entity-key :name
                                             :trace-id :parent-id :consumer
                                             :start-at])
                root-attributes (into {} (remove (comp nil? val)) root-fact)]
            (d/transact! (:conn runtime)
                         [(assoc root-attributes :db/id -1)]))))

      :child-started
      (when-let [parent (entity db parent-key)]
        (when (and (= trace-key (:trace-key parent))
                   (nil? (:end-at parent))
                   (not (entity db entity-key))
                   (entity db trace-key)
                   (< (trace-entity-count db trace-key)
                      (:max-entities-per-trace (:limits runtime)))
                   (< (entity-count db)
                      (:max-stored-entities (:limits runtime))))
          (d/transact! (:conn runtime)
                       [{:db/id       -1
                         :trace-key   trace-key
                         :entity-key  entity-key
                         :parent-key  parent-key
                         :name        (:name fact)
                         :start-at    (:start-at fact)}
                        {:db/id (:db/id parent)
                         :children [-1]}])))

      :annotation-set
      (when-let [current (entity db entity-key)]
        (when (and (= trace-key (:trace-key current))
                   (nil? (:end-at current)))
          (d/transact! (:conn runtime)
                       [{:db/id (:db/id current)
                         :annotations (:annotations fact)}])))

      :metadata-set
      (when-let [current (entity db entity-key)]
        (when (and (= trace-key (:trace-key current))
                   (nil? (:end-at current)))
          (d/transact! (:conn runtime)
                       [{:db/id (:db/id current)
                         :metadata (:metadata fact)}])))

      :exception-set
      (when-let [current (entity db entity-key)]
        (when (and (= trace-key (:trace-key current))
                   (nil? (:end-at current)))
          (d/transact! (:conn runtime)
                       [{:db/id (:db/id current)
                         :exception (:exception fact)}])))

      :entity-closed
      (when-let [current (entity db entity-key)]
        (when (= trace-key (:trace-key current))
          (if (= trace-key entity-key)
            (do
              (d/transact! (:conn runtime)
                           [{:db/id  (:db/id current)
                             :end-at (:end-at fact)}])
              (remove-trace! runtime @(:conn runtime) (:db/id current)))
            (d/transact! (:conn runtime)
                         [{:db/id  (:db/id current)
                           :end-at (:end-at fact)}])))))))

(defn- new-runtime [options]
  (let [limits (merge default-limits options)
        conn (d/create-conn schema)
        fact-mailbox (ArrayBlockingQueue. (:fact-mailbox-capacity limits))
        fact-executor (Executors/newSingleThreadExecutor
                        (daemon-thread-factory "aws-xray-facts-"))
        consumer-executor (ThreadPoolExecutor.
                            (:consumer-thread-count limits)
                            (:consumer-thread-count limits)
                            0
                            TimeUnit/MILLISECONDS
                            (ArrayBlockingQueue. (:completed-trace-capacity limits))
                            (daemon-thread-factory "aws-xray-consumer-")
                            (ThreadPoolExecutor$AbortPolicy.))
        runtime (->TraceRuntime conn fact-mailbox fact-executor consumer-executor (atom false) limits)]
    (.submit fact-executor
             ^Runnable
             (reify Runnable
               (run [_]
                 (while (not @(:stopping? runtime))
                   (when-let [fact (.poll fact-mailbox 100 TimeUnit/MILLISECONDS)]
                     (try
                       (apply-fact! runtime fact)
                       (catch Throwable _)))
                   (try
                     (expire-traces! runtime)
                     (catch Throwable _))))))
    runtime))

(defonce ^:private runtime* (atom nil))

(defn- runtime []
  (or @runtime*
      (locking runtime*
        (or @runtime*
            (reset! runtime* (new-runtime {}))))))

(defn- shutdown-runtime! [runtime]
  (when (compare-and-set! (:stopping? runtime) false true)
    (.clear ^ArrayBlockingQueue (:fact-mailbox runtime))
    (.shutdownNow ^java.util.concurrent.ExecutorService (:fact-executor runtime))
    (.shutdownNow ^java.util.concurrent.ExecutorService (:consumer-executor runtime))
    (let [db @(:conn runtime)
          retractions (mapv (fn [eid] [:db/retractEntity eid])
                            (d/q '[:find [?eid ...]
                                   :where [?eid :entity-key]]
                                 db))]
      (when (seq retractions)
        (d/transact! (:conn runtime) retractions))))
  nil)

(defn shutdown!
  ([]
   (when-let [current @runtime*]
     (shutdown-runtime! current)
     (compare-and-set! runtime* current nil))
   nil)
  ([recorder]
   (shutdown-runtime! (:runtime recorder))))

(defn trace-recorder
  ([consumer]
   (trace-recorder consumer {}))
  ([consumer options]
   (->TraceRecorder (new-runtime options) consumer)))

(defn- record! [runtime fact]
  (when-not @(:stopping? runtime)
    (.offer ^ArrayBlockingQueue (:fact-mailbox runtime) fact)))

(defrecord Entity [runtime trace-key entity-key parent-key clock]
  java.lang.AutoCloseable
  (close [entity]
    (protocol/-close! entity))

  protocol/IAutoCloseable
  (-close! [entity]
    (record! runtime {:kind       :entity-closed
                      :trace-key  trace-key
                      :entity-key entity-key
                      :end-at     (current-timestamp-seconds clock)})
    entity)

  protocol/IEntity
  (-set-exception! [entity ex]
    (record! runtime {:kind       :exception-set
                      :trace-key  trace-key
                      :entity-key entity-key
                      :exception  ex})
    entity)

  (-set-annotation! [entity annotations]
    (record! runtime {:kind        :annotation-set
                      :trace-key   trace-key
                      :entity-key  entity-key
                      :annotations annotations})
    entity)

  (-set-metadata! [entity metadata]
    (record! runtime {:kind       :metadata-set
                      :trace-key  trace-key
                      :entity-key entity-key
                      :metadata   metadata})
    entity))

(extend-protocol protocol/IEntityProvider
  TraceConsumer
  (-start! [consumer {:keys [name trace-id parent-id clock]}]
    (let [trace-key (cuid)
          current-runtime (runtime)]
      (record! current-runtime {:kind       :root-started
                                :trace-key  trace-key
                                :entity-key trace-key
                                :name       name
                                :trace-id   trace-id
                                :parent-id  parent-id
                                :consumer   consumer
                                :start-at   (current-timestamp-seconds clock)})
      (->Entity current-runtime trace-key trace-key nil clock)))

  TraceRecorder
  (-start! [{:keys [runtime consumer]} {:keys [name trace-id parent-id]}]
    (let [trace-key (cuid)
          clock (:clock (:limits runtime))]
      (record! runtime {:kind       :root-started
                        :trace-key  trace-key
                        :entity-key trace-key
                        :name       name
                        :trace-id   trace-id
                        :parent-id  parent-id
                        :consumer   consumer
                        :start-at   (current-timestamp-seconds clock)})
      (->Entity runtime trace-key trace-key nil clock)))

  Entity
  (-start! [{:keys [runtime trace-key entity-key clock]} {:keys [name]}]
    (let [child-key (cuid)]
      (record! runtime {:kind       :child-started
                        :trace-key  trace-key
                        :entity-key child-key
                        :parent-key entity-key
                        :name       name
                        :start-at   (current-timestamp-seconds clock)})
      (->Entity runtime trace-key child-key entity-key clock))))
