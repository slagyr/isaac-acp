(ns isaac.comm.acp.server
  (:require
    [isaac.bridge.cancellation :as bridge-cancel]
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.comm.acp :as acp-comm]
    [isaac.config.loader :as config]
    [isaac.config.resolve :as config-resolve]
    [isaac.config.root :as root]
    [isaac.drive.turn :as single-turn]
    [isaac.episodes.lifecycle :as lifecycle]
    [isaac.episodes.store :as episode-store]
    [isaac.fs :as fs]
    [isaac.llm.api.protocol :as llm-api]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.server.routes]
    [isaac.session.store.spi :as store]
    [isaac.session.transcript :as message-content]
    [isaac.slash.registry :as slash-registry]
    [isaac.system :as system]
    [isaac.util.jsonrpc :as dispatch]
    [isaac.util.jsonrpc :as jrpc]))

(defn- available-commands []
  ;; slash-registry/all-commands (2-arg) merges built-ins + module-declared
  ;; commands + config-defined prompt-template commands and de-dups by name
  ;; (registered wins). It falls back to the nexus for :state-dir and :fs,
  ;; but :cwd has to come from us — without it, the prompt catalog only
  ;; scans global roots and project-scoped commands are missed.
  (let [cfg (or (config/snapshot "ACP available command advertisement") {})]
    (slash-registry/all-commands (:module-index cfg)
                                 {:config cfg
                                  :cwd    (System/getProperty "user.dir")})))

(def ^:private startup-cwd (System/getProperty "user.dir"))

(defn- session-store []
  (or (system/get :session-store)
      (store/registered-store)
      (store/create (root/current-root))))

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

(defn- ambient-cfg []
  (or (config/snapshot "ACP ambient config") {}))

(defn- episode-thread-id [params]
  (or (:name params)
      (str "acp-" (.toString (java.util.UUID/randomUUID)))))

(defn- session-new-handler [crew-id cfg params message]
  (let [session-store (session-store)
        cfg           (or cfg (ambient-cfg) {})]
    (if-let [existing-session (when-let [session-name (:name params)]
                                (store/get-session session-store session-name))]
      (duplicate-session-response message (:id existing-session))
      (if (lifecycle/episodes-crew? cfg crew-id)
        (let [thread (episode-thread-id params)]
          {:notifications [(acp-comm/available-commands-update thread (available-commands))]
           :result        {:sessionId thread}})
        (let [session (with-startup-cwd #((requiring-resolve 'isaac.session.context/create-with-resolved-behavior!)
                                          (:name params) {:crew          crew-id
                                                          :channel       "acp"
                                                          :chatType      "direct"
                                                          :origin        {:kind :acp}
                                                          :session-store session-store}))]
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
  (let [{:keys [crew-id crew-members models provider-configs cfg home model-override] :or {crew-id "main"}} opts
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

(defn- replay-open-episode! [output-writer session-key crew-id]
  (let [cfg     (ambient-cfg)
        fs*     (or (nexus/get :fs) (fs/instance))
        root    (or (:root cfg) (nexus/get :root) (root/current-root))
        open    (episode-store/find-open-on-thread fs* root crew-id session-key)
        ss      (session-store)]
    (when open
      (replay-transcript! output-writer session-key (store/active-transcript ss (:id open))))
    {:sessionId session-key}))

(defn attach-session-result! [output-writer session-key]
  (let [session-store (session-store)
        cfg           (ambient-cfg)
        session       (store/get-session session-store session-key)
        crew-id       (or (:crew session) (get-in cfg [:defaults :crew]) "main")]
    (cond
      (lifecycle/episodes-crew? cfg crew-id)
      (replay-open-episode! output-writer session-key crew-id)

      session
      (do
        (replay-transcript! output-writer (:id session) (store/active-transcript session-store (:id session)))
        {:sessionId (:id session)})

      :else
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
                             :state-dir (or (:state-dir ctx) (root/current-root)))
        result   (try
                   (with-startup-cwd #(bridge/dispatch! payload))
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
                          (get-in effective-cfg [:defaults :crew]) "main")]
    (when (nil? session-id)
      (throw (invalid-params "sessionId is required")))
    (when (nil? text)
      (throw (invalid-params "Invalid params: no text in prompt")))
    (run-prompt output-writer session-id text {:config         effective-cfg
                                               :home           home
                                               :model-override model-override
                                               :origin         {:kind :acp}
                                               :crew           crew-id})))

(defn handlers
  [{:keys [crew-id crew-members models provider-configs cfg home output-writer model-override]}]
  (let [cfg     (or cfg (ambient-cfg) {})
        crew-id (or crew-id (get-in cfg [:defaults :crew]) "main")
        opts {:crew-members crew-members :models models :provider-configs provider-configs :cfg cfg :home home :crew-id crew-id :model-override model-override}]
    {"initialize"      (partial initialize-handler opts)
     "session/new"     (partial session-new-handler crew-id cfg)
     "session/load"    (partial session-load-handler output-writer crew-id)
     "session/prompt"  (partial session-prompt-handler output-writer crew-members models provider-configs cfg home model-override crew-id)
     "session/cancel"  session-cancel-handler}))

(defn dispatch-line
  [opts line]
  (let [run! #(dispatch/handle-line (handlers opts) line)]
    (if-let [state-dir (:state-dir opts)]
      (system/with-nested-system {:state-dir state-dir} (run!))
      (run!))))
