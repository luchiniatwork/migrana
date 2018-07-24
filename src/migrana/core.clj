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
  "Returns a new client out of a cfg or the very client passed through it.
  Receives a map with Datomic's :cfg or Datomic's :client."
  [{:keys [cfg client]}]
  (if client
    client
    (d/client cfg)))

(defn ^:private ensure-migrana-transactions
  "Transacts the minimum required migrana schema."
  [conn]
  (println "... ensuring DB is migrana-ready")
  (d/transact conn {:tx-data migrana-schema})
  conn)

(defn ^:private connect-transactor
  "Connects to db-name on client."
  [client db-name]
  (d/connect client {:db-name db-name}))

(defn ^:private ensure-db-exists
  "Creates DB and returns the client regardless of the existence of the
  DB."
  [client db-name]
  (println "=> Basic ensurances:")
  (println "... ensuring DB exists")
  (d/create-database client {:db-name db-name})
  client)

(defn ^:private connect
  "Main connection function. Receives a map with either a Datomic
  :client or Datomic :cfg, and a :db-name to connect to."
  [{:keys [client cfg db-name] :as opts}]
  (println "=> Connection:")
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
;; Migration transaction functions
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
  "Transacts each tx in the txs seq with a migrana meta payload. Each tx
  is a map with the txs in a :tx-data node, a :timestamp node and a
  schema snapshot at :schema-snapshot."
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema and timestamp meta functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private new-time-stamp
  "Returns a new timestamp based on local time."
  []
  (format/unparse custom-formatter
                  (local/local-now)))

(defn ^:private current-db-info
  "Returns a map with the :migrana/timestamp and :migrana/schema of the
  DB as of now."
  [conn]
  (d/pull (d/db conn)
          [:migrana/timestamp :migrana/schema]
          [:migrana/migration :current]))

(defn ^:private db-ident-flattener
  "Returns a fn that flattens the specified Datomic ident. This is used
  because Datomic's internal schema returns a lot more metadata than
  needs to be exposed back to users."
  [ident]
  #(if (-> % ident :db/ident)
     (update % ident :db/ident)
     %))

(defn ^:private is-user-schema?
  "Returns true if the provided schema map entry is provided by the user or not."
  [m]
  (not (contains? #{"db" "migrana" "fressian"
                    "db.excise" "db.alter" "db.install"}
                  (-> m
                      :db/ident
                      namespace))))

(defn ^:private get-schema
  "Returns the user-specified schema presently in the DB."
  [conn]
  (->> (d/pull (d/db conn) '{:eid 0 :selector [{:db.install/attribute [*]}]})
       :db.install/attribute
       (filter is-user-schema?)
       (map #(dissoc % :db/id))
       (map (db-ident-flattener :db/valueType))
       (map (db-ident-flattener :db/cardinality))
       (map (db-ident-flattener :db/unique))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Migration parsing functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private file->ts
  "Returns the timestamp of the specified migration file."
  [file]
  (subs (.getName file) 0 14))

(defn ^:private migration-files
  "Returns a sorted seq with all the migration files in chronological
  order."
  [migrations-path]
  (sort
   #(compare (.getName %1)
             (.getName %2))
   (or (->> migrations-path
            io/file
            file-seq
            (filter #(.isFile ^java.io.File %))
            (filter #(string/ends-with? (.getPath ^java.io.File %)
                                        ".edn")))
       [])))

(defn ^:private pre-process-files
  "Returns a seq with maps of all the migration transactions in the
  provided files."
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
  "Returns the migrations that still need to be applied to the DB.
  Receives a map with connection details and a :migrations-path"
  [conn {:keys [migrations-path]}]
  (let [{:keys [migrana/timestamp]} (current-db-info conn)]
    (->> migrations-path
         migration-files
         (filter #(> (compare (file->ts %) timestamp)))
         pre-process-files)))

(defn ^:private transact-to-latest
  "Transacts the DB to the latest state"
  [conn opts]
  (let [pre-processed-migrations (pre-process-migrations conn opts)]
    (transact-left-behind-changes
     conn
     pre-processed-migrations)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public facing functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn info
  "Simply returns the version of the DB or 'N/A'. Receives a map with
  either Datomic's :cfg or Datomic :client and a :db-name. Will create
  DB if one doesn't exist."
  [opts]
  (let [conn (connect opts)
        {:keys [migrana/timestamp]} (current-db-info conn)
        ts (or timestamp "N/A")]
    (println "=> DB is currently at" ts "\n")
    ts))

(defn create
  "Creates a migration named :name in the specified :migrations-path.
  Besides those two entries, the map also needs either Datomic's :cfg
  or Datomic :client and a :db-name. Will create DB if one doesn't
  exist."
  [{:keys [name migrations-path] :as opts}]
  (.mkdirs (io/file migrations-path))
  (let [conn (connect opts)
        new-ts (new-time-stamp)
        migration (io/file migrations-path
                           (str new-ts "_"
                                (->snake_case_string name) ".edn"))
        snapshot (get-schema conn)] 
    (println "=> Getting schema snapshot at" new-ts)
    (spit migration
          (with-out-str
            (pprint/pprint {:tx-data []
                            :schema-snapshot snapshot})))
    (println "=> Migration file created:" (.getPath migration) "\n")
    (println "Edit this file with your migrations. Put them in the :tx-data vector.\n")
    (.getPath migration)))

(defn set-db
  "Sets the DB timestamp forcefully to :timestamp. Besides this,
  the map also needs either Datomic's :cfg or Datomic :client and
  a :db-name. Will create DB if one doesn't exist."
  [{:keys [timestamp] :as opts}]
  (let [conn (connect opts)
        {:keys [migrana/timestamp]} (current-db-info conn)
        ts (or timestamp "N/A")]
    (println "=> DB is currently at" ts)
    (d/transact conn {:tx-data [{:migrana/migration :current
                                 :migrana/timestamp ts}]})
    (println "=> DB now forcefully set to" timestamp "\n")
    (println "You can now attemp an ensure-db again.\n")
    true))

(defn ensure-db
  "Ensures DB is in operational mode by running all migrations and
  applying the provided :schema. Besides this, the map also needs
  either Datomic's :cfg or Datomic :clien, a :db-name, and a
  :migrations-path. Will create DB if one doesn't exist."
  [{:keys [db-name schema] :as opts}]
  (let [conn (connect opts)
        {:keys [migrana/timestamp]} (current-db-info conn)]
    (println "=> DB is currently at" (or timestamp "N/A"))
    ;;TODO: surround with try with instructions to create migration
    (transact-to-latest conn opts)
    (println "=> Transacing latest schema")
    (d/transact conn {:tx-data schema})
    (println "=> DB is up-to-date\n")
    (println "Your DB is ready for use.\n")
    true))
