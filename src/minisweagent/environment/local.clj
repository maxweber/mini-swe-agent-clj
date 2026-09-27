(ns minisweagent.environment.local
  "The `:env/execute` effect for the local machine. Every command runs in a
  fresh `bash -c`, so `cd` and exported variables do not persist."
  (:require [clojure.java.io :as io]))

(defn- kill-tree!
  [process]
  (doseq [child (.toList (.descendants process))]
    (.destroyForcibly child))
  (.destroyForcibly process)
  (.waitFor process))

(defn- start!
  [{:keys [cwd env]} command output-file]
  (let [builder (doto (ProcessBuilder. ["bash" "-c" command])
                  (.directory (io/file (if (seq cwd)
                                         cwd
                                         (System/getProperty "user.dir"))))
                  (.redirectErrorStream true)
                  (.redirectOutput output-file))]
    (doseq [[k v] env]
      (.put (.environment builder) (name k) (str v)))
    (let [process (.start builder)]
      ;; No input: a command that reads stdin gets EOF instead of hanging.
      (.close (.getOutputStream process))
      process)))

(defn execute
  "Runs `command` and returns `{:output :returncode :exception-info}`, stdout
  and stderr combined. The output goes to a file rather than a pipe, so a
  command that leaves a background process behind still returns once bash
  exits."
  [{:keys [timeout-seconds] :or {timeout-seconds 30} :as env-config} command]
  (let [output-file (java.io.File/createTempFile "minisweagent" ".out")]
    (try
      (let [process (start! env-config command output-file)]
        (if (.waitFor process timeout-seconds java.util.concurrent.TimeUnit/SECONDS)
          {:output (slurp output-file)
           :returncode (.exitValue process)
           :exception-info ""}
          (do
            (kill-tree! process)
            {:output (slurp output-file)
             :returncode -1
             :exception-info (str "An error occurred while executing the command: Command '"
                                  command "' timed out after " timeout-seconds " seconds")})))
      (catch java.io.IOException e
        {:output ""
         :returncode -1
         :exception-info (str "An error occurred while executing the command: " (ex-message e))})
      (finally
        (.delete output-file)))))

(comment
  (execute {} "echo hello && ls /nope")
  (execute {:timeout-seconds 1} "sleep 5")
  )
