(ns metabase.driver.generic-sql.util
  "Shared functions for our generic-sql query processor."
  (:require [clojure.core.memoize :as memo]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [colorize.core :as color]
            [korma.core :as korma]
            [korma.db :as kdb]
            [metabase.db :refer [sel]]
            [metabase.driver :as driver]
            [metabase.driver.context :as context]
            (metabase.models [database :refer [Database]]
                             [field :refer [Field]]
                             [table :refer [Table]])))

;; Cache the Korma DB connections for a given Database for 60 seconds instead of creating new ones every single time
(defn- db->connection-spec [database]
  (let [driver                              (driver/engine->driver (:engine database))
        database->connection-details        (:database->connection-details driver)
        connection-details->connection-spec (:connection-details->connection-spec driver)]
    (-> database database->connection-details connection-details->connection-spec)))

(def ^{:arglists '([database])} db->korma-db
  "Return a Korma database definition for DATABASE.
   This does a little bit of smart caching (for 60 seconds) to avoid creating new connections when unneeded."
  (let [-db->korma-db (memo/ttl (fn [database]
                                  (log/debug (color/red "Creating a new DB connection..."))
                                  (kdb/create-db (db->connection-spec database)))
                                :ttl/threshold (* 60 1000))]
    ;; only :engine and :details are needed for driver/connection so just pass those so memoization works as expected
    (fn [database]
      (-db->korma-db (select-keys database [:engine :details])))))

#_(defn db->korma-db [database]
    (kdb/create-db (db->connection-spec database)))

(def ^:dynamic ^java.sql.DatabaseMetaData *jdbc-metadata*
  "JDBC metadata object for a database. This is set by `with-jdbc-metadata`."
  nil)

(defn -with-jdbc-metadata
  "Internal implementation. Don't use this directly; use `with-jdbc-metadata`."
  [database f]
  (if *jdbc-metadata* (f *jdbc-metadata*)
                      (jdbc/with-db-metadata [md (db->connection-spec database)]
                        (binding [*jdbc-metadata* md]
                          (f *jdbc-metadata*)))))

(defmacro with-jdbc-metadata
  "Execute BODY with the jdbc metadata for DATABASE bound to BINDING.
   This will reuse `*jdbc-metadata*` if it's already set (to avoid opening extra connections).
   Otherwise it will open a new metadata connection and bind `*jdbc-metadata*` so it can be reused by subsequent calls to `with-jdbc-metadata` within BODY.

    (with-jdbc-metadata [^java.sql.DatabaseMetaData md (sel :one Database :id 1)] ; (1)
      (-> (.getPrimaryKeys md nil nil nil)
          jdbc/result-set-seq                                                     ; (2)
          doall))                                                                 ; (3)

   NOTES

   1.  You should tag BINDING to avoid reflection.
   2.  Use `jdbc/result-set-seq` to convert JDBC `ResultSet` into something we can use in Clojure
   3.  Make sure to realize the lazy sequence within the BODY before connection is closed."
  [[binding database] & body]
  {:pre [(symbol? binding)]}
  `(-with-jdbc-metadata ~database
     (fn [~binding]
       ~@body)))

(defn korma-entity
  "Return a Korma entity for TABLE.

    (-> (sel :one Table :id 100)
        korma-entity
        (select (aggregate (count :*) :count)))"
  [{:keys [name db] :as table}]
  {:pre [(delay? db)]}
  {:table name
   :pk    :id
   :db    (db->korma-db @db)})

(defn table-id->korma-entity
  "Lookup `Table` with TABLE-ID and return a korma entity that can be used in a korma form."
  [table-id]
  {:pre  [(integer? table-id)]
   :post [(map? %)]}
  (korma-entity (or (and (= (:id context/*table*) table-id)
                         context/*table*)
                    (sel :one Table :id table-id)
                    (throw (Exception. (format "Table with ID %d doesn't exist!" table-id))))))


(defn castify-field
  "Wrap Field in a SQL `CAST` statement if needed (i.e., it's a `:DateTimeField`).

    (castify :name :TextField)     -> :name
    (castify :date :DateTimeField) -> (raw \"CAST(\"date\" AS DATE)"
  [field-name field-base-type]
  {:pre [(string? field-name)
         (keyword? field-base-type)]}
  ;; do we need to cast DateFields ? or just DateTimeFields ?
  (if (contains? #{:DateField :DateTimeField} field-base-type) `(korma/raw ~(format "CAST(\"%s\" AS DATE)" field-name))
      (keyword field-name)))

(def field-id->kw
  "Given a metabase `Field` ID, return a keyword for use in the Korma form (or a casted raw string for date fields)."
  (memoize                         ; This can be memozied since the names and base_types of Fields never change
   (fn [field-id]                   ; *  if a field is renamed the old field will just be marked as `inactive` and a new Field will be created
     {:pre [(integer? field-id)]}  ; *  if a field's type *actually* changes we have no logic in driver.generic-sql.sync to handle that case any way (TODO - fix issue?)
     (if-let [{field-name :name, field-type :base_type} (sel :one [Field :name :base_type] :id field-id)]
       (castify-field field-name field-type)
       (throw (Exception. (format "Field with ID %d doesn't exist!" field-id)))))))

(def date-field-id?
  "Does FIELD-ID correspond to a field that is a Date?"
  (memoize        ; memoize since the base_type of a Field isn't going to change
   (fn [field-id]
     (contains? #{:DateField :DateTimeField}
                (sel :one :field [Field :base_type] :id field-id)))))
