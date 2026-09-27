(ns minisweagent.template
  "Fills `{{name}}` placeholders. Logic belongs in functions, so there are no
  conditionals or loops."
  (:require [clojure.string :as str]))

(defn render
  "Replaces every `{{name}}` in `template` with the value of `:name` in `vars`.
  Throws on a placeholder without a value, like Jinja's StrictUndefined."
  [template vars]
  (str/replace
   template
   #"\{\{\s*([\w.?-]+)\s*\}\}"
   (fn [[placeholder var-name]]
     (let [value (get vars (keyword var-name) ::missing)]
       (when (= ::missing value)
         (throw (ex-info (str "No value for template placeholder " placeholder)
                         {:placeholder placeholder
                          :vars (keys vars)})))
       (str value)))))

(comment
  (render "Please solve this issue: {{task}}" {:task "Fix the bug"})
  )
