(ns migrana.core
  (:require [camel-snake-kebab.core :refer [->snake_case_string]]
            [clj-time.core :as time]
            [clj-time.format :as format]
            [clj-time.local :as local]
            [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [datomic.api :as datomic]
            [environ.core :as environ]))

(def ^:private custom-formatter (format/formatter "yyyyMMddHHmmss"))

(def ^:private inference-suffix "_schema_inference.edn")

(def ^:private schema-file "schema.edn")

(def ^:private migrations-path "resources/migrations/")

(def ^:private migrana-schema
  [{:db/ident :migrana/migration
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "A unique identifier for the migration currently applied to the DB."}
   {:db/ident :migrana/timestamp
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "The timestamp identifier of the particular migration applied to the DB."}
   {:db/ident :migrana/schema
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "The schema (stringified edn) of the particular migration applied to the DB."}])

(defn ^:private ensure-migrana-transactions
  [conn]
  @(datomic/transact conn migrana-schema)
  conn)

(defn ^:private default-uri
  [uri]
  (or uri (environ/env :datomic-uri)))

(defn ^:private connect-transactor
  [uri]
  (datomic/connect uri))

(defn ^:private ensure-db-exists
  [uri]
  (datomic/create-database uri)
  uri)

(defn ^:private transact-left-behind-changes
  [conn txs]
  (doseq [tx txs]
    (let [payload (concat (:tx-data tx)
                          [{:migrana/migration :current
                            :migrana/timestamp (:timestamp tx)
                            :migrana/schema (prn-str (:schema tx))}])]
      (println "* Transacting" (:timestamp tx))
      @(datomic/transact conn payload))))

(defn ^:private new-time-stamp
  []
  (format/unparse custom-formatter
                  (local/local-now)))

(defn ^:private current-db-info
  [conn]
  (datomic/pull (datomic/db conn)
                [:migrana/timestamp :migrana/schema]
                [:migrana/migration :current]))

(defn ^:private build-new-inference
  [conn]
  (let [{:keys [migrana/timestamp migrana/schema]} (current-db-info conn)
        schema-on-disk (-> schema-file io/resource slurp edn/read-string)
        diff (data/diff (set schema-on-disk) (set (edn/read-string schema)))
        gap-on-disk (vec (first diff))]
    (if (> (count gap-on-disk) 0) 
      (let [new-ts (new-time-stamp)
            migration-name (str migrations-path new-ts inference-suffix)]
        (println "* Schema changes detected")
        (.mkdir (io/file migrations-path))
        (spit migration-name
              (with-out-str
                (pprint/pprint {:tx-data gap-on-disk
                                :schema schema-on-disk})))
        (println "* New migration created" new-ts "with" (count gap-on-disk) "changes")
        true)
      false)))

(defn ^:private file->ts
  [file]
  (subs (.getName file) 0 14))

(defn ^:private migration-files
  []
  (sort
   #(compare (.getName %1)
             (.getName %2))
   (.listFiles (io/file migrations-path))))

(defn ^:private evaluate-tx-fn
  [conn tx-fn]
  (println "* Evaluating" tx-fn)
  (try (require (symbol (namespace tx-fn)))
       ((resolve tx-fn) conn)
       (catch Throwable t
         (throw (Throwable. (str "Exception evaluating " tx-fn ": " t))))))

(defn ^:private evaluate-and-concat
  [c conn tx-fn]
  (concat c (evaluate-tx-fn conn tx-fn)))

(defn ^:private flatten-tx-data
  [conn m]
  (cond-> (or (:tx-data m) [])
    (:tx-fn m) (evaluate-and-concat conn (:tx-fn m))))

(defn ^:private process-migration-file
  [conn file processed-prior]
  (let [m (-> file slurp edn/read-string)]
    {:tx-data (flatten-tx-data conn m)
     :timestamp (file->ts file)
     :schema (or (:schema m)
                 (:schema processed-prior))}))

(defn ^:private migrations-seq
  ([conn file rest-files]
   (migrations-seq conn file rest-files nil))
  ([conn file rest-files processed-prior]
   (if file
     (let [current-file (process-migration-file conn file processed-prior)]
       (lazy-seq (cons current-file
                       (migrations-seq conn
                                       (first rest-files)
                                       (rest rest-files)
                                       current-file)))))))

(defn ^:private reified-migrations
  [conn]
  (let [files (migration-files)]
    (migrations-seq conn (first files) (rest files))))

(defn ^:private filtered-reified-migrations
  [conn]
  (let [{:keys [migrana/timestamp]} (current-db-info conn)]
    (filter #(> (compare (:timestamp %) timestamp) 0)
            (reified-migrations conn))))

(defn ^:private transact-to-latest
  [conn]
  (let [left-behind-txs (filtered-reified-migrations conn)]
    (transact-left-behind-changes conn left-behind-txs)))

(defn run
  [uri]
  (println "* Connecting to" uri)
  (let [conn (-> uri
                 default-uri
                 ensure-db-exists
                 connect-transactor
                 ensure-migrana-transactions)
        {:keys [migrana/timestamp]} (current-db-info conn)]
    (println "* DB currently at" (or timestamp "N/A"))
    (transact-to-latest conn)
    (if (build-new-inference conn)
      (transact-to-latest conn))
    (println "* DB up-to-date!\n")))

(defn create
  [n]
  (let [new-ts (new-time-stamp)
        migration-name (str migrations-path new-ts "_"
                            (->snake_case_string n) ".edn")]
    (.mkdir (io/file migrations-path))
    (spit migration-name
          (with-out-str
            (pprint/pprint {:tx-data []})))
    (println "* New migration created" new-ts "\n")
    true))

(defn set-db
  [uri ts]
  (println "* Connecting to" uri)
  (let [conn (-> uri
                 default-uri
                 ensure-db-exists
                 connect-transactor
                 ensure-migrana-transactions)
        {:keys [migrana/timestamp]} (current-db-info conn)]
    (println "* DB currently at" (or timestamp "N/A"))
    @(datomic/transact conn [{:migrana/migration :current
                              :migrana/timestamp ts}])
    (println "* DB set to" ts "\n")))

#_(create "add-2-to-all-names")

#_(set-db "datomic:dev://localhost:4334/migrana-test8" "2017")

#_(run "datomic:dev://localhost:4334/migrana-test8")

#_(set-db "datomic:dev://localhost:4334/migrana-test8" "20171208161455")
