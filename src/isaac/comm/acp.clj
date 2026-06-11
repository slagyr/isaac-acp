(ns isaac.comm.acp
  (:require
    [isaac.comm :as comm]
    [isaac.comm.acp.jsonrpc :as jsonrpc]
    [isaac.util.jsonrpc :as jrpc]))

(defn- write! [output-writer message]
  (jrpc/write-message! output-writer message))

(defn- normalize-text-chunk [text]
  (some-> text str))

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

(defn- tool-kind [tool-name]
  (case tool-name
    "read"  "read"
    "edit"  "edit"
    "write" "edit"
    "exec"  "execute"
    "other"))

(defn- tool-title [tool-name arguments]
  (let [summary (or (:command arguments)
                    (:file_path arguments)
                    (first (vals arguments)))]
    (if summary
      (str tool-name ": " summary)
      tool-name)))

(defn- tool-call-notification [session-id tool-call]
  ;; status "pending" — Toad's tool-call widget renders pending as ⌛,
  ;; in_progress as nothing (literally a `pass`), completed as ✔. The
  ;; expected lifecycle is pending → completed via the eventual
  ;; tool_call_update; the prior heartbeat-dropping-at-60s bug (since
  ;; fixed) was what made hourglass appear stuck.
  ;; :content carries the rawInput rendered as text so Toad's
  ;; can_expand (gated on has_content) is true from the start — the
  ;; tool call is expandable to inspect arguments even mid-flight.
  (jsonrpc/session-update session-id {:sessionUpdate "tool_call"
                                      :status        "pending"
                                      :toolCallId    (:id tool-call)
                                      :title         (tool-title (:name tool-call) (:arguments tool-call))
                                      :kind          (tool-kind (:name tool-call))
                                      :rawInput      (:arguments tool-call)
                                      :content       [{:type    "content"
                                                       :content {:type "text"
                                                                 :text (pr-str (:arguments tool-call))}}]}))

(defn- replay-tool-call-notification [session-id tool-call result]
  {:jsonrpc "2.0"
   :method  "session/update"
   :params  {:sessionId session-id
             :update    (cond-> {:sessionUpdate "tool_call"
                                 :status        "completed"
                                 :toolCallId    (:id tool-call)
                                 :title         (tool-title (:name tool-call) (:arguments tool-call))
                                 :kind          (tool-kind (:name tool-call))
                                 :rawInput      (:arguments tool-call)}
                          (some? result)
                          (assoc :rawOutput result
                                 :content   [{:type    "content"
                                              :content {:type "text"
                                                        :text (str result)}}]))}})

(defn- tool-result-notification [session-id tool-call result]
  {:jsonrpc "2.0"
   :method  "session/update"
   :params  {:sessionId session-id
             :update {:sessionUpdate "tool_call_update"
                       :toolCallId    (:id tool-call)
                       :status        "completed"
                       :rawOutput     result
                       :content       [{:type    "content"
                                        :content {:type "text"
                                                  :text (str result)}}]}}})

(defn- tool-cancel-notification [session-id tool-call]
  {:jsonrpc "2.0"
   :method  "session/update"
   :params  {:sessionId session-id
             :update {:sessionUpdate "tool_call_update"
                      :toolCallId    (:id tool-call)
                      :status        "cancelled"}}})

(deftype AcpComm [output-writer]
  comm/Comm
  (on-turn-start [_ _ _] nil)
  (on-text-chunk [_ session-key text]
    (let [display (normalize-text-chunk text)]
      (when (seq display)
        (write! output-writer (text-notification session-key display)))))
  (on-tool-call [_ session-key tool-call]
    (write! output-writer (tool-call-notification session-key tool-call)))
  (on-tool-cancel [_ session-key tool-call]
    (write! output-writer (tool-cancel-notification session-key tool-call)))
  (on-tool-result [_ session-key tool-call result]
    (write! output-writer (tool-result-notification session-key tool-call result)))
  (on-compaction-start [_ session-key _payload]
    (write! output-writer (thought-notification session-key "compacting...")))
  (on-compaction-success [_ session-key _payload]
    (write! output-writer (thought-notification session-key "compacted.")))
  (on-compaction-failure [_ session-key payload]
    (write! output-writer (thought-notification session-key (str "compaction failed: " (or (:message payload) (:error payload))))))
  (on-compaction-disabled [_ session-key payload]
    (write! output-writer (thought-notification session-key (str "compaction disabled: " (name (:reason payload))))))
  (on-turn-end [_ _ _] nil)
  (send! [_ _] {:ok false :transient? false}))

(defn make [host]
  (->AcpComm (:output-writer host)))

(defn channel [output-writer]
  (->AcpComm output-writer))

(defn text-update [session-id text]
  (text-notification session-id text))

(defn thought-update [session-id text]
  (thought-notification session-id text))

(defn user-text-update [session-id text]
  (user-text-notification session-id text))

(defn replay-tool-call-update [session-id tool-call result]
  (replay-tool-call-notification session-id tool-call result))

(defn available-commands-update [session-id commands]
  (available-commands-notification session-id commands))
