(ns minisweagent.terminal
  "The user in a terminal: a watch that prints the new messages of the agent
  and the `:user/ask` effect."
  (:require [clojure.string :as str]))

(defn- style
  [ansi-code text]
  (str "\u001b[" ansi-code "m" text "\u001b[0m"))

(def ^:private bold-red (partial style "1;31"))
(def ^:private bold-green (partial style "1;32"))
(def ^:private bold-yellow (partial style "1;33"))
(def ^:private dim (partial style "2"))

(defn- content-text
  "The text blocks of a message content, which is a string or a vector of
  blocks."
  [content]
  (if (string? content)
    content
    (str/join "\n" (keep :text content))))

(defn- output-text
  [{:keys [output returncode exception-info]}]
  (str (when (seq exception-info)
         (str (bold-yellow "exception: ") exception-info "\n"))
       (bold-yellow "returncode: ") returncode "\n"
       output))

(defn- message-text
  [{:keys [role content extra]}]
  (cond
    (:outputs extra)
    (str/join "\n" (map output-text (:outputs extra)))

    (= "assistant" role)
    (str/join "\n\n" (remove str/blank?
                             (cons (content-text content)
                                   (map #(str "```bash\n" (:command %) "\n```")
                                        (:actions extra)))))

    :else
    (content-text content)))

(defn- label
  [agent {:keys [role extra]}]
  (cond
    (= "assistant" role)
    (str (dim (apply str (repeat 72 "─"))) "\n"
         (bold-red (format "mini-swe-agent (step %d, $%.2f):" (:n-calls agent) (:cost agent))))

    (:outputs extra)
    (bold-green "Observation:")

    :else
    (bold-green (str (str/capitalize role) ":"))))

(defn print-new-messages
  "A watch function that prints the messages added to the agent value."
  [_key _ref old-agent new-agent]
  (doseq [message (drop (count (:messages old-agent)) (:messages new-agent))]
    (println)
    (println (label new-agent message))
    (println (message-text message))))

(defn- read-line!
  []
  (or (read-line)
      (throw (ex-info (str "Standard input is closed. For unattended runs use"
                           " --yolo --exit-immediately.")
                      {}))))

(defn read-multiline!
  "Reads lines until a line with only a dot."
  []
  (loop [lines []]
    (let [line (read-line!)]
      (if (= "." (str/trim line))
        (str/trim (str/join "\n" lines))
        (recur (conj lines line))))))

(defn- help
  [mode]
  (str "Current mode: " (bold-green (name mode)) "\n"
       (bold-green "/y") " to switch to " (bold-yellow "yolo") " mode (execute LM commands without confirmation)\n"
       (bold-green "/c") " to switch to " (bold-yellow "confirmation") " mode (ask for confirmation before executing LM commands)\n"
       (bold-green "/u") " to switch to " (bold-yellow "human") " mode (execute commands issued by the user)\n"
       (bold-green "/m") " to enter a multiline comment, finished by a line with only a dot"))

(defn ask
  "The `:user/ask` effect: prints `prompt` and returns the trimmed input.
  Handles /h (help) and /m (multiline input) itself; mode switches like /y
  are returned for the agent to handle."
  [{:keys [prompt mode] :as request}]
  (print (bold-yellow prompt))
  (flush)
  (let [input (str/trim (read-line!))]
    (case input
      "/h" (do (println (help mode))
               (recur request))
      "/m" (read-multiline!)
      input)))
