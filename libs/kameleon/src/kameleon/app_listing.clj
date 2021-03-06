(ns kameleon.app-listing
  (:use [korma.core :exclude [update]]
        [kameleon.entities]
        [kameleon.queries]
        [kameleon.util :only [query-spy]]
        [kameleon.util.search]
        [kameleon.app-groups :only [get-visible-root-app-group-ids]])
  (:require [clojure.string :as str]))

(defn get-app-listing
  "Retrieves all app listing fields from the database for the given App ID."
  [app-id]
  (first (select app_listing (where {:id app-id}))))

(defn- get-all-group-ids-subselect
  "Gets a subselect that fetches the app_categories and its subgroup IDs with
   the stored procedure app_category_hierarchy_ids."
  [app_group_id]
  (subselect
    (sqlfn :app_category_hierarchy_ids app_group_id)))

(defn- get-fav-group-id-subselect
  "Gets a subselect that fetches the ID for the Favorites group at the given
   index under the app_categories with the given ID."
  [workspace_root_group_id favorites_group_index]
  (subselect
    :app_category_group
    (fields :child_category_id)
    (where {:parent_category_id workspace_root_group_id
            :child_index favorites_group_index})))

(defn- get-is-fav-sqlfn
  "Gets a sqlfn that retuns true if the App ID in its subselect is found in the
   Favorites group with the ID returned by get-fav-group-id-subselect."
  [workspace_root_group_id favorites_group_index]
  (let [fav_group_id_subselect (get-fav-group-id-subselect
                                 workspace_root_group_id
                                 favorites_group_index)]
    (sqlfn* :exists
            (subselect
              :app_category_app
              (where {:app_category_app.app_id
                      :app_listing.id})
              (where {:app_category_app.app_category_id
                      fav_group_id_subselect})))))

(defn- add-app-id-where-clause
  [query {:keys [app-ids]}]
  (if (seq app-ids)
    (where query {:id [in app-ids]})
    query))

(defn- add-agave-pipeline-where-clause
  [query {agave-enabled? :agave-enabled :or {agave-enaled? "false"}}]
  (let [agave-enabled? (Boolean/parseBoolean agave-enabled?)]
    (if-not agave-enabled?
      (where query {:step_count :task_count})
      query)))

(defn- add-app-group-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   an app group and all of its descendents."
  [base_listing_query app_group_id]
  (-> base_listing_query
    (join :app_category_app
          (= :app_category_app.app_id
             :app_listing.id))
    (where {:app_category_app.app_category_id
            [in (get-all-group-ids-subselect app_group_id)]})))

(defn- add-app-group-plus-public-apps-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   an app group and all of its descendents plus the set of public apps
   integrated by a user."
  [query app-group-id username public-app-ids]
  (-> query
    (join :app_category_app
          (= :app_category_app.app_id
             :app_listing.id))
    (where (or {:app_category_app.app_category_id
                [in (get-all-group-ids-subselect app-group-id)]}
               {:integrator_username username
                :id                  [in (seq public-app-ids)]}))))

(defn- add-public-apps-by-user-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   the set of public apps integrated by a user."
  [query username {:keys [public-app-ids]}]
  (where query
         {:integrator_username username
          :id                  [in public-app-ids]}))

(defn- get-app-count-base-query
  "Returns a base query for counting the total number of apps in the
   app_listing table."
  [query-opts]
  (->
    (select* app_listing)
    (fields (raw "count(DISTINCT app_listing.id) AS total"))
    (where {:deleted false})
    (add-app-id-where-clause query-opts)
    (add-agave-pipeline-where-clause query-opts)))

(defn count-apps-in-group-for-user
  "Counts all of the apps in an app group and all of its descendents."
  ([app-group-id query-opts]
    ((comp :total first)
      (-> (get-app-count-base-query query-opts)
        (add-app-group-where-clause app-group-id)
        (select))))
  ([app-group-id username {:keys [public-app-ids] :as query-opts}]
    ((comp :total first)
      (-> (get-app-count-base-query query-opts)
        (add-app-group-plus-public-apps-where-clause app-group-id username public-app-ids)
        (select)))))

(defn- add-app-listing-base-query-fields
  "Add minimum required columns to apps listing query results"
  [listing-query]
  (fields listing-query
          :id
          :name
          :lower_case_name
          :description
          :integrator_name
          :integrator_email
          :integration_date
          :edited_date
          :wiki_url
          :average_rating
          :total_ratings
          :is_public
          :step_count
          :tool_count
          :external_app_count
          :task_count
          :deleted
          :disabled
          :overall_job_type))

(defn- add-app-listing-is-favorite-field
  "Add user's is_favorite column to apps listing query results"
  [listing-query workspace_root_group_id favorites_group_index]
  (let [is_fav_subselect (get-is-fav-sqlfn
                           workspace_root_group_id
                           favorites_group_index)]
    (fields listing-query [is_fav_subselect :is_favorite])))

(defn- add-app-listing-ratings-fields
  "Add ratings columns to apps listing query results"
  [listing-query user-id]
  (-> listing-query
      (fields [:ratings.rating :user_rating]
              :ratings.comment_id)
      (join ratings
            (and (= :ratings.app_id
                    :app_listing.id)
                 (= :ratings.user_id
                    user-id)))))

(defn- get-app-listing-base-query
  "Gets an app_listing select query, setting any query limits and sorting
   found in the query_opts, using the given workspace (as returned by
   fetch-workspace-by-user-id) to mark whether each app is a favorite and to
   include the user's rating in each app.."
  [workspace favorites_group_index query_opts]
  (let [user_id (:user_id workspace)
        workspace_root_group_id (:root_category_id workspace)
        row_offset (or (:offset query_opts) 0)
        row_limit (or (:limit query_opts) -1)
        sort_field (keyword (or (:sort-field query_opts) (:sortfield query_opts)))
        sort_dir (keyword (or (:sort-dir query_opts) (:sortdir query_opts)))]
    (-> (select* app_listing)
        (modifier "DISTINCT")
        (add-app-listing-base-query-fields)
        (add-app-listing-is-favorite-field workspace_root_group_id favorites_group_index)
        (add-app-listing-ratings-fields user_id)
        (where {:deleted false})
        (add-app-id-where-clause query_opts)
        (add-agave-pipeline-where-clause query_opts)
        (add-query-limit row_limit)
        (add-query-offset row_offset)
        (add-query-sorting sort_field sort_dir))))

(defn get-apps-in-group-for-user
  "Lists all of the apps in an app group and all of its descendents, using the
   given workspace (as returned by fetch-workspace-by-user-id) to mark
   whether each app is a favorite and to include the user's rating in each app."
  ([app-group-id workspace faves-index query-opts]
    (-> (get-app-listing-base-query workspace faves-index query-opts)
      (add-app-group-where-clause app-group-id)
      (select)))
  ([app-group-id workspace faves-index {:keys [public-app-ids] :as query-opts} username]
    (-> (get-app-listing-base-query workspace faves-index query-opts)
      (add-app-group-plus-public-apps-where-clause app-group-id username public-app-ids)
      (select))))

(defn- get-public-group-ids-subselect
  "Gets a subselect that fetches the workspace app_categories ID, public root
   group IDs, and their subgroup IDs with the stored procedure
   app_category_hierarchy_ids."
  [workspace_id]
  (let [root_app_ids (get-visible-root-app-group-ids workspace_id)
        select-ids-fn #(str "SELECT * FROM app_category_hierarchy_ids('" % "')")
        union_select_ids (str/join
                           " UNION "
                           (map select-ids-fn root_app_ids))]
    (raw (str "(" union_select_ids ")"))))

(defn- get-deployed-component-search-subselect
  "Gets a subselect that fetches deployed components with names matching the
   given search_term, inside an exists check, for each app in the main select."
  [search_term]
  (sqlfn* :exists
          (subselect
            :tool_listing
            (where {:app_listing.id
                    :tool_listing.app_id})
            (where {(sqlfn lower :tool_listing.name)
                    [like (sqlfn lower search_term)]}))))

(defn- add-search-term-where-clauses
  "Adds where clauses to a base App search query to restrict results to apps that
   contain search_term in the app name, app description, app integrator name, or
   the tool name."
  [base_search_query search_term pre-matched-app-ids]
  (let [search_term (str "%" (format-query-wildcards search_term) "%")]
    (where base_search_query
           (or {(sqlfn lower :name) [like (sqlfn lower search_term)]}
               {(sqlfn lower :description) [like (sqlfn lower search_term)]}
               {(sqlfn lower :integrator_name) [like (sqlfn lower search_term)]}
               {:id [in pre-matched-app-ids]}
               (get-deployed-component-search-subselect search_term)))))

(defn- add-search-category-where-clauses
  "Adds where clauses to a base App search query to restrict results to apps
   in all public groups and groups under the given workspace_id."
  [base_search_query workspace_id {:keys [app-ids]}]
  (if (empty? app-ids)
    (-> base_search_query
        (join :app_category_app {:app_category_app.app_id :app_listing.id})
        (where {:app_category_app.app_category_id
                [in (get-public-group-ids-subselect workspace_id)]}))
    base_search_query))

(defn count-search-apps-for-user
  "Counts App search results that contain search_term in their name or
   description, in all public groups and groups under the given workspace_id."
  [search_term workspace_id {:keys [pre-matched-app-ids] :as params}]
  (-> (get-app-count-base-query params)
      (add-search-term-where-clauses search_term pre-matched-app-ids)
      (add-search-category-where-clauses workspace_id params)
      select
      first
      :total))

(defn search-apps-for-user
  "Searches Apps that contain search_term in their name or description, in all
   public groups and groups in workspace (as returned by
   fetch-workspace-by-user-id), marking whether each app is a favorite and
   including the user's rating in each app by the user_id found in workspace."
  [search_term {workspace_id :id :as workspace} favorites_group_index query_opts]
  (-> (get-app-listing-base-query workspace favorites_group_index query_opts)
      (add-search-term-where-clauses search_term (:pre-matched-app-ids query_opts))
      (add-search-category-where-clauses workspace_id query_opts)
      ((partial query-spy "search-apps-for-user::search_query:"))
      select))

(defn- add-deleted-and-orphaned-where-clause
  [query public-app-ids]
  (where query
    (or {:deleted true
         :id      [in (seq public-app-ids)]}
      (raw "NOT EXISTS (SELECT * FROM app_category_app aca WHERE aca.app_id = app_listing.id)"))))

(defn count-deleted-and-orphaned-apps
  "Counts the number of deleted, public apps, plus apps that are not listed under any category."
  [{:keys [public-app-ids]}]
  ((comp :count first)
   (-> (select* app_listing)
       (aggregate (count :*) :count)
       (add-deleted-and-orphaned-where-clause public-app-ids)
       (select))))

(defn list-deleted-and-orphaned-apps
  "Fetches a list of deleted, public apps, plus apps that are not listed under any category."
  [{:keys [limit offset sort-field sort-dir public-app-ids]}]
  (-> (select* app_listing)
      (add-app-listing-base-query-fields)
      (add-deleted-and-orphaned-where-clause public-app-ids)
      (add-query-limit limit)
      (add-query-offset offset)
      (add-query-sorting (when sort-field (keyword sort-field))
                         (when sort-dir (keyword (str/upper-case sort-dir))))
      (select)))

(defn count-public-apps-by-user
  "Counts the number of apps integrated by a user."
  [username params]
  ((comp :count first)
   (-> (select* app_listing)
       (aggregate (count :*) :count)
       (where {:deleted false})
       (add-public-apps-by-user-where-clause username params)
       (add-app-id-where-clause params)
       (add-agave-pipeline-where-clause params)
       (select))))

(defn list-public-apps-by-user
  "Lists the apps integrated by the user with the given"
  [workspace favorites-group-index username query-opts]
  (-> (get-app-listing-base-query workspace favorites-group-index query-opts)
      (add-public-apps-by-user-where-clause username query-opts)
      (select)))

(defn list-shared-apps
  "Lists apps that have been shared with a user. For the time being, this works by listing all apps
  that the user did not integrate, but for which the user has direct access permission."
  [workspace favorites-group-index params]
  (if (seq (:directly-accessible-app-ids params))
    (let [params (assoc params :app-ids (:directly-accessible-app-ids params))]
      (-> (get-app-listing-base-query workspace favorites-group-index params)
          (where {:integrator_id [not= (:user_id workspace)]})
          select))
    []))

(defn count-shared-apps
  "Counts the number of apps that have been shared with a user. For the time being, this works by
  counting all apps that the user did not integrate, but for which the has direct access permission."
  [workspace favorites-group-index params]
  (if (seq (:directly-accessible-app-ids params))
    (let [params (assoc params :app-ids (:directly-accessible-app-ids params))]
      (-> (select* [:app_listing :l])
          (aggregate (count :*) :count)
          (where {:deleted false})
          (where {:integrator_id [not= (:user_id workspace)]})
          (add-app-id-where-clause params)
          select first :count))
    0))

(defn list-apps-by-id
  "Lists all apps with an ID in the the given app-ids list."
  [workspace favorites-group-index app-ids params]
  (when-not (empty? app-ids)
    (select (get-app-listing-base-query workspace favorites-group-index (assoc params :app-ids app-ids)))))
