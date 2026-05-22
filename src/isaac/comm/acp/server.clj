(ns isaac.comm.acp.server
  (:require
    [isaac.bridge.cancellation :as bridge-cancel]
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.comm.acp :as acp-comm]
    [isaac.config.loader :as config]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.util.jsonrpc.dispatch :as dispatch]
    [isaac.drive.turn :as single-turn]
    [isaac.llm.api :as llm-api]
    [isaac.logger :as log]
    [isaac.session.store :as store]
    [isaac.session.store.file :as file-store]
    [isaac.session.transcript :as message-content]
    [isaac.slash.registry :as slash-registry]
    [isaac.system :as system]))

(defn- available-commands []
  (slash-registry/all-commands (:module-index (or (config/snapshot) {}))))

(def ^:private startup-cwd (System/getProperty "user.dir"))

(defn- session-store []
  (or (system/get :session-store)
      (file-store/create-store (system/get :state-dir))))

(defn- with-startup-cwd [f]
  (let [original (System/getProperty "user.dir")]
    (try
      (when-not (= startup-cwd original)
        (System/setProperty "user.dir" startup-cwd))
      (f)
      (finally
        (when-not (= startup-cwd original)
          (System/setProperty "user.dir" original))))))

(defn- invalid-params [message]
  (ex-info message {:type :invalid-params
                    :message message}))

(defn- duplicate-session-response [message session-id]
  {:notifications [(acp-comm/available-commands-update session-id (available-commands))]
   :response      {:jsonrpc "2.0"
                   :id      (:id message)
                   :error   {:code    jrpc/INVALID_PARAMS
                             :message (str "session already exists: " session-id)}}})

(defn- session-new-handler [crew-id params message]
  (let [session-store (session-store)]
    (if-let [existing-session (when-let [session-name (:name params)]
                                (store/get-session session-store session-name))]
      (duplicate-session-response message (:id existing-session))
      (let [session (with-startup-cwd #((requiring-resolve 'isaac.session.context/create-with-resolved-behavior!)
                                        (:name params) {:crew     crew-id
                                                       :channel  "acp"
                                                       :chatType "direct"
                                                       :origin   {:kind :acp}}))]
        {:notifications [(acp-comm/available-commands-update (:id session) (available-commands))]
         :result        {:sessionId (:id session)}}))))

(defn- initialize-result [model provider]
  {:protocolVersion   1
   :agentInfo         (cond-> {:name "isaac" :version "dev"}
                         model    (assoc :model model)
                         provider (assoc :provider provider))
   :agentCapabilities {:loadSession true
                       :promptCapabilities {:text true}}})

(defn- resolve-crew-members [crew-members cfg]
  (or crew-members
      (some-> cfg config/normalize-config :crew)
      {}))

(defn- effective-cfg [cfg crew-members models provider-configs]
  (config/normalize-config
    (cond-> (or cfg {})
      (seq crew-members)     (assoc :crew crew-members)
      (seq models)           (assoc :models models)
      (seq provider-configs) (update :providers merge provider-configs))))

(defn- initialize-handler [opts _params _message]
  (let [{:keys [crew-id crew-members models provider-configs cfg home model-override] :or {crew-id "main"}} opts
        cfg                    (effective-cfg cfg (resolve-crew-members crew-members cfg) (or models {}) (or provider-configs {}))
        {:keys [model provider]} (config/resolve-crew-context cfg crew-id (cond-> {:home home}
                                                                             model-override (assoc :model-override model-override)))]
    (initialize-result model
                         (when provider
                           (llm-api/display-name provider)))))

(defn- prompt->text [prompt]
  (->> (or prompt [])
       (filter #(= "text" (:type %)))
       first
       :text))

(defn- content->text [content]
  (message-content/content->text content))

(defn- extract-tool-calls [message]
  (message-content/tool-calls message))

(defn- tool-results-by-id [transcript]
  (->> transcript
       (keep (fn [entry]
               (let [message (:message entry)
                     role    (:role message)
                     tc-id   (or (:toolCallId message) (:id message))]
                 (when (and (= "message" (:type entry))
                            (= "toolResult" role)
                            tc-id)
                   [tc-id (or (content->text (:content message))
                              (some-> (:content message) str))]))))
       (into {})))

(defn- replay-transcript-entry! [output-writer session-id tool-results entry]
  (case (:type entry)
    "compaction"
    (when-let [summary (:summary entry)]
      (jrpc/write-message! output-writer (acp-comm/text-update session-id summary)))

    "message"
    (let [message    (:message entry)
          role       (:role message)
          tool-calls (extract-tool-calls message)]
      (cond
        (seq tool-calls)
        (doseq [tool-call tool-calls]
          (jrpc/write-message! output-writer
                              (acp-comm/replay-tool-call-update session-id tool-call (get tool-results (:id tool-call)))))

        (= "user" role)
        (when-let [text (content->text (:content message))]
          (jrpc/write-message! output-writer (acp-comm/user-text-update session-id text)))

        (= "assistant" role)
        (when-let [text (content->text (:content message))]
          (jrpc/write-message! output-writer (acp-comm/text-update session-id text)))))

    nil))

(defn- replay-transcript! [output-writer session-id transcript]
  (when output-writer
    (let [tool-results (tool-results-by-id transcript)]
      (doseq [entry transcript]
        (replay-transcript-entry! output-writer session-id tool-results entry)))))

(defn attach-session-result! [output-writer session-key]
  (let [session-store (session-store)]
    (if-let [session (store/get-session session-store session-key)]
      (do
        (replay-transcript! output-writer (:id session) (store/get-transcript session-store (:id session)))
        {:sessionId (:id session)})
      (throw (invalid-params (str "session not found: " session-key))))))

(defn- session-load-handler [output-writer _crew-id params _message]
  (if-let [session-id (:sessionId params)]
    (do
      (attach-session-result! output-writer session-id)
      nil)
    (throw (invalid-params "sessionId is required"))))

(defn- session-cancel-handler [params _message]
  (let [session-id (get params :sessionId)]
    (log/info :acp/session-cancel-received :sessionId session-id :params params)
    (bridge-cancel/cancel! session-id)
    nil))

(defn- emit-status-notification! [output-writer data]
  (jrpc/write-message! output-writer
                      (jrpc/notification "chat/status" data)))

(defn- emit-command-text! [output-writer session-id text]
  (jrpc/write-message! output-writer (acp-comm/text-update session-id text)))

(defn- end-turn-with-error! [output-writer session-id message]
  (emit-command-text! output-writer session-id message)
  {:stopReason "end_turn"})

(defn- unknown-crew-message [crew-id]
  (str "unknown crew: " crew-id "\n"
       "use /crew {name} to switch, or add " crew-id " to config\n"))

(defn- run-prompt [output-writer session-id text ctx]
  (let [channel  (acp-comm/channel output-writer)
        payload  (assoc ctx :comm channel :session-key session-id :input text)
        result   (try
                   (with-startup-cwd #(bridge/dispatch! (charge/build payload)))
                  (catch Exception e
                    (log/ex :acp/turn-error e :session session-id)
                    {:error :exception :message (or (.getMessage e) "Unexpected error")}))]
    (cond
      (bridge-cancel/cancelled-response? result)
      result

      (:error result)
      (if (:already-emitted? result)
        {:stopReason "end_turn"}
        (end-turn-with-error! output-writer session-id (single-turn/error-message result)))

      (= :status (:command result))
      (do
        (emit-status-notification! output-writer (:data result))
        {:stopReason "end_turn"})

      :else
      {:stopReason "end_turn"})))

(defn- session-prompt-handler [output-writer crew-members models provider-configs cfg home model-override params _message]
  (let [session-id       (get params :sessionId)
        text             (prompt->text (get params :prompt))
        session-entry    (when session-id (store/get-session (session-store) session-id))
        crew-members     (resolve-crew-members crew-members cfg)
        effective-cfg    (effective-cfg cfg crew-members (or models {}) (or provider-configs {}))
        _                (config/set-snapshot! effective-cfg)]
    (when (nil? session-id)
      (throw (invalid-params "sessionId is required")))
    (when (nil? text)
      (throw (invalid-params "Invalid params: no text in prompt")))
    (let [result (run-prompt output-writer session-id text {:cfg            effective-cfg
                                                            :home           home
                                                            :model-override model-override})]
      (if (and (= :unknown-crew (:error result)) session-entry)
        (end-turn-with-error! output-writer session-id (unknown-crew-message (or (:crew session-entry) (:agent session-entry))))
        result))))

(defn handlers
  [{:keys [crew-id crew-members models provider-configs cfg home output-writer model-override] :or {crew-id "main"}}]
  (let [opts {:crew-members crew-members :models models :provider-configs provider-configs :cfg cfg :home home :crew-id crew-id :model-override model-override}]
    {"initialize"      (partial initialize-handler opts)
     "session/new"     (partial session-new-handler crew-id)
     "session/load"    (partial session-load-handler output-writer crew-id)
     "session/prompt"  (partial session-prompt-handler output-writer crew-members models provider-configs cfg home model-override)
     "session/cancel"  session-cancel-handler}))

(defn dispatch-line
  [opts line]
  (let [run! #(dispatch/handle-line (handlers opts) line)]
    (if-let [state-dir (:state-dir opts)]
      (system/with-nested-system {:state-dir state-dir} (run!))
      (run!))))
