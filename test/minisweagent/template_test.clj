(ns minisweagent.template-test
  (:require [clojure.test :refer [deftest is]]
            [minisweagent.template :as template]))

(deftest render-test
  (is (= "Please solve this issue: Fix $1 \\o/"
         (template/render "Please solve this issue: {{ task }}" {:task "Fix $1 \\o/"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\{\{missing\}\}"
                        (template/render "{{missing}}" {:task "x"}))))
