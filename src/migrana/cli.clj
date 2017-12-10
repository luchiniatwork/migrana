(ns migrana.cli
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as string]
            [migrana.core :as core]))

(def ^:private cli-options
  [["-s" "--schema SCHEMA_FILE" "Schema file (default resources/schema.edn)"
    :id :schema]
   ["-m" "--migrations MIGRATIONS_PATH" "Migrations path (defatul resources/migrations/)"
    :id :migrations]
   [nil "--no-inference" "Runs with no schema change inference"
    :id :no-inference
    :default false]])

(defn ^:private print-help
  [summary]
  (println "Syntax: lein migrana <command> <options>")
  (println "")
  (println "Available commands:")
  (println "")
  (println "  apply <uri>               Transacts pending migrations onto database at <uri>")
  (println "  info <uri>                Shows current database information")
  (println "  create <name>             Creates new manual migration called <name>")
  (println "  dry-run <uri>             Simulates what `apply` would do")
  (println "  set-db <uri> <timestamp>  Sets the database at <uri> with <timestamp>")

  (println "")
  (println "Options for `apply`, `dry-run`, and `set-db` commands:")
  (println "")
  (println summary)
  (println ""))

(defn -main
  [& args]
  (println "Migrana 0.1.0\n")
  (try
    (let [{:keys [options arguments summary]} (parse-opts args cli-options)
          command (string/lower-case (or (first arguments) ""))]
      (cond
        (= "apply" command) (core/apply-run (second arguments))
        (= "info" command) (core/info (second arguments))
        (= "create" command) (core/create (second arguments))
        (= "dry-run" command) (core/dry-run (second arguments))
        (= "set-db" command) (core/set-db (second arguments) (nth arguments 2))
        :else (print-help summary)))
    (catch Throwable t
      (println t)
      (System/exit 1)))
  (System/exit 0))
