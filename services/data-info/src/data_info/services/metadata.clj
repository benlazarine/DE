(ns data-info.services.metadata
  (:use [clojure-commons.error-codes]
        [clojure-commons.validators]
        [clj-jargon.item-ops :only [copy-stream input-stream]]
        [clj-jargon.metadata]
        [kameleon.uuids :only [uuidify]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.set :as s]
            [clojure-commons.file-utils :as ft]
            [cheshire.core :as json]
            [dire.core :refer [with-pre-hook! with-post-hook!]]
            [data-info.services.directory :as directory]
            [data-info.services.page-tabular :as csv]
            [data-info.services.stat :as stat]
            [data-info.services.uuids :as uuids]
            [data-info.util.config :as cfg]
            [data-info.util.irods :as irods]
            [data-info.util.paths :as paths]
            [data-info.util.validators :as validators]
            [metadata-client.core :as metadata]))


(defn- fix-unit
  "Used to replace the IPCRESERVED unit with an empty string."
  [avu]
  (if (= (:unit avu) paths/IPCRESERVED)
    (assoc avu :unit "")
    avu))

(def ^:private ipc-regex #"(?i)^ipc")

(defn- ipc-avu?
  "Returns a truthy value if the AVU map passed in is reserved for the DE's use."
  [avu]
  (re-find ipc-regex (:attr avu)))

(defn- authorized-avus
  "Validation to make sure the AVUs aren't system AVUs. Throws a slingshot error
   map if the validation fails."
  [avus]
  (when (some ipc-avu? avus)
    (throw+ {:error_code ERR_NOT_AUTHORIZED
             :avus avus})))

(defn- list-path-metadata
  "Returns the metadata for a path. Passes all AVUs to (fix-unit).
   AVUs with a unit matching IPCSYSTEM are filtered out."
  [cm path & {:keys [system] :or {system false}}]
  (let [fixed-metadata (map fix-unit (get-metadata cm (ft/rm-last-slash path)))]
  (if system
      fixed-metadata
      (remove ipc-avu? fixed-metadata))))

(defn- reserved-unit
  "Turns a blank unit into a reserved unit."
  [avu-map]
  (if (string/blank? (:unit avu-map))
    paths/IPCRESERVED
    (:unit avu-map)))

(defn- resolve-data-type
  "Returns a type converted from the type field of a stat result to a type expected by the
   metadata service endpoints."
  [type]
  (let [type (name type)]
    (if (= type "dir")
      "folder"
      type)))

(defn- get-readable-data-item
  [cm user data-id]
  (let [{:keys [path] :as data-item} (uuids/path-stat-for-uuid cm user data-id)]
    (validators/path-readable cm user path)
    data-item))

(defn metadata-get
  "Returns the metadata for a path. Filters out system AVUs
   if :system true is not passed to it, and replaces
   units set to ipc-reserved with an empty string."
  [user data-id & {:keys [system] :or {system false}}]
  (irods/with-jargon-exceptions [cm]
    (validators/user-exists cm user)
    (let [{:keys [path type]} (get-readable-data-item cm user data-id)
          metadata-response   (metadata/list-avus user (resolve-data-type type) data-id :as :json)]
      (merge (:body metadata-response)
             {:irods-avus (list-path-metadata cm path :system system)
              :path       path}))))

(defn admin-metadata-get
  "Lists metadata for a path, showing all AVUs."
  [data-id]
  (metadata-get (cfg/irods-user) data-id :system true))

(defn- common-metadata-add
  "Adds an AVU to 'path'. The AVU is passed in as a map in the format:
   {
      :attr attr-string
      :value value-string
      :unit unit-string
   }
   It's a no-op if an AVU with the same attribute and value is already
   associated with the path."
  [cm path avu-map]
  (let [fixed-path (ft/rm-last-slash path)
        new-unit   (reserved-unit avu-map)
        attr       (:attr avu-map)
        value      (:value avu-map)]
    (log/debug "Fixed Path:" fixed-path)
    (log/debug "check" (true? (attr-value? cm fixed-path attr value)))
    (when-not (attr-value? cm fixed-path attr value)
      (log/debug "Adding " attr value "to" fixed-path)
      (add-metadata cm fixed-path attr value new-unit))
    fixed-path))

(defn- common-metadata-delete
  "Removes an AVU from 'path'. The AVU is passed somewhat confusingly
   as a map of attr and value:
   {
      :attr attr-string
      :value value-string
   }
   It's a no-op if no AVU with that attribute and value is associated
   with the path."
   [cm path avu-map]
  (let [fixed-path (ft/rm-last-slash path)
        attr       (:attr avu-map)
        value      (:value avu-map)]
    (log/debug "Fixed Path:" fixed-path)
    (log/debug "check" (true? (attr-value? cm fixed-path attr value)))
    (when (attr-value? cm fixed-path attr value)
      (log/debug "Removing " attr value "from" fixed-path)
      (delete-metadata cm fixed-path attr value))
    fixed-path))

(defn metadata-add
  "Allows user to set metadata on a path. The user must exist in iRODS
   and have write permissions on the path. The path must exist. The
   irods-avus parameter must be a list of objects in this format:
   {
      :attr attr-string
      :value value-string
      :unit unit-string
   }

   The 'metadata' parameter must be in a format expected by the metadata service add AVUs endpoint,
   in addition to the 'irods-avus' key.
   Pass :system true to ignore restrictions on AVUs which may be added."
  [user data-id {:keys [irods-avus] :as metadata} & {:keys [system] :or {system false}}]
  (irods/with-jargon-exceptions [cm]
    (validators/user-exists cm user)
    (let [{:keys [path type]} (get-readable-data-item cm user data-id)
          path (ft/rm-last-slash path)
          metadata (dissoc metadata :irods-avus)]
      (validators/path-writeable cm user path)
      (when-not system (authorized-avus irods-avus))
      (when-not (empty? metadata)
        (metadata/update-avus user (resolve-data-type type) data-id (json/encode metadata)))
      (doseq [avu-map irods-avus]
        (common-metadata-add cm path avu-map))
      {:path path
       :user user})))

(defn admin-metadata-add
  "Adds AVUs to path, bypassing user permission checks. See (metadata-add)
   for the AVU map format."
  [data-id body]
  (metadata-add (cfg/irods-user) data-id body :system true))

(defn metadata-set
  "Allows user to set metadata on an item with the given data-id.
   The user must exist in iRODS and have write permissions on the data item.
   The 'metadata' parameter should be in a format expected by the metadata service set AVUs endpoint,
   with an 'irods-avus' key following the format used for (metadata-add)."
  [user data-id {:keys [irods-avus] :as metadata}]
  (irods/with-jargon-exceptions [cm]
    (validators/user-exists cm user)
    (let [{:keys [path type]} (uuids/path-stat-for-uuid cm user data-id)
          irods-avus (set (map #(select-keys % [:attr :value :unit]) irods-avus))
          current-avus (set (list-path-metadata cm path :system false))
          delete-irods-avus (s/difference current-avus irods-avus)
          metadata-request (json/encode (dissoc metadata :irods-avus))]
      (validators/path-writeable cm user path)
      (authorized-avus irods-avus)

      (metadata/set-avus user (resolve-data-type type) data-id metadata-request)
      (doseq [del-avu delete-irods-avus]
        (common-metadata-delete cm path del-avu))
      (doseq [avu irods-avus]
        (common-metadata-add cm path avu))

      {:path path
       :user user})))

(defn- format-copy-dest-item
  [{:keys [id type]}]
  {:id   id
   :type (resolve-data-type type)})

(defn- get-writable-data-items
  [cm user data-ids]
  (validators/validate-num-paths data-ids)
  (let [data-items (map (partial stat/uuid-stat cm user) data-ids)
        paths (map :path data-items)]
    (validators/all-paths-writeable cm user paths)
    data-items))

(defn metadata-copy
  "Copies all IRODS AVUs visible to the client, and Metadata AVUs, from the data item with
   src-id to the items with dest-ids. When the 'force?' parameter is false or not set, additional
   validation is performed."
  [user src-id dest-ids]
  (irods/with-jargon-exceptions [cm]
    (validators/user-exists cm user)
    (let [{:keys [path type]} (get-readable-data-item cm user src-id)
          dest-items (get-writable-data-items cm user dest-ids)
          dest-paths (map :path dest-items)
          dest-ids (map :id dest-items)
          irods-avus (list-path-metadata cm path)]
      (metadata/copy-metadata-avus user
                                   (resolve-data-type type)
                                   src-id
                                   (map format-copy-dest-item dest-items))
      (doseq [dest-id dest-ids]
        (metadata-add user dest-id {:irods-avus irods-avus}))
      {:user  user
       :src   path
       :paths dest-paths})))

(defn- stat-is-dir?
  [{:keys [type]}]
  (= :dir type))

(defn- segregate-files-from-folders
  "Takes a list of path-stat results and splits the folders and files into a map with :folders and
   :files keys, with the segregated lists as values."
  [folder-children]
  (zipmap [:folders :files]
    ((juxt filter remove) stat-is-dir? folder-children)))

(defn- get-data-item-metadata-for-save
  "Adds a :metadata key to the given data-item, with a list of all IRODS and Template AVUs together
   as the key's value. If recursive? is true and data-item is a folder, then includes all files and
   subfolders (plus all their files and subfolders) with their metadata in the resulting stat map."
  [cm user recursive? {:keys [id path type] :as data-item}]
  (let [irods-metadata (list-path-metadata cm path)
        metadata-avus (-> (metadata/list-avus user (resolve-data-type type) (uuidify id) :as :json)
                          :body
                          :avus)
        data-item (assoc data-item :metadata (concat irods-metadata metadata-avus))
        path->metadata (comp (partial get-data-item-metadata-for-save cm user recursive?)
                             (partial stat/path-stat cm user))]
    (if (and recursive? (stat-is-dir? data-item))
      (merge data-item
             (segregate-files-from-folders
               (map path->metadata (directory/get-paths-in-folder user path))))
      data-item)))

(defn- build-metadata-for-save
  [cm user data-item recursive?]
  (-> (get-data-item-metadata-for-save cm user recursive? data-item)
      (dissoc :uuid)
      (json/encode {:pretty true})))

(defn- metadata-save
  "Allows a user to export metadata from a file or folder with the given data-id to a file specified
   by dest."
  [user data-id dest recursive?]
  (irods/with-jargon-exceptions [cm]
    (validators/user-exists cm user)
    (let [dest-dir (ft/dirname dest)
          src-data (uuids/path-stat-for-uuid cm user data-id)
          src-path (:path src-data)]
      (validators/path-readable cm user src-path)
      (validators/path-exists cm dest-dir)
      (validators/path-writeable cm user dest-dir)
      (validators/path-not-exists cm dest)
      (when recursive?
        (validators/validate-num-paths-under-folder user src-path))

      (with-in-str (build-metadata-for-save cm user src-data recursive?)
        {:file (stat/decorate-stat cm user (copy-stream cm *in* user dest))}))))

(defn do-metadata-save
  "Entrypoint for the API. Calls (metadata-save)."
  [data-id {:keys [user]} {:keys [dest recursive]}]
  (metadata-save user (uuidify data-id) (ft/rm-last-slash dest) (boolean recursive)))

(defn- bulk-add-file-avus
  "Applies metadata from a list of attributes and values to the given path."
  [cm user attrs path values]
  (let [{:keys [type id]} (uuids/uuid-for-path cm user path)
        avus (map (partial zipmap [:attr :value :unit]) (map vector attrs values (repeat "")))
        metadata {:avus avus}]
    (when-not (empty? avus)
      (metadata/update-avus user (resolve-data-type type) id (json/encode {:avus avus})))
    (merge {:path path} metadata)))

(defn- format-csv-metadata-path
  [dest-dir ^String path]
  (ft/rm-last-slash
    (if (.startsWith path "/")
      path
      (ft/path-join dest-dir path))))

(defn- bulk-add-avus
  "Applies metadata from a list of attributes and path/values to those paths found under dest-dir."
  [cm user dest-dir attrs csv-path-values]
  (let [format-path (partial format-csv-metadata-path dest-dir)
        paths (map (comp format-path first) csv-path-values)
        value-lists (map rest csv-path-values)]
    (validators/all-paths-writeable cm user paths)
    (mapv (partial bulk-add-file-avus cm user attrs)
          paths value-lists)))

(defn- get-csv
  [cm src-path separator]
  (let [stream-reader (java.io.InputStreamReader. (input-stream cm src-path) "UTF-8")]
    (mapv (partial mapv string/trim) (csv/read-csv-stream separator stream-reader))))

(defn- parse-metadata-csv
  "Parses paths and metadata to apply from a source CSV file in the data store"
  [user dest-id ^String separator src-path]
  (irods/with-jargon-exceptions [cm]
    (validators/user-exists cm user)
    (let [dest-dir (:path (get-readable-data-item cm user dest-id))
          csv (get-csv cm src-path separator)
          attrs (set (-> csv first rest))
          csv-path-values (rest csv)]
      {:path-metadata
       (bulk-add-avus cm user dest-dir attrs csv-path-values)})))

(defn parse-metadata-csv-file
  "Parses paths and metadata to apply from a source CSV file in the data store"
  [dest-id {:keys [user src separator] :or {separator ","}}]
  (parse-metadata-csv user dest-id separator src))
