(ns minisweagent.model.http-test
  "Runs the model effect and the whole command against a fake Anthropic API."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [minisweagent.cli :as cli]
            [minisweagent.log :as log]
            [minisweagent.model.http :as http]
            [minisweagent.trajectory :as trajectory]))

(defn- with-fake-api
  "Calls `f` with the base url of a server that answers the n-th request with
  `(respond n request)`, a map of `:status` and `:body`. Returns the requests
  the server received."
  [respond f]
  (let [!requests (atom [])
        server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify com.sun.net.httpserver.HttpHandler
                      (handle [_ exchange]
                        (let [request {:path (str (.getRequestURI exchange))
                                       :headers (into {} (map (fn [[k v]] [(.toLowerCase k) (first v)]))
                                                      (.getRequestHeaders exchange))
                                       :body (json/read-str (slurp (.getRequestBody exchange))
                                                            :key-fn keyword)}
                              [old] (swap-vals! !requests conj request)
                              {:keys [status body]} (respond (count old) request)
                              bytes (.getBytes (json/write-str body) "UTF-8")]
                          (.sendResponseHeaders exchange status (alength bytes))
                          (with-open [out (.getResponseBody exchange)]
                            (.write out bytes))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1"))
      @!requests
      (finally
        (.stop server 0)))))

(defn- tool-use-body
  [id command]
  {:content [{:type "text" :text "Running a command."}
             {:type "tool_use" :id id :name "bash" :input {:command command}}]
   :stop_reason "tool_use"
   :usage {:input_tokens 100 :output_tokens 10}
   :model "claude-opus-5"})

(deftest query-test
  (let [!response (atom nil)
        [request] (with-fake-api
                    (fn [_ _] {:status 200 :body (tool-use-body "toolu_1" "ls")})
                    (fn [base-url]
                      (reset! !response ((http/query-fn {})
                                         {:api :anthropic
                                          :id "claude-opus-5"
                                          :base-url base-url
                                          :headers {"anthropic-beta" "server-side-fallback-2026-07-01"}
                                          :params {:max_tokens 16000}
                                          :action-format :toolcall}
                                         [{:role "system" :content "Be helpful."}
                                          {:role "user" :content "Fix it."}]))))]
    (is (= "/v1/messages" (:path request)))
    (is (= "2023-06-01" (get-in request [:headers "anthropic-version"])))
    (is (= "server-side-fallback-2026-07-01" (get-in request [:headers "anthropic-beta"])))
    (is (= {:model "claude-opus-5"
            :max_tokens 16000
            :system "Be helpful."
            :messages [{:role "user" :content "Fix it."}]}
           (dissoc (:body request) :tools)))
    (is (= [{:id "toolu_1" :name "bash" :input {:command "ls"}}]
           (:tool-calls @!response)))))

(deftest client-error-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed with 400.*max_tokens"
                        (with-fake-api
                          (fn [_ _] {:status 400
                                     :body {:type "error"
                                            :error {:type "invalid_request_error"
                                                    :message "max_tokens: too large"}}})
                          (fn [base-url]
                            ((http/query-fn {})
                             {:api :anthropic :id "claude-opus-5" :base-url base-url :params {}}
                             [{:role "user" :content "Hi"}]))))))

(deftest command-test
  (let [output (str (java.io.File/createTempFile "minisweagent" ".traj.edn"))
        !final (atom nil)
        requests (with-fake-api
                   (fn [n _]
                     {:status 200
                      :body (tool-use-body (str "toolu_" n)
                                           (if (zero? n)
                                             "echo hello"
                                             "echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT"))})
                   (fn [base-url]
                     (with-out-str
                       (reset! !final (cli/run {:task "Say hello"
                                                :yolo true
                                                :exit-immediately true
                                                :model "anthropic/claude-opus-5"
                                                :config ["mini.edn" (str "model.base-url=" base-url)]
                                                :output output})))))]
    (is (= 2 (count requests)))
    (is (= [{:type "tool_result"
             :tool_use_id "toolu_0"
             :content "{\"returncode\":0,\"output\":\"hello\\n\"}"}]
           (get-in (second requests) [:body :messages 2 :content])))
    (is (= "Submitted" (:status (log/exit @!final))))
    (is (= @!final (trajectory/load-edn output))
        "the saved trajectory is the final log")))
