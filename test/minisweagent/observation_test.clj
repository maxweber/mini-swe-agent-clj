(ns minisweagent.observation-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [minisweagent.observation :as observation]))

(deftest text-test
  (is (= "{\"returncode\":0,\"output\":\"héllo/\\n\"}"
         (observation/text 10000 {:output "héllo/\n" :returncode 0 :exception-info ""})))
  (is (= {"returncode" -1 "output" "" "exception_info" "action was not executed"}
         (json/read-str (observation/text 10000 observation/not-executed))))
  (is (= {"returncode" 0
          "output_head" "01234"
          "output_tail" "56789"
          "elided_chars" 2
          "warning" "Output too long."}
         (json/read-str (observation/text 10 {:output "01234xx56789" :returncode 0})))))

(deftest submission-test
  (is (= "the diff\n"
         (observation/submission {:output "\n COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT \nthe diff\n" :returncode 0})))
  (is (= "" (observation/submission {:output "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\n" :returncode 0})))
  (is (nil? (observation/submission {:output "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\n" :returncode 1})))
  (is (nil? (observation/submission {:output "echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT" :returncode 0}))))
