(defproject migrana "0.1.2-SNAPSHOT"
  :description "Migrana is a Datomic migration tool that gives you the control over how your Datomic database evolves."
  :url "https://github.com/luchiniatwork/migrana"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [clj-time "0.14.2"]
                 [com.datomic/datomic-pro "0.9.5561.62"]
                 [environ "1.1.0"]
                 [camel-snake-kebab "0.4.0"]
                 [org.clojure/tools.cli "0.3.5"]]

  :eval-in-leiningen true
  
  :plugins [[lein-environ "1.1.0"]]

  :repositories {"my.datomic.com" {:url "https://my.datomic.com/repo"
                                   :creds :gpg}}

  :min-lein-version "2.6.1"

  :target-path "target/%s"
  
  :uberjar-name "migrana.jar"

  :profiles {:uberjar {:aot :all}})
