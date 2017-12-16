(ns migrana.core
  (:require [camel-snake-kebab.core :refer [->snake_case_string]]
            [clj-time.core :as time]
            [clj-time.format :as format]
            [clj-time.local :as local]
            [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [datomic.api :as datomic]))

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
  "Transacts the minimum required migrana schema"
  [conn]
  @(datomic/transact conn migrana-schema)
  conn)

(defn ^:private connect-transactor
  "Uri to connectation"
  [uri]
  (datomic/connect uri))

(defn ^:private ensure-db-exists
  "Creates DB and returns the uri either the DB exists or not"
  [uri]
  (datomic/create-database uri)
  uri)

(defn ^:private evaluate-tx-fn
  "Evaluates tx-fn and calls it passing conn to it"
  [conn tx-fn]
  (println "... evaluating" tx-fn)
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

(defn ^:private transact-left-behind-changes
  "Transacts each tx in the txs seq with a migrana meta payload. Each tx is a map with
  the txs in a :tx-data node, a :timestamp node and a full :schema node."
  [conn txs]
  (doseq [tx txs]
    (println "=> Transacting" (:timestamp tx))
    (let [payload (concat (flatten-tx-data conn tx)
                          [{:migrana/migration :current
                            :migrana/timestamp (:timestamp tx)
                            :migrana/schema (prn-str (:schema tx))}])] 
      @(datomic/transact conn payload))))

(defn ^:private print-left-behind-changes
  [txs]
  (doseq [tx txs]
    (println "=> Would transact" (:timestamp tx))
    (if (:tx-fn tx) (println "... would evaluate" (:tx-fn tx) "for" ))))

(defn ^:private new-time-stamp
  "Returns a new time stamp based on local time"
  []
  (format/unparse custom-formatter
                  (local/local-now)))

(defn ^:private current-db-info
  "Returns a map with the :migrana/timestamp and :migrana/schema of the DB as of now"
  [conn]
  (datomic/pull (datomic/db conn)
                [:migrana/timestamp :migrana/schema]
                [:migrana/migration :current]))

(defn ^:private build-new-inference
  "Compares the schema in the DB and on disk and creates an inferred migration file if there
  are differences."
  [conn]
  (let [{:keys [migrana/timestamp migrana/schema]} (current-db-info conn)
        schema-on-disk (-> schema-file io/resource slurp edn/read-string)
        diff (data/diff (set schema-on-disk) (set (edn/read-string schema)))
        gap-on-disk (vec (first diff))]
    (if (> (count gap-on-disk) 0) 
      (let [new-ts (new-time-stamp)
            migration-name (str migrations-path new-ts inference-suffix)]
        (println "=> Schema changes detected")
        (.mkdir (io/file migrations-path))
        (spit migration-name
              (with-out-str
                (pprint/pprint {:tx-data gap-on-disk
                                :schema schema-on-disk})))
        (println "=> New inferred migration created" new-ts "with" (count gap-on-disk) "changes")
        true)
      false)))

(defn ^:private dryrun-new-inference
  [last-tx]
  (let [{:keys [timestamp schema]} last-tx
        schema-on-disk (-> schema-file io/resource slurp edn/read-string)
        diff (data/diff (set schema-on-disk) (set schema))
        gap-on-disk (vec (first diff))]
    (if (> (count gap-on-disk) 0)
      (do
        (println "=> Schema changes detected")
        (println "=> Would create new inferred migration with" (count gap-on-disk) "changes")
        true)
      false)))

(defn ^:private file->ts
  "Returns the timestamp of a migration file"
  [file]
  (subs (.getName file) 0 14))

(defn ^:private migration-files
  "Returns seq with all the migration files in chronological order"
  []
  (sort
   #(compare (.getName %1)
             (.getName %2))
   (.listFiles (io/file migrations-path))))

(defn ^:private pre-process-files
  [files]
  (reduce (fn [c file]
            (let [m (-> file slurp edn/read-string)]
              (conj c {:timestamp (file->ts file)
                       :tx-data (or (:tx-data m) [])
                       :tx-fn (:tx-fn m)
                       :schema (or (:schema m)
                                   (:schema (last c))
                                   [])})))
          []
          files))

(defn ^:private pre-process-migrations
  "Returns the migrations that still need to be applied to the DB"
  [conn]
  (let [{:keys [migrana/timestamp]} (current-db-info conn)
        pre-processed-migrations (pre-process-files (migration-files))]
    {:filtered-migrations (filter #(> (compare (:timestamp %) timestamp) 0)
                                  pre-processed-migrations)
     :last-migration (last pre-processed-migrations)}))

(defn ^:private transact-to-latest
  "Transacts the DB to the latest state"
  [conn & args]
  (let [{:keys [dryrun]} (apply hash-map args)
        pre-processed-migrations (pre-process-migrations conn)
        left-behind-txs (:filtered-migrations pre-processed-migrations)]
    (if dryrun
      (print-left-behind-changes left-behind-txs)
      (transact-left-behind-changes conn left-behind-txs))
    pre-processed-migrations))

(defn ^:private base-uri-connect
  "Connects to URI, makes sure the DB exists and ensures bsasic migrana
  schema is in place"
  [uri]
  (if-not uri (throw (Throwable. "Must have URI to connect to")))
  (println "=> Connecting to" uri)
  (-> uri
      ensure-db-exists
      connect-transactor
      ensure-migrana-transactions))

(defn run
  "Connect to the DB, fast forwards it to the latest state in disk, infers new schema
  changes, creates extra migration if needed, and then fast forward to this new state"
  [uri]
  (let [conn (base-uri-connect uri)
        {:keys [migrana/timestamp]} (current-db-info conn)]
    (println "=> DB is currently at" (or timestamp "N/A"))
    (transact-to-latest conn)
    (if (build-new-inference conn)
      (transact-to-latest conn))
    (datomic/release conn)
    (println "=> DB is up-to-date!\n")))

(defn info
  "Simply prints the version of the DB"
  [uri]
  (let [conn (base-uri-connect uri)
        {:keys [migrana/timestamp]} (current-db-info conn)]
    (println "=> DB is currently at" (or timestamp "N/A") "\n")
    (datomic/release conn)))

(defn dry-run
  "Similar to apply-run but instead of applying the outstanding migrations it prints
  out what the migrations would do."
  [uri]
  (let [conn (base-uri-connect uri)
        {:keys [migrana/timestamp]} (current-db-info conn)]
    (println "=> DB is currently at" (or timestamp "N/A"))
    (let [last-tx (:last-migration (transact-to-latest conn :dryrun true))]
      (println "=> Last known migration at" (:timestamp last-tx))
      (if (dryrun-new-inference last-tx)
        (println "=> Would transact inferred schema changes"))
      (if (= timestamp (:timestamp last-tx))
        (println "=> DB is up-to-date!\n")
        (println "=> DB is behind!\n")))
    (datomic/release conn)))

(defn create
  "Creates a migration named n"
  [n]
  (let [new-ts (new-time-stamp)
        migration-name (str migrations-path new-ts "_"
                            (->snake_case_string n) ".edn")]
    (.mkdir (io/file migrations-path))
    (spit migration-name
          (with-out-str
            (pprint/pprint {:tx-data []})))
    (println "=> Migration created" new-ts "at" migration-name "\n")
    true))

(defn set-db
  "Sets DB timestamp forcefully to ts"
  [uri ts]
  (let [conn (base-uri-connect uri)
        {:keys [migrana/timestamp]} (current-db-info conn)]
    (println "=> DB is currently at" (or timestamp "N/A"))
    @(datomic/transact conn [{:migrana/migration :current
                              :migrana/timestamp ts}])
    (datomic/release conn)
    (println "=> DB now set to" ts "\n")))
