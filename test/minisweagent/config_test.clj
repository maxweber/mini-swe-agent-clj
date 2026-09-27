(ns minisweagent.config-test
  (:require [clojure.test :refer [deftest is]]
            [minisweagent.config :as config]))

(deftest read-spec-test
  (is (= {:agent {:step-limit 10}} (config/read-spec "agent.step-limit=10")))
  (is (= {:agent {:mode :yolo}} (config/read-spec "agent.mode=:yolo")))
  (is (= {:model {:name "openai/gpt-5.1"}} (config/read-spec "model.name=openai/gpt-5.1")))
  (is (= {:agent {:system-template "Be brief"}} (config/read-spec "agent.system-template=Be brief")))
  (is (= :toolcall (get-in (config/read-spec "mini.edn") [:model :action-format]))))

(deftest build-test
  (let [config (config/build ["mini.edn"
                              "agent.step-limit=10"
                              {:model {:name "anthropic/claude-opus-5"
                                       :params {:max_tokens 8000}}}])]
    (is (= 10 (get-in config [:agent :step-limit])))
    (is (= 3.0 (get-in config [:agent :cost-limit])))
    (is (= {:name "anthropic/claude-opus-5"
            :id "claude-opus-5"
            :api :anthropic
            :base-url "https://api.anthropic.com/v1"
            :api-key-env "ANTHROPIC_API_KEY"
            :action-format :toolcall
            :params {:max_tokens 8000
                     :cache_control {:type "ephemeral"}
                     :fallbacks "default"}
            :headers {"anthropic-beta" "server-side-fallback-2026-07-01"}
            :price {:input 5.0 :output 25.0}}
           (:model config))))
  (is (= {:id "moonshotai/kimi-k2" :api :openai :base-url "https://openrouter.ai/api/v1"}
         (select-keys (:model (config/build ["mini-textbased.edn" "model.name=openrouter/moonshotai/kimi-k2"]))
                      [:id :api :base-url])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"known provider"
                        (config/build ["mini.edn" "model.name=claude-opus-5"]))))
