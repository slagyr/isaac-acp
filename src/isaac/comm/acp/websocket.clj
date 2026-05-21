(ns isaac.comm.acp.websocket
  (:require
    [cheshire.core :as json]
    [isaac.comm.acp.server :as acp-server]
    [isaac.config.loader :as config]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.session.store :as store]
    [isaac.session.store.file :as file-store]
    [isaac.system :as system]
    [isaac.util.jsonrpc.dispatch :as dispatch]
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

(defn- requested-session-key [{:keys [query-params crew-id]}]
  (let [state-dir         (system/get :state-dir)
        requested-session (get query-params "session")]
    (cond
      requested-session
      (if (store/get-session (or (system/get :session-store) (file-store/create-store state-dir)) requested-session)
        requested-session
        ::missing-session)

      (and state-dir (= "true" (get query-params "resume")))
      (some->> (store/list-sessions-by-agent (or (system/get :session-store) (file-store/create-store state-dir)) (or crew-id "main"))
               (sort-by :updated-at)
               last
               :id)

      :else
      nil)))

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
        crew-id     (or (:crew opts) (get query "crew"))
        model-value (or (:model-override opts) (:model opts) (get query "model"))]
    (cond-> {:cfg cfg :home home :state-dir state-dir}
            (:crew-members opts) (assoc :crew-members (:crew-members opts))
            (:models opts) (assoc :models (:models opts))
            (:provider-configs opts) (assoc :provider-configs (:provider-configs opts))
            crew-id (assoc :crew-id crew-id)
            model-value (assoc :model-override model-value))))

(defn receive-line! [opts request send-line! line]
  (log-frame-received! request line)
  (let [task #(let [result (dispatch-line (assoc opts :output-writer send-line!) request line)]
                (send-dispatch-result! send-line! result))]
    (if (async-prompt? line)
      (.submit dispatch-executor ^Runnable task)
      (task))))

(defn handler [request]
  (let [opts     {:cfg          (config/snapshot)
                  :query-params (query-params request)
                  :state-dir    (system/get :state-dir)}
        cfg-opts opts]
    (if-not (:websocket? request)
      {:status  400
       :headers {"Content-Type" "text/plain"}
       :body    "websocket required"}
      (httpkit/as-channel request {:on-open    (fn [_channel]
                                                 (log/debug :acp-ws/connection-opened
                                                            :client (request-client request)
                                                            :uri (:uri request)))
                                   :on-close   (fn
                                                 ([_channel status]
                                                  (log/debug :acp-ws/connection-closed
                                                             :client (request-client request)
                                                             :status status
                                                             :uri (:uri request)))
                                                 ([_channel status reason]
                                                  (log/debug :acp-ws/connection-closed
                                                             :client (request-client request)
                                                             :reason reason
                                                             :status status
                                                             :uri (:uri request))))
                                   :on-receive (fn [channel line]
                                                 (receive-line! opts request #(send-line! request channel %) line))}))))
