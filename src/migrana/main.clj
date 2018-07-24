(ns migrana.main
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [environ.core :as environ]
            [expound.alpha :as expound]
            [migrana.core :as core]
            [migrana.shared-specs]))

(s/def ::op #{'info-db 'ensure-db 'set-db 'create-migration})
(s/def ::migration-name string?)
(s/def ::migrations-path string?)
(s/def ::schema-file string?)

(defmulti operation :op)
(defmethod operation 'info-db [_]
  (s/keys :req-un [::op :migrana-shared/cfg ::db-name]))
(defmethod operation 'ensure-db [_]
  (s/keys :req-un [::op :migrana-shared/cfg ::db-name
                   ::schema-file ::migrations-path]))
(defmethod operation 'set-db [_]
  (s/keys :req-un [::op :migrana-shared/cfg ::db-name ::timestamp]))
(defmethod operation 'create-migration [_]
  (s/keys :req-un [::op ::migration-name ::migrations-path]))

(s/def ::payload (s/and #(not (nil? %))
                        #(s/valid? ::op (:op %))
                        (s/multi-spec operation ::op)))

(expound/defmsg
  ::payload
  "required EDN map with key :op being one of info-db, ensure-db, set-db, or create-migration")

(def ^:dynamic *in-repl* false)

(defn ^:private exit [code]
  (if-not *in-repl*
    (System/exit (or code 0))))

(defn ^:private abort [msg]
  (println msg)
  (exit 0))

(defn ^:private error [msg]
  (println msg)
  (exit 1))

(defn ^:private check-payload [payload]
  (if-not (s/valid? ::payload payload)
    [false (expound/expound-str ::payload payload)]
    [true payload]))

(defn ^:private ensure-map [file-path-or-map]
  (if (string? file-path-or-map)
    (-> file-path-or-map io/file slurp edn/read-string)
    file-path-or-map))

(defn ^:private parse-opts
  [{:keys [cfg db-name migration-name
           migrations-path schema-file
           timestamp]}]
  (cond-> {}
    cfg (assoc :cfg (ensure-map cfg))
    db-name (assoc :db-name db-name)
    migration-name (assoc :migration-name migration-name)
    migrations-path (assoc :migrations-path migrations-path)
    schema-file (assoc :schema (ensure-map schema-file))
    timestamp (assoc :timestamp timestamp)))

(defmulti process (fn [{:keys [op]}] op))

(defmethod process 'info-db [opts]
  (core/info-db (parse-opts opts)))

(defmethod process 'ensure-db [opts]
  (core/ensure-db (parse-opts opts)))

(defmethod process 'set-db [opts]
  (core/set-db (parse-opts opts)))

(defmethod process 'create-migration [opts]
  (core/create-migration (parse-opts opts)))

(defn -main
  [& args]
  (let [payload (->> args (string/join " ") edn/read-string)
        [valid? msg] (check-payload payload)]
    (if valid? 
      (do (process payload)
          (exit 0))
      (error msg))))
