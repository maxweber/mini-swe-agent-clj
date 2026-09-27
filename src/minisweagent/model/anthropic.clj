(ns minisweagent.model.anthropic
  "Request and response shapes of the Anthropic Messages API."
  (:require [clojure.string :as str]))

(def bash-tool
  {:name "bash"
   :description "Execute a bash command"
   :input_schema {:type "object"
                  :properties {:command {:type "string"
                                         :description "The bash command to execute"}}
                  :required ["command"]}})

(defn auth-headers
  [api-key]
  {"x-api-key" api-key})

(defn request
  "The HTTP request for `messages`. Leading system messages become the
  `system` parameter."
  [{:keys [id base-url headers params action-format]} messages]
  (let [[system conversation] (split-with #(= "system" (:role %)) messages)]
    {:url (str base-url "/messages")
     :headers (merge {"anthropic-version" "2023-06-01"} headers)
     :body (cond-> (assoc params
                          :model id
                          :messages (vec conversation))
             (seq system) (assoc :system (str/join "\n\n" (map :content system)))
             (= :toolcall (keyword action-format)) (assoc :tools [bash-tool]))}))

(defn- blocks
  [content block-type]
  (filter #(= block-type (:type %)) content))

(defn response
  "The normalized response, see `minisweagent.model`. The content is kept as
  returned, since thinking blocks must be passed back unchanged."
  [{:keys [content stop_reason usage model]}]
  {:message {:role "assistant"
             :content content}
   :text (str/join "\n" (map :text (blocks content "text")))
   :tool-calls (mapv #(select-keys % [:id :name :input])
                     (blocks content "tool_use"))
   :truncated? (= "max_tokens" stop_reason)
   :tokens {:input (or (:input_tokens usage) 0)
            :output (or (:output_tokens usage) 0)
            :cache-write (or (:cache_creation_input_tokens usage) 0)
            :cache-read (or (:cache_read_input_tokens usage) 0)}
   :model model})

(defn observations
  "All tool results go into one user message, text-based observations into
  one user message each."
  [results]
  (if (:tool-call-id (:action (first results)))
    [{:message {:role "user"
                :content (mapv (fn [{:keys [action text]}]
                                 {:type "tool_result"
                                  :tool_use_id (:tool-call-id action)
                                  :content text})
                               results)}
      :outputs (mapv :output results)}]
    (mapv (fn [{:keys [output text]}]
            {:message {:role "user"
                       :content text}
             :outputs [output]})
          results)))
