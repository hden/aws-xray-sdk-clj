# aws-xray-sdk-clj [![CircleCI](https://circleci.com/gh/hden/aws-xray-sdk-clj/tree/main.svg?style=svg)](https://circleci.com/gh/hden/aws-xray-sdk-clj/tree/main)
 [![codecov](https://codecov.io/gh/hden/aws-xray-sdk-clj/branch/main/graph/badge.svg?token=YHH1W6IGIW)](https://codecov.io/gh/hden/aws-xray-sdk-clj) [![Clojars Project](https://img.shields.io/clojars/v/com.github.hden/aws-xray-sdk-clj.svg)](https://clojars.org/com.github.hden/aws-xray-sdk-clj)

A light wrapper for aws-xray-sdk-java.

## Why

> High mutability and circular refrences between segments and subsegments create a prime landscape for thread issues. -- https://github.com/aws/aws-xray-sdk-java/pull/306#issue-1011630726

This library tries to mitigate the problems by making sure that the thread local
context is set before any operations.

## Usage

For synchronous execution

```clj
(require '[aws-xray-sdk-clj.core :as core])

(def trace-id (core/root-trace-id http-header-string))

(core/with-open [segment (core/start! core/global-recorder {:trace-id trace-id
                                                            :name     "foo"})]
  (core/set-annotation! segment {:foo "bar"})
  (core/with-open [subsegment (core/start! segment {:name "baz"})]
    (core/set-annotation! subsegment {:bar "baz"})))
```

For asynchronous codes

```clj
(require '[promesa.core :as promesa])
(require '[aws-xray-sdk-clj.promise :refer [with-segment]])

;; A light wrapper around promesa/finally
@(with-segment [segment (core/start! recorder {:trace-id trace-id
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
