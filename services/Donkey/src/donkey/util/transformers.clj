(ns donkey.util.transformers
  (:use [cemerick.url :only [url]]
        [donkey.util.service :only [decode-stream]]
        [donkey.auth.user-attributes]
        [medley.core :only [remove-vals]])
  (:require [cheshire.core :as cheshire]))

(def remove-nil-vals (partial remove-vals nil?))

(defn add-username-to-json
  "Adds the name of the currently authenticated user to a JSON object in the
   body of a request, and returns only the updated body."
  [req]
  (let [m (decode-stream (:body req))
        username (get-in req [:user-attributes "uid"])]
    (cheshire/encode (assoc m :user username))))

(defn add-current-user-to-map
  "Adds the name and e-mail address of the currently authenticated user to a
   map that can be used to generate a query string."
  [query]
  (assoc query
    :user       (:shortUsername current-user)
    :email      (:email current-user)
    :first-name (:firstName current-user)
    :last-name  (:lastName current-user)))

(defn add-current-user-to-url
  "Adds the name of the currently authenticated user to the query string of a
   URL."
  [addr]
  (let [url-map (url addr)
        query   (add-current-user-to-map (:query url-map))
        url-map (assoc url-map :query query)]
    (str url-map)))
