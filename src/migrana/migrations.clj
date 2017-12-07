(ns migrana.migrations
  (:require [datomic.api :as d]))

(defn test-fn [conn]
  [{:person/name "Tiago"}])

(defn add-2-to-all-names [conn]
  (let [names (d/q '[:find (pull ?e [:db/id :person/name])
                     :where
                     [?e :person/name]]
                   (d/db conn))]
    (map (fn [n] {:db/id (:db/id (first n))
                  :person/name (str (:person/name (first n)) "2")})
         names)))
