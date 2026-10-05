(ns isaac.comm.acp.server
  (:require
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.agent.bridge.core :as bridge]
    [isaac.foundation.cli.host :as host]
    [isaac.comm.acp :as acp-comm]
    [isaac.agent.config.defaults :as defaults]
    [isaac.foundation.config.loader :as config]
    [isaac.agent.config.resolve :as config-resolve]
    [isaac.foundation.config.root :as root]
    [isaac.agent.drive.turn :as single-turn]
    [isaac.agent.llm.api.protocol :as llm-api]
    [isaac.foundation.logger :as log]
    [isaac.http.routes]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.session.transcript :as message-content]
    [isaac.agent.slash.registry :as slash-registry]
    [isaac.comm.acp.system :as system]
    [isaac.agent.tool.memory :as memory]
    [isaac.agent.util.jsonrpc :as dispatch]
    [isaac.agent.util.jsonrpc :as jrpc])
  (:import
    (java.time ZoneOffset ZonedDateTime)
    (java.time.format DateTimeFormatter)
    (java.util UUID)))

(defn- available-commands []
  ;; slash-registry/all-commands (2-arg) merges built-ins + module-declared
  ;; commands + config-defined prompt-template commands and de-dups by name
  ;; (registered wins). It falls back to the nexus for :state-dir and :fs,
  ;; but :cwd has to come from us — without it, the prompt catalog only
  ;; scans global roots and project-scoped commands are missed.
  (let [cfg (or (config/snapshot "ACP available command advertisement") {})]
    (slash-registry/all-commands (:module-index cfg)
                                 {:config cfg
                                  :cwd    (host/cwd)})))

(defn- session-store []
  (or (system/get :session-store)
      (store/registered-store)
      (store/create (root/current-root))))

(defn- invalid-params [message]
  (ex-info message {:type :invalid-params
                    :message message}))

(defn- duplicate-session-response [message session-id]
  {:notifications [(acp-comm/available-commands-update session-id (available-commands))]
   :response      {:jsonrpc "2.0"
                   :id      (:id message)
                   :error   {:code    jrpc/INVALID_PARAMS
                             :message (str "session already exists: " session-id)}}})

(def ^:private mint-id-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmm"))

(defn- mint-session-id
  "A fresh session id for session/new without an explicit name. Never call
   store/open-session! with a nil/blank name (isaac-j95x): every SPI
   implementation refuses it rather than silently degrading it into a
   collision with an unrelated session."
  []
  (let [ts     (.format mint-id-formatter (ZonedDateTime/ofInstant (memory/now) ZoneOffset/UTC))
        suffix (subs (str (UUID/randomUUID)) 0 4)]
    (str "acp-" ts "-" suffix)))

(defn- crew-error-response [message reason]
  {:response {:jsonrpc "2.0"
             :id      (:id message)
             :error   {:code    jrpc/INVALID_PARAMS
                       :message reason}}})

(defn- crew-str [crew]
  (when crew (if (keyword? crew) (name crew) (str crew))))

(defn- ambient-cfg []
  (or (config/snapshot "ACP ambient config") {}))

(defn- open-acp-session! [session-id crew-id session-store]
  (store/open-session! session-store session-id
                       {:crew      crew-id
                        :channel   "acp"
                        :chat-type "direct"
                        :origin    {:kind :acp}
                        :cwd       (host/cwd)}))

(defn- open-for-crew!
  "Open session-id under crew-id via the session store, catching a refusal
   (cross-crew id collision) so the ACP layer can turn it into a JSON-RPC
   error instead of an uncaught exception."
  [session-id crew-id session-store]
  (try
    {:session (open-acp-session! session-id crew-id session-store)}
    (catch Exception e
      {:refused (or (ex-message e) "session open refused")})))

(defn- session-new-handler [crew-id params message]
  (let [session-store (session-store)]
    (if-let [existing-session (when-let [session-name (:name params)]
                                (store/get-session session-store session-name))]
      (duplicate-session-response message (:id existing-session))
      (let [session-id  (or (:name params)
                            (mint-session-id))
            {:keys [session refused]} (open-for-crew! session-id crew-id session-store)
            opened-crew (crew-str (:crew session))
            want-crew   (crew-str crew-id)]
        (cond
          refused
          (crew-error-response message refused)

          (and opened-crew want-crew (not= opened-crew want-crew))
          (crew-error-response message
                               (str "session " (:id session) " belongs to crew " opened-crew ", not " want-crew))

          :else
          {:notifications [(acp-comm/available-commands-update (:id session) (available-commands))]
           :result        {:sessionId (:id session)}})))))

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

(defn- providers-from-models [models]
  (into {}
        (keep (fn [[_ model]]
                (when-let [provider (:provider model)]
                  (let [id (if (keyword? provider) (name provider) (str provider))]
                    [id {:api id :auth "none"}]))))
        (or models {})))

(defn- effective-cfg [cfg crew-members models provider-configs]
  (let [cfg* (cond-> (or cfg {})
               (seq crew-members)     (assoc :crew crew-members)
               (seq models)           (assoc :models models)
               (seq provider-configs) (update :providers merge provider-configs))
        cfg* (if (seq (:providers cfg*))
               cfg*
               (let [inferred (providers-from-models (or models (:models cfg*)))]
                 (cond-> cfg*
                   (seq inferred) (assoc :providers inferred))))]
    (config/normalize-config cfg*)))

(defn- initialize-handler [opts _params _message]
  (let [{:keys [crew-id crew-members models provider-configs cfg home model-override]} opts
        cfg                    (effective-cfg cfg (resolve-crew-members crew-members cfg) (or models {}) (or provider-configs {}))
        {:keys [model provider]} (config-resolve/resolve-crew-context cfg crew-id (cond-> {:home home}
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
  (let [session-store (session-store)
        session       (store/get-session session-store session-key)]
    (if session
      (do
        (replay-transcript! output-writer (:id session) (store/active-transcript session-store (:id session)))
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

(defn- run-prompt [output-writer session-id text ctx]
  (let [channel  (acp-comm/channel output-writer)
        payload  (assoc ctx :comm channel
                             :session-key session-id
                             :input text
                             :origin {:kind :acp}
                             :state-dir (or (:state-dir ctx) (root/current-root))
                             :config (assoc (:config ctx) :root (or (:state-dir ctx) (root/current-root))))
        result   (try
                   (bridge/dispatch! payload)
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

(defn- session-prompt-handler [output-writer crew-members models provider-configs cfg home model-override crew-id params _message]
  (let [session-id    (get params :sessionId)
        text          (prompt->text (get params :prompt))
        session-entry (when session-id (store/get-session (session-store) session-id))
        cfg*          (or (config/snapshot "ACP session/prompt config base") cfg {})
        crew-members  (resolve-crew-members crew-members cfg*)
        effective-cfg (effective-cfg cfg* crew-members (or models {}) (or provider-configs {}))
        crew-id       (or (:crew session-entry) (:agent session-entry) crew-id
                          (defaults/crew-id effective-cfg))]
    (when (nil? session-id)
      (throw (invalid-params "sessionId is required")))
    (when (nil? text)
      (throw (invalid-params "Invalid params: no text in prompt")))
    (run-prompt output-writer session-id text {:config         effective-cfg
                                               :home           home
                                               :state-dir      (root/current-root)
                                               :model-override model-override
                                               :origin         {:kind :acp}
                                               :crew           crew-id})))

(defn handlers
  [{:keys [crew-id crew-members models provider-configs cfg home output-writer model-override]}]
  (let [cfg     (or cfg (ambient-cfg) {})
        crew-id (or crew-id (get-in cfg [:defaults :frequencies :crew]))
        opts {:crew-members crew-members :models models :provider-configs provider-configs :cfg cfg :home home :crew-id crew-id :model-override model-override}]
    {"initialize"      (partial initialize-handler opts)
     "session/new"     (partial session-new-handler crew-id)
     "session/load"    (partial session-load-handler output-writer crew-id)
     "session/prompt"  (partial session-prompt-handler output-writer crew-members models provider-configs cfg home model-override crew-id)
     "session/cancel"  session-cancel-handler}))

(defn dispatch-line
  [opts line]
  (let [run! #(dispatch/handle-line (handlers opts) line)]
    (if-let [state-dir (:state-dir opts)]
      (system/with-nested-system {:state-dir state-dir} (run!))
      (run!))))
