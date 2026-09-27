(ns minisweagent.agent
  "The agent is one immutable value:

      {:task       the task
       :config     {:agent {...} :model {...} :environment {...}}
       :messages   [system, instance, assistant, observation, ..., exit]
       :cost       USD spent so far
       :n-calls    model calls so far
       :n-format-errors  consecutive format errors
       :started-at epoch ms}

  The messages decide what happens next (see `phase`). `step` computes
  the next value and reaches the outside world only through the effect
  functions it receives:

      {:model/query (fn [model-config messages] normalized-response)
       :env/execute (fn [environment-config command] output)
       :user/ask    (fn [{:keys [prompt mode]}] input)
       :clock/now   (fn [] epoch-ms)}

  `run!` keeps the current value in an atom, the only mutable place.
  Printing and saving the trajectory are watches on that atom."
  (:refer-clojure :exclude [run!])
  (:require [minisweagent.actions :as actions]
            [minisweagent.model :as model]
            [minisweagent.observation :as observation]
            [minisweagent.template :as template]))

(defn- append
  [agent now messages]
  (update agent :messages into (map #(assoc-in % [:extra :timestamp] now)) messages))

(defn init
  "The agent value at the start of a run. `vars` are the values for the
  system and instance templates besides `:task`, e.g. the platform."
  [{:keys [config task vars now]}]
  (let [{:keys [system-template instance-template]} (:agent config)
        template-vars (assoc vars :task task)]
    (append {:task task
             :config config
             :messages []
             :cost 0.0
             :n-calls 0
             :n-format-errors 0
             :started-at now}
            now
            [{:role "system"
              :content (template/render system-template template-vars)}
             {:role "user"
              :content (template/render instance-template template-vars)}])))

(defn- last-outputs
  "The outputs of the trailing observation messages."
  [messages]
  (mapcat #(get-in % [:extra :outputs])
          (take-while #(get-in % [:extra :outputs]) (rseq messages))))

(defn phase
  "What happens next: :query the model, :execute the actions of the last
  message, :submit after an output submitted the task, or :done."
  [{:keys [messages]}]
  (let [{:keys [role extra]} (peek messages)]
    (cond
      (= "exit" role) :done
      (seq (:actions extra)) :execute
      (some observation/submission (last-outputs messages)) :submit
      :else :query)))

(defn mode
  "`:confirm` asks before executing, `:yolo` executes right away and in
  `:human` mode the user types the commands."
  [agent]
  (keyword (get-in agent [:config :agent :mode] :yolo)))

(def mode-commands
  {"/y" :yolo
   "/c" :confirm
   "/u" :human})

(defn- set-mode
  [agent new-mode]
  (assoc-in agent [:config :agent :mode] new-mode))

(defn- exit
  ([agent now status]
   (exit agent now status nil))
  ([agent now status submission]
   (append agent now [{:role "exit"
                       :content (or submission status)
                       :extra {:exit-status status
                               :submission (or submission "")}}])))

(defn- interrupt
  [agent now interrupt-type content]
  (append agent now [{:role "user"
                      :content content
                      :extra {:interrupt-type interrupt-type}}]))

(defn- ask-user
  [{:keys [user/ask]} agent prompt]
  (when-not ask
    (throw (ex-info (str "Mode " (mode agent) " and confirm-exit need a user,"
                         " but the effects have no :user/ask.")
                    {:mode (mode agent)})))
  (ask {:prompt prompt
        :mode (mode agent)}))

(defn- reached?
  [limit value]
  (and (pos? (or limit 0))
       (<= limit value)))

(defn limits-exceeded?
  [{:keys [config n-calls cost]}]
  (let [{:keys [step-limit cost-limit]} (:agent config)]
    (or (reached? step-limit n-calls)
        (reached? cost-limit cost))))

(defn time-exceeded?
  [{:keys [config started-at]} now]
  (reached? (get-in config [:agent :wall-time-limit-seconds])
            (quot (- now started-at) 1000)))

(defn- format-error
  [agent now {:keys [error truncated?] :as extra}]
  (let [{:keys [format-error-template truncated-response-template
                max-consecutive-format-errors]} (get-in agent [:config :agent])
        n-format-errors (inc (:n-format-errors agent))
        content (template/render (if truncated?
                                   truncated-response-template
                                   format-error-template)
                                 {:error error})
        agent (-> agent
                  (assoc :n-format-errors n-format-errors)
                  (append now [{:role "user"
                                :content content
                                :extra (assoc extra :interrupt-type "FormatError")}]))]
    (if (reached? max-consecutive-format-errors n-format-errors)
      (exit agent now "RepeatedFormatError")
      agent)))

(defn receive
  "Adds a model response: the assistant message with its actions, or a
  format error message for the model. A malformed assistant message is
  not added, like in mini-swe-agent."
  [agent now response]
  (let [model-config (get-in agent [:config :model])
        cost (model/cost model-config response)
        {:keys [actions error]} (actions/parse (:action-format model-config) response)
        agent (-> agent
                  (update :n-calls inc)
                  (update :cost + cost))
        extra {:cost cost
               :tokens (:tokens response)
               :model (:model response)}]
    (if error
      (format-error agent now (assoc extra
                                     :error error
                                     :truncated? (:truncated? response)
                                     :response (:message response)))
      (-> agent
          (assoc :n-format-errors 0)
          (append now [(assoc (:message response)
                              :extra (assoc extra :actions actions))])))))

(defn- human-command
  [{:keys [clock/now] :as effects} agent]
  (let [input (ask-user effects agent "> ")]
    (cond
      (mode-commands input) (set-mode agent (mode-commands input))
      (= "" input) agent
      :else (append agent (now) [{:role "user"
                                  :content (str "User command: \n```bash\n" input "\n```")
                                  :extra {:actions [{:command input}]}}]))))

(defn- new-limits
  "In a mode with a user, the user may raise the limits instead of exiting."
  [{:keys [clock/now] :as effects} agent]
  (let [{:keys [step-limit cost-limit]} (get-in agent [:config :agent])
        ask? (#{:confirm :human} (mode agent))
        new-step-limit (when ask?
                         (parse-long (ask-user effects agent
                                               (format (str "Limits exceeded. Limits: %s steps, $%s."
                                                            " Current spend: %s steps, $%.2f.\n"
                                                            "New step limit (Enter to stop): ")
                                                       step-limit cost-limit
                                                       (:n-calls agent) (:cost agent)))))
        new-cost-limit (when new-step-limit
                         (parse-double (ask-user effects agent "New cost limit: ")))]
    (if new-cost-limit
      (update-in agent [:config :agent] assoc
                 :step-limit new-step-limit
                 :cost-limit new-cost-limit)
      (exit agent (now) "LimitsExceeded"))))

(defn- query-model
  [{:keys [model/query clock/now] :as effects} agent]
  (cond
    (= :human (mode agent)) (human-command effects agent)
    (time-exceeded? agent (now)) (exit agent (now) "TimeExceeded")
    (limits-exceeded? agent) (new-limits effects agent)
    :else (let [response (query (get-in agent [:config :model]) (:messages agent))]
            (receive agent (now) response))))

(defn observe
  "Adds the observation messages for the `outputs` of `actions`. A tool call
  without output is answered as not executed, since every tool call needs a
  result."
  [agent now actions outputs]
  (let [max-chars (get-in agent [:config :agent :output-max-chars] 10000)
        results (keep (fn [[action output]]
                        (when-let [output (or output
                                              (when (:tool-call-id action)
                                                observation/not-executed))]
                          {:action action
                           :output output
                           :text (observation/text max-chars output)}))
                      (map vector actions (concat outputs (repeat nil))))]
    (append agent now (model/observations (get-in agent [:config :model]) results))))

(defn- run-actions
  "Executes the actions in order until one submits."
  [{:keys [env/execute clock/now]} agent actions]
  (let [environment-config (get-in agent [:config :environment])
        outputs (reduce (fn [outputs {:keys [command]}]
                          (let [outputs (conj outputs (execute environment-config command))]
                            (if (observation/submission (peek outputs))
                              (reduced outputs)
                              outputs)))
                        []
                        actions)]
    (observe agent (now) actions outputs)))

(defn- whitelisted?
  [patterns command]
  (some #(.lookingAt (re-matcher (re-pattern %) command)) patterns))

(defn- needs-confirmation?
  [agent commands]
  (let [patterns (get-in agent [:config :agent :whitelist-actions])]
    (and (= :confirm (mode agent))
         (not-every? #(whitelisted? patterns %) commands))))

(defn- reject
  [agent now actions interrupt-type content]
  (-> agent
      (observe now actions [])
      (interrupt now interrupt-type content)))

(defn- execute-actions
  [{:keys [clock/now] :as effects} agent]
  (let [actions (get-in (peek (:messages agent)) [:extra :actions])]
    (if (needs-confirmation? agent (map :command actions))
      (let [input (ask-user effects agent (str "Execute " (count actions) " action(s)? Enter to"
                                               " confirm, type a comment to reject,"
                                               " /h for commands\n> "))]
        (case input
          ("" "/c") (run-actions effects agent actions)
          "/y" (run-actions effects (set-mode agent :yolo) actions)
          "/u" (reject (set-mode agent :human) (now) actions "UserRejection"
                       "Commands not executed. Switching to human mode")
          (reject agent (now) actions "UserRejection"
                  (str "Commands not executed. The user rejected your commands with"
                       " the following message: " input))))
      (run-actions effects agent actions))))

(defn- submit
  "The agent submitted its work. With `:confirm-exit` the user may give a new
  task instead."
  [{:keys [clock/now] :as effects} agent]
  (let [submission (some observation/submission (last-outputs (:messages agent)))]
    (if-not (get-in agent [:config :agent :confirm-exit])
      (exit agent (now) "Submitted" submission)
      (let [input (ask-user effects agent (str "Agent wants to finish. Type new task or Enter"
                                               " to quit (/h for commands)\n> "))]
        (cond
          (= "" input) (exit agent (now) "Submitted" submission)
          (= "/u" input) (-> agent
                             (set-mode :human)
                             (interrupt (now) "UserInterruption" "Switched to human mode."))
          (mode-commands input) (set-mode agent (mode-commands input))
          :else (interrupt agent (now) "UserNewTask" (str "The user added a new task: " input)))))))

(defn step
  "The next agent value."
  [effects agent]
  (case (phase agent)
    :query (query-model effects agent)
    :execute (execute-actions effects agent)
    :submit (submit effects agent)
    :done agent))

(defn crash
  "Adds an exit message for an uncaught exception."
  [agent now e]
  (let [trace (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. trace))
    (append agent now [{:role "exit"
                        :content (str e)
                        :extra {:exit-status (.getSimpleName (class e))
                                :submission ""
                                :exception (str e)
                                :traceback (str trace)}}])))

(defn run!
  "Steps the agent value in the atom `!agent` until it exits and returns the
  final value. On an uncaught exception the agent exits with the exception's
  class name as status and the exception is rethrown."
  [{:keys [clock/now] :as effects} !agent]
  (while (not= :done (phase @!agent))
    (let [agent @!agent]
      (try
        (reset! !agent (step effects agent))
        (catch Exception e
          (reset! !agent (crash agent (now) e))
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

  (phase @!agent)
  (:cost @!agent)
  (map :role (:messages @!agent))
  )
