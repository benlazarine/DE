(ns donkey.services.filesystem.directory
  (:use [clojure-commons.validators]
        [donkey.services.filesystem.common-paths]
        [clj-jargon.init :only [with-jargon]]
        [clj-jargon.item-info]
        [clj-jargon.permissions]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure-commons.file-utils :as ft]
            [dire.core :refer [with-pre-hook! with-post-hook!]]
            [donkey.services.filesystem.validators :as validators]
            [donkey.services.filesystem.uuids :as uuids]
            [donkey.persistence.metadata :as meta]
            [clj-icat-direct.icat :as icat]
            [donkey.util.config :as cfg]
            [donkey.services.filesystem.icat :as jargon])
  (:import [java.util UUID]))


(defn get-paths-in-folder
  ([user folder]
    (get-paths-in-folder user folder (cfg/fs-max-paths-in-request)))

  ([user folder limit]
    (let [listing (icat/paged-folder-listing user (cfg/irods-zone) folder :base-name :asc limit 0)]
      (map :full_path listing))))

(defn- filtered-paths
  "Returns a seq of full paths that should not be included in paged listing."
  [user]
  [(cfg/fs-community-data)
   (ft/path-join (cfg/irods-home) user)
   (ft/path-join (cfg/irods-home) "public")])

(defn- should-filter?
  "Returns true if the map is okay to include in a directory listing."
  [user path-to-check]
  (let [fpaths (set (concat (cfg/fs-filter-files) (filtered-paths user)))]
    (or  (contains? fpaths path-to-check)
         (not (valid-path? path-to-check)))))

(defn- page-entry->map
  "Turns a entry in a paged listing result into a map containing file/directory
   information that can be consumed by the front-end."
  [user {:keys [type full_path base_name data_size modify_ts create_ts access_type_id uuid]}]
  (let [base-map {:id            uuid
                  :path          full_path
                  :label         base_name
                  :isFavorite    (meta/is-favorite? user (UUID/fromString uuid))
                  :filter        (or (should-filter? user full_path)
                                     (should-filter? user base_name))
                  :file-size     data_size
                  :date-created  (* (Integer/parseInt create_ts) 1000)
                  :date-modified (* (Integer/parseInt modify_ts) 1000)
                  :permission    (fmt-perm access_type_id)}]
    (if (= type "dataobject")
      base-map
      (merge base-map {:hasSubDirs true
                       :file-size  0}))))

(defn- page->map
  "Transforms an entire page of results for a paged listing in a map that
   can be returned to the client."
  [user page]
  (let [entry-types (group-by :type page)
        do          (get entry-types "dataobject")
        collections (get entry-types "collection")
        xformer     (partial page-entry->map user)]
    {:files   (mapv xformer do)
     :folders (mapv xformer collections)}))

(defn- user-col->api-col
  [sort-col]
  (case sort-col
    "NAME"         :base-name
    "ID"           :full-path
    "LASTMODIFIED" :modify-ts
    "DATECREATED"  :create-ts
    "SIZE"         :data-size
    "PATH"         :full-path
                   :base-name))

(defn- user-order->api-order
  [sort-order]
  (case sort-order
    "ASC"  :asc
    "DESC" :desc
           :asc))

(defn paged-dir-listing
  "Provides paged directory listing as an alternative to (list-dir). Always contains files."
  [user path limit offset & {:keys [sort-col sort-order]
                             :or {:sort-col   "NAME"
                                  :sort-order "ASC"}}]
  (log/warn "paged-dir-listing - user:" user "path:" path "limit:" limit "offset:" offset)
  (let [path      (ft/rm-last-slash path)
        sort-col  (string/upper-case sort-col)
        sort-order (string/upper-case sort-order)]
    (with-jargon (jargon/jargon-cfg) [cm]
      (validators/user-exists cm user)
      (validators/path-exists cm path)
      (validators/path-readable cm user path)
      (validators/path-is-dir cm path)

      (when-not (contains? #{"NAME" "ID" "LASTMODIFIED" "DATECREATED" "SIZE" "PATH"} sort-col)
        (log/warn "invalid sort column" sort-col)
        (throw+ {:error_code "ERR_INVALID_SORT_COLUMN"
                 :column sort-col}))

      (when-not (contains? #{"ASC" "DESC"} sort-order)
        (log/warn "invalid sort order" sort-order)
        (throw+ {:error_code "ERR_INVALID_SORT_ORDER"
                 :sort-order sort-order}))

      (let [stat (stat cm path)
            scol (user-col->api-col sort-col)
            sord (user-order->api-order sort-order)
            zone (cfg/irods-zone)
            uuid (:uuid (uuids/uuid-for-path cm user path))]
        (merge
         (hash-map
          :id               uuid
          :path             path
          :label            (id->label cm user path)
          :isFavorite       (meta/is-favorite? user (UUID/fromString uuid))
          :filter           (should-filter? user path)
          :permission       (permission-for cm user path)
          :hasSubDirs       true
          :date-created     (:date-created stat)
          :date-modified    (:date-modified stat)
          :file-size        0)
         (icat/number-of-items-in-folder user zone path)
         (icat/number-of-filtered-items-in-folder user zone path
                                                  (cfg/fs-filter-chars)
                                                  (cfg/fs-filter-files)
                                                  (filtered-paths user))
         (page->map user (log/spy (icat/paged-folder-listing user zone path scol sord limit offset))))))))

(defn list-directories
  "Lists the directories contained under path."
  [user path]
  (let [path (ft/rm-last-slash path)]
    (with-jargon (jargon/jargon-cfg) [cm]
      (validators/user-exists cm user)
      (validators/path-exists cm path)
      (validators/path-readable cm user path)
      (validators/path-is-dir cm path)

      (let [stat (stat cm path)
            zone (cfg/irods-zone)
            uuid (:uuid (uuids/uuid-for-path cm user path))]
        (merge
         (hash-map
          :id            uuid
          :path          path
          :label         (id->label cm user path)
          :isFavorite    (meta/is-favorite? user (UUID/fromString uuid))
          :filter        (should-filter? user path)
          :permisssion   (permission-for cm user path)
          :hasSubDirs    true
          :date-created  (:date-created stat)
          :date-modified (:date-modified stat)
          :file-size     0)
         (dissoc (page->map user (icat/list-folders-in-folder user zone path)) :files))))))

(defn- top-level-listing
  [{user :user}]
  (let [comm-f     (future (list-directories user (cfg/fs-community-data)))
        share-f    (future (list-directories user (cfg/irods-home)))
        home-f     (future (list-directories user (user-home-dir user)))]
    {:roots [@home-f @comm-f @share-f]}))

(defn- shared-with-me-listing?
  [path]
  (= (ft/add-trailing-slash path) (ft/add-trailing-slash (cfg/irods-home))))

(defn do-directory
  [{:keys [user path] :or {path nil} :as params}]
  (log/warn "path" path)
  (cond
    (nil? path)
    (top-level-listing params)

    (shared-with-me-listing? path)
    (list-directories user (cfg/irods-home))

    :else
    (list-directories user path)))

(defn do-paged-listing
  "Entrypoint for the API that calls (paged-dir-listing)."
  [{user       :user
    path       :path
    limit      :limit
    offset     :offset
    sort-col   :sort-col
    sort-order :sort-order
    :as params}]
  (let [limit      (Integer/parseInt limit)
        offset     (Integer/parseInt offset)
        sort-col   (if sort-col sort-col "NAME")
        sort-order (if sort-order sort-order "ASC")]
    (paged-dir-listing user path limit offset :sort-col sort-col :sort-order sort-order)))

(defn do-unsecured-paged-listing
  "Entrypoint for the API that calls (paged-dir-listing)."
  [{path       :path
    limit      :limit
    offset     :offset
    sort-col   :sort-col
    sort-order :sort-order}]
  (let [user       "ipctest"
        limit      (Integer/parseInt limit)
        offset     (Integer/parseInt offset)
        sort-col   (if sort-col sort-col "NAME")
        sort-order (if sort-order sort-order "ASC")]
    (paged-dir-listing user path limit offset :sort-col sort-col :sort-order sort-order)))

(with-pre-hook! #'do-directory
  (fn [params]
    (log-call "do-directory" params)
    (validate-map params {:user string?})))

(with-post-hook! #'do-directory (log-func "do-directory"))

(with-pre-hook! #'do-paged-listing
  (fn [params]
    (log-call "do-paged-listing" params)
    (validate-map params {:user string? :path string? :limit string? :offset string?})))

(with-post-hook! #'do-paged-listing (log-func "do-paged-listing"))

(with-pre-hook! #'do-unsecured-paged-listing
  (fn [params]
    (log-call "do-unsecured-paged-listing" params)
    (validate-map params {:path string? :limit string? :offset string?})))

(with-post-hook! #'do-unsecured-paged-listing (log-func "do-unsecured-paged-listing"))
