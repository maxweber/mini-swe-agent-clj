(ns minisweagent.trajectory
  "The log is the complete trajectory of a run, so saving it is all there is
  to do."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn save!
  "Writes the log as EDN, or as JSON if `path` ends with .json."
  [path log]
  (io/make-parents path)
  (spit path (if (str/ends-with? path ".json")
               (json/write-str log
                               :indent true
                               :escape-unicode false
                               :escape-slash false)
               (pr-str log))))

(defn load-edn
  [path]
  (edn/read-string (slurp path)))

(defn watch-fn
  "A watch function that saves every new log to `path`."
  [path]
  (fn [_key _ref _old-log new-log]
    (save! path new-log)))
