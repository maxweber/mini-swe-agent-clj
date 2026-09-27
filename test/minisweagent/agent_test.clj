(ns minisweagent.agent-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [minisweagent.agent :as agent]
            [minisweagent.config :as config]
            [minisweagent.environment.local :as local]
            [minisweagent.log :as log]
            [minisweagent.model.anthropic :as anthropic]
            [minisweagent.model.openai :as openai]))

(def submit
  "echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT")

(defn- test-config
  [& specs]
  (config/build (into ["mini.edn"
                       {:model {:name "anthropic/claude-opus-5"}
                        :agent {:mode :yolo
                                :confirm-exit false}}]
                      specs)))

(defn- tool-use
  "A normalized Anthropic response calling bash with `commands`."
  [text & commands]
  (anthropic/response
   {:content (into [{:type "text" :text text}]
                   (map-indexed (fn [i command]
                                  {:type "tool_use"
                                   :id (str "toolu_" i)
                                   :name "bash"
                                   :input {:command command}})
                                commands))
    :stop_reason "tool_use"
    :usage {:input_tokens 1000
            :output_tokens 100}
    :model "claude-opus-5"}))

(defn- scripted
  "A function that returns the n-th value on the n-th call."
  [& values]
  (let [!values (atom values)]
    (fn [& _]
      (ffirst (swap-vals! !values rest)))))

(defn- fake-shell
  [_environment-config command]
  {:output (if (= submit command)
             "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\n"
             (str "ran " command "\n"))
   :returncode 0
   :exception-info ""})

(defn- run
  [config effects]
  (agent/run! (merge {:env/execute fake-shell
                      :clock/now (constantly 0)}
                     effects)
              (atom (agent/init {:config config
                                 :task "Fix the bug"
                                 :vars (config/platform-vars)
                                 :now 0}))))

(defn- exit-status
  [log]
  (:status (log/exit log)))

(defn- roles
  [log]
  (map :role (log/conversation log)))

(deftest submit-test
  (let [final (run (test-config)
                   {:model/query (scripted (tool-use "Let me look." "ls")
                                           (tool-use "Done." submit))})]
    (is (= "Submitted" (exit-status final)))
    (is (= [:run/started :prompt/rendered :prompt/rendered
            :model/responded :actions/observed
            :model/responded :actions/observed
            :run/exited]
           (map :type final)))
    (is (= ["system" "user" "assistant" "user" "assistant" "user"]
           (roles final)))
    (is (str/includes? (:content (second (log/conversation final)))
                       "Please solve this issue: Fix the bug"))
    (is (= [{:type "tool_result"
             :tool_use_id "toolu_0"
             :content "{\"returncode\":0,\"output\":\"ran ls\\n\"}"}]
           (:content (nth (log/conversation final) 3))))
    (is (= 2 (log/n-calls final)))
    (is (= (* 2 (/ (+ (* 1000 5.0) (* 100 25.0)) 1e6))
           (log/cost final)))))

(deftest actions-after-submit-are-not-executed-test
  (let [final (run (test-config)
                   {:model/query (scripted (tool-use "Done." submit "rm -rf /"))})]
    (is (= "Submitted" (exit-status final)))
    (is (= [{:type "tool_result"
             :tool_use_id "toolu_1"
             :content "{\"returncode\":-1,\"output\":\"\",\"exception_info\":\"action was not executed\"}"}]
           (rest (:content (nth (log/conversation final) 3)))))))

(deftest format-error-test
  (let [final (run (test-config)
                   {:model/query (scripted (tool-use "No tool call.")
                                           (tool-use "Done." submit))})]
    (is (= "Submitted" (exit-status final)))
    (is (= 2 (log/n-calls final)))
    (is (= 0 (log/consecutive-format-errors final)))
    (is (= ["system" "user" "user" "assistant" "user"] (roles final))
        "the malformed assistant message is not part of the conversation")
    (is (str/includes? (:content (nth (log/conversation final) 2)) "No tool calls found"))
    (is (= "No tool call." (get-in final [3 :response :content 0 :text]))
        "but it is in the log")))

(deftest repeated-format-error-test
  (let [final (run (test-config)
                   {:model/query (constantly (tool-use "I think I'm done."))})]
    (is (= "RepeatedFormatError" (exit-status final)))
    (is (= 3 (log/n-calls final)))
    (is (= 3 (log/consecutive-format-errors final)))
    (is (= ["system" "user" "user" "user" "user"] (roles final)))))

(deftest limits-test
  (let [query (constantly (tool-use "Again." "ls"))]
    (is (= 1 (log/n-calls (run (test-config {:agent {:step-limit 1}})
                               {:model/query query}))))
    (let [final (run (test-config {:agent {:cost-limit 0.01}})
                     {:model/query query})]
      (is (= "LimitsExceeded" (exit-status final)))
      (is (= 2 (log/n-calls final))))
    (let [final (run (test-config {:agent {:wall-time-limit-seconds 1}})
                     {:model/query query
                      :clock/now (constantly 5000)})]
      (is (= "TimeExceeded" (exit-status final)))
      (is (= 0 (log/n-calls final))))))

(deftest raise-limits-test
  (let [final (run (test-config {:agent {:mode :confirm
                                         :step-limit 1
                                         :whitelist-actions ["ls\\b" "echo "]}})
                   {:model/query (scripted (tool-use "Look." "ls")
                                           (tool-use "Done." submit))
                    :user/ask (scripted "5" "1.5")})]
    (is (= "Submitted" (exit-status final)))
    (is (= {:type :limits/raised :step-limit 5 :cost-limit 1.5 :at 0}
           (first (filter #(= :limits/raised (:type %)) final))))
    (is (= {:step-limit 5 :cost-limit 1.5}
           (select-keys (:agent (log/config final)) [:step-limit :cost-limit])))
    (is (= 1 (get-in (first final) [:config :agent :step-limit]))
        "the config the run started with stays in the log")))

(deftest confirm-test
  (let [final (run (test-config {:agent {:mode :confirm}})
                   {:model/query (scripted (tool-use "Look." "ls")
                                           (tool-use "Done." submit))
                    :user/ask (scripted "Read the README first" "")})
        conversation (log/conversation final)]
    (is (= "Submitted" (exit-status final)))
    (is (= ["system" "user" "assistant" "user" "user" "assistant" "user"]
           (roles final)))
    (is (str/includes? (get-in conversation [3 :content 0 :content]) "action was not executed"))
    (is (= (str "Commands not executed. The user rejected your commands with the"
                " following message: Read the README first")
           (get-in conversation [4 :content])))))

(deftest mode-switch-test
  (let [final (run (test-config {:agent {:mode :confirm}})
                   {:model/query (scripted (tool-use "Look." "ls")
                                           (tool-use "Done." submit))
                    :user/ask (scripted "/y")})]
    (is (= "Submitted" (exit-status final)))
    (is (= :yolo (log/mode final)))
    (is (= [{:type :mode/switched :mode :yolo :at 0}]
           (filter #(= :mode/switched (:type %)) final))
        "the switch is a fact in the log, not an overwritten config")))

(deftest whitelist-test
  (let [final (run (test-config {:agent {:mode :confirm
                                         :whitelist-actions ["ls\\b" "echo "]}})
                   {:model/query (scripted (tool-use "Look." "ls -la")
                                           (tool-use "Done." submit))})]
    (is (= "Submitted" (exit-status final)))))

(deftest missing-user-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :user/ask"
                        (run (test-config {:agent {:mode :confirm}})
                             {:model/query (scripted (tool-use "Look." "ls"))}))))

(deftest human-mode-test
  (let [final (run (test-config {:agent {:mode :human}})
                   {:model/query (fn [& _] (throw (ex-info "no model in human mode" {})))
                    :user/ask (scripted submit)})]
    (is (= "Submitted" (exit-status final)))
    (is (= 0 (log/n-calls final)))
    (is (= ["system" "user" "user" "user"] (roles final)))
    (is (= [{:command submit}]
           (:actions (first (filter #(= :user/commanded (:type %)) final)))))))

(deftest new-task-test
  (let [final (run (test-config {:agent {:confirm-exit true}})
                   {:model/query (scripted (tool-use "Done." submit)
                                           (tool-use "Done again." submit))
                    :user/ask (scripted "Also add a test" "")})]
    (is (= "Submitted" (exit-status final)))
    (is (= 2 (log/n-calls final)))
    (is (some #(= "The user added a new task: Also add a test" (:content %))
              (log/conversation final)))))

(deftest textbased-test
  (let [response (openai/response
                  {:choices [{:message {:role "assistant"
                                        :content (str "THOUGHT: done.\n\n```mswea_bash_command\n"
                                                      submit "\n```")}
                              :finish_reason "stop"}]
                   :usage {:prompt_tokens 10 :completion_tokens 5}})
        final (run (config/build ["mini-textbased.edn"
                                  {:model {:name "openai/local-llm"
                                           :cost-tracking :ignore-errors}
                                   :agent {:mode :yolo
                                           :confirm-exit false}}])
                   {:model/query (scripted response)})]
    (is (= "Submitted" (exit-status final)))
    (is (= ["system" "user" "assistant" "user"] (roles final)))
    (is (= "{\"returncode\":0,\"output\":\"COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\\n\"}"
           (:content (peek (log/conversation final)))))))

(deftest submit-with-unexecuted-tool-call-test
  (let [tool-call (fn [id command]
                    {:id id
                     :type "function"
                     :function {:name "bash"
                                :arguments (json/write-str {:command command})}})
        response (openai/response
                  {:choices [{:message {:role "assistant"
                                        :content "Done."
                                        :tool_calls [(tool-call "call_1" submit)
                                                     (tool-call "call_2" "rm -rf /")]}
                              :finish_reason "tool_calls"}]})
        final (run (config/build ["mini.edn"
                                  {:model {:name "openai/local-llm"
                                           :cost-tracking :ignore-errors}
                                   :agent {:mode :yolo
                                           :confirm-exit true}}])
                   {:model/query (scripted response)
                    :user/ask (scripted "/c" "")})]
    (is (= "Submitted" (exit-status final)))
    (is (= :confirm (log/mode final)) "a mode switch at the finish prompt asks again")
    (is (= ["system" "user" "assistant" "tool" "tool"] (roles final)))
    (is (str/includes? (:content (peek (log/conversation final))) "action was not executed"))))

(deftest crash-test
  (let [!agent (atom (agent/init {:config (test-config)
                                  :task "Fix the bug"
                                  :vars (config/platform-vars)
                                  :now 0}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overloaded"
                          (agent/run! {:model/query (fn [& _] (throw (ex-info "overloaded" {})))
                                       :clock/now (constantly 0)}
                                      !agent)))
    (is (= "ExceptionInfo" (exit-status @!agent)))
    (is (= :done (log/phase @!agent)))))

(deftest fork-test
  (let [final (run (test-config)
                   {:model/query (scripted (tool-use "Let me look." "ls")
                                           (tool-use "Done." submit))})
        prefix (subvec final 0 5)]
    (is (= :query (log/phase prefix)) "every prefix of the log is a state")
    (is (= 1 (log/n-calls prefix)))
    (is (= "Submitted"
           (exit-status (agent/run! {:model/query (scripted (tool-use "Done." submit))
                                     :env/execute fake-shell
                                     :clock/now (constantly 0)}
                                    (atom prefix)))))))

(deftest local-environment-test
  (let [dir (str (java.nio.file.Files/createTempDirectory "minisweagent"
                                                         (make-array java.nio.file.attribute.FileAttribute 0)))
        final (run (test-config {:environment {:cwd dir}})
                   {:model/query (scripted (tool-use "Write." "printf hi > hello.txt && cat hello.txt")
                                           (tool-use "Done." submit))
                    :env/execute local/execute})]
    (is (= "Submitted" (exit-status final)))
    (is (= [{:output "hi" :returncode 0 :exception-info ""}]
           (:outputs (first (filter #(= :actions/observed (:type %)) final)))))
    (is (= "hi" (slurp (str dir "/hello.txt"))))))
