(ns minisweagent.trajectory
  "The agent value is the complete trajectory of a run, so saving it is all
  there is to do."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn save!
  "Writes the agent value as EDN, or as JSON if `path` ends with .json."
  [path agent]
  (io/make-parents path)
  (spit path (if (str/ends-with? path ".json")
               (json/write-str agent
                               :indent true
                               :escape-unicode false
                               :escape-slash false)
               (pr-str agent))))

(defn load-edn
  [path]
  (edn/read-string (slurp path)))

(defn watch-fn
  "A watch function that saves every new agent value to `path`."
  [path]
  (fn [_key _ref _old-agent new-agent]
    (save! path new-agent)))
