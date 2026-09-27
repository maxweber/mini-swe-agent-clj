(ns minisweagent.agent
  "The agent value is its log (see `minisweagent.log`). `step` appends the
  entries of the next step and reaches the outside world only through the
  effect functions it receives:

      {:model/query (fn [model-config messages] normalized-response)
       :env/execute (fn [environment-config command] output)
       :user/ask    (fn [{:keys [prompt mode]}] input)
       :clock/now   (fn [] epoch-ms)}

  `run!` keeps the log in an atom, the only mutable place. Printing and
  saving the trajectory are watches on that atom."
  (:refer-clojure :exclude [run!])
  (:require [minisweagent.actions :as actions]
            [minisweagent.log :as log]
            [minisweagent.model :as model]
            [minisweagent.observation :as observation]
            [minisweagent.template :as template]))

(defn- record
  "Appends `entries`, stamped with the time."
  [log now & entries]
  (into log (map #(assoc % :at now)) entries))

(defn init
  "The log at the start of a run. `vars` are the values for the system and
  instance templates besides `:task`, e.g. the platform."
  [{:keys [config task vars now]}]
  (let [{:keys [system-template instance-template]} (:agent config)
        template-vars (assoc vars :task task)]
    (record []
            now
            {:type :run/started
             :task task
             :config config}
            {:type :prompt/rendered
             :message {:role "system"
                       :content (template/render system-template template-vars)}}
            {:type :prompt/rendered
             :message {:role "user"
                       :content (template/render instance-template template-vars)}})))

(def mode-commands
  {"/y" :yolo
   "/c" :confirm
   "/u" :human})

(defn- switch-mode
  [log now new-mode]
  (if (= new-mode (log/mode log))
    log
    (record log now {:type :mode/switched
                     :mode new-mode})))

(defn- exit
  ([log now status]
   (exit log now status ""))
  ([log now status submission]
   (record log now {:type :run/exited
                    :status status
                    :submission submission})))

(defn- interrupt
  [log now interrupt-type content]
  (record log now {:type :user/interrupted
                   :interrupt-type interrupt-type
                   :message {:role "user"
                             :content content}}))

(defn- ask-user
  [{:keys [user/ask]} log prompt]
  (when-not ask
    (throw (ex-info (str "Mode " (log/mode log) " and confirm-exit need a user,"
                         " but the effects have no :user/ask.")
                    {:mode (log/mode log)})))
  (ask {:prompt prompt
        :mode (log/mode log)}))

(defn- reached?
  [limit value]
  (and (pos? (or limit 0))
       (<= limit value)))

(defn limits-exceeded?
  [log]
  (let [{:keys [step-limit cost-limit]} (:agent (log/config log))]
    (or (reached? step-limit (log/n-calls log))
        (reached? cost-limit (log/cost log)))))

(defn time-exceeded?
  [log now]
  (reached? (get-in (log/config log) [:agent :wall-time-limit-seconds])
            (quot (- now (log/started-at log)) 1000)))

(defn receive
  "Appends a model response: the assistant message with its actions, or a
  format error message for the model. A malformed assistant message is not
  part of the conversation, like in mini-swe-agent."
  [log now response]
  (let [{agent-config :agent model-config :model} (log/config log)
        {:keys [actions error]} (actions/parse (:action-format model-config) response)
        call {:cost (model/cost model-config response)
              :tokens (:tokens response)
              :model (:model response)}]
    (if error
      (let [log (record log now (assoc call
                                       :type :model/format-error
                                       :error error
                                       :truncated? (:truncated? response)
                                       :response (:message response)
                                       :message {:role "user"
                                                 :content (template/render
                                                           (if (:truncated? response)
                                                             (:truncated-response-template agent-config)
                                                             (:format-error-template agent-config))
                                                           {:error error})}))]
        (if (reached? (:max-consecutive-format-errors agent-config)
                      (log/consecutive-format-errors log))
          (exit log now "RepeatedFormatError")
          log))
      (record log now (assoc call
                             :type :model/responded
                             :message (:message response)
                             :actions actions)))))

(defn- human-command
  [{:keys [clock/now] :as effects} log]
  (let [input (ask-user effects log "> ")]
    (cond
      (mode-commands input) (switch-mode log (now) (mode-commands input))
      (= "" input) log
      :else (record log (now) {:type :user/commanded
                               :message {:role "user"
                                         :content (str "User command: \n```bash\n" input "\n```")}
                               :actions [{:command input}]}))))

(defn- new-limits
  "In a mode with a user, the user may raise the limits instead of exiting."
  [{:keys [clock/now] :as effects} log]
  (let [{:keys [step-limit cost-limit]} (:agent (log/config log))
        new-step-limit (when (#{:confirm :human} (log/mode log))
                         (parse-long (ask-user effects log
                                               (format (str "Limits exceeded. Limits: %s steps, $%s."
                                                            " Current spend: %s steps, $%.2f.\n"
                                                            "New step limit (Enter to stop): ")
                                                       step-limit cost-limit
                                                       (log/n-calls log) (log/cost log)))))
        new-cost-limit (when new-step-limit
                         (parse-double (ask-user effects log "New cost limit: ")))]
    (if new-cost-limit
      (record log (now) {:type :limits/raised
                         :step-limit new-step-limit
                         :cost-limit new-cost-limit})
      (exit log (now) "LimitsExceeded"))))

(defn- query-model
  [{:keys [model/query clock/now] :as effects} log]
  (cond
    (= :human (log/mode log)) (human-command effects log)
    (time-exceeded? log (now)) (exit log (now) "TimeExceeded")
    (limits-exceeded? log) (new-limits effects log)
    :else (let [response (query (:model (log/config log)) (log/conversation log))]
            (receive log (now) response))))

(defn observe
  "Appends the observation messages for the `outputs` of `actions`. A tool
  call without output is answered as not executed, since every tool call
  needs a result."
  [log now actions outputs]
  (let [config (log/config log)
        max-chars (get-in config [:agent :output-max-chars] 10000)
        results (keep (fn [[action output]]
                        (when-let [output (or output
                                              (when (:tool-call-id action)
                                                observation/not-executed))]
                          {:action action
                           :output output
                           :text (observation/text max-chars output)}))
                      (map vector actions (concat outputs (repeat nil))))]
    (apply record log now (map #(assoc % :type :actions/observed)
                               (model/observations (:model config) results)))))

(defn- run-actions
  "Executes the actions in order until one submits."
  [{:keys [env/execute clock/now]} log actions]
  (let [environment-config (:environment (log/config log))
        outputs (reduce (fn [outputs {:keys [command]}]
                          (let [outputs (conj outputs (execute environment-config command))]
                            (if (observation/submission (peek outputs))
                              (reduced outputs)
                              outputs)))
                        []
                        actions)]
    (observe log (now) actions outputs)))

(defn- whitelisted?
  [patterns command]
  (some #(.lookingAt (re-matcher (re-pattern %) command)) patterns))

(defn- needs-confirmation?
  [log commands]
  (let [patterns (get-in (log/config log) [:agent :whitelist-actions])]
    (and (= :confirm (log/mode log))
         (not-every? #(whitelisted? patterns %) commands))))

(defn- reject
  [log now actions interrupt-type content]
  (-> log
      (observe now actions [])
      (interrupt now interrupt-type content)))

(defn- execute-actions
  [{:keys [clock/now] :as effects} log]
  (let [actions (log/pending-actions log)]
    (if (needs-confirmation? log (map :command actions))
      (let [input (ask-user effects log (str "Execute " (count actions) " action(s)? Enter to"
                                             " confirm, type a comment to reject,"
                                             " /h for commands\n> "))]
        (case input
          ("" "/c") (run-actions effects log actions)
          "/y" (run-actions effects (switch-mode log (now) :yolo) actions)
          "/u" (reject (switch-mode log (now) :human) (now) actions "UserRejection"
                       "Commands not executed. Switching to human mode")
          (reject log (now) actions "UserRejection"
                  (str "Commands not executed. The user rejected your commands with"
                       " the following message: " input))))
      (run-actions effects log actions))))

(defn- submit
  "The agent submitted its work. With `:confirm-exit` the user may give a new
  task instead."
  [{:keys [clock/now] :as effects} log]
  (let [submission (log/submission log)]
    (if-not (get-in (log/config log) [:agent :confirm-exit])
      (exit log (now) "Submitted" submission)
      (let [input (ask-user effects log (str "Agent wants to finish. Type new task or Enter"
                                             " to quit (/h for commands)\n> "))]
        (cond
          (= "" input) (exit log (now) "Submitted" submission)
          (= "/u" input) (-> log
                             (switch-mode (now) :human)
                             (interrupt (now) "UserInterruption" "Switched to human mode."))
          (mode-commands input) (switch-mode log (now) (mode-commands input))
          :else (interrupt log (now) "UserNewTask" (str "The user added a new task: " input)))))))

(defn step
  "Appends the entries of the next step."
  [effects log]
  (case (log/phase log)
    :query (query-model effects log)
    :execute (execute-actions effects log)
    :submit (submit effects log)
    :done log))

(defn crash
  "Appends the exit of an uncaught exception."
  [log now e]
  (let [trace (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. trace))
    (record log now {:type :run/exited
                     :status (.getSimpleName (class e))
                     :submission ""
                     :exception (str e)
                     :traceback (str trace)})))

(defn run!
  "Steps the log in the atom `!agent` until the run exits and returns the
  final log. On an uncaught exception the run exits with the exception's
  class name as status and the exception is rethrown."
  [{:keys [clock/now] :as effects} !agent]
  (while (not= :done (log/phase @!agent))
    (let [log @!agent]
      (try
        (reset! !agent (step effects log))
        (catch Exception e
          (reset! !agent (crash log (now) e))
          (throw e)))))
  @!agent)

(comment
  ;; A run from the REPL, in the background, so that the atom can be
  ;; inspected while the agent works:
  (require '[minisweagent.environment.local :as local]
           '[minisweagent.model.http :as http]
           '[minisweagent.config :as config])

  (def !agent
    (atom (init {:config (config/build ["mini.edn" "agent.mode=:yolo" "agent.confirm-exit=false"])
                 :task "Create hello.py that prints Hello, World!"
                 :vars (config/platform-vars)
                 :now (System/currentTimeMillis)})))

  (def run
    (future (run! {:model/query (http/query-fn {})
                   :env/execute local/execute
                   :clock/now #(System/currentTimeMillis)}
                  !agent)))

  (log/phase @!agent)
  (log/cost @!agent)
  (map :type @!agent)
  )
