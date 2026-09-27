(ns minisweagent.log
  "The agent value is a log: a vector of entries, each a map with `:type` and
  `:at` (epoch ms). An entry's `:message` is kept exactly as it was sent to or
  received from the model API, so the conversation only ever grows. All other
  state is a projection of the log.

      :run/started        :task :config
      :prompt/rendered    :message
      :model/responded    :message :actions :cost :tokens :model
      :model/format-error :message :error :truncated? :response :cost :tokens :model
      :actions/observed   :message :outputs
      :user/commanded     :message :actions
      :user/interrupted   :message :interrupt-type
      :mode/switched      :mode
      :limits/raised      :step-limit :cost-limit
      :run/exited         :status :submission, after a crash :exception :traceback"
  (:require [minisweagent.observation :as observation]))

(defn task
  [log]
  (:task (first log)))

(defn started-at
  [log]
  (:at (first log)))

(defn config
  "The config the run started with, updated by mode switches and raised
  limits."
  [log]
  (reduce (fn [config {:keys [type] :as entry}]
            (case type
              :mode/switched (assoc-in config [:agent :mode] (:mode entry))
              :limits/raised (update config :agent merge (select-keys entry [:step-limit :cost-limit]))
              config))
          (:config (first log))
          (rest log)))

(defn mode
  "`:confirm` asks before executing, `:yolo` executes right away and in
  `:human` mode the user types the commands."
  [log]
  (keyword (get-in (config log) [:agent :mode] :yolo)))

(defn conversation
  "The messages exchanged with the model."
  [log]
  (into [] (keep :message) log))

(def ^:private model-call?
  (comp #{:model/responded :model/format-error} :type))

(defn n-calls
  [log]
  (count (filter model-call? log)))

(defn cost
  "USD spent on model calls."
  [log]
  (reduce + 0.0 (keep :cost log)))

(defn consecutive-format-errors
  [log]
  (->> (rseq log)
       (filter model-call?)
       (take-while #(= :model/format-error (:type %)))
       (count)))

(defn- message-entries
  [log]
  (filterv :message log))

(defn pending-actions
  "The actions of the last message, if the model or the user issued it."
  [log]
  (:actions (peek (message-entries log))))

(defn submission
  "The submitted text if the last observed outputs submit the task."
  [log]
  (->> (rseq (message-entries log))
       (take-while #(= :actions/observed (:type %)))
       (mapcat :outputs)
       (some observation/submission)))

(defn exit
  "The :run/exited entry once the run is over."
  [log]
  (let [entry (peek log)]
    (when (= :run/exited (:type entry))
      entry)))

(defn phase
  "What happens next: :query the model, :execute the pending actions, :submit
  after an output submitted the task, or :done."
  [log]
  (cond
    (exit log) :done
    (seq (pending-actions log)) :execute
    (submission log) :submit
    :else :query))
