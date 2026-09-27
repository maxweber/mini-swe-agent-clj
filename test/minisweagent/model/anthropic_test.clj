(ns minisweagent.model.anthropic-test
  (:require [clojure.test :refer [deftest is]]
            [minisweagent.model.anthropic :as anthropic]))

(def model-config
  {:id "claude-opus-5"
   :base-url "https://api.anthropic.com/v1"
   :headers {"anthropic-beta" "server-side-fallback-2026-07-01"}
   :params {:max_tokens 16000}
   :action-format :toolcall})

(deftest request-test
  (let [{:keys [url headers body]}
        (anthropic/request model-config
                           [{:role "system" :content "Be helpful." :extra {:timestamp 1}}
                            {:role "user" :content "Fix it." :extra {:timestamp 1}}])]
    (is (= "https://api.anthropic.com/v1/messages" url))
    (is (= {"anthropic-version" "2023-06-01"
            "anthropic-beta" "server-side-fallback-2026-07-01"}
           headers))
    (is (= {:model "claude-opus-5"
            :max_tokens 16000
            :system "Be helpful."
            :messages [{:role "user" :content "Fix it."}]
            :tools [anthropic/bash-tool]}
           body)))
  (is (not (contains? (:body (anthropic/request (assoc model-config :action-format :text)
                                                [{:role "user" :content "Fix it."}]))
                      :tools))))

(deftest response-test
  (let [content [{:type "thinking" :thinking "" :signature "sig"}
                 {:type "text" :text "Let me look."}
                 {:type "tool_use" :id "toolu_1" :name "bash" :input {:command "ls"}}]]
    (is (= {:message {:role "assistant" :content content}
            :text "Let me look."
            :tool-calls [{:id "toolu_1" :name "bash" :input {:command "ls"}}]
            :truncated? false
            :tokens {:input 10 :output 20 :cache-write 0 :cache-read 30}
            :model "claude-opus-5"}
           (anthropic/response {:content content
                                :stop_reason "tool_use"
                                :usage {:input_tokens 10
                                        :output_tokens 20
                                        :cache_creation_input_tokens nil
                                        :cache_read_input_tokens 30}
                                :model "claude-opus-5"})))))

(deftest observations-test
  (is (= [{:role "user"
           :content [{:type "tool_result" :tool_use_id "toolu_1" :content "{1}"}
                     {:type "tool_result" :tool_use_id "toolu_2" :content "{2}"}]
           :extra {:outputs [:o1 :o2]}}]
         (anthropic/observations [{:action {:command "a" :tool-call-id "toolu_1"} :output :o1 :text "{1}"}
                                  {:action {:command "b" :tool-call-id "toolu_2"} :output :o2 :text "{2}"}])))
  (is (= [{:role "user" :content "{1}" :extra {:outputs [:o1]}}]
         (anthropic/observations [{:action {:command "a"} :output :o1 :text "{1}"}])))
  (is (= [] (anthropic/observations []))))
