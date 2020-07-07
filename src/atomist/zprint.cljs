(ns atomist.zprint
  (:require [zprint.core :as zprint]
            [cljs-node-io.core :as io :refer [slurp spit]]
            [atomist.cljs-log :as log]))

(defn find-files
  [dir]
  (let [f (io/file dir)]
    (if (.isDirectory f)
      (filter #(re-find #"\.clj[sx]?$" %) (io/file-seq dir))
      [f])))

(defn run
  "Format files with cljfmt"
  [dir opts]
  (let [merged-opts (merge {:configured? true} opts)
        edited-files
        (->>
         (for [f (find-files dir)
               :let [original (slurp f)]]
           (let [[k v e]
                 (try [:formatted
                       (zprint/zprint-file-str original "skill" merged-opts)]
                      (catch :default ex [:failed-to-process f ex]))]
             (case k
               :formatted (when (not= original v)
                            (log/info "Reformatting " f)
                            (spit f v)
                            f)
               :failed-to-process
               (log/warnf "zprint could not process %s - %s" v e))))
         (filter identity))]
    (if (> (count edited-files) 0)
      (log/infof "edited %d files" (count edited-files))
      (log/info "No clojure files formatted"))))
