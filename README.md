# mini-swe-agent-clj

A Clojure version of [mini-swe-agent](https://github.com/SWE-agent/mini-swe-agent):
a software engineering agent that has nothing but bash. The model gets a task,
answers with bash commands, sees their output and repeats until it runs
`echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT`.

Runs on the JVM and on [babashka](https://babashka.org) (64 ms startup).

## Quick start

```bash
export ANTHROPIC_API_KEY=...
bin/mini -t "Add a --verbose flag to cli.py"          # babashka, asks before each command
bin/mini -y --exit-immediately -t "Fix the failing test"  # no questions
clojure -M:mini --help                                 # the same on the JVM, from this directory
```

Models are named `provider/id` like in LiteLLM:

| Model name                         | API                         | Key                  |
|------------------------------------|-----------------------------|----------------------|
| `anthropic/claude-opus-5` (default)| Anthropic Messages API      | `ANTHROPIC_API_KEY`  |
| `openai/<id>`                      | OpenAI chat completions     | `OPENAI_API_KEY`     |
| `openrouter/<vendor>/<id>`         | OpenRouter                  | `OPENROUTER_API_KEY` |

Any OpenAI-compatible server works via its base url, e.g. Ollama:
`bin/mini -m openai/qwen3 -c mini.edn -c model.base-url=http://localhost:11434/v1 -c model.cost-tracking=:ignore-errors`.
For models without tool calling use `-c mini-textbased.edn`.

While the agent runs, `/y` switches to yolo mode, `/c` to confirm mode, `/u`
to human mode (you type the commands) and `/h` shows the help.

## Design

The Python version is a handful of classes with mutable fields
(`messages`, `cost`, `n_calls`, `config.mode`, ...), subclassing for the
interactive agent and exceptions for control flow (`Submitted`,
`LimitsExceeded`, `FormatError`, ...). Here:

- **One value, a log.** The agent is a vector of entries: `:run/started`
  (task and config), `:model/responded`, `:actions/observed`,
  `:mode/switched`, `:limits/raised`, `:run/exited`, ... Entries that carry a
  message keep it exactly as exchanged with the API, so the conversation only
  grows (prompt caching, thinking blocks). Cost, call count, mode, limits,
  the conversation and what happens next are projections of the log
  (`minisweagent.log`). The log is also the trajectory file, and every prefix
  of it is a valid state to continue from.
- **One mutable place.** `minisweagent.agent/run!` keeps the log in an atom
  and replaces it after every step. Printing and saving the trajectory are
  watches on that atom, not overrides.
- **Effects are passed in.** The core calls the world only through functions
  in a map, and its destructuring shows which ones it uses:
  `:model/query`, `:env/execute`, `:user/ask`, `:clock/now`. Tests pass plain
  functions instead of mocks or HTTP servers.
- **Control flow is data.** What happens next (`query`, `execute`, `submit`,
  `done`) is derived from the log by `log/phase`. Exiting means appending a
  `:run/exited` entry.
- **API adapters are pure.** Each API is a map of functions from request data
  to request data and from response to a normalized response. The only HTTP
  code is `minisweagent.model.http`.
- **Model knowledge is data.** Providers, prices and per-model request
  parameters live in [`models.edn`](resources/minisweagent/models.edn).

| Namespace                        | Role                                                    |
|----------------------------------|---------------------------------------------------------|
| `minisweagent.agent`             | `init`, `step`, `run!`: appends entries to the log      |
| `minisweagent.log`               | the entry types and all projections of the log         |
| `minisweagent.actions`           | commands out of a model response (tool calls or text)  |
| `minisweagent.observation`       | command output as the model sees it, submission check  |
| `minisweagent.model`             | adapter dispatch, cost                                  |
| `minisweagent.model.anthropic`   | Messages API shapes                                     |
| `minisweagent.model.openai`      | chat completions shapes                                 |
| `minisweagent.model.http`        | effect: `:model/query` with retries                     |
| `minisweagent.environment.local` | effect: `:env/execute` in a fresh `bash -c`             |
| `minisweagent.terminal`          | effect: `:user/ask`, watch that prints messages         |
| `minisweagent.trajectory`        | watch that saves the agent value                        |
| `minisweagent.config`            | EDN configs, `key.path=value` specs, model resolution   |
| `minisweagent.template`          | `{{name}}` placeholders                                 |
| `minisweagent.cli`               | the `mini` command                                      |

## From the REPL

```clojure
(require '[minisweagent.agent :as agent]
         '[minisweagent.config :as config]
         '[minisweagent.environment.local :as local]
         '[minisweagent.log :as log]
         '[minisweagent.model.http :as http])

(def !agent
  (atom (agent/init {:config (config/build ["mini.edn" "agent.mode=:yolo" "agent.confirm-exit=false"])
                     :task "Create hello.py that prints Hello, World!"
                     :vars (config/platform-vars)
                     :now (System/currentTimeMillis)})))

(future (agent/run! {:model/query (http/query-fn {})
                     :env/execute local/execute
                     :clock/now #(System/currentTimeMillis)}
                    !agent))

(log/phase @!agent)            ; watch it work
(log/cost @!agent)
(map :type @!agent)
```

`(agent/step effects @!agent)` computes a single step. To fork a run, take a
prefix of a saved log and continue it:
`(agent/run! effects (atom (subvec (minisweagent.trajectory/load-edn path) 0 n)))`.

## Config

`bin/mini` uses [`mini.edn`](resources/minisweagent/config/mini.edn), the
port of `mini.yaml`. `-c` replaces it and can be repeated; files, builtin
names and `key.path=value` specs are deep-merged in order:

```bash
bin/mini -c mini.edn -c agent.step-limit=30 -c agent.whitelist-actions='["ls\\b" "cat "]'
```

Templates only fill `{{name}}` placeholders (`task`, `system`, `release`,
`machine`, `cwd`); logic lives in functions instead of Jinja conditionals.

## Differences from mini-swe-agent

- LiteLLM is replaced by two small adapters. Claude is called through the
  native Messages API with automatic prompt caching and, for the models that
  support it, server-side refusal fallbacks (`fallbacks: "default"`).
- Observations are always the JSON of `mini.yaml`.
- A command's output goes to a temp file instead of a pipe, so a command
  that starts a background process returns when bash exits.
- The trajectory is the log as EDN (or JSON if the path ends with `.json`),
  not the Python inspector format.
- Not ported: Docker and other environments, SWE-bench batch runs, the
  inspector, multimodal input, Ctrl-C interruption, the global cost limit and
  the `.env` config file.

## Tests

```bash
clojure -M:test
```

## License

MIT, see [LICENSE](LICENSE). Prompts and design are ported from
[mini-swe-agent](https://github.com/SWE-agent/mini-swe-agent) (MIT).
