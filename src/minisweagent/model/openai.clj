(ns minisweagent.model.openai
  "Request and response shapes of OpenAI-compatible chat completion APIs
  (OpenAI, OpenRouter, vLLM, Ollama, LiteLLM proxy, ...)."
  (:require [clojure.data.json :as json]))

(def bash-tool
  {:type "function"
   :function {:name "bash"
              :description "Execute a bash command"
              :parameters {:type "object"
                           :properties {:command {:type "string"
                                                  :description "The bash command to execute"}}
                           :required ["command"]}}})

(defn auth-headers
  [api-key]
  {"Authorization" (str "Bearer " api-key)})

(defn request
  [{:keys [id base-url headers params action-format]} messages]
  {:url (str base-url "/chat/completions")
   :headers (or headers {})
   :body (cond-> (assoc params
                        :model id
                        :messages (vec messages))
           (= :toolcall (keyword action-format)) (assoc :tools [bash-tool]))})

(defn- arguments
  [json-text]
  (try
    {:input (json/read-str json-text :key-fn keyword)}
    (catch Exception e
      {:input-error (ex-message e)})))

(defn response
  "The normalized response, see `minisweagent.model`."
  [{:keys [choices usage model]}]
  (let [{:keys [message finish_reason]} (first choices)
        cached (or (get-in usage [:prompt_tokens_details :cached_tokens]) 0)]
    {:message message
     :text (or (:content message) "")
     :tool-calls (mapv (fn [{:keys [id function]}]
                         (merge {:id id
                                 :name (:name function)}
                                (arguments (:arguments function))))
                       (:tool_calls message))
     :truncated? (= "length" finish_reason)
     :tokens {:input (- (or (:prompt_tokens usage) 0) cached)
              :output (or (:completion_tokens usage) 0)
              :cache-read cached}
     :reported-cost (:cost usage)
     :model model}))

(defn observations
  "One tool message per tool call, one user message per text-based action."
  [results]
  (mapv (fn [{:keys [action output text]}]
          {:message (if-let [id (:tool-call-id action)]
                      {:role "tool"
                       :tool_call_id id
                       :content text}
                      {:role "user"
                       :content text})
           :outputs [output]})
        results))
