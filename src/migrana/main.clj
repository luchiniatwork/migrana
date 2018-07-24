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

(s/def ::op #{'info 'create 'dry-run 'run 'set-db})
(s/def ::name string?)
(s/def ::db-name string?)

;;FIXME: regex here
(s/def ::timestamp string?)

(defmulti operation :op)
(defmethod operation 'info [_]
  (s/keys :req-un [::op :migrana-shared/cfg ::db-name]))
(defmethod operation 'create [_]
  (s/keys :req-un [::op ::name]))
(defmethod operation 'dry-run [_]
  (s/keys :req-un [::op :migrana-shared/cfg ::db-name]))
(defmethod operation 'run [_]
  (s/keys :req-un [::op :migrana-shared/cfg ::db-name]))
(defmethod operation 'set-db [_]
  (s/keys :req-un [::op :migrana-shared/cfg ::db-name ::timestamp]))

(s/def ::payload (s/and #(not (nil? %))
                        #(s/valid? ::op (:op %))
                        (s/multi-spec operation ::op)))

(expound/defmsg
  ::payload
  "required EDN map with key :op being one of info, create, dry-run, run, or set-db")

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

(defn ^:private parse-cfg [{:keys [cfg] :as payload}]
  (if (string? cfg)
    (assoc payload :cfg (-> cfg io/file slurp edn/read-string))
    payload))

(defmulti process (fn [{:keys [op]}] op))

(defmethod process 'info [{:keys [cfg db-name]}]
  (core/info cfg db-name))

(defmethod process 'create [{:keys [name]}]
  (core/create name))

(defmethod process 'dry-run [{:keys [cfg db-name]}]
  (core/dry-run cfg db-name))

(defmethod process 'run [{:keys [cfg db-name]}]
  (core/run cfg db-name))

(defmethod process 'set-db [{:keys [cfg db-name timestamp]}]
  (core/set-db cfg db-name timestamp))


(defn -main
  [& args]
  (let [payload (->> args (string/join " ") edn/read-string parse-cfg)
        [valid? msg] (check-payload payload)]
    (if valid?
      (do (process (-> payload parse-cfg))
          (exit 0))
      (error msg))))
