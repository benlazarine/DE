(ns data-info.routes
  (:use [clojure-commons.lcase-params :only [wrap-lcase-params]]
        [clojure-commons.query-params :only [wrap-query-params]]
        [compojure.api.sweet]
        [compojure.api.legacy]
        [ring.util.response :only [redirect]])
  (:require [data-info.routes.data :as data-routes]
            [data-info.routes.exists :as exists-routes]
            [data-info.routes.home :as home-routes]
            [data-info.routes.legacy :as legacy-routes]
            [data-info.routes.navigation :as navigation-routes]
            [data-info.routes.stats :as stat-routes]
            [data-info.util :as util]
            [data-info.util.config :as config]
            [liberator.dev :as liberator]
            [ring.middleware.keyword-params :as params]
            [service-logging.thread-context :as tc]))

(defn context-middleware
  [handler]
  (tc/wrap-thread-context handler config/svc-info))

(defapi app
  (swagger-ui "/api")
  (swagger-docs
    {:info {:title "Discovery Environment Data Info API"
            :description "Documentation for the Discovery Environment Data Info REST API"
            :version "2.0.0"}})
  (GET "/" [] (redirect "/api"))
  (GET "/favicon.ico" [] {:status 404})
  (middlewares
    [tc/add-user-to-context
     wrap-query-params
     wrap-lcase-params
     params/wrap-keyword-params
     util/req-logger
     context-middleware]
    data-routes/data-operations
    exists-routes/existence-marker
    home-routes/home
    navigation-routes/navigation
    stat-routes/stat-gatherer)
  (middlewares
    [tc/add-user-to-context
     wrap-query-params
     wrap-lcase-params
     params/wrap-keyword-params
     util/req-logger
     #_(liberator/wrap-trace :header :ui)
     util/trap-handler
     context-middleware]
    legacy-routes/all-routes))
