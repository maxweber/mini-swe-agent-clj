(ns minisweagent.environment.local-test
  (:require [clojure.test :refer [deftest is]]
            [minisweagent.environment.local :as local]))

(deftest execute-test
  (is (= {:output "out\nerr\n" :returncode 3 :exception-info ""}
         (local/execute {} "echo out; echo err >&2; exit 3")))
  (is (= "/tmp\n" (:output (local/execute {:cwd "/tmp"} "pwd"))))
  (is (= "cat\n" (:output (local/execute {:env {"PAGER" "cat"}} "echo $PAGER"))))
  (is (= "" (:output (local/execute {} "cat")))
      "stdin is empty, so reading it does not hang"))

(deftest timeout-test
  (let [started (System/currentTimeMillis)
        {:keys [output returncode exception-info]}
        (local/execute {:timeout-seconds 1} "echo started; sleep 10")]
    (is (< (- (System/currentTimeMillis) started) 5000))
    (is (= "started\n" output))
    (is (= -1 returncode))
    (is (re-find #"timed out after 1 seconds" exception-info))))

(deftest background-process-test
  (let [started (System/currentTimeMillis)]
    (is (= "ok\n" (:output (local/execute {:timeout-seconds 10} "sleep 3 & echo ok"))))
    (is (< (- (System/currentTimeMillis) started) 2000)
        "returns when bash exits, not when the background process does")))
