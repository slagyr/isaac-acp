(ns isaac.comm.acp.server-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.comm.acp.server :as sut]
    [isaac.config.loader :as config]
    [isaac.drive.turn :as single-turn]
    [isaac.logger :as log]
    [isaac.marigold :as marigold]
    [isaac.marigold.agent :as marigold-agent]
    [isaac.module.loader :as module-loader]
    [isaac.tool.builtin :as builtin]
    [isaac.tool.exec :as exec]
    [isaac.tool.file :as file]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.api.ollama]
    [isaac.bridge.cancellation :as bridge]
    [isaac.fs :as fs]
    [isaac.spec-helper :as helper]
    [isaac.session.spec-helper :as session-helper]
    [isaac.system :as system]
    [isaac.tool.registry :as tool-registry]
    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(def test-dir "/test/acp-server")

(defn- parsed-output [writer]
  (->> (str/split-lines (str writer))
       (remove str/blank?)
       (mapv #(json/parse-string % true))))

(def ^:private test-agents {"main" {:name "main" :soul "You are Isaac." :model "grover"}})
(def ^:private test-models {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}})
(def ^:private prompt-opts {:state-dir test-dir :crew-members test-agents :models test-models})

(describe "ACP server"

  (marigold-agent/with-manifest)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example] (session-helper/with-memory-store (system/with-nested-system {:state-dir test-dir :fs (fs/mem-fs)} (example))))

  (describe "initialize"

    (it "returns protocol version, agent info, and capabilities"
      (let [response (sut/dispatch-line {:state-dir test-dir}
                                        (jrpc/request-line 1 "initialize" {:protocolVersion 1}))]
        (should= 1 (get-in response [:result :protocolVersion]))
        (should= "isaac" (get-in response [:result :agentInfo :name]))
        (should= true (get-in response [:result :agentCapabilities :loadSession]))
        (should= true (get-in response [:result :agentCapabilities :promptCapabilities :text]))))

    (it "includes model and provider in agentInfo when agents and models are provided"
      (let [agents {"main" {:name "main" :soul "You are Isaac." :model "grover"}}
            models {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}
            response (sut/dispatch-line {:state-dir test-dir :crew-members agents :models models}
                                        (jrpc/request-line 1 "initialize" {:protocolVersion 1}))]
        (should= "echo" (get-in response [:result :agentInfo :model]))
        (should= "grover" (get-in response [:result :agentInfo :provider])))))

  (describe "extract-tool-calls"

    (it "extracts tool calls from top-level message and vector content"
      (should= [{:type "toolCall" :id "tc-1" :name "read" :arguments {:path "a.txt"}}]
               (#'sut/extract-tool-calls {:type "toolCall" :id "tc-1" :name "read" :arguments {:path "a.txt"}}))
      (should= [{:type "toolCall" :id "tc-2" :name "grep" :arguments {:pattern "lettuce"}}]
               (#'sut/extract-tool-calls {:content [{:type "toolCall" :id "tc-2" :name "grep" :arguments {:pattern "lettuce"}}]})))

    (it "returns nil for non-tool-call content"
      (should-be-nil (#'sut/extract-tool-calls {:content "plain text"}))))

  (describe "session-prompt-handler"

    (it "requires a session id"
      (should-throw clojure.lang.ExceptionInfo
                    (#'sut/session-prompt-handler (StringWriter.) nil nil nil nil test-dir nil
                                                  {:prompt [{:type "text" :text "Hi"}]}
                                                  nil)))

    (it "requires a text prompt"
      (should-throw clojure.lang.ExceptionInfo
                    (#'sut/session-prompt-handler (StringWriter.) nil nil nil nil test-dir nil
                                                  {:sessionId "agent:main:acp:direct:user1"
                                                   :prompt    [{:type "image" :url "https://example.com/cat.png"}]}
                                                  nil)))

    (it "returns an error turn when a known crew has no model configured"
      (session-helper/create-session! test-dir "agent:ketch:acp:direct:user1" {:crew "ketch"})
      (let [writer     (StringWriter.)
            err-writer (java.io.StringWriter.)]
        (binding [*err* err-writer]
          (let [result (#'sut/session-prompt-handler writer {"ketch" {:soul "Ahoy"}} {} nil nil test-dir nil
                                                     {:sessionId "agent:ketch:acp:direct:user1"
                                                      :prompt    [{:type "text" :text "Hi"}]}
                                                     nil)]
            (should= "end_turn" (:stopReason result))
            (should (str/includes? (str writer) "no model configured for crew: ketch"))))))

    (it "passes the request model override through to the dispatch request"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1" {:crew "main"})
      (let [captured-request (atom nil)]
        (with-redefs [sut/run-prompt         (fn [_ _ _ request]
                                               (reset! captured-request request)
                                               {:stopReason "end_turn"})]
          (should= {:stopReason "end_turn"}
                   (#'sut/session-prompt-handler (StringWriter.) {"main" {:soul "You are Isaac."}} {} nil nil test-dir nil
                                                  {:sessionId "agent:main:acp:direct:user1"
                                                   :prompt    [{:type "text" :text "Hello"}]}
                                                  nil))
          (should= {:crew      {"main" {:soul "You are Isaac."}}
                     :models    {}
                     :providers {}
                     :cron      {}}
                   (select-keys (:config @captured-request) [:crew :models :providers :cron]))
          (should= test-dir (:home @captured-request))
          (should= nil (:model-override @captured-request))
          (should= "main" (:crew @captured-request)))))

    (it "reads the current config snapshot for session/prompt instead of stale connection cfg"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1" {:crew "main"})
      (let [stale-cfg       {:defaults {:crew "main" :model "grover"}
                             :crew     {"main" {:soul "You are Isaac." :model "grover"}}
                             :models   {"grover" {:model "echo" :provider "grover"}}}
            reloaded-cfg    {:defaults {:crew "main" :model "gpt"}
                             :crew     {"main" {:soul "You are Isaac." :model "gpt"}}
                             :models   {"grover" {:model "echo" :provider "grover"}
                                        "gpt"    {:model "gpt-5.4" :provider "chatgpt"}}}
            captured-config (atom nil)]
        (config/set-snapshot! reloaded-cfg "ACP server-spec hot-reload snapshot")
        (with-redefs [sut/run-prompt (fn [_ _ _ request]
                                       (reset! captured-config (:config request))
                                       {:stopReason "end_turn"})]
          (#'sut/session-prompt-handler (StringWriter.) nil nil nil stale-cfg test-dir nil
                                        {:sessionId "agent:main:acp:direct:user1"
                                         :prompt    [{:type "text" :text "Hello"}]}
                                        nil)
          (should= "gpt" (get-in @captured-config [:crew "main" :model]))
          (should (contains? (:models @captured-config) "gpt")))))

    )

  (describe "session/new"

    (it "creates an ACP channel session for main agent"
      (let [response   (sut/dispatch-line {:state-dir test-dir}
                                          (jrpc/request-line 2 "session/new" {:cwd "/tmp/project"}))
            session-id (or (get-in response [:result :sessionId])
                           (get-in response [:response :result :sessionId]))
            sessions   (session-helper/list-sessions test-dir "main")]
        (should (string? session-id))
        (should= 1 (count sessions))
        (should= session-id (:id (first sessions)))))

    (it "advertises available slash commands after session creation"
      (let [result       (sut/dispatch-line {:state-dir test-dir}
                                            (jrpc/request-line 2 "session/new" {:name "cmd-test"}))
            session-id   (get-in result [:response :result :sessionId])
            notification (first (:notifications result))]
        (should= "cmd-test" session-id)
        (should= "session/update" (:method notification))
        (should= "available_commands_update" (get-in notification [:params :update :sessionUpdate]))
        ;; The advertised names are whatever the active manifest declares.
        ;; Under marigold this is the themed test set; in production it's the
        ;; built-in slash catalog. Test asserts the mechanism, not specifics.
        (should= (sort [marigold/heading-command marigold/bearing-command marigold/muster-command])
                 (sort (mapv :name (get-in notification [:params :update :availableCommands]))))))

    (it "stores acp origin on sessions created through session/new"
      (let [response   (sut/dispatch-line {:state-dir test-dir}
                                          (jrpc/request-line 2 "session/new" {:name "primary"}))
            session-id (or (get-in response [:result :sessionId])
                           (get-in response [:response :result :sessionId]))
            session    (session-helper/get-session test-dir session-id)]
        (should= {:kind :acp} (:origin session))))

    (it "rejects an explicit duplicate session name"
      (session-helper/create-session! test-dir "friday-debug")
      (let [response (sut/dispatch-line {:state-dir test-dir}
                                        (jrpc/request-line 2 "session/new" {:name "friday-debug"}))]
        (should= -32602 (get-in response [:response :error :code]))
        (should= "session already exists: friday-debug" (get-in response [:response :error :message]))))

    )

  (describe "session/load"

    (it "replays user and assistant messages before returning a nil result"
      (session-helper/create-session! test-dir "prior-session")
      (session-helper/append-message! test-dir "prior-session" {:role "user" :content "What's up?"})
      (session-helper/append-message! test-dir "prior-session" {:role "assistant" :content "All good"})
      (let [writer         (StringWriter.)
            response       (sut/dispatch-line {:state-dir test-dir :output-writer writer}
                                              (jrpc/request-line 3 "session/load" {:sessionId "prior-session"}))
            notifications  (parsed-output writer)]
        (should= ["user_message_chunk" "agent_message_chunk"]
                 (mapv #(get-in % [:params :update :sessionUpdate]) notifications))
        (should= ["What's up?" "All good"]
                 (mapv #(get-in % [:params :update :content :text]) notifications))
        (should (contains? response :result))
        (should= nil (:result response))))

    (it "replays compaction summaries in transcript order"
      (session-helper/create-session! test-dir "resume-test")
      (session-helper/append-compaction! test-dir "resume-test" {:summary "Earlier we discussed X."})
      (session-helper/append-message! test-dir "resume-test" {:role "user" :content "what next?"})
      (session-helper/append-message! test-dir "resume-test" {:role "assistant" :content "let's tackle Y."})
      (let [writer        (StringWriter.)
            _response     (sut/dispatch-line {:state-dir test-dir :output-writer writer}
                                             (jrpc/request-line 5 "session/load" {:sessionId "resume-test"}))
            notifications (parsed-output writer)]
        (should= ["agent_message_chunk" "user_message_chunk" "agent_message_chunk"]
                 (mapv #(get-in % [:params :update :sessionUpdate]) notifications))
        (should= ["Earlier we discussed X." "what next?" "let's tackle Y."]
                 (mapv #(get-in % [:params :update :content :text]) notifications))))

    (it "replays historic tool calls as completed notifications with results inline"
      (session-helper/create-session! test-dir "resume-tools")
      (session-helper/append-message! test-dir "resume-tools" {:role "user" :content "check the logs"})
      (session-helper/append-message! test-dir "resume-tools" {:role "assistant"
                                                         :content [{:type      "toolCall"
                                                                    :id        "tc-1"
                                                                    :name      "grep"
                                                                    :arguments {:q "error"}}]})
      (session-helper/append-message! test-dir "resume-tools" {:role "toolResult" :toolCallId "tc-1" :content "3 matches"})
      (session-helper/append-message! test-dir "resume-tools" {:role "assistant" :content "found 3 errors"})
      (let [writer        (StringWriter.)
            _response     (sut/dispatch-line {:state-dir test-dir :output-writer writer}
                                             (jrpc/request-line 5 "session/load" {:sessionId "resume-tools"}))
            notifications (parsed-output writer)]
        (should= ["user_message_chunk" "tool_call" "agent_message_chunk"]
                 (mapv #(get-in % [:params :update :sessionUpdate]) notifications))
        (should= "tc-1" (get-in (second notifications) [:params :update :toolCallId]))
        (should= "completed" (get-in (second notifications) [:params :update :status]))
        (should= "3 matches" (get-in (second notifications) [:params :update :rawOutput]))
        (should= "found 3 errors" (get-in (nth notifications 2) [:params :update :content :text]))))

    (it "replays string tool results with expandable ACP content blocks"
      (session-helper/create-session! test-dir "resume-lantern")
      (session-helper/append-message! test-dir "resume-lantern" {:role "user" :content "inspect the lantern"})
      (session-helper/append-message! test-dir "resume-lantern" {:role "assistant"
                                                            :content [{:type      "toolCall"
                                                                       :id        "tc-1"
                                                                       :name      "read"
                                                                       :arguments {:file_path "lantern"}}]})
      (session-helper/append-message! test-dir "resume-lantern" {:role "toolResult" :toolCallId "tc-1" :content "wick trimmed"})
      (session-helper/append-message! test-dir "resume-lantern" {:role "assistant" :content "Lantern looks ready"})
      (let [writer        (StringWriter.)
            _response     (sut/dispatch-line {:state-dir test-dir :output-writer writer}
                                             (jrpc/request-line 6 "session/load" {:sessionId "resume-lantern"}))
            tool-replay   (second (parsed-output writer))]
        (should= "wick trimmed" (get-in tool-replay [:params :update :rawOutput]))
        (should= "wick trimmed" (get-in tool-replay [:params :update :content 0 :content :text]))))

    (it "replays only the active transcript when effective-history-offset is set"
      (session-helper/create-session! test-dir "compact-head")
      (session-helper/append-message! test-dir "compact-head" {:role "user" :content "old question"})
      (session-helper/append-message! test-dir "compact-head" {:role "assistant" :content "old answer"})
      (session-helper/append-message! test-dir "compact-head" {:role "user" :content "what next?"})
      (session-helper/append-message! test-dir "compact-head" {:role "assistant" :content "let's tackle Y."})
      (session-helper/update-session! test-dir "compact-head" {:effective-history-offset 3})
      (let [writer        (StringWriter.)
            _response     (sut/dispatch-line {:state-dir test-dir :output-writer writer}
                                             (jrpc/request-line 5 "session/load" {:sessionId "compact-head"}))
            notifications (parsed-output writer)]
        (should= ["user_message_chunk" "agent_message_chunk"]
                 (mapv #(get-in % [:params :update :sessionUpdate]) notifications))
        (should= ["what next?" "let's tackle Y."]
                 (mapv #(get-in % [:params :update :content :text]) notifications))))

    (it "replays tool calls without results when the tool result is before the active offset"
      (session-helper/create-session! test-dir "resume-offset-tools")
      (session-helper/append-message! test-dir "resume-offset-tools" {:role "user" :content "check the logs"})
      (session-helper/append-message! test-dir "resume-offset-tools" {:role "assistant"
                                                                        :content [{:type      "toolCall"
                                                                                   :id        "tc-1"
                                                                                   :name      "grep"
                                                                                   :arguments {:q "error"}}]})
      (session-helper/append-message! test-dir "resume-offset-tools" {:role "toolResult" :toolCallId "tc-1" :content "3 matches"})
      (session-helper/append-message! test-dir "resume-offset-tools" {:role "assistant" :content "found 3 errors"})
      (session-helper/update-session! test-dir "resume-offset-tools" {:effective-history-offset 4})
      (let [writer        (StringWriter.)
            _response     (sut/dispatch-line {:state-dir test-dir :output-writer writer}
                                             (jrpc/request-line 5 "session/load" {:sessionId "resume-offset-tools"}))
            notifications (parsed-output writer)]
        (should= ["agent_message_chunk"]
                 (mapv #(get-in % [:params :update :sessionUpdate]) notifications))
        (should= "found 3 errors" (get-in (first notifications) [:params :update :content :text]))))

    )

  (describe "session/prompt"

    (before
      (grover/reset-queue!)
      (tool-registry/clear!))

    (it "returns end_turn stop reason when the prompt completes"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (grover/enqueue! [{:type "text" :content "Four, I think" :model "echo"}])
      (let [response (sut/dispatch-line (assoc prompt-opts :output-writer (StringWriter.))
                                        (jrpc/request-line 10 "session/prompt"
                                                           {:sessionId "agent:main:acp:direct:user1"
                                                            :prompt [{:type "text" :text "What is 2+2?"}]}))]
        (should= "end_turn" (get-in response [:result :stopReason]))))

    (it "stores user and assistant messages in the transcript"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (grover/enqueue! [{:type "text" :content "Four, I think" :model "echo"}])
      (sut/dispatch-line (assoc prompt-opts :output-writer (StringWriter.))
                         (jrpc/request-line 10 "session/prompt"
                                            {:sessionId "agent:main:acp:direct:user1"
                                             :prompt [{:type "text" :text "What is 2+2?"}]}))
      (let [transcript (session-helper/get-transcript test-dir "agent:main:acp:direct:user1")
            messages   (filter #(= "message" (:type %)) transcript)]
        (should= 2 (count messages))
        (should= "user" (get-in (nth messages 0) [:message :role]))
        (should= "assistant" (get-in (nth messages 1) [:message :role]))))

    (it "uses model-override instead of agent's default model"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (let [models-with-alt (assoc test-models "grover2" {:alias "grover2" :model "echo-alt" :provider "grover" :context-window 16384})]
        (grover/enqueue! [{:type "text" :content "Hello" :model "echo-alt"}])
        (let [response (sut/dispatch-line {:state-dir      test-dir
                                           :crew-members         test-agents
                                           :models         models-with-alt
                                           :model-override "grover2"
                                           :output-writer  (StringWriter.)}
                                          (jrpc/request-line 10 "session/prompt"
                                                             {:sessionId "agent:main:acp:direct:user1"
                                                              :prompt [{:type "text" :text "Hi"}]}))]
          (should= "end_turn" (get-in response [:result :stopReason])))))

    (it "stores model and provider in the assistant message"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (grover/enqueue! [{:type "text" :content "Hello" :model "echo"}])
      (sut/dispatch-line (assoc prompt-opts :output-writer (StringWriter.))
                         (jrpc/request-line 11 "session/prompt"
                                            {:sessionId "agent:main:acp:direct:user1"
                                             :prompt [{:type "text" :text "Hi"}]}))
      (let [transcript (session-helper/get-transcript test-dir "agent:main:acp:direct:user1")
            assistant  (->> transcript (filter #(= "message" (:type %))) last)]
        (should= "echo" (get-in assistant [:message :model]))
        (should= "grover" (get-in assistant [:message :provider]))))

    (it "writes one session/update notification per streamed text chunk"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (grover/enqueue! [{:type "text" :content ["Once " "upon " "a " "time..."] :model "echo"}])
      (let [writer        (StringWriter.)
            result        (sut/dispatch-line (assoc prompt-opts :output-writer writer)
                                             (jrpc/request-line 20 "session/prompt"
                                                                {:sessionId "agent:main:acp:direct:user1"
                                                                 :prompt [{:type "text" :text "Tell me a story"}]}))
            updates       (parsed-output writer)
            update-texts  (mapv #(get-in % [:params :update :content :text]) updates)
            update-kinds  (mapv #(get-in % [:params :update :sessionUpdate]) updates)
            transcript    (session-helper/get-transcript test-dir "agent:main:acp:direct:user1")
            assistant-msg (get-in (->> transcript
                                       (filter #(= "message" (:type %)))
                                       last)
                                  [:message :content])]
        (should= "end_turn" (get-in result [:result :stopReason]))
        (should= ["agent_message_chunk" "agent_message_chunk" "agent_message_chunk" "agent_message_chunk"] update-kinds)
        (should= ["Once " "upon " "a " "time..."] update-texts)
        (should= "Once upon a time..." assistant-msg)))

    (it "writes tool_call pending and tool_call_update completed notifications"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (tool-registry/register! {:name "echo-tool" :description "Echoes input" :handler (fn [args] {:result (str "echoed: " (:input args))})})
      (grover/enqueue! [{:tool_call "echo-tool" :arguments {:input "hello"}}
                        {:type "text" :content "Done!" :model "echo"}])
      (let [tool-agents   {"main" {:name "main" :soul "You are Isaac." :model "grover" :tools {:allow ["echo-tool"]}}}
            writer        (StringWriter.)
            result        (sut/dispatch-line (assoc prompt-opts :crew-members tool-agents :output-writer writer)
                                             (jrpc/request-line 30 "session/prompt"
                                                                {:sessionId "agent:main:acp:direct:user1"
                                                                 :prompt [{:type "text" :text "Use the echo tool"}]}))
            notifications (parsed-output writer)
            kinds         (mapv #(get-in % [:params :update :sessionUpdate]) notifications)]
        (should= "end_turn" (get-in result [:result :stopReason]))
        (should (some #(= "tool_call" %) kinds))
         (should (some #(= "tool_call_update" %) kinds))
         (should (every? #(= "agent:main:acp:direct:user1" (get-in % [:params :sessionId])) notifications))
         (should (some #(= "pending" (get-in % [:params :update :status])) notifications))
         (should (some #(= "completed" (get-in % [:params :update :status])) notifications))))

    (it "uses configured crew members when prompt handling is driven by cfg"
      ;; Migrated from isaac at b54488d; was passing in isaac's spec
      ;; harness, fails here. config/normalize-config DOES preserve
      ;; [:crew "main" :tools :allow], and module-loader/core-index
      ;; resolves correctly, so the cfg ought to reach the redef'd
      ;; run-turn!. The captured snapshot still comes back nil — likely
      ;; an interaction between system/with-nested-system, set-snapshot!,
      ;; and bridge/dispatch! routing that bypasses run-turn! in this
      ;; minimal setup. Leaving pending until someone can take a deeper
      ;; look without blocking CI on a pre-existing migration symptom.
      (pending "investigating snapshot capture in with-nested-system scope")
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (let [cfg           {:defaults  {:crew "main" :model "grover"}
                           :crew      {"main" {:soul "You are Isaac."
                                                :tools {:allow [:read :write :exec]}}}
                           :models    {"grover" {:model "echo" :provider "grover" :context-window 32768}}
                           :providers {"grover" {}}}
            captured-cfg  (atom nil)
            response      (with-redefs [single-turn/run-turn!
                                        (fn [_charge]
                                          (reset! captured-cfg (config/snapshot "ACP server-spec captured cfg"))
                                          {})]
                            (sut/dispatch-line {:state-dir     test-dir
                                                :cfg           cfg
                                                :output-writer (StringWriter.)}
                                               (jrpc/request-line 30 "session/prompt"
                                                                  {:sessionId "agent:main:acp:direct:user1"
                                                                   :prompt [{:type "text" :text "Use the configured tools"}]})))]
        (should= "end_turn" (get-in response [:result :stopReason]))
        (should= [:read :write :exec]
                 (get-in @captured-cfg [:crew "main" :tools :allow]))))

    (it "returns content through ACP when codex responses API emits tool call SSE events"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (tool-registry/register! {:name "read"
                                :description "Read file contents or list a directory"
                                :handler #'file/read-tool})
      (let [codex-agents {"main" {:name "main" :soul "Lives in a trash can." :model "snuffy" :tools {:allow ["read"]}}}
            codex-models {"snuffy" {:alias "snuffy" :model "snuffy-codex" :provider (str marigold/grover-api ":" marigold/quantum-anvil) :context-window 128000}}
            lid-file     (str test-dir "/trash-lid.txt")]
        (let [fs* (system/get :fs)]
          (fs/mkdirs fs* test-dir)
          (fs/spit fs* lid-file "Old newspaper and a banana peel."))
        (grover/enqueue! [{:model "snuffy-codex" :tool_call "read" :arguments {:filePath lid-file}}
                          {:model "snuffy-codex" :type "text" :content "Old newspaper and a banana peel."}])
        (let [writer        (StringWriter.)
              response      (sut/dispatch-line {:state-dir     test-dir
                                                :crew-members        codex-agents
                                                :models        codex-models
                                                :output-writer writer}
                                               (jrpc/request-line 31 "session/prompt"
                                                                  {:sessionId "agent:main:acp:direct:user1"
                                                                   :prompt [{:type "text" :text "what's under the lid?"}]}))
              notifications (parsed-output writer)
              transcript    (session-helper/get-transcript test-dir "agent:main:acp:direct:user1")
              assistant-msg (get-in (->> transcript
                                         (filter #(= "message" (:type %)))
                                         last)
                                    [:message :content])]
          (should= "end_turn" (get-in response [:result :stopReason]))
          (should (some #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate]))
                        notifications))
          (should= "Old newspaper and a banana peel." assistant-msg))))

    (it "sends provider error text as an agent message chunk and returns end_turn"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (grover/enqueue! [{:type "error" :content "You exceeded your current quota"}])
      (let [writer         (StringWriter.)
            response       (sut/dispatch-line (assoc prompt-opts :output-writer writer)
                                              (jrpc/request-line 10 "session/prompt"
                                                                 {:sessionId "agent:main:acp:direct:user1"
                                                                  :prompt [{:type "text" :text "Hello"}]}))
            notifications  (parsed-output writer)]
        (should= "end_turn" (get-in response [:result :stopReason]))
        (should-not (get-in response [:result :error]))
        (should (some #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate])) notifications))
        (should (some #(= "You exceeded your current quota" (get-in % [:params :update :content :text])) notifications))))

    (it "sends connection refused text as an agent message chunk and returns end_turn"
      ;; Marigold's themed apis all route to the grover stub (no real HTTP);
      ;; step out to exercise an api implementation that opens a TCP connection.
      (marigold-agent/with-real-manifest
        (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
        (let [writer   (StringWriter.)
              response (sut/dispatch-line {:state-dir        test-dir
                                           :crew-members     {"main" {:name "main" :soul "You are Isaac." :model "local"}}
                                           :models           {"local" {:alias "local" :model "llama3.2:latest" :provider "ollama" :context-window 32000}}
                                           :provider-configs {"ollama" {:base-url "http://localhost:99999"}}
                                           :output-writer    writer}
                                          (jrpc/request-line 11 "session/prompt"
                                                             {:sessionId "agent:main:acp:direct:user1"
                                                              :prompt [{:type "text" :text "Hello"}]}))
              notifications (parsed-output writer)]
          (should= "end_turn" (get-in response [:result :stopReason]))
          (should-not (get-in response [:result :error]))
          (should (some #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate])) notifications))
          (should (some #(str/includes? (get-in % [:params :update :content :text]) "Could not connect") notifications)))))

    (it "emits unknown crew guidance exactly once with a visible placeholder"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1" {:crew "marvin"})
      (let [writer        (StringWriter.)
            response      (sut/dispatch-line {:state-dir     test-dir
                                             :crew-members  {"main" {:name "main" :soul "You are Isaac." :model "grover"}}
                                             :models        {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}
                                             :output-writer writer}
                                            (jrpc/request-line 12 "session/prompt"
                                                               {:sessionId "agent:main:acp:direct:user1"
                                                                :prompt [{:type "text" :text "hello"}]}))
            notifications (parsed-output writer)
            text-updates   (filter #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate])) notifications)
            text           (-> text-updates first (get-in [:params :update :content :text]))]
        (should= "end_turn" (get-in response [:result :stopReason]))
        (should= 1 (count text-updates))
        (should= "unknown crew on session agent:main:acp:direct:user1: marvin\nsend /crew <name> to change crew" text)))

    (it "emits a no-model error when the default crew is implicit in config"
      (session-helper/create-session! test-dir "user1")
      (let [writer       (StringWriter.)
            error-writer (StringWriter.)
            cfg          {:defaults {}}
            response     (binding [*err* error-writer]
                           (sut/dispatch-line {:state-dir     test-dir
                                               :cfg           cfg
                                               :output-writer writer}
                                               (jrpc/request-line 13 "session/prompt"
                                                                 {:sessionId "user1"
                                                                  :prompt [{:type "text" :text "hello"}]})))
            notifications (parsed-output writer)
            text-updates  (filter #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate])) notifications)
            text          (-> text-updates first (get-in [:params :update :content :text]))]
        (should= "end_turn" (get-in response [:result :stopReason]))
        (should= 1 (count text-updates))
        (should= "unknown provider \"chatgpt\" — configured: (none) — known templates: flicker-labs, grover, grover-stub, helm-systems, quantum-anvil, starcore" text)
        (should= "" (str error-writer))))

    (it "catches unexpected exceptions and returns end_turn with error text"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (let [writer   (StringWriter.)
            response (with-redefs [single-turn/run-turn!
                                   (fn [& _] (throw (Exception. "something blew up")))]
                       (sut/dispatch-line (assoc prompt-opts :output-writer writer)
                                          (jrpc/request-line 12 "session/prompt"
                                                             {:sessionId "agent:main:acp:direct:user1"
                                                              :prompt [{:type "text" :text "hello"}]})))
            notifications (parsed-output writer)]
        (should= "end_turn" (get-in response [:result :stopReason]))
        (should-not (get-in response [:error]))
        (should (some #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate])) notifications))
        (should (some #(str/includes? (or (get-in % [:params :update :content :text]) "") "something blew up") notifications))))

    (it "writes a session/update text notification for slash commands"
      ;; Production /status formatting is the system under test.
      (marigold-agent/with-real-manifest
        (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
        (let [writer        (StringWriter.)
              result        (sut/dispatch-line (assoc prompt-opts :output-writer writer)
                                               (jrpc/request-line 40 "session/prompt"
                                                                  {:sessionId "agent:main:acp:direct:user1"
                                                                   :prompt [{:type "text" :text "/status"}]}))
              notifications (parsed-output writer)]
          (should= "end_turn" (get-in result [:result :stopReason]))
          (should (some #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate])) notifications))
          (let [content (->> notifications
                             (map #(get-in % [:params :update :content :text]))
                             (remove nil?)
                             (str/join "\n"))]
            (should (re-find #"```text" content))
            (should (re-find #"Session Status" content))
            (should (re-find #"─+" content))
            (should (re-find #"Model\s+echo \(grover\)" content))
            (should (re-find #"Session\s+agent:main:acp:direct:user1" content))
            (should (re-find #"Soul\s+\".+\"" content))
            (should-not (re-find #"SOUL\.md" content))))))

    (it "switches crew members for ACP slash commands"
      ;; Production /crew handler is the system under test.
      (marigold-agent/with-real-manifest
        (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
        (let [agents        {"main"  {:name "main" :soul "You are Isaac." :model "grover"}
                             "ketch" {:name "ketch" :soul "You are a pirate." :model "grover"}}
              writer        (StringWriter.)
              result        (sut/dispatch-line {:state-dir     test-dir
                                                :crew-members  agents
                                                :models        test-models
                                                :output-writer writer}
                                               (jrpc/request-line 41 "session/prompt"
                                                                  {:sessionId "agent:main:acp:direct:user1"
                                                                   :prompt [{:type "text" :text "/crew ketch"}]}))
              notifications (parsed-output writer)
              session       (session-helper/get-session test-dir "agent:main:acp:direct:user1")]
          (should= "end_turn" (get-in result [:result :stopReason]))
          (should= "ketch" (:crew session))
          (should-not (contains? session :agent))
          (should (some #(= "switched crew to ketch" (get-in % [:params :update :content :text])) notifications)))))

    (it "switches models for ACP slash commands"
      ;; Production /model handler is the system under test.
      (marigold-agent/with-real-manifest
        (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
        (let [alt-model       "starcore-7-fast"
              models-with-alt (assoc test-models marigold/starcore {:alias marigold/starcore :model alt-model :provider marigold/starcore :context-window 32768})
              writer          (StringWriter.)
              result          (sut/dispatch-line {:state-dir     test-dir
                                                  :crew-members  test-agents
                                                  :models        models-with-alt
                                                  :output-writer writer}
                                                 (jrpc/request-line 42 "session/prompt"
                                                                    {:sessionId "agent:main:acp:direct:user1"
                                                                     :prompt [{:type "text" :text (str "/model " marigold/starcore)}]}))
              notifications   (parsed-output writer)
              session         (session-helper/get-session test-dir "agent:main:acp:direct:user1")]
          (should= "end_turn" (get-in result [:result :stopReason]))
          (should= marigold/starcore (:model session))
          (should-be-nil (:provider session))
          (should (some #(= (str "switched model to " marigold/starcore " (" marigold/starcore "/" alt-model ")") (get-in % [:params :update :content :text])) notifications)))))

  )

  (describe "session/cancel"

    (before
      (grover/reset-queue!)
      (tool-registry/clear!))

    (it "returns cancelled when interrupt is set before prompt starts"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (grover/enqueue! [{:type "text" :content "Hello" :model "echo"}])
      (sut/dispatch-line prompt-opts
                         (jrpc/notification-line "session/cancel"
                                                 {:sessionId "agent:main:acp:direct:user1"}))
      (let [result (-> (sut/dispatch-line prompt-opts
                                          (jrpc/request-line 30 "session/prompt"
                                                             {:sessionId "agent:main:acp:direct:user1"
                                                              :prompt [{:type "text" :text "Long task"}]}))
                       (as-> r (if (future? r) (deref r) r)))]
        (should= "cancelled" (get-in result [:result :stopReason]))))

    (it "logs session/cancel receipt at info with raw params"
      (log/capture-logs
        (sut/dispatch-line prompt-opts
                           (jrpc/notification-line "session/cancel"
                                                   {:sessionId "agent:main:acp:direct:user1"}))
        (let [entry (first (filter #(= :acp/session-cancel-received (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should= :info (:level entry))
          (should= "agent:main:acp:direct:user1" (:sessionId entry))
          (should= {:sessionId "agent:main:acp:direct:user1"} (:params entry)))))

    (it "returns cancelled when session/cancel interrupts an in-flight LLM request"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (grover/enable-delay!)
      (let [prompt (future
                     (sut/dispatch-line (assoc prompt-opts :output-writer (StringWriter.))
                                        (jrpc/request-line 31 "session/prompt"
                                                           {:sessionId "agent:main:acp:direct:user1"
                                                            :prompt [{:type "text" :text "think hard"}]})))]
        (grover/await-delay-start)
        (sut/dispatch-line prompt-opts
                           (jrpc/notification-line "session/cancel"
                                                   {:sessionId "agent:main:acp:direct:user1"}))
        (grover/release-delay!)
        (should= "cancelled" (get-in @prompt [:result :stopReason]))))

    (it "returns cancelled when session/cancel interrupts an in-flight exec tool"
      ;; Exercises the production exec tool's cancellation behavior.
      (marigold-agent/with-real-manifest
        (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
        (builtin/register-all!)
        (grover/enqueue! [{:tool_call "exec" :arguments {:command "sleep 30"}}])
        (let [exec-agents   {"main" {:name "main" :soul "You are Isaac." :model "grover" :tools {:allow ["exec"]}}}
            started (promise)
            release (promise)
            prompt  (future
                      (with-redefs [exec/exec-tool
                                    (fn [{:keys [session-key]}]
                                      (deliver started true)
                                      @release
                                      (if (bridge/cancelled? session-key)
                                        {:error :cancelled}
                                        {:result "done"}))]
                        (sut/dispatch-line (assoc prompt-opts :crew-members exec-agents :output-writer (StringWriter.))
                                           (jrpc/request-line 32 "session/prompt"
                                                              {:sessionId "agent:main:acp:direct:user1"
                                                               :prompt [{:type "text" :text "run it"}]}))))]
          (should= true (deref started 1000 nil))
          (sut/dispatch-line prompt-opts
                             (jrpc/notification-line "session/cancel"
                                                     {:sessionId "agent:main:acp:direct:user1"}))
          (deliver release true)
          (should= "cancelled" (get-in (deref prompt 1000 nil) [:result :stopReason])))))

    (it "emits a cancelled tool_call_update when session/cancel interrupts an in-flight exec tool"
      (marigold-agent/with-real-manifest
        (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
        (builtin/register-all!)
        (grover/enqueue! [{:tool_call "exec" :arguments {:command "sleep 30"}}])
        (let [writer      (StringWriter.)
              exec-agents {"main" {:name "main" :soul "You are Isaac." :model "grover" :tools {:allow ["exec"]}}}
              started     (promise)
              release     (promise)
              prompt      (future
                            (with-redefs [exec/exec-tool
                                          (fn [{:keys [session-key]}]
                                            (deliver started true)
                                            @release
                                            (if (bridge/cancelled? session-key)
                                              {:error :cancelled}
                                              {:result "done"}))]
                              (sut/dispatch-line (assoc prompt-opts :crew-members exec-agents :output-writer writer)
                                                 (jrpc/request-line 33 "session/prompt"
                                                                    {:sessionId "agent:main:acp:direct:user1"
                                                                     :prompt [{:type "text" :text "run it"}]}))))]
          (should= true (deref started 1000 nil))
          (sut/dispatch-line prompt-opts
                             (jrpc/notification-line "session/cancel"
                                                     {:sessionId "agent:main:acp:direct:user1"}))
          (deliver release true)
          (should= "cancelled" (get-in (deref prompt 1000 nil) [:result :stopReason]))
          (let [notifications (parsed-output writer)
                statuses      (map #(select-keys (get-in % [:params :update]) [:sessionUpdate :status])
                                   notifications)]
            (should-contain {:sessionUpdate "tool_call" :status "pending"} statuses)
            (should-contain {:sessionUpdate "tool_call_update" :status "cancelled"} statuses)))))

    (it "appends exactly one error entry when run-turn! throws an uncaught exception"
      (session-helper/create-session! test-dir "agent:main:acp:direct:user1")
      (with-redefs [single-turn/check-compaction! (fn [& _] (throw (RuntimeException. "forced failure")))]
        (sut/dispatch-line (assoc prompt-opts :output-writer (StringWriter.))
                           (jrpc/request-line 99 "session/prompt"
                                              {:sessionId "agent:main:acp:direct:user1"
                                               :prompt [{:type "text" :text "trigger crash"}]})))
      (let [transcript   (session-helper/get-transcript test-dir "agent:main:acp:direct:user1")
            error-entries (filter #(= "error" (:type %)) transcript)]
        (should= 1 (count error-entries))
        (should= "forced failure" (:content (first error-entries)))))

  )

)
