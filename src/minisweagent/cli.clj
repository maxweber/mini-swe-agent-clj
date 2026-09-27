(ns minisweagent.cli
  "The `mini` command: runs the agent in the local environment."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as tools.cli]
            [minisweagent.agent :as agent]
            [minisweagent.config :as config]
            [minisweagent.environment.local :as local]
            [minisweagent.model.http :as http]
            [minisweagent.terminal :as terminal]
            [minisweagent.trajectory :as trajectory]))

(def default-output
  (str (io/file (System/getProperty "user.home")
                ".config" "mini-swe-agent-clj" "last_mini_run.traj.edn")))

(def cli-options
  [["-m" "--model NAME" "Model as provider/id, e.g. anthropic/claude-opus-5 or openrouter/moonshotai/kimi-k2. Default: $MSWEA_MODEL_NAME, else claude-opus-5"]
   ["-t" "--task TASK" "Task/problem statement"]
   ["-y" "--yolo" "Run without confirmation"]
   ["-l" "--cost-limit USD" "Cost limit, 0 disables it"
    :parse-fn parse-double
    :validate [some? "Must be a number"]]
   ["-c" "--config SPEC" (str "Config file, builtin config (mini.edn, mini-textbased.edn) or"
                              " key.path=value. Repeatable, merged in order. Replaces the"
                              " default config mini.edn, e.g. -c mini.edn -c agent.step-limit=20")
    :multi true
    :default []
    :update-fn conj]
   ["-o" "--output PATH" "Trajectory file, .edn or .json"
    :default default-output]
   [nil "--exit-immediately" "Exit when the agent wants to finish instead of asking for a new task"]
   ["-h" "--help"]])

(defn- flag-overrides
  [{:keys [model yolo cost-limit exit-immediately]}]
  (cond-> {}
    model (assoc-in [:model :name] model)
    yolo (assoc-in [:agent :mode] :yolo)
    cost-limit (assoc-in [:agent :cost-limit] cost-limit)
    exit-immediately (assoc-in [:agent :confirm-exit] false)))

(defn- ask-task!
  []
  (println "What do you want to do? (finish with a line with only a dot)")
  (terminal/read-multiline!))

(defn run
  "Runs the agent for the command line `opts` and returns the final agent
  value."
  [opts]
  (let [config (config/build (conj (if (seq (:config opts))
                                     (:config opts)
                                     ["mini.edn"])
                                   (flag-overrides opts)))
        !agent (atom (agent/init {:config config
                                  :task (or (:task opts) (ask-task!))
                                  :vars (config/platform-vars)
                                  :now (System/currentTimeMillis)}))]
    (println "Model:" (get-in config [:model :name]) "- trajectory:" (:output opts))
    (add-watch !agent ::print terminal/print-new-messages)
    (add-watch !agent ::save (trajectory/watch-fn (:output opts)))
    (agent/run! {:model/query (http/query-fn {})
                 :env/execute local/execute
                 :user/ask terminal/ask
                 :clock/now #(System/currentTimeMillis)}
                !agent)))

(defn -main
  [& args]
  (let [{:keys [options errors summary]} (tools.cli/parse-opts args cli-options)]
    (cond
      (:help options)
      (println (str "Usage: mini [options]\n\n" summary))

      (seq errors)
      (do (binding [*out* *err*]
            (println (str/join "\n" errors)))
          (System/exit 2))

      :else
      (let [final-agent (try
                          (run options)
                          (catch Exception e
                            (binding [*out* *err*]
                              (println "Error:" (ex-message e)))
                            (System/exit 1)))]
        (System/exit (if (= "Submitted" (get-in (peek (:messages final-agent))
                                                [:extra :exit-status]))
                       0
                       1))))))
