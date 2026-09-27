(ns minisweagent.model-test
  (:require [clojure.test :refer [deftest is]]
            [minisweagent.model :as model]))

(deftest cost-test
  (is (= 0.5 (model/cost {} {:reported-cost 0.5})))
  (is (= (/ (+ (* 1000 5.0) (* 100 25.0) (* 2000 6.25) (* 10000 0.5)) 1e6)
         (model/cost {:price {:input 5.0 :output 25.0}}
                     {:tokens {:input 1000 :output 100 :cache-write 2000 :cache-read 10000}})))
  (is (= 0.0 (model/cost {:cost-tracking :ignore-errors} {:tokens {:input 10}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No price for model local-llm"
                        (model/cost {:id "local-llm"} {:tokens {:input 10}}))))
