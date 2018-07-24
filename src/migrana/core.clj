(ns migrana.core
  (:require [camel-snake-kebab.core :refer [->snake_case_string]]
            [clj-time.core :as time]
            [clj-time.format :as format]
            [clj-time.local :as local]
            [clojure.data :as data]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [datomic.client.api :as d]))

(def ^:private custom-formatter (format/formatter "yyyyMMddHHmmss"))

#_(def ^:private inference-suffix "_schema_inference.edn")

#_(def ^:private schema-path "resources/schema.edn")

#_(def ^:private migrations-path "resources/migrations/")

(def ^:private migrana-schema
  [{:db/ident :migrana/migration
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "A unique identifier for the migration currently applied to the DB."}
   {:db/ident :migrana/timestamp
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "The timestamp identifier of the particular migration applied to the DB."}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Connecting functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private get-client
  [{:keys [cfg client]}]
  (if client
    client
    (d/client cfg)))

(defn ^:private ensure-migrana-transactions
  "Transacts the minimum required migrana schema"
  [conn]
  (println "... ensuring DB is migrana-ready")
  (d/transact conn {:tx-data migrana-schema})
  conn)

(defn ^:private connect-transactor
  [client db-name]
  (d/connect client {:db-name db-name}))

(defn ^:private ensure-db-exists
  "Creates DB and returns the client regardless of the existence of the DB"
  [client db-name]
  (println "=> Basic ensurances")
  (println "... ensuring DB exists")
  (d/create-database client {:db-name db-name})
  client)

(defn ^:private connect
  [{:keys [client cfg db-name] :as opts}]
  (println "=> Connecting")
  (if-not (or client cfg) (throw (Throwable. "Must have :cfg or :client to connect to")))
  (if-not db-name (throw (Throwable. "Must have :db-name to connect to")))
  (if client
    (println "... using provided client")
    (println "... using cfg, system:" (:system cfg)))
  (println "... connecting to DB:" db-name)
  (-> opts
      get-client
      (ensure-db-exists db-name)
      (connect-transactor db-name)
      ensure-migrana-transactions))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transactions for migrations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
    (println "=> Processing migration" (:timestamp tx))
    (let [tx-snapshot (:schema-snapshot tx)
          tx-payload (concat (flatten-tx-data conn tx)
                             [{:migrana/migration :current
                               :migrana/timestamp (:timestamp tx)}])]
      (println "... equalizing schema snapshot at that point")
      (d/transact conn {:tx-data tx-snapshot})
      (println "... transacting migration itself")
      (d/transact conn {:tx-data tx-payload})))
  txs)

#_(defn ^:private print-left-behind-changes
    [txs]
    (doseq [tx txs]
      (println "=> Would transact" (:timestamp tx))
      (if (:tx-fn tx) (println "... would evaluate" (:tx-fn tx) "for" ))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema and timestamp meta functions 
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private new-time-stamp
  "Returns a new time stamp based on local time"
  []
  (format/unparse custom-formatter
                  (local/local-now)))

(defn ^:private current-db-info
  "Returns a map with the :migrana/timestamp and :migrana/schema of the DB as of now"
  [conn]
  (d/pull (d/db conn)
          [:migrana/timestamp :migrana/schema]
          [:migrana/migration :current]))

(defn ^:private db-ident-flattener [ident]
  #(if (-> % ident :db/ident)
     (update % ident :db/ident)
     %))

(defn ^:private is-user-schema? [m]
  (not (contains? #{"db" "fressian" "db.excise" "db.alter" "db.install"}
                  (-> m
                      :db/ident
                      namespace))))

(defn ^:private get-schema
  [conn]
  (->> (d/pull (d/db conn) '{:eid 0 :selector [{:db.install/attribute [*]}]})
       :db.install/attribute
       (filter is-user-schema?)
       (map #(dissoc % :db/id))
       (map (db-ident-flattener :db/valueType))
       (map (db-ident-flattener :db/cardinality))
       (map (db-ident-flattener :db/unique))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(defn ^:private build-new-inference
    "Compares the schema in the DB and on disk and creates an inferred migration file if there
  are differences."
    [conn]
    (let [{:keys [migrana/timestamp migrana/schema]} (current-db-info conn)
          schema-on-disk (-> schema-path slurp edn/read-string)
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

#_(defn ^:private dryrun-new-inference
    [last-tx]
    (let [{:keys [timestamp schema]} last-tx
          schema-on-disk (-> schema-path slurp edn/read-string)
          diff (data/diff (set schema-on-disk) (set schema))
          gap-on-disk (vec (first diff))]
      (if (> (count gap-on-disk) 0)
        (do
          (println "=> Schema changes detected")
          (println "=> Would create new inferred migration with" (count gap-on-disk) "changes")
          true)
        false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Migration functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private file->ts
  "Returns the timestamp of a migration file"
  [file]
  (subs (.getName file) 0 14))

(defn ^:private migration-files
  "Returns seq with all the migration files in chronological order"
  [migrations-path]
  (sort
   #(compare (.getName %1)
             (.getName %2))
   (->> migrations-path
        io/file
        file-seq
        (filter #(.isFile ^java.io.File %))
        (filter #(string/ends-with? (.getPath ^java.io.File %)
                                    ".edn")))))

(defn ^:private pre-process-files
  [files]
  (reduce (fn [c file]
            (let [m (-> file slurp edn/read-string)]
              (conj c {:timestamp (file->ts file)
                       :tx-data (or (:tx-data m) [])
                       :tx-fn (:tx-fn m)
                       :schema-snapshot (or (:schema-snapshot m) [])})))
          []
          files))

(defn ^:private pre-process-migrations
  "Returns the migrations that still need to be applied to the DB"
  [conn {:keys [migrations-path]}]
  (let [{:keys [migrana/timestamp]} (current-db-info conn)
        pre-processed-migrations (-> migrations-path
                                     migration-files
                                     pre-process-files)]
    (filter #(> (compare (:timestamp %) timestamp) 0)
            pre-processed-migrations)))

(defn ^:private transact-to-latest
  "Transacts the DB to the latest state"
  [conn opts]
  (let [pre-processed-migrations (pre-process-migrations conn opts)]
    (transact-left-behind-changes
     conn
     pre-processed-migrations)))

#_(defn run
    "Connect to the DB, fast forwards it to the latest state in disk, infers new schema
  changes, creates extra migration if needed, and then fast forward to this new state"
    [cfg db-name]
    (let [conn (base-cfg-connect cfg db-name)
          {:keys [migrana/timestamp]} (current-db-info conn)]
      (println "=> DB is currently at" (or timestamp "N/A"))
      (transact-to-latest conn)
      (if (build-new-inference conn)
        (transact-to-latest conn))
      (println "=> DB is up-to-date!\n")))

#_(defn info
    "Simply prints the version of the DB"
    [cfg db-name]
    (let [conn (base-cfg-connect cfg db-name)
          {:keys [migrana/timestamp]} (current-db-info conn)]
      (println "=> DB is currently at" (or timestamp "N/A") "\n")))

#_(defn dry-run
    "Similar to apply-run but instead of applying the outstanding migrations it prints
  out what the migrations would do."
    [cfg db-name]
    (let [conn (base-cfg-connect cfg db-name)
          {:keys [migrana/timestamp]} (current-db-info conn)]
      (println "=> DB is currently at" (or timestamp "N/A"))
      (let [last-tx (:last-migration (transact-to-latest conn :dryrun true))
            would-infer? (dryrun-new-inference last-tx)]
        (println "=> Last known migration at" (or (:timestamp last-tx) "N/A"))
        (if would-infer?
          (println "=> Would transact inferred schema changes"))
        (if (and
             (= timestamp (:timestamp last-tx))
             (not (nil? (:timestamp last-tx)))
             (not (nil? timestamp))
             (not would-infer?))
          (println "=> DB is up-to-date!\n")
          (println "=> DB is behind!\n")))))

(defn create
  "Creates a migration named n"
  [{:keys [name migrations-path] :as opts}]
  (let [conn (connect opts)
        new-ts (new-time-stamp)
        migration-name (str migrations-path new-ts "_"
                            (->snake_case_string name) ".edn")]
    (.mkdirs (io/file migrations-path))
    (spit migration-name
          (with-out-str
            (pprint/pprint {:tx-data []
                            :schema-snapshot (get-schema conn)})))
    (println "=> Migration created" new-ts "at" migration-name "\n")
    true))

#_(defn set-db
    "Sets DB timestamp forcefully to ts"
    [cfg db-name ts]
    (let [conn (base-cfg-connect cfg db-name)
          {:keys [migrana/timestamp]} (current-db-info conn)]
      (println "=> DB is currently at" (or timestamp "N/A"))
      (d/transact conn {:tx-data [{:migrana/migration :current
                                   :migrana/timestamp ts}]})
      (println "=> DB now set to" ts "\n")))

(defn ensure-db
  [{:keys [db-name schema] :as opts}]
  (let [conn (connect opts)
        {:keys [migrana/timestamp]} (current-db-info conn)]
    (println "=> DB is currently at" (or timestamp "N/A"))
    ;;TODO: 1) load migrations,
    ;;2) if there are, enter loop,
    ;;3) apply snapshot, then migration transaction
    ;;4) then schema
    ;;Attention: surround with try with instructions to create migration
    (transact-to-latest conn opts)
    #_(if (build-new-inference conn)
        (transact-to-latest conn))
    (println "=> Transacing latest schema")
    (d/transact conn {:tx-data schema})
    (println "=> DB is up-to-date!\n")
    true))
