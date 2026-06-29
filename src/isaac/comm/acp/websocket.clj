(ns isaac.comm.acp.websocket
  (:require
    [cheshire.core :as json]
    [isaac.comm.acp.server :as acp-server]
    [isaac.comm.acp.websocket.heartbeat :as heartbeat]
    [isaac.config.loader :as config]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.config.root :as root]
    [isaac.session.frequencies :as frequencies]
    [isaac.session.frequencies-cli :as frequencies-cli]
    [isaac.session.store.spi :as store]
    [isaac.system :as system]
    [isaac.util.jsonrpc :as dispatch]
    [org.httpkit.server :as httpkit]
    [ring.util.codec :as codec])
  (:import
    (java.util.concurrent ExecutorService Executors ThreadFactory)))

(defn- request-client [request]
  (or (get-in request [:headers "x-forwarded-for"])
      (:remote-addr request)
      "unknown"))

(defn- event-name [method]
  ({"initialize"     :acp-ws/initialize
    "session/new"    :acp-ws/session-new
    "session/prompt" :acp-ws/session-prompt
    "session/cancel" :acp-ws/session-cancel} method))

(declare server-opts)

(defn- query-params [request]
  (codec/form-decode (or (:query-string request) "")))

(defn- send-json-line! [send-line! message]
  (send-line! (json/generate-string message)))

(defn- send-line! [_request channel line]
  (httpkit/send! channel line))

(defonce ^:private ^ExecutorService dispatch-executor
         (Executors/newFixedThreadPool
           8
           (reify ThreadFactory
             (newThread [_ r]
               (doto (Thread. ^Runnable r "acp-ws-dispatch")
                 (.setDaemon true))))))

(defn- query->frequency-opts [query]
  ;; Project the forwarded ACP query params onto the keys the shared
  ;; frequencies-cli adapter understands, so stdio and --remote resolve a
  ;; session the same way.
  (cond-> {}
    (get query "session")           (assoc :session (get query "session"))
    (get query "crew")              (assoc :crew (get query "crew"))
    (get query "session-tag")       (assoc :session-tag (let [v (get query "session-tag")]
                                                          (if (sequential? v) (vec v) [v])))
    (= "true" (get query "resume")) (assoc :resume true)
    (get query "prefer")            (assoc :prefer (get query "prefer"))
    (get query "create")            (assoc :create (frequencies-cli/parse-create (get query "create")))))

(defn- requested-session-key [{:keys [query-params]}]
  (let [session-store (or (system/get :session-store)
                          (store/registered-store)
                          (store/create (root/current-root)))
        freq-opts     (query->frequency-opts query-params)
        target        (frequencies/resolve-session-targets
                        (frequencies-cli/build-frequencies freq-opts)
                        session-store)]
    (cond
      ;; explicit --session that does not exist
      (and (:session freq-opts) (:create? target)) ::missing-session
      ;; policy resolves to create -> let session/new open a fresh session
      (:create? target)                            nil
      :else                                        (:session-key target))))

(defn- log-dispatch! [request message result]
  (when-let [event (event-name (:method message))]
    (let [session-id (or (get-in result [:sessionId])
                         (get-in result [:result :sessionId])
                         (get-in result [:response :result :sessionId])
                         (get-in message [:params :sessionId]))]
      (log/debug event
                 :client (request-client request)
                 :sessionId session-id
                 :uri (:uri request)))))

(defn- parse-method [line]
  (try
    (:method (json/parse-string line true))
    (catch Exception _
      nil)))

(defn- async-prompt? [line]
  (= "session/prompt" (parse-method line)))

(defn- log-frame-received! [request line]
  (when-let [method (parse-method line)]
    (log/info :acp-ws/frame-received
              :method method
              :async? (= method "session/prompt")
              :client (request-client request)
              :uri (:uri request))))

(defn dispatch-line [opts request line]
  (let [message     (json/parse-string line true)
        server-opts (assoc (server-opts opts) :output-writer (:output-writer opts))
        run!        (fn []
                      (let [attach-key (when (= "session/new" (:method message))
                                         (requested-session-key (assoc opts :crew-id (:crew-id server-opts))))
                            result     (cond
                                         (= ::missing-session attach-key)
                                         {:jsonrpc "2.0"
                                          :id      (:id message)
                                          :error   {:code    -32602
                                                    :message (str "session not found: " (get-in opts [:query-params "session"]))}}

                                         attach-key
                                         (dispatch/handle-line (assoc (acp-server/handlers server-opts)
                                                                 "session/new"
                                                                 (fn [_ _] (acp-server/attach-session-result! (:output-writer server-opts)
                                                                                                              attach-key)))
                                                               line)

                                         :else
                                         (acp-server/dispatch-line server-opts line))]
                        (log-dispatch! request message result)
                        result))]
    (if-let [state-dir (:state-dir server-opts)]
      (system/with-nested-system {:state-dir state-dir} (run!))
      (run!))))

(defn send-dispatch-result! [send-line! result]
  (when result
    (cond
      (contains? result :notifications)
      (do
        (doseq [notification (:notifications result)]
          (send-json-line! send-line! notification))
        (when-let [response (:response result)]
          (send-json-line! send-line! response)))

      (contains? result :response)
      (send-json-line! send-line! (:response result))

      :else
      (send-json-line! send-line! result))))

(defn- server-opts [{:keys [cfg home state-dir] :as opts}]
  (let [state-dir   state-dir
        home        (or home (some-> state-dir fs/parent))
        query       (:query-params opts)
        ;; --with-crew overrides the turn crew; otherwise the selector crew. The
        ;; legacy "model" param remains accepted alongside the shared --with-model.
        crew-id     (or (:crew opts) (get query "with-crew") (get query "crew"))
        model-value (or (:model-override opts) (:model opts) (get query "with-model") (get query "model"))]
    (cond-> {:cfg cfg :home home :state-dir state-dir}
            (:crew-members opts) (assoc :crew-members (:crew-members opts))
            (:models opts) (assoc :models (:models opts))
            (:provider-configs opts) (assoc :provider-configs (:provider-configs opts))
            crew-id (assoc :crew-id crew-id)
            model-value (assoc :model-override model-value))))

(defn receive-line! [opts request send-line! line]
  (log-frame-received! request line)
  (let [opts* (assoc opts :cfg (config/snapshot "ACP websocket request"))
        task  #(let [result (dispatch-line (assoc opts* :output-writer send-line!) request line)]
                (send-dispatch-result! send-line! result))]
    (if (async-prompt? line)
      (.submit dispatch-executor ^Runnable task)
      (task))))

(defn handler [request]
  (let [opts     {:cfg          (config/snapshot "ACP websocket handler entry")
                  :query-params (query-params request)
                  :state-dir    (root/current-root)}
        cfg-opts opts]
    (if-not (:websocket? request)
      {:status  400
       :headers {"Content-Type" "text/plain"}
       :body    "websocket required"}
      (httpkit/as-channel request {:on-open    (fn [channel]
                                                 ;; Register the channel + ensure the shared heartbeat
                                                 ;; task is running. The task sends a tiny JSON-RPC
                                                 ;; `$/heartbeat` notification to every open channel
                                                 ;; every :acp :heartbeat-interval-ms (default 30s),
                                                 ;; keeping the link warm against NAT, reverse-proxy,
                                                 ;; and Tailscale idle timeouts. App-layer because
                                                 ;; babashka's SCI can't construct httpkit's
                                                 ;; Frame$PingFrame; the keepalive effect is identical
                                                 ;; — what matters is bytes on the wire.
                                                 (heartbeat/register-channel! channel)
                                                 (heartbeat/ensure-started! (:cfg cfg-opts))
                                                 (log/debug :acp-ws/connection-opened
                                                            :client (request-client request)
                                                            :uri (:uri request)))
                                   :on-close   (fn
                                                 ([channel status]
                                                  (heartbeat/deregister-channel! channel)
                                                  (log/debug :acp-ws/connection-closed
                                                             :client (request-client request)
                                                             :status status
                                                             :uri (:uri request)))
                                                 ([channel status reason]
                                                  (heartbeat/deregister-channel! channel)
                                                  (log/debug :acp-ws/connection-closed
                                                             :client (request-client request)
                                                             :reason reason
                                                             :status status
                                                             :uri (:uri request))))
                                   :on-receive (fn [channel line]
                                                 (receive-line! opts request #(send-line! request channel %) line))}))))
