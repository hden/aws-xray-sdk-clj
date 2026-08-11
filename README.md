# aws-xray-sdk-clj

Best-effort trace recording for Clojure. Trace facts are collected in one
Datascript store per `TraceRecorder` and delivered as immutable completed
traces to a `TraceConsumer`. Passing a consumer directly to `start!` uses the
process-wide default recorder.

AWS X-Ray is one optional consumer implementation; it does not participate in
trace state management.

## Usage

```clojure
(require '[aws-xray-sdk-clj.core :as core]
         '[aws-xray-sdk-clj.xray :as xray])

;; Creates and configures an AWSXRayRecorder as a TraceConsumer.
(def consumer (xray/xray-trace-consumer))

(core/with-open [trace (core/start! consumer {:name "checkout"})]
  (core/set-annotation! trace {:request-id "abc"})
  (core/with-open [database (core/start! trace {:name "database"})]
    (core/set-metadata! database {:table "orders"})))
```

`xray-trace-consumer` wraps `AWSXRayRecorderBuilder/standard` and accepts the
AWS SDK's `:emitter`, `:plugins`, and `:sampling-strategy` options. The SDK's
default emitter requires an X-Ray daemon or collector at its configured daemon
address (by default `127.0.0.1:2000`).

`close!` never waits for storage or delivery. A full trace mailbox, a full
consumer queue, or a consumer failure drops trace data without changing
application control flow.

`TraceConsumer` directly uses the process-wide default recorder. To isolate a
test or configure finite trace-owned resources, create and explicitly shut down
an independent recorder:

```clojure
(def tracing
  (core/trace-recorder consumer
                       {:max-stored-roots 32
                        :max-entities-per-trace 64
                        :root-ttl-seconds 30}))

(core/start! tracing {:name "checkout"})
(core/shutdown! tracing)
```

## Testing

`CapturedTraceConsumer` receives immutable traces without creating AWS SDK
objects.

```clojure
(require '[aws-xray-sdk-clj.xray :as xray]
         '[aws-xray-sdk-clj.protocols :as protocol])

(def consumer (xray/captured-trace-consumer))

;; Use consumer as the root argument to core/start!, then inspect:
@(:traces consumer)
```

Call `core/shutdown!` during controlled process shutdown to discard pending
trace work and stop the process-wide default recorder. Call
`(core/shutdown! tracing)` for each independent recorder.

## License

Copyright © 2021 Haokang Den

Licensed under the Apache License, Version 2.0.
