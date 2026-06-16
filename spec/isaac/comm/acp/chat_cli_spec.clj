(ns isaac.comm.acp.chat-cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [isaac.cli.registry :as registry]
    [isaac.comm.acp.chat-cli :as sut]
    [isaac.bridge.core :as bridge]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as config]
    [isaac.drive.dispatch :as dispatch]
    [isaac.drive.turn :as single-turn]
    [isaac.llm.api.messages :as anthropic]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.provider :as llm-provider]
    [isaac.llm.api.ollama :as ollama]
    [isaac.llm.api.chat-completions :as chat-completions]
    [isaac.llm.providers :as providers]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.logger :as log]
    [isaac.session.store.spi :as store]
    [isaac.session.compaction :as compaction]
    [isaac.spec-helper :as storage]
    [isaac.session.spec-helper :as session-helper]
    isaac.server.routes
    isaac.slash.registry
    [isaac.tool.registry :as tool-registry]
    [isaac.util.shell :as shell]
    [isaac.fs :as fs]
    [isaac.config.root :as home]
    [isaac.main :as main]
    [isaac.system :as system]
    [speclj.core :refer :all]))

(def test-dir "/test/chat")

(defn- clean-dir! [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defn- write-config! [home data]
  (let [path (str home "/.isaac/config/isaac.edn")
        fs*  (system/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (pr-str data))))

(defn- dispatch-turn! [session-key input opts]
  (bridge/dispatch! (merge {:origin      {:kind :cli}
                            :session-key session-key
                            :input       input}
                           opts)))

(describe "CLI Chat"

  (after (single-turn/clear-async-compactions!))
  (around [it] (session-helper/with-memory-store (system/with-system {:state-dir test-dir :fs (fs/mem-fs)} (it))))

  (describe "run"

    (it "fails clearly when no config exists"
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (with-out-str
            (should= 1 (sut/run {:home "/test/chat-no-config" :dry-run true}))))
        (should-contain "no config found" (str err))
        (should-contain "config/isaac.edn" (str err))))

    (it "fails clearly when no config exists via main/run"
      (let [home-dir "/tmp/chat-main-no-config"
            err      (java.io.StringWriter.)]
        (clean-dir! home-dir)
        (registry/register! (sut/make-command))
        (binding [*err* err
                  *out* (java.io.StringWriter.)
                  home/*user-home* home-dir
                  main/*extra-opts* {:home home-dir}]
          (should= 1 (main/run ["chat" "--dry-run"])))
        (should-contain "no config found" (str err))
        (should-contain "config/isaac.edn" (str err))))

    (it "fails clearly when no config exists via main/run with an unrelated in-memory state"
      (let [home-dir  "/test/chat-no-config-home"
            state-dir "/test/chat-seeded/.isaac"
            err       (java.io.StringWriter.)]
        (system/with-nested-system {:fs (fs/mem-fs)}
          (write-config! "/test/chat-seeded" {})
          (registry/register! (sut/make-command))
          (binding [*err* err
                    *out* (java.io.StringWriter.)
                    home/*user-home* home-dir
                    main/*extra-opts* {:home home-dir :fs (system/get :fs)}]
            (should= 1 (main/run ["chat" "--dry-run"]))))
        (should-contain "no config found" (str err))
        (should-contain "config/isaac.edn" (str err))))

    (it "launches Toad by default"
      (let [captured (atom nil)]
        (write-config! test-dir {})
        (with-redefs [shell/cmd-available? (constantly true)
                      sut/spawn-toad!      (fn [opts]
                                              (reset! captured opts)
                                              0)]
          (should= 0 (sut/run {:home test-dir}))
          (should= {:home test-dir} @captured))))

    (it "prints the dry-run command without requiring --toad"
      (write-config! test-dir {})
      (with-redefs [shell/cmd-available? (constantly true)]
        (let [output (with-out-str (should= 0 (sut/run {:home test-dir :dry-run true :resume true})))]
          (should-contain "toad" output)
          (should-contain "isaac acp --resume" output)))))

  (describe "run-fn"

    (it "prints command help and returns 0 when --help is requested"
      (with-redefs [sut/parse-option-map (fn [_] {:options {:help true} :errors []})
                    registry/get-command (fn [_] {:name "chat"})
                    registry/command-help (fn [_] "chat help")]
        (let [output (with-out-str (should= 0 (sut/run-fn {:_raw-args ["--help"]})))]
          (should-contain "chat help" output))))

    (it "prints parse errors and returns 1"
      (with-redefs [sut/parse-option-map (fn [_] {:options {} :errors ["bad arg"]})]
        (let [output (with-out-str (should= 1 (sut/run-fn {:_raw-args ["--bogus"]})))]
          (should-contain "bad arg" output))))

    (it "delegates to run with parsed options merged into opts"
      (let [captured (atom nil)]
        (with-redefs [sut/parse-option-map (fn [_] {:options {:resume true} :errors []})
                      sut/run              (fn [opts]
                                             (reset! captured opts)
                                             0)]
          (should= 0 (sut/run-fn {:_raw-args ["--resume"] :home "/tmp/home"}))
          (should= {:home "/tmp/home" :resume true} @captured)))))

  (describe "build-chat-request"

    (it "builds request for ollama provider"
      (let [result (single-turn/build-chat-request (llm-provider/make-provider "ollama" {})
                     {:model      "qwen:7b"
                      :soul       "You are helpful."
                      :transcript [{:type "message" :message {:role "user" :content "hi"}}]})]
        (should= "qwen:7b" (:model result))
        (should-not-be-nil (:messages result))
        (should-be-nil (:system result))))

    (it "includes tool definitions when tools are provided"
      (let [tools  [{:name "read" :description "Read a file" :parameters {}}]
            result (single-turn/build-chat-request (llm-provider/make-provider "ollama" {})
                     {:model      "qwen:7b"
                      :soul       "You are helpful."
                      :transcript [{:type "message" :message {:role "user" :content "hi"}}]
                      :tools      tools})]
        (should-not-be-nil (:tools result))
        (should= 1 (count (:tools result)))))

    (it "omits tools key when no tools are provided"
      (let [result (single-turn/build-chat-request (llm-provider/make-provider "ollama" {})
                     {:model      "qwen:7b"
                      :soul       "You are helpful."
                      :transcript [{:type "message" :message {:role "user" :content "hi"}}]})]
        (should-be-nil (:tools result))))

    (it "preserves tool call history with type:function for openai provider"
      (let [result (single-turn/build-chat-request (llm-provider/make-provider "openai" {:api "chat-completions"})
                     {:model      "gpt-5.4"
                      :soul       "You are helpful."
                      :transcript [{:type "message" :message {:role "user" :content "read the fridge"}}
                                   {:type "message" :message {:role "assistant"
                                                              :content [{:type "toolCall"
                                                                         :id "call_123"
                                                                         :name "read"
                                                                         :arguments {:filePath "fridge.txt"}}]}}
                                   {:type "message" :message {:role "toolResult"
                                                              :id "call_123"
                                                              :content "1 sad lemon, Hieronymus's emergency lettuce"}}
                                   {:type "message" :message {:role "assistant" :content "The fridge has a sad lemon and forbidden lettuce."}}]})
            msgs (:messages result)
            tool-msg (first (filter #(contains? % :tool_calls) msgs))
            tool-result-msg (first (filter #(= "tool" (:role %)) msgs))]
        (should-not-be-nil tool-msg)
        (should= "function" (get-in tool-msg [:tool_calls 0 :type]))
        (should= "read" (get-in tool-msg [:tool_calls 0 :function :name]))
        (should-not-be-nil tool-result-msg)
        (should= "call_123" (:tool_call_id tool-result-msg))))

    (it "builds request for anthropic provider with system"
      (let [result (single-turn/build-chat-request (llm-provider/make-provider "anthropic" {})
                     {:model      "claude-sonnet-4-20250514"
                      :soul       "You are helpful."
                      :transcript [{:type "message" :message {:role "user" :content "hi"}}]})]
        (should= "claude-sonnet-4-20250514" (:model result))
        (should-not-be-nil (:messages result))
        (should-not-be-nil (:system result))
        (should-not-be-nil (:max_tokens result)))))

  (describe "private helpers"

    (it "resolves the ollama api explicitly"
      (should= :ollama (api/resolve-api "ollama" {})))

    (it "marks tool results as errors when the result starts with Error"
      (let [messages (atom [])
            recorder (reify store/SessionStore
                       (append-message! [_ _ message] (swap! messages conj message)))]
        (single-turn/run-tool-calls! {:session-store recorder}
                                     "agent:main:cli:direct:toolerr"
                                     [[{:id "tc-1" :name "boom" :type "toolCall" :arguments {}}
                                       "Error: something went wrong"]])
        (should= true (:isError (second @messages)))))

    )

  (describe "dispatch-chat"

    (storage/with-captured-logs)

    (it "dispatches chat-completions errors and logs them"
      (let [provider (reify api/Api
                       (chat [_ _] {:error :auth-failed :status 401})
                       (chat-stream [_ _ _] (throw (ex-info "unused" {})))
                       (followup-messages [_ _ _ _ _] (throw (ex-info "unused" {})))
                       (config [_] {})
                       (display-name [_] "openai")
                       (build-prompt [_ _] (throw (ex-info "unused" {})))
                       (format-tools [_ _] nil))
            result   (dispatch/dispatch-chat provider {:model "m" :messages []})]
        (should= :auth-failed (:error result))
        (should= [:chat/request :chat/error] (mapv :event @log/captured-logs)))))

  (describe "dispatch-chat-stream"

    (storage/with-captured-logs)

    (it "dispatches ollama stream requests and logs success"
      (let [chunks   (atom [])
            provider (reify api/Api
                       (chat [_ _] (throw (ex-info "unused" {})))
                       (chat-stream [_ _ on-chunk]
                         (on-chunk {:message {:content "hi"}})
                         {:model "qwen" :message {:role "assistant" :content "hi"}})
                       (followup-messages [_ _ _ _ _] (throw (ex-info "unused" {})))
                       (config [_] {})
                       (display-name [_] "ollama")
                       (build-prompt [_ _] (throw (ex-info "unused" {})))
                       (format-tools [_ _] nil))]
        (let [result (dispatch/dispatch-chat-stream provider {:model "m" :messages []}
                                               #(swap! chunks conj %))]
          (should= "qwen" (:model result))
          (should= 1 (count @chunks))
          (should= [:chat/stream-request :chat/stream-response] (mapv :event @log/captured-logs))))))

    (it "dispatches anthropic stream errors and logs them"
      (log/capture-logs
        (let [provider (reify api/Api
                         (chat [_ _] (throw (ex-info "unused" {})))
                         (chat-stream [_ _ _] {:error :connection-refused})
                         (followup-messages [_ _ _ _ _] (throw (ex-info "unused" {})))
                         (config [_] {})
                         (display-name [_] "anthropic")
                         (build-prompt [_ _] (throw (ex-info "unused" {})))
                         (format-tools [_ _] nil))
              result   (dispatch/dispatch-chat-stream provider {:model "m" :messages []} identity)]
          (should= :connection-refused (:error result))
          (should= [:chat/stream-request :chat/stream-error] (mapv :event @log/captured-logs))))))

  (describe "extract-tokens"

    (it "extracts tokens from anthropic-style response"
      (let [result {:content  "hello"
                    :response {:usage {:input-tokens  100
                                       :output-tokens 50
                                       :cache-read    10
                                       :cache-write   5}}}
            tokens (single-turn/extract-tokens result)]
        (should= 100 (:input-tokens tokens))
        (should= 50 (:output-tokens tokens))
        (should= 10 (:cache-read tokens))
        (should= 5 (:cache-write tokens))))

    (it "extracts tokens from ollama-style response"
      (let [result {:content  "hello"
                    :response {:prompt_eval_count 200
                               :eval_count        80}}
            tokens (single-turn/extract-tokens result)]
        (should= 200 (:input-tokens tokens))
        (should= 80 (:output-tokens tokens))))

    (it "defaults to zero when no usage data"
      (let [result {:content "hello" :response {}}
            tokens (single-turn/extract-tokens result)]
        (should= 0 (:input-tokens tokens))
        (should= 0 (:output-tokens tokens))
        (should-be-nil (:cache-read tokens))
        (should-be-nil (:cache-write tokens)))))

  (describe "process-response!"

    (around [it] (session-helper/with-memory-store (system/with-nested-system {:fs (fs/mem-fs)} (it))))
    (storage/with-captured-logs)

    (it "appends assistant message and updates tokens on success"
      (let [key-str "testuser"
            _       (session-helper/create-session! test-dir key-str)
            result  {:content  "I can help!"
                     :response {:usage {:input-tokens 50 :output-tokens 20}}}]
        (single-turn/process-response! key-str result {:model "qwen:7b" :provider "ollama"})
        (let [transcript (session-helper/get-transcript test-dir key-str)
              messages   (filter #(= "message" (:type %)) transcript)
              last-msg   (last messages)]
          (should= "assistant" (get-in last-msg [:message :role]))
          (should= "I can help!" (get-in last-msg [:message :content]))
          (should= 70 (get-in last-msg [:message :tokens])))))

    (it "updates last-input-tokens from the latest response usage"
      (let [key-str "agent:main:cli:direct:last-input"
            _       (session-helper/create-session! test-dir key-str)
            _       (session-helper/update-tokens! test-dir key-str {:input-tokens 10 :output-tokens 5})
            result  {:content  "Hello!"
                     :response {:usage {:input-tokens 42 :output-tokens 5}}}]
        (single-turn/process-response! key-str result {:model "qwen:7b" :provider "ollama"})
        (let [entry (session-helper/get-session test-dir key-str)]
          (should= 42 (:last-input-tokens entry)))))

    (it "accumulates response usage onto the existing session counters"
      (let [key-str "agent:main:cli:direct:token-accumulation"
            _       (session-helper/create-session! test-dir key-str)
            _       (session-helper/update-tokens! test-dir key-str {:input-tokens 10 :output-tokens 5 :cache-read 3 :cache-write 2})
            result  {:content  "Hello again!"
                     :response {:usage {:input-tokens 42 :output-tokens 5 :cache-read 7 :cache-write 11}}}]
        (single-turn/process-response! key-str result {:model "qwen:7b" :provider "ollama"})
        (let [entry (session-helper/get-session test-dir key-str)]
          (should= 52 (:input-tokens entry))
          (should= 10 (:output-tokens entry))
          (should= 62 (:total-tokens entry))
          (should= 42 (:last-input-tokens entry))
          (should= 10 (:cache-read entry))
          (should= 13 (:cache-write entry)))))

    (it "stores the provider-returned model in the transcript"
      (let [key-str "agent:main:cli:direct:model-test"
            _       (session-helper/create-session! test-dir key-str)
            result  {:content  "Hello!"
                     :response {:model "gpt-5-20250714"
                                :usage {:input-tokens 10 :output-tokens 5}}}]
        (single-turn/process-response! key-str result {:model "gpt-5" :provider "openai"})
        (let [transcript (session-helper/get-transcript test-dir key-str)
              messages   (filter #(= "message" (:type %)) transcript)
              last-msg   (last messages)]
          (should= "gpt-5-20250714" (get-in last-msg [:message :model])))))

    (it "falls back to configured model when provider returns no model"
      (let [key-str "agent:main:cli:direct:fallback-test"
            _       (session-helper/create-session! test-dir key-str)
            result  {:content  "Hello!"
                     :response {:usage {:input-tokens 10 :output-tokens 5}}}]
        (single-turn/process-response! key-str result {:model "qwen:7b" :provider "ollama"})
        (let [transcript (session-helper/get-transcript test-dir key-str)
              messages   (filter #(= "message" (:type %)) transcript)
              last-msg   (last messages)]
          (should= "qwen:7b" (get-in last-msg [:message :model])))))

    (it "returns error result on failure"
      (let [result (single-turn/process-response! "agent:x:cli:direct:x"
                                          {:error true :message "API timeout"}
                                          {:model "m" :provider "p"})]
        (should= true (:error result))
        (should= "API timeout" (:message result))))

    (it "records error entries in transcript when llm call fails"
      (let [key-str "agent:main:cli:direct:error-test"
            _      (session-helper/create-session! test-dir key-str)
            _      (single-turn/process-response! key-str
                                           {:error :connection-refused :message "refused"}
                                           {:model "qwen:7b" :provider "ollama"})
            transcript (session-helper/get-transcript test-dir key-str)
            last-entry (last transcript)]
        (should= "error" (:type last-entry))
        (should= ":connection-refused" (:error last-entry))
        (should= "refused" (:content last-entry))
        (should= "qwen:7b" (:model last-entry))
        (should= "ollama" (:provider last-entry))))

    (it "returns body error details in result when message is absent"
      (let [result (single-turn/process-response! "agent:x:cli:direct:x"
                                          {:error  :api-error
                                           :status 400
                                           :body   {:error {:type "invalid_request_error"
                                                            :message "Bad request"}}}
                                          {:model "m" :provider "p"})]
        (should= :api-error (:error result))
        (should= 400 (:status result))))

    (it "returns http status error in result when only status is available"
      (let [result (single-turn/process-response! "agent:x:cli:direct:x"
                                          {:error  :api-error
                                           :status 503}
                                          {:model "m" :provider "p"})]
        (should= :api-error (:error result))
        (should= 503 (:status result))))

    (it "returns nil on success"
      (let [key-str "agent:main:cli:direct:success-ret"
            _       (session-helper/create-session! test-dir key-str)
            result  (single-turn/process-response! key-str
                                           {:content "Hello!" :response {:usage {:input-tokens 5 :output-tokens 3}}}
                                           {:model "m" :provider "p"})]
        (should-be-nil result)))

    (it "logs :chat/response-failed at error with session and provider on error"
      (single-turn/process-response! "agent:x:cli:direct:x"
                             {:error :connection-refused}
                             {:model "m" :provider "ollama"})
      (let [entry (first (filter #(= :chat/response-failed (:event %)) @log/captured-logs))]
        (should-not-be-nil entry)
        (should= :error (:level entry))
        (should= "ollama" (:provider entry))
        (should= "agent:x:cli:direct:x" (:session entry))))

    (it "logs :session/message-stored at debug with session and model on success"
      (let [key-str "agent:main:cli:direct:log-test"
            _       (session-helper/create-session! test-dir key-str)]
        (single-turn/process-response! key-str
                               {:content  "Hello!"
                                :response {:model "grover" :usage {:input-tokens 10 :output-tokens 5}}}
                               {:model "grover" :provider "grover"})
        (let [entry (first (filter #(= :session/message-stored (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should= :debug (:level entry))
          (should= key-str (:session entry))
          (should= "grover" (:model entry)))))

  ) ; end describe process-response!

  (describe "check-compaction!"

    (around [it] (session-helper/with-memory-store (system/with-nested-system {:fs (fs/mem-fs)} (it))))
    (storage/with-captured-logs)

    (it "does not compact when under context window"
      (let [key-str  "agent:main:cli:direct:comptest"
            _        (session-helper/create-session! test-dir key-str)
            compacted (atom false)]
        (with-redefs [compaction/should-compact? (constantly false)
                      compaction/compact!        (fn [& _] (reset! compacted true))]
          (single-turn/check-compaction! key-str
                                 {:model "m" :soul "s" :context-window 32768
                                  :provider (llm-provider/make-provider "ollama" {})})
          (should= false @compacted))))

    (it "compacts when over context window"
      (let [key-str  "agent:main:cli:direct:comptest2"
            _        (session-helper/create-session! test-dir key-str)
            compacted (atom false)]
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [& _] (reset! compacted true))]
          (with-out-str
            (single-turn/check-compaction! key-str
                                   {:model "m" :soul "s" :context-window 32768
                                    :provider (llm-provider/make-provider "ollama" {})}))
          (should= true @compacted))))

    (it "passes the matching session entry to compaction checks"
      (let [checked-entry (atom nil)
            store-stub    (reify store/SessionStore
                            (get-session [_ key-str]
                              (when (= key-str "agent:main:cli:direct:target")
                                {:key "agent:main:cli:direct:target" :context-window 2})))]
        (with-redefs [compaction/should-compact?  (fn [entry _]
                                             (reset! checked-entry entry)
                                             false)]
          (single-turn/check-compaction! {:session-store store-stub}
                                 "agent:main:cli:direct:target"
                                 {:model "m" :soul "s" :context-window 32768
                                  :provider (llm-provider/make-provider "ollama" {})})
          (should= "agent:main:cli:direct:target" (:key @checked-entry)))))

    (it "logs :session/compaction-check at debug with session, provider, model, total-tokens, context-window"
      (let [key-str "agent:main:cli:direct:checklog"
            _       (session-helper/create-session! test-dir key-str)
            _       (session-helper/update-tokens! test-dir key-str {:input-tokens 50 :output-tokens 0})]
        (with-redefs [compaction/should-compact? (constantly false)]
          (single-turn/check-compaction! key-str
                                 {:model "echo" :soul "s" :context-window 100
                                  :provider (llm-provider/make-provider "grover" {})}))
        (let [entry (first (filter #(= :session/compaction-check (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should= :debug (:level entry))
          (should= key-str (:session entry))
          (should= "grover" (:provider entry))
          (should= "echo" (:model entry))
          (should= 50 (:total-tokens entry))
          (should= 100 (:context-window entry)))))

    (it "logs :session/compaction-started at info when compaction triggers"
      (let [key-str "agent:main:cli:direct:startlog"
            _       (session-helper/create-session! test-dir key-str)
            _       (session-helper/update-tokens! test-dir key-str {:input-tokens 50 :output-tokens 0})]
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [& _] nil)]
          (with-out-str
            (single-turn/check-compaction! key-str
                                   {:model "echo" :soul "s" :context-window 100
                                    :provider (llm-provider/make-provider "grover" {})})))
        (let [entry (first (filter #(= :session/compaction-started (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should= :info (:level entry))
          (should= key-str (:session entry))
          (should= "grover" (:provider entry))
          (should= "echo" (:model entry))
          (should= 50 (:total-tokens entry))
          (should= 100 (:context-window entry)))))

    (it "threads :api through to compaction/compact!"
      (let [key-str  "agent:main:cli:direct:providerpass"
            _        (session-helper/create-session! test-dir key-str)
            captured (atom nil)]
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [_ opts]
                                            (reset! captured opts)
                                            {:type "compaction"})]
          (with-out-str
            (single-turn/check-compaction! key-str
                                           {:model "echo" :soul "s" :context-window 100
                                            :provider (llm-provider/make-provider "chatgpt" {})})))
        (should= "chatgpt" (api/display-name (:api @captured)))))

    (it "does not log :session/compaction-started when under threshold"
      (let [key-str "agent:main:cli:direct:nolog"
            _       (session-helper/create-session! test-dir key-str)]
        (with-redefs [compaction/should-compact? (constantly false)]
          (single-turn/check-compaction! key-str
                                 {:model "m" :soul "s" :context-window 100
                                  :provider (llm-provider/make-provider "grover" {})}))
        (let [entry (first (filter #(= :session/compaction-started (:event %)) @log/captured-logs))]
          (should-be-nil entry))))

    (it "logs :session/compaction-skipped when compaction is disabled for the session"
      (let [key-str "agent:main:cli:direct:skipdisabled"
            _       (session-helper/create-session! test-dir key-str)]
        (session-helper/update-session! test-dir key-str {:compaction-disabled true})
        (single-turn/check-compaction! key-str
                                       {:model "m" :soul "s" :context-window 100
                                        :provider (llm-provider/make-provider "grover" {})})
        (let [entry (first (filter #(= :session/compaction-skipped (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should= :info (:level entry))
          (should= key-str (:session entry))
          (should= "grover" (:provider entry))
          (should= "m" (:model entry))
          (should= 0 (:total-tokens entry))
          (should= 100 (:context-window entry))
          (should= :disabled (:reason entry)))))

    (it "logs :session/compaction-failed at error when compact! returns an error"
      (let [key-str "agent:main:cli:direct:faillog"
            _       (session-helper/create-session! test-dir key-str)]
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [& _] {:error :llm-error :message "context length exceeded"})]
          (with-out-str
            (single-turn/check-compaction! key-str
                                   {:model "m" :soul "s" :context-window 100
                                    :provider (llm-provider/make-provider "grover" {})})))
        (let [entry (first (filter #(= :session/compaction-failed (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should= :error (:level entry))
          (should= key-str (:session entry))
          (should= :llm-error (:error entry))
          (should= "context length exceeded" (:message entry)))))

    (it "increments consecutive compaction failures on error"
      (let [key-str "agent:main:cli:direct:failcount"
            _       (session-helper/create-session! test-dir key-str)]
        (session-helper/update-session! test-dir key-str {:compaction {:consecutive-failures 1}})
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [& _] {:error :llm-error :message "context length exceeded"})]
          (with-out-str
            (single-turn/check-compaction! key-str
                                           {:model "m" :soul "s" :context-window 100
                                            :provider (llm-provider/make-provider "grover" {})})))
        (should= 2 (get-in (session-helper/get-session test-dir key-str) [:compaction :consecutive-failures]))))

    (it "disables compaction after another failure once the consecutive threshold is reached"
      (let [key-str "agent:main:cli:direct:giveup"
            _       (session-helper/create-session! test-dir key-str)
            tried?  (atom false)]
        (session-helper/update-session! test-dir key-str {:compaction {:consecutive-failures 5}})
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [& _]
                                            (reset! tried? true)
                                            {:error :llm-error :message "context length exceeded"})]
          (single-turn/check-compaction! key-str
                                         {:model "m" :soul "s" :context-window 100
                                          :provider (llm-provider/make-provider "grover" {})}))
        (should= true @tried?)
        (let [session (session-helper/get-session test-dir key-str)
              entry   (first (filter #(= :session/compaction-stopped (:event %)) @log/captured-logs))]
          (should= true (:compaction-disabled session))
          (should= :too-many-failures (:reason entry)))))

    (it "resets consecutive compaction failures after a successful compaction"
      (let [key-str "agent:main:cli:direct:failreset"
            _       (session-helper/create-session! test-dir key-str)]
        (session-helper/update-session! test-dir key-str {:compaction {:consecutive-failures 3}})
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [compact-key _]
                                            (session-helper/update-session! test-dir compact-key {:total-tokens 10})
                                            {:type "compaction"})]
          (with-out-str
            (single-turn/check-compaction! key-str
                                           {:model "m" :soul "s" :context-window 100
                                            :provider (llm-provider/make-provider "grover" {})})))
        (should= 0 (get-in (session-helper/get-session test-dir key-str) [:compaction :consecutive-failures]))))

    (it "repeats compaction until the session no longer exceeds the context window"
      (let [key-str   "agent:main:cli:direct:repeatloop"
            _         (session-helper/create-session! test-dir key-str)
            _         (session-helper/update-session! test-dir key-str {:last-input-tokens 62})
            attempts  (atom 0)]
        (with-redefs [compaction/compact! (fn [compact-key _]
                                     (swap! attempts inc)
                                     (session-helper/update-session! test-dir compact-key
                                                               {:last-input-tokens (case @attempts
                                                                                     1 40
                                                                                     2 20)})
                                     {:type "compaction"})]
          (with-out-str
            (single-turn/check-compaction! key-str
                                   {:model "qwen3-coder:30b" :soul "You are Isaac." :context-window 32
                                    :provider (llm-provider/make-provider "grover" {})})))
        (should= 2 @attempts)))

    (it "notifies the channel with compaction-start when compaction triggers"
      (let [key-str       "agent:main:cli:direct:channelatom"
            _             (session-helper/create-session! test-dir key-str)
            chunks        (atom [])
            mock-channel  (reify comm/Comm
                            (on-turn-start [_ _ _] nil)
                            (on-text-chunk [_ _ _] nil)
                            (on-tool-call [_ _ _] nil)
                            (on-tool-cancel [_ _ _] nil)
                            (on-tool-result [_ _ _ _] nil)
                            (on-compaction-start [_ _ payload] (swap! chunks conj payload))
                            (on-compaction-success [_ _ _] nil)
                            (on-compaction-failure [_ _ _] nil)
                            (on-compaction-disabled [_ _ _] nil)
                            (on-turn-end [_ _ _] nil))]
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [& _] nil)]
          (single-turn/check-compaction! key-str
                                 {:model "m" :soul "s" :context-window 100
                                  :provider (llm-provider/make-provider "grover" {})
                                  :comm mock-channel}))
        (should= [{:provider "grover" :model "m" :total-tokens 0 :context-window 100}] @chunks)))

    (it "does not notify the channel when compaction does not trigger"
      (let [key-str       "agent:main:cli:direct:nochunk"
            _             (session-helper/create-session! test-dir key-str)
            chunks        (atom [])
            mock-channel  (reify comm/Comm
                            (on-turn-start [_ _ _] nil)
                            (on-text-chunk [_ _ _] nil)
                            (on-tool-call [_ _ _] nil)
                            (on-tool-cancel [_ _ _] nil)
                            (on-tool-result [_ _ _ _] nil)
                            (on-compaction-start [_ _ payload] (swap! chunks conj payload))
                            (on-compaction-success [_ _ _] nil)
                            (on-compaction-failure [_ _ _] nil)
                            (on-compaction-disabled [_ _ _] nil)
                            (on-turn-end [_ _ _] nil))]
        (with-redefs [compaction/should-compact? (constantly false)]
           (single-turn/check-compaction! key-str
                                  {:model "m" :soul "s" :context-window 100
                                   :provider (llm-provider/make-provider "grover" {})
                                   :comm mock-channel}))
        (should= [] @chunks)))

    (it "starts async compaction in the background when enabled"
      (let [key-str     "agent:main:cli:direct:asyncstart"
            _           (session-helper/create-session! test-dir key-str)
            _           (session-helper/update-session! test-dir key-str {:compaction {:strategy :slinky :threshold 80 :head 40 :async? true}})
            entered?    (promise)
            release!    (promise)
            completed?  (atom false)]
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [& _]
                                            (deliver entered? true)
                                            @release!
                                            (reset! completed? true)
                                            {:type "compaction"})]
          (should-not= ::pending
                       (deref (future
                                (single-turn/check-compaction! key-str
                                                               {:model "m" :soul "s" :context-window 100
                                                                :provider (llm-provider/make-provider "grover" {})}))
                              100
                              ::pending))
          (should= true (deref entered? 100 false))
          (should (single-turn/async-compaction-in-flight? key-str))
          (deliver release! true)
          (single-turn/await-async-compaction! key-str)
          (should= true @completed?)))

    (it "skips starting a second async compaction while one is in flight"
      (let [key-str    "agent:main:cli:direct:asyncskip"
            _          (session-helper/create-session! test-dir key-str)
            _          (session-helper/update-session! test-dir key-str {:compaction {:strategy :slinky :threshold 80 :head 40 :async? true}})
            attempts   (atom 0)
            entered?   (promise)
            release!   (promise)]
        (with-redefs [compaction/should-compact? (constantly true)
                      compaction/compact!        (fn [& _]
                                            (swap! attempts inc)
                                            (deliver entered? true)
                                            @release!
                                            {:type "compaction"})]
          (single-turn/check-compaction! key-str
                                         {:model "m" :soul "s" :context-window 100
                                          :provider (llm-provider/make-provider "grover" {})})
          (should= true (deref entered? 100 false))
          (single-turn/check-compaction! key-str
                                         {:model "m" :soul "s" :context-window 100
                                          :provider (llm-provider/make-provider "grover" {})})
          (should= 1 @attempts)
          (deliver release! true)
          (single-turn/await-async-compaction! key-str))))

    (it "stops repeated compaction when token usage does not decrease"
      (let [key-str  "agent:main:cli:direct:noprogress"
            _        (session-helper/create-session! test-dir key-str)
            _        (session-helper/update-session! test-dir key-str {:total-tokens 62})
            attempts (atom 0)]
        (with-redefs [compaction/compact! (fn [compact-key _]
                                     (swap! attempts inc)
                                     (session-helper/update-session! test-dir compact-key {:total-tokens 62})
                                     {:type "compaction"})]
          (with-out-str
            (single-turn/check-compaction! key-str
                                   {:model "qwen3-coder:30b" :soul "You are Isaac." :context-window 32
                                    :provider (llm-provider/make-provider "grover" {})})))
        (should= 1 @attempts))))

  ) ; end describe check-compaction!

  (describe "stream-response!"

    (around [it] (system/with-nested-system {:fs (fs/mem-fs)} (it)))

    (it "accumulates streamed content and returns result"
      (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ on-chunk]
                                               (on-chunk {:message {:content "Hello"}})
                                               (on-chunk {:message {:content " world"} :done true})
                                               {:message {:role "assistant" :content "Hello world"}})]
        (let [result (single-turn/stream-response! (llm-provider/make-provider "ollama" {}) {} (fn [_]))]
          (should= "Hello world" (:content result)))))

    (it "returns error map on stream failure"
      (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ _] {:error :connection-refused :message "fail"})]
        (let [result (single-turn/stream-response! (llm-provider/make-provider "ollama" {}) {} (fn [_]))]
          (should= :connection-refused (:error result)))))

    (it "extracts content from anthropic-style delta chunks"
      (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ on-chunk]
                                               (on-chunk {:delta {:text "Hi"}})
                                               (on-chunk {:delta {:text "!"} :done true})
                                               {:message {:role "assistant" :content "Hi!"}})]
        (should= "Hi!" (:content (single-turn/stream-response! (llm-provider/make-provider "anthropic" {}) {} (fn [_]))))))

    (it "extracts content from openai-style delta chunks"
      (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ on-chunk]
                                                (on-chunk {:choices [{:delta {:content "Hey"}}]})
                                                {:message {:role "assistant" :content "Hey"}})]
        (should= "Hey" (:content (single-turn/stream-response! (llm-provider/make-provider "openai" {:api "chat-completions"}) {} (fn [_]))))))

    (it "prefers openai streamed delta content over fallback result content"
      (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ on-chunk]
                                               (on-chunk {:choices [{:delta {:content "streamed"}}]})
                                               {:message {:role "assistant" :content "fallback"}})]
        (should= "streamed" (:content (single-turn/stream-response! (llm-provider/make-provider "openai" {:api "chat-completions"}) {} (fn [_]))))))

    (it "uses result message content when no streaming content"
      (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ _]
                                               {:message {:role "assistant" :content "fallback"}})]
        (should= "fallback" (:content (single-turn/stream-response! (llm-provider/make-provider "ollama" {}) {} (fn [_]))))))

    (it "keeps the done chunk as the final response"
      (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ on-chunk]
                                               (on-chunk {:message {:content "Hello"}})
                                               (on-chunk {:done true :message {:content " world"} :status :finished})
                                               {:message {:role "assistant" :content "ignored"}})]
        (let [result (single-turn/stream-response! (llm-provider/make-provider "ollama" {}) {} (fn [_]))]
          (should= :finished (get-in result [:response :status])))))

    (it "does not duplicate a final done chunk that repeats the full content"
      (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ on-chunk]
                                               (on-chunk {:message {:content "README "}})
                                               (on-chunk {:done true :message {:content "README summary"}})
                                               {:message {:role "assistant" :content "README summary"}})]
        (should= "README summary" (:content (single-turn/stream-response! (llm-provider/make-provider "grover" {}) {} (fn [_])))))))

  (describe "active-tools (via run-turn!)"

    (around [it] (session-helper/with-memory-store (system/with-nested-system {:fs (fs/mem-fs)} (it))))

    (it "streams even when tools are registered"
      (let [key-str       "agent:main:cli:direct:grover-tools"
             _             (session-helper/create-session! test-dir key-str)
             _             (tool-registry/register! {:name "echo" :description "Echo" :handler (fn [args] (:msg args))})
             tools-called  (atom false)
             stream-called (atom false)]
        (with-redefs [single-turn/check-compaction!        (fn [& _] nil)
                      config/snapshot                      (fn [& _] {:crew {"main" {:tools {:allow [:echo]}}}})
                      tool-loop/run                        (fn [chat-fn _ request _ & _]
                                                              (chat-fn request))
                      dispatch/dispatch-chat-stream         (fn [_ _ on-chunk]
                                                              (reset! stream-called true)
                                                              (on-chunk {:message {:content "done"} :done true})
                                                              {:message {:role "assistant" :content "done"}})]
          (with-out-str
            (dispatch-turn! key-str "hi"
                            {:config {:crew {"main" {:tools {:allow [:echo]}}}}
                             :model "test-model"
                             :soul "You are helpful."
                             :provider (llm-provider/make-provider "grover" {})
                             :context-window 32768})))
        (should= false @tools-called)
        (should= true @stream-called)))

    (it "filters prompt tools to the crew member allow list"
      (let [key-str          "agent:main:cli:direct:allow-tools"
            _                (session-helper/create-session! test-dir key-str)
            captured-request (atom nil)]
        (with-redefs [single-turn/check-compaction!         (fn [& _] nil)
                      config/snapshot                      (fn [& _] {:crew {"main" {:tools {:allow [:read :write]}}}})
                      tool-loop/run                        (fn [chat-fn _ request _ & _]
                                                              (chat-fn request))
                      tool-registry/tool-definitions        (fn
                                                               ([] [{:name "read" :description "Read" :parameters {}}
                                                                    {:name "write" :description "Write" :parameters {}}
                                                                    {:name "exec" :description "Exec" :parameters {}}])
                                                               ([allowed & _]
                                                                (->> [{:name "read" :description "Read" :parameters {}}
                                                                      {:name "write" :description "Write" :parameters {}}
                                                                      {:name "exec" :description "Exec" :parameters {}}]
                                                                     (filter #(contains? allowed (:name %)))
                                                                     vec)))
                      tool-registry/tool-fn                 (fn
                                                               ([] (fn [_ _] nil))
                                                               ([_] (fn [_ _] nil))
                                                               ([_ _ _ & _] (fn [_ _] nil)))
                      dispatch/dispatch-chat-stream         (fn [_ request _]
                                                              (reset! captured-request request)
                                                              {:message {:role "assistant" :content "summary"}})]
          (with-out-str
            (dispatch-turn! key-str "summarize the readme"
                            {:config {:crew {"main" {:tools {:allow [:read :write]}}}}
                             :model "qwen"
                             :soul "You are helpful."
                             :provider (llm-provider/make-provider "ollama" {})
                             :context-window 32768})))
        (should= ["read" "write"] (mapv #(or (:name %) (get-in % [:function :name])) (:tools @captured-request)))))

    (it "omits tools when the crew member has an empty tools allow list"
      (let [key-str       "agent:main:cli:direct:no-tools"
            _             (session-helper/create-session! test-dir key-str)
            captured-request (atom nil)]
        (with-redefs [single-turn/check-compaction!         (fn [& _] nil)
                      config/snapshot                      (fn [& _] {:crew {"main" {:tools {:allow []}}}})
                      tool-loop/run                        (fn [chat-fn _ request _ & _]
                                                              (chat-fn request))
                      dispatch/dispatch-chat                 (fn [_ request]
                                                               (reset! captured-request request)
                                                               {:message {:role "assistant" :content "done"}})]
          (with-out-str
            (dispatch-turn! key-str "hi"
                            {:config {:crew {"main" {:tools {:allow []}}}}
                             :model "test-model"
                             :soul "You are helpful."
                             :provider (llm-provider/make-provider "grover" {})
                             :context-window 32768})))
        (should-be-nil (:tools @captured-request)))))

  (describe "dispatch-chat-with-tools"

    (it "drives the tool loop using the provider's chat and followup-messages"
      (let [provider (reify api/Api
                       (chat [_ _] {:message {:role "assistant" :content "done"} :model "echo"})
                       (chat-stream [_ _ _] (throw (ex-info "unused" {})))
                       (followup-messages [_ _ _ _ _] [])
                       (config [_] {})
                       (display-name [_] "ollama")
                       (build-prompt [_ _] (throw (ex-info "unused" {})))
                       (format-tools [_ _] nil))
            tool-fn  (fn [_ _] "tool result")
            result   (dispatch/dispatch-chat-with-tools provider {:model "echo" :messages []} tool-fn)]
        (should-not (:error result))
        (should= [] (:tool-calls result)))))

  (describe "run-turn!"

    (around [it] (session-helper/with-memory-store (system/with-nested-system {:fs (fs/mem-fs)} (it))))
    (it "sends a cancelled tool update when a tool call is interrupted"
      (let [real-dir  (str (System/getProperty "user.dir") "/target/test-chat-cancel")
            key-str   "agent:main:cli:direct:cancel-tool"
            _         (session-helper/create-session! real-dir key-str)
            fs*       (system/get :fs)
            store*    (store/registered-store)
            started*  (promise)
            release*  (promise)
            events    (atom [])
             ch        (reify comm/Comm
                         (on-turn-start [_ _ _] nil)
                         (on-text-chunk [_ _ _] nil)
                         (on-tool-call [_ _ tool-call]
                           (swap! events conj [:tool-call (:id tool-call)]))
                         (on-tool-cancel [_ _ tool-call]
                           (swap! events conj [:tool-cancel (:id tool-call)]))
                         (on-tool-result [_ _ tool-call _]
                           (swap! events conj [:tool-result (:id tool-call)]))
                          (on-compaction-start [_ _ _] nil)
                          (on-compaction-success [_ _ _] nil)
                          (on-compaction-failure [_ _ _] nil)
                          (on-compaction-disabled [_ _ _] nil)
                          (on-turn-end [_ _ _] nil))]
        (with-redefs [config/snapshot (fn [& _] {:crew {"main" {:tools {:allow [:sleepy]}}}})
                      tool-loop/run   (fn [_chat-fn _followup-fn _request tool-fn & _]
                                        (tool-fn "sleepy" {:command "sleep 30"}))]
          (let [turn (future
                       (system/with-system {:state-dir real-dir
                                            :fs        fs*
                                            :sessions  {:store store*}}
                         (tool-registry/register! {:name        "sleepy"
                                                   :description "waits until cancelled"
                                                   :parameters  {}
                                                   :handler     (fn [_]
                                                                  (deliver started* :started)
                                                                  @release*
                                                                  {:error :cancelled})})
                         (dispatch-turn! key-str "run it"
                                         {:comm            ch
                                          :model           "echo"
                                          :soul            "You are helpful."
                                          :provider        (llm-provider/make-provider "grover" {})
                                          :context-window  32768})))]
            @started*
            (isaac.bridge.cancellation/cancel! key-str)
            (deliver release* :released)
            (let [result @turn]
              (should= "cancelled" (:stopReason result))
              (should= 2 (count @events))
              (should= :tool-call (ffirst @events))
              (should= :tool-cancel (ffirst (rest @events)))
              (should= (second (first @events)) (second (second @events))))))))

  (describe "run-tool-calls!"

    (around [it] (session-helper/with-memory-store (system/with-nested-system {:fs (fs/mem-fs)} (it))))

    (it "stores tool calls and results in the transcript"
      (let [key-str "agent:main:cli:direct:tooltest"
            _       (session-helper/create-session! test-dir key-str)
            tool-results [[{:id "tc-1" :name "echo" :type "toolCall" :arguments {:msg "hi"}}
                           "echo result"]]]
        (single-turn/run-tool-calls! key-str tool-results)
        (let [transcript (session-helper/get-transcript test-dir key-str)
              messages   (filter #(= "message" (:type %)) transcript)]
          (should= 2 (count messages))
          (should= "assistant"  (get-in (first messages) [:message :role]))
          (should= "toolResult" (get-in (second messages) [:message :role])))))

    (it "marks tool results as errors when tool-fn returns an error string"
      (let [key-str "agent:main:cli:direct:toolerr"
            _       (session-helper/create-session! test-dir key-str)
            tool-results [[{:id "tc-1" :name "boom" :type "toolCall" :arguments {}}
                           "Error: something went wrong"]]]
        (single-turn/run-tool-calls! key-str tool-results)
        (let [transcript (session-helper/get-transcript test-dir key-str)
              tool-result (second (filter #(= "message" (:type %)) transcript))]
          (should= true (get-in tool-result [:message :isError])))))

    (it "does not log tool pair persistence diagnostics"
      (let [key-str "agent:main:cli:direct:toollogs"
            _       (session-helper/create-session! test-dir key-str)
            tool-results [[{:id "tc-1" :name "echo" :type "toolCall" :arguments {:msg "hi"}}
                           "echo result"]]]
        (log/capture-logs
          (single-turn/run-tool-calls! key-str tool-results)
          (let [events (map :event @log/captured-logs)]
            (should-not-contain :turn/persisting-tool-pairs events)
            (should-not-contain :turn/tool-pair-persisted events)))))

  ) ; end describe run-tool-calls!

  (describe "run-turn!"

    (around [it] (session-helper/with-memory-store (system/with-nested-system {:fs (fs/mem-fs)} (it))))
    (it "includes tools in the streaming request when tools are available"
      (let [key-str          "agent:main:cli:direct:tool-user"
             _                (session-helper/create-session! test-dir key-str)
             captured-request (atom nil)]
        (with-redefs [compaction/should-compact?           (constantly false)
                      tool-registry/tool-definitions (fn
                                                        ([] [{:name "read" :description "Read a file" :parameters {}}])
                                                        ([_] [{:name "read" :description "Read a file" :parameters {}}]))
                      tool-registry/tool-fn          (fn
                                                        ([] (fn [_ _] "README"))
                                                        ([_] (fn [_ _] "README"))
                                                        ([_ _ _] (fn [_ _] "README")))
                      dispatch/dispatch-chat-stream  (fn [_ request _]
                                                       (reset! captured-request request)
                                                       {:message {:role "assistant" :content "summary"}})]
          (with-out-str
            (dispatch-turn! key-str "summarize the readme"
                            {:model "qwen"
                             :soul "You are helpful."
                             :provider (llm-provider/make-provider "ollama" {})
                             :context-window 32768})))
        (should= 1 (count (:tools @captured-request)))))

    (it "preserves the triggering user message after compaction and completes chat"
      (let [key-str "agent:main:cli:direct:compact-user"
            _       (session-helper/create-session! test-dir key-str)
            _       (session-helper/append-message! test-dir key-str {:role "user" :content "Please summarize our work"})]
        (with-redefs [compaction/should-compact?        (constantly true)
                      compaction/compact!               (fn [compact-key _]
                                                   (session-helper/append-compaction! test-dir compact-key
                                                                             {:summary "Summary of prior chat"
                                                                              :firstKeptEntryId "kept-id"
                                                                              :tokensBefore 95}))
                      tool-registry/tool-definitions (constantly nil)
                      dispatch/dispatch-chat         (fn [_ request]
                                                       {:message {:role "assistant" :content "README summary"}
                                                        :usage   {:input-tokens 10 :output-tokens 5}
                                                        :model   (:model request)})]
          (with-out-str
            (dispatch-turn! key-str "Can you summarize README.md?"
                            {:model "test-model"
                             :soul "You are Isaac."
                             :provider (llm-provider/make-provider "grover" {})
                             :context-window 100})))
        (let [transcript (session-helper/get-transcript test-dir key-str)]
          (should= "compaction" (:type (nth transcript 2)))
          (should= "user" (get-in (nth transcript 3) [:message :role]))
          (should= [{:type "text" :text "Can you summarize README.md?"}]
                   (get-in (nth transcript 3) [:message :content]))
          (should= "assistant" (get-in (nth transcript 4) [:message :role]))
          (should= "README summary" (get-in (nth transcript 4) [:message :content])))))

    (it "persists responses-api reasoning summary on the stored assistant message"
      (let [key-str      "agent:main:cli:direct:reasoning-summary"
            _            (session-helper/create-session! test-dir key-str)
            provider-cfg (providers/lookup {:providers {:chatgpt {}}}
                                           nil
                                           "chatgpt")]
        (with-redefs [compaction/should-compact?              (constantly false)
                      tool-registry/tool-definitions   (constantly nil)
                      dispatch/dispatch-chat           (fn [_ request]
                                                         {:message  {:role "assistant" :content "Scram!"}
                                                          :model    (:model request)
                                                          :response {:message   {:role "assistant" :content "Scram!"}
                                                                     :model     (:model request)
                                                                     :reasoning {:effort "high"
                                                                                 :summary "Considered the simplest reply."}
                                                                     :usage     {:input_tokens 100
                                                                                 :output_tokens 50
                                                                                 :output_tokens_details {:reasoning_tokens 32}
                                                                                 :input_tokens_details  {:cached_tokens 7}}}
                                                          :usage    {:input-tokens 100 :output-tokens 50}})]
          (with-out-str
            (dispatch-turn! key-str "knock knock"
                            {:model "gpt-5.4"
                             :soul "Lives in a trash can."
                             :provider (llm-provider/make-provider "chatgpt" provider-cfg)
                             :context-window 128000})))
        (let [transcript (session-helper/get-transcript test-dir key-str)
              assistant  (last (filter #(= "assistant" (get-in % [:message :role])) transcript))]
          (should= "Scram!" (get-in assistant [:message :content]))
          (should= "high" (get-in assistant [:message :reasoning :effort]))
          (should= "Considered the simplest reply." (get-in assistant [:message :reasoning :summary]))
          (should= 100 (get-in assistant [:message :usage :input-tokens]))
          (should= 50 (get-in assistant [:message :usage :output-tokens]))
          (should= 150 (get-in assistant [:message :usage :total-tokens]))
          (should= 32 (get-in assistant [:message :usage :reasoning-tokens]))
          (should= 7 (get-in assistant [:message :usage :cache-read]))
          (should= 0 (get-in assistant [:message :usage :cache-write])))))

    (it "returns error result when LLM call fails"
      (let [key-str "agent:main:cli:direct:err-return"
            _       (session-helper/create-session! test-dir key-str)
            result  (atom nil)]
        (with-redefs [compaction/should-compact?          (constantly false)
                      tool-registry/tool-definitions (constantly nil)
                      dispatch/dispatch-chat         (fn [& _] {:error :connection-refused :message "refused"})]
          (with-out-str
            (reset! result (dispatch-turn! key-str "hello"
                                           {:model "test" :soul "." :provider (llm-provider/make-provider "ollama" {}) :context-window 32768}))))
        (should= :connection-refused (:error @result))))

    (it "passes the session root through provider config"
      (let [key-str              "agent:main:cli:direct:state-dir-provider"
            _                    (session-helper/create-session! test-dir key-str)
            captured-provider-cfg (atom nil)
            provider-cfg          (providers/lookup {:providers {:chatgpt {}}}
                                                    nil
                                                    "chatgpt")]
        (with-redefs [compaction/should-compact?              (constantly false)
                      tool-registry/tool-definitions   (constantly nil)
                      dispatch/dispatch-chat           (fn [p _]
                                                        (reset! captured-provider-cfg (api/config p))
                                                        {:message {:role "assistant" :content "Hello"}
                                                         :usage   {:input-tokens 2 :output-tokens 1}
                                                         :model   "echo"})
                      dispatch/dispatch-chat-stream    (fn [p _ _]
                                                        (reset! captured-provider-cfg (api/config p))
                                                        {:message {:role "assistant" :content "Hello"}
                                                         :usage   {:input-tokens 2 :output-tokens 1}
                                                         :model   "echo"})]
          (with-out-str
            (dispatch-turn! key-str "hello"
                            {:config {:root test-dir}
                             :model "echo"
                             :soul "You are Isaac."
                             :provider (llm-provider/make-provider "chatgpt" provider-cfg)
                             :context-window 32768})))
        (should= test-dir (:root @captured-provider-cfg))))

    (it "rejects a turn when the session crew is unknown"
      (let [key-str       "agent:main:cli:direct:unknown-crew"
            _             (session-helper/create-session! test-dir key-str {:crew "marvin"})
            result        (atom nil)
            output        (atom nil)
            stream-called (atom false)]
        (log/capture-logs
          (with-redefs [compaction/should-compact? (constantly false)
                        config/snapshot     (fn [& _] {:crew {"main" {:model "grover" :soul "You are Isaac."}}
                                                    :models {"grover" {:model "echo" :provider "grover" :context-window 32768}}})
                        tool-loop/run       (fn [& _]
                                              (reset! stream-called true)
                                              {:response {:message {:content "should not happen"}}})]
            (reset! output (with-out-str
                             (reset! result (dispatch-turn! key-str "hello"
                                                            {:model "echo"
                                                             :soul "You are Isaac."
                                                             :provider (llm-provider/make-provider "grover" {})
                                                             :context-window 32768}))))))
        (should= :unknown-crew (:error @result))
        (should-contain "unknown crew on session" (:message @result))
        (should-contain "marvin" (:message @result))
        (should-contain "pass --crew to override" (:message @result))
        (should-not @stream-called)
        (should= [] (filter #(= "message" (:type %)) (session-helper/get-transcript test-dir key-str)))
        (let [entry (last @log/captured-logs)]
          (should= :drive/turn-rejected (:event entry))
          (should= key-str (:session entry))
          (should= "marvin" (:crew entry))
          (should= :unknown-crew (:reason entry)))))

    (it "logs accepted turns with the session crew"
      (let [key-str "agent:main:cli:direct:accepted-turn"
            _       (session-helper/create-session! test-dir key-str {:crew "main"})]
        (log/capture-logs
          (with-redefs [compaction/should-compact?            (constantly false)
                        tool-registry/tool-definitions (constantly nil)
                        tool-loop/run                  (fn [& _]
                                                         {:response {:message {:role "assistant" :content "Hello"}
                                                                     :usage   {:input-tokens 2 :output-tokens 1}
                                                                     :model   "echo"}})]
            (with-out-str
              (dispatch-turn! key-str "hello"
                              {:model "echo"
                               :soul "You are Isaac."
                               :provider (llm-provider/make-provider "grover" {})
                               :context-window 32768})))
          (should (some #(and (= :drive/turn-accepted (:event %))
                              (= key-str (:session %))
                              (= "main" (:crew %)))
                        @log/captured-logs)))))

    (it "prints [tool call: name] to stdout when a tool is called"
      (let [key-str    "agent:main:cli:direct:tool-status-print"
             _          (session-helper/create-session! test-dir key-str)
             call-count (atom 0)
             output     (atom nil)]
        (with-redefs [compaction/should-compact?           (constantly false)
                      tool-registry/tool-definitions (fn
                                                       ([] [{:name "read_file" :description "Read" :parameters {}}])
                                                       ([_] [{:name "read_file" :description "Read" :parameters {}}]))
                      tool-registry/tool-fn          (fn
                                                        ([] (fn [_ _] "contents"))
                                                        ([_] (fn [_ _] "contents"))
                                                        ([_ _ _] (fn [_ _] "contents")))
                      dispatch/dispatch-chat-stream  (fn [_ _ on-chunk]
                                                       (swap! call-count inc)
                                                       (let [chunk (if (= 1 @call-count)
                                                                     {:message {:role "assistant" :content ""
                                                                                :tool_calls [{:function {:name "read_file"
                                                                                                          :arguments {:path "README.md"}}}]}
                                                                      :done true}
                                                                     {:message {:role "assistant" :content "done"}
                                                                      :done true})]
                                                         (on-chunk chunk)
                                                         chunk))]
          (reset! output (with-out-str
                           (dispatch-turn! key-str "read it"
                                           {:model "llama3" :soul "." :provider (llm-provider/make-provider "ollama" {}) :context-window 32768}))))
        (should-contain "[tool call: read_file]" @output)))

    (it "prints response content to stdout after tool calls complete"
      (let [key-str    "agent:main:cli:direct:tool-content-print"
             _          (session-helper/create-session! test-dir key-str)
             call-count (atom 0)
             output     (atom nil)]
        (with-redefs [compaction/should-compact?           (constantly false)
                      tool-registry/tool-definitions (fn
                                                       ([] [{:name "read_file" :description "Read" :parameters {}}])
                                                       ([_] [{:name "read_file" :description "Read" :parameters {}}]))
                      tool-registry/tool-fn          (fn
                                                        ([] (fn [_ _] "contents"))
                                                        ([_] (fn [_ _] "contents"))
                                                        ([_ _ _] (fn [_ _] "contents")))
                      dispatch/dispatch-chat-stream  (fn [_ _ on-chunk]
                                                       (swap! call-count inc)
                                                       (let [chunk (if (= 1 @call-count)
                                                                     {:message {:role "assistant" :content ""
                                                                                :tool_calls [{:function {:name "read_file"
                                                                                                          :arguments {}}}]}
                                                                      :done true}
                                                                     {:message {:role "assistant" :content "The file says hello"}
                                                                      :done true})]
                                                         (on-chunk chunk)
                                                         chunk))]
          (reset! output (with-out-str
                            (dispatch-turn! key-str "read it"
                                            {:model "llama3" :soul "." :provider (llm-provider/make-provider "ollama" {}) :context-window 32768}))))
        (should-contain "The file says hello" @output)))

    (it "asks the LLM for a final no-tools summary when the tool loop hits max iterations"
      (let [key-str      "agent:main:cli:direct:tool-loop-cap"
            _            (session-helper/create-session! test-dir key-str)
            call-count   (atom 0)
            requests     (atom [])
            provider-cfg (providers/lookup {:providers {:chatgpt {}}}
                                           nil
                                           "chatgpt")]
        (with-redefs [compaction/should-compact?           (constantly false)
                      tool-registry/tool-definitions (fn
                                                       ([] [{:name "grep" :description "Search" :parameters {}}])
                                                       ([_] [{:name "grep" :description "Search" :parameters {}}]))
                      tool-registry/tool-fn          (fn
                                                       ([] (fn [_ _] "3 matches"))
                                                       ([_] (fn [_ _] "3 matches"))
                                                       ([_ _ _] (fn [_ _] "3 matches")))
                      dispatch/dispatch-chat-stream  (fn [_ request on-chunk]
                                                        (swap! requests conj request)
                                                         (swap! call-count inc)
                                                         (let [chunk (case @call-count
                                                                       1 {:message {:role "assistant"
                                                                                    :content ""
                                                                                    :tool_calls [{:id       "tc-1"
                                                                                                  :function {:name "grep"
                                                                                                             :arguments {}}}]}
                                                                          :done true}
                                                                       2 {:message {:role "assistant"
                                                                                    :content ""
                                                                                    :tool_calls [{:id       "tc-2"
                                                                                                  :function {:name "grep"
                                                                                                             :arguments {}}}]}
                                                                          :done true}
                                                                       {:message {:role "assistant"
                                                                                  :content "I checked grep once, hit the loop limit, and need to continue manually."}
                                                                        :done true})]
                                                           (on-chunk chunk)
                                                           chunk))
                      config/snapshot            (fn [& _] {:crew {"main" {:tools {:allow ["grep"]}}}})
                      tool-loop/default-max-loops 1]
          (with-out-str
            (dispatch-turn! key-str "poke around"
                            {:model "gpt-5.4"
                             :soul "You are helpful."
                             :provider (llm-provider/make-provider "chatgpt" provider-cfg)
                             :context-window 32768})))
        (let [messages            (filter #(= "message" (:type %)) (session-helper/get-transcript test-dir key-str))
              last-assistant-msg  (last (filter #(= "assistant" (get-in % [:message :role])) messages))
              summary-request     (nth @requests 2)
              summary-instruction (-> summary-request :messages last :content)
              tool-call-ids       (->> messages
                                        (mapcat (fn [entry]
                                                  (->> (get-in entry [:message :content])
                                                      (filter #(= "toolCall" (:type %)))
                                                      (map :id))))
                                       set)
              tool-result-ids     (->> messages
                                        (filter #(= "toolResult" (get-in % [:message :role])))
                                        (map #(or (get-in % [:message :toolCallId])
                                                  (get-in % [:message :id])))
                                         set)]
          (should= 3 @call-count)
          (should= tool-call-ids tool-result-ids)
          (should= [] (:tools summary-request))
          (should-contain "Do not call any more tools" summary-instruction)
          (should= "I checked grep once, hit the loop limit, and need to continue manually."
                   (get-in last-assistant-msg [:message :content])))))

    (it "falls back to the canned message when the forced summary is still empty"
      (let [key-str      "agent:main:cli:direct:tool-loop-fallback"
            _            (session-helper/create-session! test-dir key-str)
            call-count   (atom 0)
            requests     (atom [])
            provider-cfg (providers/lookup {:providers {:chatgpt {}}}
                                           nil
                                           "chatgpt")]
        (with-redefs [compaction/should-compact?           (constantly false)
                      tool-registry/tool-definitions (fn
                                                       ([] [{:name "grep" :description "Search" :parameters {}}])
                                                       ([_] [{:name "grep" :description "Search" :parameters {}}]))
                      tool-registry/tool-fn          (fn
                                                       ([] (fn [_ _] "3 matches"))
                                                       ([_] (fn [_ _] "3 matches"))
                                                       ([_ _ _] (fn [_ _] "3 matches")))
                      dispatch/dispatch-chat-stream  (fn [_ request on-chunk]
                                                        (swap! requests conj request)
                                                        (swap! call-count inc)
                                                        (let [chunk (case @call-count
                                                                      1 {:message {:role "assistant"
                                                                                   :content ""
                                                                                   :tool_calls [{:id       "tc-1"
                                                                                                 :function {:name "grep"
                                                                                                            :arguments {}}}]}
                                                                         :done true}
                                                                      2 {:message {:role "assistant"
                                                                                   :content ""
                                                                                   :tool_calls [{:id       "tc-2"
                                                                                                 :function {:name "grep"
                                                                                                            :arguments {}}}]}
                                                                         :done true}
                                                                      {:message {:role "assistant"
                                                                                 :content ""}
                                                                       :done true})]
                                                          (on-chunk chunk)
                                                          chunk))
                      config/snapshot            (fn [& _] {:crew {"main" {:tools {:allow ["grep"]}}}})
                      tool-loop/default-max-loops 1]
          (with-out-str
            (dispatch-turn! key-str "poke around"
                            {:model "gpt-5.4"
                             :soul "You are helpful."
                             :provider (llm-provider/make-provider "chatgpt" provider-cfg)
                             :context-window 32768})))
        (let [messages           (filter #(= "message" (:type %)) (session-helper/get-transcript test-dir key-str))
              last-assistant-msg (last (filter #(= "assistant" (get-in % [:message :role])) messages))
              summary-request    (nth @requests 2)]
          (should= 3 @call-count)
          (should= [] (:tools summary-request))
          (should= "I ran several tools but did not reach a conclusion before hitting the tool loop limit. Ask me to continue if you want me to keep digging."
                   (get-in last-assistant-msg [:message :content])))))

  (describe "uncaught exception handling"

    (around [it] (session-helper/with-memory-store (system/with-nested-system {:fs (fs/mem-fs)} (it))))

    (it "appends an error entry to the transcript when an exception is thrown"
      (let [key-str "agent:main:cli:direct:crash-test"
            _       (session-helper/create-session! test-dir key-str {:crew "main" :cwd test-dir})]
        (try
          (with-redefs [single-turn/check-compaction!
                        (fn [& _] (throw (ex-info "simulated crash" {:boom true})))]
            (with-out-str
              (dispatch-turn! key-str "trigger crash"
                              {:model "test" :soul "." :provider (llm-provider/make-provider "grover" {}) :context-window 4096})))
          (catch Exception _))
        (let [transcript (session-helper/get-transcript test-dir key-str)
              error-entry (last (filter #(= "error" (:type %)) transcript))]
          (should-not-be-nil error-entry)
          (should= "simulated crash" (:content error-entry))
          (should= "exception" (:error error-entry))
          (should-not-be-nil (:ex-class error-entry)))))

    (it "still propagates the exception after appending the error"
      (let [key-str "agent:main:cli:direct:rethrow-test"
            _       (session-helper/create-session! test-dir key-str {:crew "main" :cwd test-dir})]
        (should-throw
          (with-redefs [single-turn/check-compaction!
                        (fn [& _] (throw (RuntimeException. "boom")))]
            (with-out-str
              (dispatch-turn! key-str "hi"
                              {:model "test" :soul "." :provider (llm-provider/make-provider "grover" {}) :context-window 4096}))))))

    (it "transcript ends with error entry so next turn sees balanced user/assistant trail"
      (let [key-str "agent:main:cli:direct:balance-test"
            _       (session-helper/create-session! test-dir key-str {:crew "main" :cwd test-dir})]
        (try
          (with-redefs [single-turn/check-compaction!
                        (fn [& _] (throw (ex-info "oops" {})))]
            (with-out-str
              (dispatch-turn! key-str "user input"
                              {:model "test" :soul "." :provider (llm-provider/make-provider "grover" {}) :context-window 4096})))
          (catch Exception _))
        (let [transcript (session-helper/get-transcript test-dir key-str)
              last-entry  (last transcript)]
          (should= "error" (:type last-entry)))))

    ))

;; region ----- Toad -----

(describe "build-toad-command"

  (it "uses toad as the command"
    (should= "toad" (:command (sut/build-toad-command))))

  (it "passes isaac acp as the agent command"
    (let [args (:args (sut/build-toad-command))]
      (should (some #(= "isaac acp" %) args))))

  (it "includes --model in the acp subprocess command"
    (let [args (:args (sut/build-toad-command {:model "bosun"}))]
      (should (some #(= "isaac acp --model bosun" %) args))))

  (it "includes --crew in the acp subprocess command"
    (let [args (:args (sut/build-toad-command {:crew "bosun"}))]
      (should (some #(= "isaac acp --crew bosun" %) args))))

  (it "includes --remote in the acp subprocess command"
    (let [args (:args (sut/build-toad-command {:remote "ws://host:6674/acp"}))]
      (should (some #(= "isaac acp --remote ws://host:6674/acp" %) args))))

  (it "includes --token in the acp subprocess command"
    (let [args (:args (sut/build-toad-command {:token "secret123"}))]
      (should (some #(= "isaac acp --token secret123" %) args))))

  (it "includes --resume in the acp subprocess command"
    (let [args (:args (sut/build-toad-command {:resume true}))]
      (should (some #(= "isaac acp --resume" %) args))))

  (it "includes --session in the acp subprocess command"
    (let [args (:args (sut/build-toad-command {:session "agent:main:acp:direct:abc"}))]
      (should (some #(= "isaac acp --session agent:main:acp:direct:abc" %) args)))))

(describe "format-toad-command"

  (it "returns a string containing toad and isaac acp"
    (let [s (sut/format-toad-command)]
      (should (clojure.string/includes? s "toad"))
      (should (clojure.string/includes? s "isaac acp"))))

  (it "returns a string containing model and crew flags"
    (let [s (sut/format-toad-command {:model "bosun" :crew "grok"})]
      (should (clojure.string/includes? s "--model bosun"))
      (should (clojure.string/includes? s "--crew grok"))))

  (it "returns a string containing remote and token flags"
    (let [s (sut/format-toad-command {:remote "ws://host:6674/acp" :token "secret123"})]
      (should (clojure.string/includes? s "--remote ws://host:6674/acp"))
      (should (clojure.string/includes? s "--token secret123"))))

  (it "returns a string containing resume and session flags"
    (let [s (sut/format-toad-command {:resume true :session "agent:main:acp:direct:abc"})]
      (should (clojure.string/includes? s "--resume"))
      (should (clojure.string/includes? s "--session agent:main:acp:direct:abc")))))

;; endregion ^^^^^ Toad ^^^^^
) ; end describe CLI Chat
