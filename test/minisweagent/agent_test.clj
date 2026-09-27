(ns minisweagent.agent-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [minisweagent.agent :as agent]
            [minisweagent.config :as config]
            [minisweagent.environment.local :as local]
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

(defn- n-model-calls
  [messages]
  (count (filter #(or (= "assistant" (:role %))
                      (= "FormatError" (get-in % [:extra :interrupt-type])))
                 messages)))

(defn- scripted-model
  "Answers the n-th model call with the n-th response."
  [& responses]
  (fn [_model-config messages]
    (nth responses (n-model-calls messages))))

(defn- fake-shell
  [_environment-config command]
  {:output (if (= submit command)
             "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\n"
             (str "ran " command "\n"))
   :returncode 0
   :exception-info ""})

(defn- scripted-user
  "Answers the n-th question with the n-th answer."
  [& answers]
  (let [!answers (atom answers)]
    (fn [_request]
      (ffirst (swap-vals! !answers rest)))))

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
  [agent]
  (get-in (peek (:messages agent)) [:extra :exit-status]))

(deftest submit-test
  (let [final (run (test-config)
                   {:model/query (scripted-model (tool-use "Let me look." "ls")
                                                 (tool-use "Done." submit))})]
    (is (= "Submitted" (exit-status final)))
    (is (= ["system" "user" "assistant" "user" "assistant" "user" "exit"]
           (map :role (:messages final))))
    (is (str/includes? (:content (second (:messages final)))
                       "Please solve this issue: Fix the bug"))
    (is (= [{:type "tool_result"
             :tool_use_id "toolu_0"
             :content "{\"returncode\":0,\"output\":\"ran ls\\n\"}"}]
           (:content (nth (:messages final) 3))))
    (is (= 2 (:n-calls final)))
    (is (= (* 2 (/ (+ (* 1000 5.0) (* 100 25.0)) 1e6))
           (:cost final)))))

(deftest actions-after-submit-are-not-executed-test
  (let [final (run (test-config)
                   {:model/query (scripted-model (tool-use "Done." submit "rm -rf /"))})]
    (is (= "Submitted" (exit-status final)))
    (is (= [{:type "tool_result"
             :tool_use_id "toolu_1"
             :content "{\"returncode\":-1,\"output\":\"\",\"exception_info\":\"action was not executed\"}"}]
           (rest (:content (nth (:messages final) 3)))))))

(deftest format-error-test
  (let [final (run (test-config)
                   {:model/query (scripted-model (tool-use "No tool call.")
                                                 (tool-use "Done." submit))})]
    (is (= "Submitted" (exit-status final)))
    (is (= 2 (:n-calls final)))
    (is (= 0 (:n-format-errors final)))
    (is (str/includes? (:content (nth (:messages final) 2)) "No tool calls found"))))

(deftest repeated-format-error-test
  (let [final (run (test-config)
                   {:model/query (constantly (tool-use "I think I'm done."))})]
    (is (= "RepeatedFormatError" (exit-status final)))
    (is (= 3 (:n-calls final)))
    (is (= ["system" "user" "user" "user" "user" "exit"]
           (map :role (:messages final))))))

(deftest limits-test
  (let [query (constantly (tool-use "Again." "ls"))]
    (is (= 1 (:n-calls (run (test-config {:agent {:step-limit 1}})
                            {:model/query query}))))
    (let [final (run (test-config {:agent {:cost-limit 0.01}})
                     {:model/query query})]
      (is (= "LimitsExceeded" (exit-status final)))
      (is (= 2 (:n-calls final))))
    (let [final (run (test-config {:agent {:wall-time-limit-seconds 1}})
                     {:model/query query
                      :clock/now (constantly 5000)})]
      (is (= "TimeExceeded" (exit-status final)))
      (is (= 0 (:n-calls final))))))

(deftest raise-limits-test
  (let [final (run (test-config {:agent {:mode :confirm
                                         :step-limit 1
                                         :whitelist-actions ["ls\\b" "echo "]}})
                   {:model/query (scripted-model (tool-use "Look." "ls")
                                                 (tool-use "Done." submit))
                    :user/ask (scripted-user "5" "1.5")})]
    (is (= "Submitted" (exit-status final)))
    (is (= {:step-limit 5 :cost-limit 1.5}
           (select-keys (get-in final [:config :agent]) [:step-limit :cost-limit])))))

(deftest confirm-test
  (let [final (run (test-config {:agent {:mode :confirm}})
                   {:model/query (scripted-model (tool-use "Look." "ls")
                                                 (tool-use "Done." submit))
                    :user/ask (scripted-user "Read the README first" "")})
        messages (:messages final)]
    (is (= "Submitted" (exit-status final)))
    (is (= ["system" "user" "assistant" "user" "user" "assistant" "user" "exit"]
           (map :role messages)))
    (is (str/includes? (get-in messages [3 :content 0 :content]) "action was not executed"))
    (is (= (str "Commands not executed. The user rejected your commands with the"
                " following message: Read the README first")
           (get-in messages [4 :content])))))

(deftest whitelist-test
  (let [final (run (test-config {:agent {:mode :confirm
                                         :whitelist-actions ["ls\\b" "echo "]}})
                   {:model/query (scripted-model (tool-use "Look." "ls -la")
                                                 (tool-use "Done." submit))})]
    (is (= "Submitted" (exit-status final)))))

(deftest missing-user-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :user/ask"
                        (run (test-config {:agent {:mode :confirm}})
                             {:model/query (scripted-model (tool-use "Look." "ls"))}))))

(deftest human-mode-test
  (let [final (run (test-config {:agent {:mode :human}})
                   {:model/query (fn [& _] (throw (ex-info "no model in human mode" {})))
                    :user/ask (scripted-user submit)})]
    (is (= "Submitted" (exit-status final)))
    (is (= 0 (:n-calls final)))
    (is (= ["system" "user" "user" "user" "exit"]
           (map :role (:messages final))))
    (is (= [{:command submit}]
           (get-in final [:messages 2 :extra :actions])))))

(deftest new-task-test
  (let [final (run (test-config {:agent {:confirm-exit true}})
                   {:model/query (scripted-model (tool-use "Done." submit)
                                                 (tool-use "Done again." submit))
                    :user/ask (scripted-user "Also add a test" "")})]
    (is (= "Submitted" (exit-status final)))
    (is (= 2 (:n-calls final)))
    (is (some #(= "The user added a new task: Also add a test" (:content %))
              (:messages final)))))

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
                   {:model/query (scripted-model response)})]
    (is (= "Submitted" (exit-status final)))
    (is (= ["system" "user" "assistant" "user" "exit"]
           (map :role (:messages final))))
    (is (= "{\"returncode\":0,\"output\":\"COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\\n\"}"
           (get-in final [:messages 3 :content])))))

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
                   {:model/query (scripted-model response)
                    :user/ask (scripted-user "/c" "")})]
    (is (= "Submitted" (exit-status final)))
    (is (= :confirm (agent/mode final)) "a mode switch at the finish prompt asks again")
    (is (= ["system" "user" "assistant" "tool" "tool" "exit"]
           (map :role (:messages final))))
    (is (str/includes? (get-in final [:messages 4 :content]) "action was not executed"))))

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
    (is (= :done (agent/phase @!agent)))))

(deftest local-environment-test
  (let [dir (str (java.nio.file.Files/createTempDirectory "minisweagent"
                                                         (make-array java.nio.file.attribute.FileAttribute 0)))
        final (run (test-config {:environment {:cwd dir}})
                   {:model/query (scripted-model (tool-use "Write." "printf hi > hello.txt && cat hello.txt")
                                                 (tool-use "Done." submit))
                    :env/execute local/execute})]
    (is (= "Submitted" (exit-status final)))
    (is (= [{:output "hi" :returncode 0 :exception-info ""}]
           (get-in final [:messages 3 :extra :outputs])))
    (is (= "hi" (slurp (str dir "/hello.txt"))))))
