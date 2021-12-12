(defproject com.github.hden/aws-xray-sdk-clj "0.5.0-SNAPSHOT"
  :description "A light wrapper for aws-xray-sdk-java"
  :url "https://github.com/hden/aws-xray-sdk-clj"
  :license {:name "Apache License, Version 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}
  :java-source-paths ["src/java"]
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [camel-snake-kebab "0.4.2"]
                 [com.amazonaws/aws-xray-recorder-sdk-core "2.8.0"]
                 [funcool/promesa "6.0.2"]]
  :plugins [[lein-cloverage "1.2.2"]]
  :repl-options {:init-ns aws-xray-sdk-clj.core}
  :profiles
  {:dev {:dependencies [[cuid "0.1.2"]
                        [org.clojure/data.json "2.4.0"]]}})
