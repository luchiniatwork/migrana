(defproject migrana "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clojure-future-spec "1.9.0-alpha17"]
                 [clj-time "0.14.2"]
                 [com.datomic/datomic-pro "0.9.5561.62"]
                 [environ "1.1.0"]
                 [camel-snake-kebab "0.4.0"]]

  :plugins [[lein-environ "1.1.0"]]

  :repositories {"my.datomic.com" {:url "https://my.datomic.com/repo"
                                   :creds :gpg}}

  :min-lein-version "2.6.1"

  :main ^:skip-aot migrana.core

  :target-path "target/%s"
  
  :uberjar-name "clj-10kft.jar"

  :profiles {:uberjar {:aot :all}})
