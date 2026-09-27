(ns minisweagent.actions
  "Reads the bash commands out of a model response."
  (:require [clojure.string :as str]))

(defn- tool-call-error
  [{:keys [name input input-error]}]
  (cond
    input-error (str "Error parsing tool call arguments: " input-error ".")
    (not= "bash" name) (str "Unknown tool '" name "'.")
    (not (string? (:command input))) "Missing 'command' argument in bash tool call."))

(defn from-tool-calls
  "One action per bash tool call, or an `:error` for the model."
  [{:keys [tool-calls]}]
  (let [errors (keep tool-call-error tool-calls)]
    (cond
      (empty? tool-calls)
      {:error "No tool calls found in the response. Every response MUST include at least one tool call."}

      (seq errors)
      {:error (str/join " " errors)}

      :else
      {:actions (mapv (fn [{:keys [id input]}]
                        {:command (:command input)
                         :tool-call-id id})
                      tool-calls)})))

(def text-action-regex
  #"(?s)```mswea_bash_command\s*\n(.*?)\n```")

(defn from-text
  "The single fenced `mswea_bash_command` block of the response text, or an
  `:error` for the model."
  [{:keys [text]}]
  (let [commands (map (comp str/trim second)
                      (re-seq text-action-regex (or text "")))]
    (if (= 1 (count commands))
      {:actions [{:command (first commands)}]}
      {:error (str "Expected exactly 1 action, found " (count commands) ".")})))

(defn parse
  "The actions of a normalized model response (see `minisweagent.model`),
  depending on the `action-format` :toolcall or :text."
  [action-format response]
  (case (keyword action-format)
    :toolcall (from-tool-calls response)
    :text (from-text response)))

(comment
  (parse :text {:text "Let me look.\n\n```mswea_bash_command\nls -la\n```"})
  (parse :toolcall {:tool-calls [{:id "toolu_1" :name "bash" :input {:command "ls"}}]})
  )
