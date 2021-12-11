# aws-xray-sdk-clj
[![Clojars Project](https://img.shields.io/clojars/v/com.github.hden/aws-xray-sdk-clj.svg)](https://clojars.org/com.github.hden/aws-xray-sdk-clj)


A light wrapper for aws-xray-sdk-java.

## Why

aws-xray-sdk-java heavily use thread local storage and mutations, which can cause
lots of accidental complexity when beginning and ending segments / subsegments.

This library tries to mitigate the problem by making sure that the thread local
context is set before any operations.

## Usage

For synchronous execution

```clj
(require '[aws-xray-sdk-clj.core :as core])

(def trace-id (core/root-trace-id http-header-string))

(with-open [segment (core/begin! core/global-recorder {:trace-id trace-id
                                                       :name     "foo"})]
  (core/set-annotation! segment {:foo "bar"})
  (core/with-open [subsegment (core/begin! segment {:name "baz"})]
    (core/set-annotation! subsegment {:bar "baz"})))
```

For asynchronous codes

```clj
(require '[promesa.core :as promesa])
(require '[aws-xray-sdk-clj.promise :refer [with-segment]])

;; A light wrapper around promesa/finally
@(with-segment [segment (core/begin! recorder {:trace-id trace-id
                                               :name     "bar"})]
   (promesa/delay 10 "foobar"))
```

## Note

When unit testing on a machine that doesn't have a XRay agent running,
you might want to override the default UDP emitter to prevent errors.

```clj
(:import [com.amazonaws.xray.emitters Emitter])

(def mock-emitter
  (proxy [Emitter][]
    (sendSegment [x]
      true)

    (sendSubsegment [x]
      true)))

(def recorder (core/recorder {:emitter mock-emitter}))
```

## License

Copyright © 2021 Haokang Den

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
