(ns minisweagent.model.http
  "The `:model/query` effect: one model request over HTTP, with retries."
  (:require [clojure.data.json :as json]
            [minisweagent.model :as model]))

(defn- post!
  [client {:keys [url headers body]}]
  (let [builder (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                    (.timeout (java.time.Duration/ofMinutes 10))
                    (.header "content-type" "application/json")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                            (json/write-str body))))]
    (doseq [[header-name value] headers]
      (.header builder header-name value))
    (let [response (.send client
                          (.build builder)
                          (java.net.http.HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :body (.body response)})))

(defn- retryable?
  [{:keys [status exception]}]
  (or (some? exception)
      (contains? #{408 409 429} status)
      (<= 500 status)))

(defn- backoff-ms
  [attempt]
  (min 60000 (* 4000 (bit-shift-left 1 (dec attempt)))))

(defn- post-with-retries!
  [client request attempts]
  (loop [attempt 1]
    (let [result (try
                   (post! client request)
                   (catch java.io.IOException e
                     {:exception e}))]
      (cond
        (and (:status result) (<= 200 (:status result) 299))
        result

        (and (retryable? result) (< attempt attempts))
        (let [ms (backoff-ms attempt)]
          (binding [*out* *err*]
            (println (str "Model request failed (" (or (:status result)
                                                        (ex-message (:exception result)))
                          "), retrying in " (quot ms 1000) " s")))
          (Thread/sleep ms)
          (recur (inc attempt)))

        :else
        (throw (ex-info (str "Model request failed with "
                             (or (:status result) (ex-message (:exception result)))
                             ": " (:body result))
                        (dissoc result :exception)
                        (:exception result)))))))

(defn query-fn
  "Returns the `:model/query` effect: a function of a model config and the
  messages that returns the normalized response (see `minisweagent.model`).
  The API key is read from the environment variable named by the config's
  `:api-key-env`, so it never becomes part of the agent value."
  [{:keys [attempts]
    :or {attempts 10}}]
  (let [client (java.net.http.HttpClient/newHttpClient)]
    (fn query
      [{:keys [api-key-env] :as model-config} messages]
      (let [{:keys [request response auth-headers]} (model/adapter model-config)
            api-key (some-> api-key-env System/getenv)
            http-request (update (request model-config messages)
                                 :headers merge (when api-key
                                                  (auth-headers api-key)))
            {:keys [body]} (post-with-retries! client http-request attempts)]
        (response (json/read-str body :key-fn keyword))))))
