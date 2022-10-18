(defproject com.github.hden/aws-xray-sdk-clj "0.7.0"
  :description "A light wrapper for aws-xray-sdk-java"
  :url "https://github.com/hden/aws-xray-sdk-clj"
  :license {:name "Apache License, Version 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}
  :java-source-paths ["src/java"]
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [camel-snake-kebab "0.4.3"]
                 [com.amazonaws/aws-xray-recorder-sdk-core "2.12.0"]
                 [datascript "1.3.15"]
                 [funcool/promesa "9.0.489"]]
  :plugins [[lein-cloverage "1.2.4"]]
  :repl-options {:init-ns aws-xray-sdk-clj.core}
  :global-vars {*warn-on-reflection* true}
  :profiles
  {:dev {:dependencies [[cuid "0.1.2"]
                        [org.clojure/data.json "2.4.0"]]}})
