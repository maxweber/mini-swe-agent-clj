(ns minisweagent.model
  "The API adapters and the cost of a response.

  Each adapter is a map of pure functions:

  - `:request`      model config, messages -> {:url :headers :body}
  - `:response`     parsed response body   -> normalized response
  - `:observations` action results         -> [{:message :outputs}]
  - `:auth-headers` api key                -> headers

  A normalized response looks the same for every API:

      {:message       the assistant message in the API's own format
       :text          the text of the message
       :tool-calls    [{:id :name :input} or {:id :name :input-error}]
       :truncated?    whether the output token limit cut the message
       :tokens        {:input :output :cache-write :cache-read}
       :reported-cost USD if the API reports it (OpenRouter), else nil
       :model         the model that answered}"
  (:require [minisweagent.model.anthropic :as anthropic]
            [minisweagent.model.openai :as openai]))

(def adapters
  {:anthropic {:request anthropic/request
               :response anthropic/response
               :observations anthropic/observations
               :auth-headers anthropic/auth-headers}
   :openai {:request openai/request
            :response openai/response
            :observations openai/observations
            :auth-headers openai/auth-headers}})

(defn adapter
  [{:keys [api]}]
  (or (get adapters (keyword api))
      (throw (ex-info (str "Unknown model API " api)
                      {:api api
                       :known (keys adapters)}))))

(defn observations
  "The messages that answer the actions of the last assistant message, each
  with the outputs it carries. `results` are maps of `:action`, `:output` and
  the observation `:text`."
  [model-config results]
  ((:observations (adapter model-config)) results))

(defn- price-per-million
  [price token-kind]
  (or (get price token-kind)
      (case token-kind
        :cache-write (* 1.25 (:input price 0))
        :cache-read (* 0.1 (:input price 0))
        0)))

(defn cost
  "The cost of a response in USD: as reported by the API, or computed from
  the tokens and the `:price` (USD per million tokens) of the model config."
  [{:keys [id price cost-tracking]} {:keys [reported-cost tokens]}]
  (cond
    (number? reported-cost)
    (double reported-cost)

    price
    (/ (reduce + (map (fn [[token-kind n]]
                        (* n (price-per-million price token-kind)))
                      tokens))
       1e6)

    (= :ignore-errors (keyword cost-tracking))
    0.0

    :else
    (throw (ex-info (str "No price for model " id ". Add a price in USD per million"
                         " tokens as [:model :price], e.g. {:input 3.0 :output 15.0},"
                         " or set [:model :cost-tracking] to :ignore-errors.")
                    {:model id}))))

(comment
  (cost {:id "claude-opus-5" :price {:input 5.0 :output 25.0}}
        {:tokens {:input 1200 :output 300 :cache-read 10000}})
  )
