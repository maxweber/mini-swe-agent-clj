(ns minisweagent.observation
  "What the model sees of a command output, and whether the output submits
  the task."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def submit-command
  "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT")

(def not-executed
  "The output of an action that did not run, e.g. because the user rejected it."
  {:output ""
   :returncode -1
   :exception-info "action was not executed"})

(defn text
  "The output as JSON text. Outputs of `max-chars` or more are cut to their
  head and tail."
  [max-chars {:keys [output returncode exception-info]}]
  (let [half (quot max-chars 2)
        length (count output)]
    (json/write-str
     (cond-> (if (< length max-chars)
               {:returncode returncode
                :output output}
               {:returncode returncode
                :output_head (subs output 0 half)
                :output_tail (subs output (- length half))
                :elided_chars (- length max-chars)
                :warning "Output too long."})
       (seq exception-info) (assoc :exception_info exception-info))
     :escape-unicode false
     :escape-slash false)))

(defn submission
  "The submitted text if the output starts with the submit command and the
  command succeeded, otherwise nil."
  [{:keys [output returncode]}]
  (let [[first-line more] (str/split (str/triml output) #"\r?\n" 2)]
    (when (and (= 0 returncode)
               (= submit-command (str/trim first-line)))
      (or more ""))))

(comment
  (text 10000 {:output "hello\n" :returncode 0 :exception-info ""})
  (submission {:output "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\ndiff --git ..." :returncode 0})
  )
