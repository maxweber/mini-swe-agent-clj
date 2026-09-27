(ns minisweagent.model.openai-test
  (:require [clojure.test :refer [deftest is]]
            [minisweagent.model.openai :as openai]))

(deftest request-test
  (is (= {:url "https://openrouter.ai/api/v1/chat/completions"
          :headers {}
          :body {:model "moonshotai/kimi-k2"
                 :usage {:include true}
                 :messages [{:role "system" :content "Be helpful."}
                            {:role "user" :content "Fix it."}]
                 :tools [openai/bash-tool]}}
         (openai/request {:id "moonshotai/kimi-k2"
                          :base-url "https://openrouter.ai/api/v1"
                          :params {:usage {:include true}}
                          :action-format :toolcall}
                         [{:role "system" :content "Be helpful." :extra {:timestamp 1}}
                          {:role "user" :content "Fix it."}]))))

(deftest response-test
  (let [message {:role "assistant"
                 :content nil
                 :tool_calls [{:id "call_1" :type "function"
                               :function {:name "bash" :arguments "{\"command\": \"ls\"}"}}
                              {:id "call_2" :type "function"
                               :function {:name "bash" :arguments "{\"command\": "}}]}
        response (openai/response {:choices [{:message message :finish_reason "tool_calls"}]
                                   :usage {:prompt_tokens 100
                                           :completion_tokens 20
                                           :prompt_tokens_details {:cached_tokens 60}
                                           :cost 0.0012}
                                   :model "moonshotai/kimi-k2"})]
    (is (= message (:message response)))
    (is (= "" (:text response)))
    (is (= {:id "call_1" :name "bash" :input {:command "ls"}} (first (:tool-calls response))))
    (is (string? (:input-error (second (:tool-calls response)))))
    (is (= {:input 40 :output 20 :cache-read 60} (:tokens response)))
    (is (= 0.0012 (:reported-cost response)))
    (is (true? (:truncated? (openai/response {:choices [{:message {:content "cut"}
                                                         :finish_reason "length"}]}))))))

(deftest observations-test
  (is (= [{:role "tool" :tool_call_id "call_1" :content "{1}" :extra {:outputs [:o1]}}
          {:role "tool" :tool_call_id "call_2" :content "{2}" :extra {:outputs [:o2]}}]
         (openai/observations [{:action {:command "a" :tool-call-id "call_1"} :output :o1 :text "{1}"}
                               {:action {:command "b" :tool-call-id "call_2"} :output :o2 :text "{2}"}]))))
