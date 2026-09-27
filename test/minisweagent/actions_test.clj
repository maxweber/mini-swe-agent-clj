(ns minisweagent.actions-test
  (:require [clojure.test :refer [deftest is]]
            [minisweagent.actions :as actions]))

(deftest tool-calls-test
  (is (= {:actions [{:command "ls" :tool-call-id "t1"}
                    {:command "pwd" :tool-call-id "t2"}]}
         (actions/parse :toolcall {:tool-calls [{:id "t1" :name "bash" :input {:command "ls"}}
                                                {:id "t2" :name "bash" :input {:command "pwd"}}]})))
  (is (re-find #"No tool calls" (:error (actions/parse :toolcall {:tool-calls []}))))
  (is (= "Unknown tool 'python'."
         (:error (actions/parse :toolcall {:tool-calls [{:id "t1" :name "python" :input {:command "ls"}}]}))))
  (is (= "Missing 'command' argument in bash tool call."
         (:error (actions/parse :toolcall {:tool-calls [{:id "t1" :name "bash" :input {:cmd "ls"}}]}))))
  (is (re-find #"Error parsing tool call arguments"
               (:error (actions/parse :toolcall {:tool-calls [{:id "t1" :name "bash" :input-error "EOF"}]})))))

(deftest text-test
  (is (= {:actions [{:command "ls -la"}]}
         (actions/parse "text" {:text "THOUGHT: look\n\n```mswea_bash_command\nls -la\n```\n"})))
  (is (= "Expected exactly 1 action, found 0."
         (:error (actions/parse :text {:text "```bash\nls\n```"}))))
  (is (= "Expected exactly 1 action, found 2."
         (:error (actions/parse :text {:text "```mswea_bash_command\nls\n```\n```mswea_bash_command\npwd\n```"})))))
