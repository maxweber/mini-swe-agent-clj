(ns minisweagent.config
  "Builds the config map from EDN files and `key.path=value` specs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-model-name
  "anthropic/claude-opus-5")

(defn deep-merge
  "Merges maps recursively, later values win."
  [& maps]
  (apply merge-with
         (fn [a b]
           (if (and (map? a) (map? b))
             (deep-merge a b)
             b))
         maps))

(defn- parse-value
  "EDN if the value reads as a number, keyword, boolean, collection or
  string, otherwise the raw text."
  [text]
  (let [value (try
                (edn/read-string text)
                (catch Exception _
                  ::raw))]
    (if (or (= ::raw value) (symbol? value) (nil? value))
      text
      value)))

(defn- read-edn
  [spec]
  (let [file (io/file spec)
        source (if (.exists file)
                 file
                 (io/resource (str "minisweagent/config/" spec)))]
    (when-not source
      (throw (ex-info (str "Config " spec " is neither a file nor a builtin config"
                           " (mini.edn, mini-textbased.edn)")
                      {:spec spec})))
    (edn/read-string (slurp source))))

(defn read-spec
  "A config map from a spec: `key.path=value`, an EDN file or the name of a
  builtin config."
  [spec]
  (if-let [[_ key-path value] (re-matches #"([\w.-]+)=(.*)" spec)]
    (assoc-in {} (mapv keyword (str/split key-path #"\.")) (parse-value value))
    (read-edn spec)))

(defn catalog
  "Defaults per provider and per model, see resources/minisweagent/models.edn."
  []
  (edn/read-string (slurp (io/resource "minisweagent/models.edn"))))

(defn resolve-model
  "Completes a model config from its `:name`, e.g. \"anthropic/claude-opus-5\"
  or \"openrouter/moonshotai/kimi-k2\", like LiteLLM model names. Precedence:
  provider defaults < model defaults < the given config."
  [{:keys [providers models]} {:keys [name] :as model-config}]
  (let [[provider id] (str/split name #"/" 2)
        provider-defaults (get providers provider)]
    (when-not (and provider-defaults id)
      (throw (ex-info (str "Model name " name " must start with a known provider: "
                           (str/join ", " (keys providers)))
                      {:name name})))
    (deep-merge provider-defaults
                {:id id}
                (get models id)
                model-config)))

(defn build
  "The config for `specs` (see `read-spec`, or maps), merged in order, with
  the model resolved. The model name defaults to $MSWEA_MODEL_NAME or
  claude-opus-5."
  [specs]
  (let [config (apply deep-merge (map #(if (map? %) % (read-spec %)) specs))
        model-name (or (get-in config [:model :name])
                       (System/getenv "MSWEA_MODEL_NAME")
                       default-model-name)]
    (update config :model #(resolve-model (catalog) (assoc % :name model-name)))))

(defn platform-vars
  "Template values that describe the machine."
  []
  {:system (System/getProperty "os.name")
   :release (System/getProperty "os.version")
   :machine (System/getProperty "os.arch")
   :cwd (System/getProperty "user.dir")})

(comment
  (build ["mini.edn" "agent.step-limit=10" "model.name=openrouter/moonshotai/kimi-k2"])
  )
