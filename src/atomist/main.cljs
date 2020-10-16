;; Copyright © 2020 Atomist, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns atomist.main
  (:require [cljs.core.async :refer [<!]]
            [goog.string.format]
            [atomist.cljs-log :as log]
            [atomist.api :as api]
            [atomist.zprint]
            [goog.string :as gstring]
            [clojure.edn :as edn])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn run-zprint
  [request]
  (go (try (atomist.zprint/run (-> request
                                   :project
                                   :path)
                               (merge {} (:zprint-opts request)))
           :done
           (catch :default ex
             (log/error ex "unable to run zprint library")
             {:error ex, :message "unable to run zprint library"}))))

(defn- is-default-branch?
  [request]
  (let [push (-> request
                 :data
                 :Push
                 first)]
    (= (:branch push)
       (-> push
           :repo
           :defaultBranch))))

(defn check-zprint-config
  [handler]
  (fn [request]
    (go (if (:config request)
          (try (let [c (edn/read-string (:config request))]
                 (if (not (map? c))
                   (<! (api/finish request
                                   :failure
                                   (gstring/format
                                    "%s is not a valid zprint config map"
                                    (:config request))))
                   (<! (handler (assoc request :zprint-opts c)))))
               (catch :default ex
                 (log/warn ex "error parsing edn " (:config request))
                 (<! (api/finish request
                                 :failure
                                 (gstring/format
                                  "%s is not a valid zprint config map"
                                  (:config request))))))
          (<! (handler request))))))

(defn check-configuration
  [handler]
  (fn [request]
    (go
      (cond
        (or (= "inPR" (:fix request))
            (and (= "inPROnDefaultBranch" (:fix request))
                 (is-default-branch? request)))
        (<!
         (handler
          (assoc
           request
           :atomist.gitflows/configuration
           {:branch (gstring/format "zprint-%s"
                                    (-> request
                                        :ref
                                        :branch))
            :target-branch (-> request
                               :ref
                               :branch)
            :body
            (gstring/format
             "running [zprint fix](https://github.com/atomist-skills/zprint-skill) with configuration %s"
             (-> request
                 :configuration
                 :name))
            :title "zprint fix"
            :type :in-pr})))
        (or (= "onBranch" (:fix request))
            (and (= "inPROnDefaultBranch" (:fix request))
                 (not (is-default-branch? request)))
            (and (= "onDefaultBranch" (:fix request))
                 (is-default-branch? request)))
        (<! (handler
             (assoc request
                    :atomist.gitflows/configuration
                    {:message "running zprint fix", :type :commit-then-push})))
        :else (<! (api/finish request
                              :success (gstring/format "nothing to do: %s policy"
                                                       (:fix request))
                              :visibility :hidden))))))

(defn ^:export handler
  "handler
    must return a Promise - we don't do anything with the value
    params
      data - Incoming Request #js object
      sendreponse - callback ([obj]) puts an outgoing message on the response topic"
  [data sendreponse]
  (api/make-request
   data
   sendreponse
   (api/dispatch
    {:OnAnyPush (-> (api/finished)
                    (api/from-channel run-zprint)
                    (api/edit-inside-PR :atomist.gitflows/configuration)
                    (api/clone-ref)
                    (check-configuration)
                    (check-zprint-config)
                    (api/add-skill-config :fix :config)
                    (api/extract-github-token)
                    (api/create-ref-from-event)
                    (api/status
                     :send-status
                     (fn [request]
                       (cond
                         (= :raised
                            (-> request
                                :edit-result))
                         (gstring/format "**zprint skill** raised a PR")
                         (= :committed
                            (-> request
                                :edit-result))
                         (gstring/format "**zprint skill** pushed a Commit")
                         (= :skipped
                            (-> request
                                :edit-result))
                         (gstring/format "**zprint skill** made no fixes")
                         :else "handled Push successfully"))))})))

