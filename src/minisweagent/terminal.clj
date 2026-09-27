(ns minisweagent.terminal
  "The user in a terminal: a watch that prints the new log entries and the
  `:user/ask` effect."
  (:require [clojure.string :as str]
            [minisweagent.log :as log]))

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

(defn- actions-text
  [actions]
  (str/join "\n\n" (map #(str "```bash\n" (:command %) "\n```") actions)))

(defn- entry-text
  "The heading and body of a log entry, or nil for entries not worth showing."
  [log {:keys [type message actions outputs] :as entry}]
  (case type
    :run/started
    nil

    :prompt/rendered
    [(bold-green (str (str/capitalize (:role message)) ":"))
     (content-text (:content message))]

    :model/responded
    [(str (dim (apply str (repeat 72 "─"))) "\n"
          (bold-red (format "mini-swe-agent (step %d, $%.2f):" (log/n-calls log) (log/cost log))))
     (str/join "\n\n" (remove str/blank? [(content-text (:content message))
                                          (actions-text actions)]))]

    :model/format-error
    [(bold-yellow (format "Format error (step %d):" (log/n-calls log)))
     (:content message)]

    :actions/observed
    [(bold-green "Observation:")
     (str/join "\n" (map output-text outputs))]

    :user/commanded
    [(bold-green "User command:")
     (actions-text actions)]

    :user/interrupted
    [(bold-green "User:")
     (:content message)]

    :mode/switched
    [(bold-yellow (str "Switched to " (name (:mode entry)) " mode."))]

    :limits/raised
    [(bold-yellow (format "Limits raised to %s steps, $%s."
                          (:step-limit entry) (:cost-limit entry)))]

    :run/exited
    [(bold-green (str "Exit: " (:status entry)))
     (or (:exception entry) (:submission entry))]))

(defn print-new-entries
  "A watch function that prints the entries appended to the log."
  [_key _ref old-log new-log]
  (doseq [entry (drop (count old-log) new-log)
          :let [[heading body] (entry-text new-log entry)]
          :when heading]
    (println)
    (println heading)
    (when-not (str/blank? body)
      (println body))))

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
