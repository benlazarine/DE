(ns metadactyl.service.apps.de
  (:use [kameleon.uuids :only [uuidify]])
  (:require [metadactyl.service.apps.de.edit :as edit]
            [metadactyl.service.apps.de.job-view :as job-view]
            [metadactyl.service.apps.de.listings :as listings]
            [metadactyl.service.apps.de.metadata :as app-metadata]
            [metadactyl.service.util :as util]))

(deftype DeApps [user]
  metadactyl.protocols.Apps

  (getClientName [_]
    "de")

  (listAppCategories [_ params]
    (listings/get-app-groups user params))

  (hasCategory [_ category-id]
    (listings/has-category category-id))

  (listAppsInCategory [_ category-id params]
    (listings/list-apps-in-group user category-id params))

  (searchApps [_ _ params]
    (listings/search-apps user params))

  (canEditApps [_]
    true)

  (addApp [_ app]
    (edit/add-app user app))

  (previewCommandLine [_ app]
    (app-metadata/preview-command-line app))

  (listAppIds [_]
    (listings/list-app-ids))

  (deleteApps [_ deletion-request]
    (app-metadata/delete-apps user deletion-request))

  (getAppJobView [_ app-id]
    (when (util/uuid? app-id)
      (job-view/get-app (uuidify app-id))))

  (deleteApp [_ app-id]
    (when (util/uuid? app-id)
      (app-metadata/delete-app user (uuidify app-id)))))
