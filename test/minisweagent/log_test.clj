(ns minisweagent.log-test
  (:require [clojure.test :refer [deftest is]]
            [minisweagent.log :as log]))

(def started
  {:type :run/started
   :task "Fix the bug"
   :config {:agent {:mode :confirm :step-limit 10 :cost-limit 3.0}}
   :at 0})

(def submitted
  {:type :actions/observed
   :message {:role "user" :content "..."}
   :outputs [{:output "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\n" :returncode 0}]
   :at 2})

(deftest config-test
  (let [log [started
             {:type :mode/switched :mode :yolo :at 1}
             {:type :limits/raised :step-limit 20 :cost-limit 5.0 :at 2}]]
    (is (= {:mode :yolo :step-limit 20 :cost-limit 5.0} (:agent (log/config log))))
    (is (= :yolo (log/mode log)))
    (is (= :confirm (log/mode (subvec log 0 1))))))

(deftest phase-test
  (is (= :query (log/phase [started])))
  (is (= :execute (log/phase [started {:type :model/responded
                                       :message {:role "assistant"}
                                       :actions [{:command "ls"}]
                                       :at 1}])))
  (is (= :submit (log/phase [started submitted])))
  (is (= :submit (log/phase [started submitted {:type :mode/switched :mode :yolo :at 3}]))
      "entries without a message do not change the phase")
  (is (= :done (log/phase [started submitted {:type :run/exited :status "Submitted" :at 3}]))))

(deftest counters-test
  (let [log [started
             {:type :model/format-error :cost 0.5 :message {:role "user"} :at 1}
             {:type :model/responded :cost 0.25 :message {:role "assistant"} :at 2}
             {:type :model/format-error :cost 0.25 :message {:role "user"} :at 3}
             {:type :user/interrupted :message {:role "user"} :at 4}]]
    (is (= 3 (log/n-calls log)))
    (is (= 1.0 (log/cost log)))
    (is (= 1 (log/consecutive-format-errors log)))
    (is (= 4 (count (log/conversation log))))))
