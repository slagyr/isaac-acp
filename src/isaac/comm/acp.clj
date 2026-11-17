(ns isaac.comm.acp
  (:require
    [clojure.string :as str]
    [isaac.comm.acp.jsonrpc :as jsonrpc]
    [isaac.agent.comm.protocol :as comm]
    [isaac.agent.comm.render :as render]
    [isaac.foundation.logger :as log]
    [isaac.agent.util.jsonrpc :as jrpc]))

(defn- write! [output-writer message]
  (jrpc/write-message! output-writer message))

(defn- normalize-text-chunk [text]
  (some-> text render/present-for-markdown))

(defn- text-notification [session-id text]
  (jsonrpc/session-update session-id {:sessionUpdate "agent_message_chunk"
                                      :content       {:type "text"
                                                      :text text}}))

(defn- user-text-notification [session-id text]
  (jsonrpc/session-update session-id {:sessionUpdate "user_message_chunk"
                                      :content       {:type "text"
                                                      :text text}}))

(defn- thought-notification [session-id text]
  (jsonrpc/session-update session-id {:sessionUpdate "agent_thought_chunk"
                                      :content       {:type "text"
                                                      :text text}}))

(defn- command-input [command]
  (when-let [hint (some-> (:params command) first str not-empty)]
    {:hint hint}))

(defn- advertised-command [command]
  (cond-> (select-keys command [:description :name])
    (command-input command) (assoc :input (command-input command))))

(defn- available-commands-notification [session-id commands]
  (let [built-in-order {"status" 0
                        "model"  1
                        "crew"   2
                        "cwd"    3
                        "effort" 4}
        rank          (fn [command]
                        (if-let [idx (get built-in-order (:name command))]
                          [0 idx (:name command)]
                          [1 0 (:name command)]))
        commands      (->> commands
                           (sort-by rank)
                           (map advertised-command))]
    (jsonrpc/session-update session-id {:sessionUpdate     "available_commands_update"
                                        :availableCommands commands})))

(def ^:private tool-kinds
  ;; ACP v1 ToolKind. Explicitly classify agent built-ins; preserve historic names for session/load.
  {"fs__read" "read" "fs__write" "edit" "fs__edit" "edit" "fs__multi_edit" "edit"
   "fs__grep" "search" "fs__glob" "search" "web__fetch" "fetch" "web__search" "search"
   "memory__write" "edit" "memory__get" "read" "memory__search" "search"
   "exec__run" "execute" "timer__set" "other" "timer__list" "read" "timer__cancel" "other"
   "subagent__spawn" "other" "subagent__list" "read" "subagent__cancel" "other"
   "turn__get" "read" "session__info" "read" "session__model" "other"
   "session__list" "read" "session__read" "read" "prompt__load" "read" "prompt__list" "read"
   "comm__send" "other" "hail__send" "other" "provider__quota" "fetch"
   "recall__search" "search" "recall__scene" "read" "dream__habit" "read"
   "read" "read" "write" "edit" "edit" "edit" "exec" "execute"
   "grep" "search" "glob" "search" "skill__load" "read" "skill__list" "read"
   "load_skill" "read" "list_skills" "read" "memory_write" "edit" "memory_get" "read"
   "memory_search" "search" "web_fetch" "fetch" "web_search" "search"
   "session_info" "read" "search_replace" "edit" "run_terminal_cmd" "execute"})

(defn- tool-kind [tool-name]
  (get tool-kinds tool-name "other"))

(defn- error-prefix? [result]
  (and (string? result) (str/starts-with? result "Error:")))

(defn- result-status [result error?]
  (if (if (some? error?) error? (error-prefix? result)) "failed" "completed"))

(defn- tool-title [tool-name arguments]
  (let [summary (or (:command arguments)
                    (:file_path arguments)
                    (first (vals arguments)))]
    (if summary
      (str tool-name ": " summary)
      tool-name)))

(defn- tool-call-fields [tool-call]
  {:title    (tool-title (:name tool-call) (:arguments tool-call))
   :kind     (tool-kind (:name tool-call))
   :rawInput (:arguments tool-call)})

(defn- tool-result-content [result]
  [{:type    "content"
    :content {:type "text"
              :text (str result)}}])

(defn- tool-call-notification [session-id tool-call]
  ;; status "pending" — Toad's tool-call widget renders pending as ⌛,
  ;; in_progress as nothing (literally a `pass`), completed as ✔. The
  ;; expected lifecycle is pending → completed via the eventual
  ;; tool_call_update; the prior heartbeat-dropping-at-60s bug (since
  ;; fixed) was what made hourglass appear stuck.
  ;; :content carries the rawInput rendered as text so Toad's
  ;; can_expand (gated on has_content) is true from the start — the
  ;; tool call is expandable to inspect arguments even mid-flight.
  (jsonrpc/session-update session-id (merge {:sessionUpdate "tool_call"
                                             :status        "pending"
                                             :toolCallId    (:id tool-call)
                                             :content       [{:type    "content"
                                                              :content {:type "text"
                                                                        :text (pr-str (:arguments tool-call))}}]}
                                            (tool-call-fields tool-call))))

(defn- replay-tool-call-notification [session-id tool-call result error?]
  {:jsonrpc "2.0"
   :method  "session/update"
   :params  {:sessionId session-id
             :update    (cond-> (merge {:sessionUpdate "tool_call"
                                        :status        (result-status result error?)
                                        :toolCallId    (:id tool-call)}
                                       (tool-call-fields tool-call))
                          (some? result)
                          (assoc :rawOutput result
                                 :content   (tool-result-content result)))}})

(defn- tool-result-notification [session-id tool-call result]
  {:jsonrpc "2.0"
   :method  "session/update"
   :params  {:sessionId session-id
             :update    (merge {:sessionUpdate "tool_call_update"
                                :toolCallId    (:id tool-call)
                                :status        (result-status result (when (contains? tool-call :error?) (:error? tool-call)))
                                :rawOutput     result
                                :content       (tool-result-content result)}
                               (tool-call-fields tool-call))}})

(defn- tool-cancel-notification [session-id tool-call]
  {:jsonrpc "2.0"
   :method  "session/update"
   :params  {:sessionId session-id
             :update {:sessionUpdate "tool_call_update"
                      :toolCallId    (:id tool-call)
                      :status        "failed"
                      :content       (tool-result-content "cancelled")}}})

(defn- bulletin->thought [bulletin]
  (case (:kind bulletin)
    :compaction/start "compacting..."
    :compaction/success "compacted."
    :compaction/failure (str "compaction failed: "
                             (or (get bulletin :message)
                                 (get bulletin :error)
                                 (get-in bulletin [:payload :message])
                                 (get-in bulletin [:payload :error])))
    :compaction/disabled (str "compaction disabled: "
                              (name (or (get bulletin :reason)
                                        (get-in bulletin [:payload :reason]))))
    nil))

(defn- on-chatter* [this session-key _cycle text]
  (let [display (normalize-text-chunk text)]
    (when (seq display)
      (write! (.-output-writer this) (text-notification session-key display)))))

(defn- on-tool-call* [this session-key tool-call]
  (log/debug :acp-comm/tool-call-emit :session session-key :toolCallId (:id tool-call) :tool (:name tool-call))
  (write! (.-output-writer this) (tool-call-notification session-key tool-call)))

(defn- on-tool-cancel* [this session-key tool-call]
  (write! (.-output-writer this) (tool-cancel-notification session-key tool-call)))

(defn- on-tool-result* [this session-key tool-call result]
  (log/debug :acp-comm/tool-result-emit :session session-key :toolCallId (:id tool-call) :tool (:name tool-call))
  (write! (.-output-writer this) (tool-result-notification session-key tool-call result)))

(defn- on-bulletin* [this session-key bulletin]
  (when-let [text (bulletin->thought bulletin)]
    (write! (.-output-writer this) (thought-notification session-key text))))

(defn- send!* [_ _]
  {:ok false :transient? false})

(deftype AcpComm [output-writer])

(extend AcpComm
  comm/Comm
  (merge comm/defaults
         {:on-turn-start  (fn [this session-key input]
                            (write! (.-output-writer this) (user-text-notification session-key input)))
          :on-chatter     on-chatter*
          :on-tool-call   on-tool-call*
          :on-tool-cancel on-tool-cancel*
          :on-tool-result on-tool-result*
          :on-bulletin    on-bulletin*
          :send!          send!*}))

(defn make [host]
  (->AcpComm (:output-writer host)))

(deftype BackgroundAcpComm [output-writer])

(extend BackgroundAcpComm
  comm/Comm
  (merge comm/defaults
         {:on-turn-start (fn [this session-key input]
                           (write! (.-output-writer this) (user-text-notification session-key input)))
          :on-chatter on-chatter*
          :on-tool-call on-tool-call*
          :on-tool-cancel on-tool-cancel*
          :on-tool-result on-tool-result*
          :on-bulletin on-bulletin*
          :send! send!*}))

(defn background-channel [output-writer]
  (->BackgroundAcpComm output-writer))

(defn writer [channel]
  (.-output-writer channel))

(defn channel [output-writer]
  (->AcpComm output-writer))

(defn text-update [session-id text]
  (text-notification session-id text))

(defn thought-update [session-id text]
  (thought-notification session-id text))

(defn user-text-update [session-id text]
  (user-text-notification session-id text))

(defn replay-tool-call-update
  ([session-id tool-call result] (replay-tool-call-update session-id tool-call result nil))
  ([session-id tool-call result error?]
   (replay-tool-call-notification session-id tool-call result error?)))

(defn available-commands-update [session-id commands]
  (available-commands-notification session-id commands))
