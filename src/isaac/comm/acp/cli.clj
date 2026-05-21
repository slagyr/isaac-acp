;; mutation-tested: 2026-05-06
(ns isaac.comm.acp.cli
  (:require
    [cheshire.core :as json]
    [clojure.tools.cli :as tools-cli]
    [clojure.string :as str]
    [isaac.cli :as registry]
    [isaac.comm.acp :as acp]
    [isaac.comm.acp.server :as server]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.util.jsonrpc.dispatch :as dispatch]
    [isaac.util.ws-client :as ws]
    [isaac.config.loader :as config]
    [isaac.logger :as log]
    [isaac.scheduler :as scheduler]
    [isaac.session.store :as store]
    [isaac.session.store.file :as file-store]
    [isaac.system :as system]
    [isaac.tool.builtin :as builtin]))

(def option-spec
  [["-v" "--verbose"      "Log inbound method names to stderr"]
   ["-s" "--session KEY"  "Attach to an existing session key"]
   ["-m" "--model ALIAS"  "Override the agent's default model"]
   ["-c" "--crew NAME"    "Use a named crew member (default: main)"]
   ["-R" "--resume"       "Resume the most recent session for the crew member"]
   ["-r" "--remote URL"   "Proxy ACP over a remote WebSocket endpoint"]
   ["-t" "--token TOKEN"  "Bearer token for remote ACP authentication"]
   ["-h" "--help"         "Show help"]])

(defn- parse-option-map [raw-args]
  (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options
                   (remove (comp nil? val))
                   (into {}))
     :errors  errors}))

(defn- home-dir [{:keys [home state-dir]}]
  (or home state-dir (System/getProperty "user.home")))

(defn- valid-model? [server-opts model-alias]
  (if-let [models (:models server-opts)]
    (contains? models model-alias)
    (let [cfg          (:cfg server-opts)
          named-models (:models (config/normalize-config cfg))]
      (boolean (or (get named-models model-alias)
                    (config/parse-model-ref model-alias))))))

(defn- build-server-opts [opts]
  (let [home      (home-dir opts)
        cfg       (config/normalize-config (config/load-config {:home home}))
        sdir      (or (:state-dir opts) (:stateDir cfg)
                       (str home "/.isaac"))
        out       (or (:output-writer opts) *out*)
        crew-members (or (when (map? (:crew opts)) (:crew opts)) (:agents opts))
        models    (:models opts)
        prov-cfgs (:provider-configs opts)
        crew-id   (when (string? (:crew opts)) (:crew opts))]
    (cond-> {:state-dir sdir :home home :output-writer out}
      crew-members (assoc :crew-members crew-members)
      models    (assoc :models models)
      prov-cfgs (assoc :provider-configs prov-cfgs)
      crew-id   (assoc :crew-id crew-id)
      (nil? crew-members) (assoc :cfg cfg))))

(defn- write-result! [result]
  (when result
    (cond
      (contains? result :notifications)
      (do (doseq [n (:notifications result)]
            (jrpc/write-message! *out* n))
          (when-let [r (:response result)]
            (jrpc/write-message! *out* r)))

      (contains? result :response)
      (jrpc/write-message! *out* (:response result))

      :else
      (jrpc/write-message! *out* result))))

(defn- session-store []
  (or (system/get :session-store)
      (file-store/create-store (system/get :state-dir))))

(defn- session-exists? [session-key]
  (some? (store/get-transcript (session-store) session-key)))

(defn- find-most-recent-session [crew-id]
  (when (system/get :state-dir)
    (->> (store/list-sessions-by-agent (session-store) crew-id)
         (sort-by :updated-at)
         last)))

(defn- resumed-session-key [crew-id]
  (some-> (find-most-recent-session crew-id) :id))

(defn- attach-session-handler [handlers session-key]
  (assoc handlers "session/new" (fn [_ _] {:sessionId session-key})))

(defn- run-loop [handlers]
  (let [reader (java.io.BufferedReader. *in*)]
    (loop []
      (when-let [line (.readLine reader)]
        (write-result! (dispatch/handle-line handlers line))
        (recur)))))

(defn- run-loop-verbose [handlers]
  (let [dispatch* dispatch/dispatch]
    (with-redefs [dispatch/dispatch (fn [dispatch-handlers message]
                                 (when-let [method (:method message)]
                                   (binding [*out* *err*]
                                     (println method)))
                                 (dispatch* dispatch-handlers message))]
      (run-loop handlers))))

(defn- print-error! [message]
  (binding [*out* *err*]
    (println message)))

(defn- ensure-local-config! [opts]
  (when-not (or (map? (:crew opts))
                (map? (:agents opts)))
    (let [result (config/load-config-result {:home (home-dir opts)})]
      (when (:missing-config? result)
        (print-error! (get-in result [:errors 0 :value]))
        false))))

(defn- write-line! [line]
  (.write *out* line)
  (.write *out* "\n")
  (.flush *out*))

(defn- parse-line [line]
  (try
    (json/parse-string line true)
    (catch Exception _ nil)))

(defn- message-session-id [message]
  (or (get-in message [:params :sessionId])
      (get-in message [:result :sessionId])))

(defn- cache-session-id! [session-id* line]
  (when-let [session-id (some-> line parse-line message-session-id)]
    (reset! session-id* session-id)))

(defn- crew-id [{:keys [crew]}]
  (or (when (string? crew) crew) "main"))

(defn- default-session-id [opts]
  (or (:session opts)
      (some-> (find-most-recent-session (crew-id opts)) :id)))

(defn- status-notification [session-id text]
  (let [text (cond
               (str/ends-with? text "\n\n") text
               (str/ends-with? text "\n")   (str text "\n")
               :else                          (str text "\n\n"))]
    (assoc-in (acp/text-update session-id text) [:params :update :sessionUpdate] "agent_thought_chunk")))

(defn- write-status-notification! [session-id* opts text]
  (when-let [session-id (or @session-id* (default-session-id opts))]
    (reset! session-id* session-id)
    (jrpc/write-message! *out* (status-notification session-id text))))

(defn- request-id [line]
  (try
    (:id (json/parse-string line true))
    (catch Exception _ nil)))

(defn- proxy-event-name [method]
  ({"initialize"     :acp-proxy/initialize
    "session/new"    :acp-proxy/session-new
    "session/prompt" :acp-proxy/session-prompt} method))

(defn- log-proxy-message! [url line]
  (let [message    (json/parse-string line true)
        event      (proxy-event-name (:method message))
        session-id (get-in message [:params :sessionId])]
    (when event
      (log/debug event
                 :sessionId session-id
                 :url       url))))

(defn- authentication-error? [error]
  (let [cause      (or (ex-cause error) error)
        class-name (.getName (class cause))
        message    (or (.getMessage cause) "")]
    (or (= "java.net.http.WebSocketHandshakeException" class-name)
        (re-find #"(?i)401|unauthorized|authentication failed" message))))

(defn- remote-headers [token]
  (cond-> {}
    token (assoc "Authorization" (str "Bearer " token))))

(defn- url-encode [value]
  (java.net.URLEncoder/encode (str value) "UTF-8"))

(defn- remote-query-params [opts]
  (cond-> []
    (:model opts)  (conj ["model" (:model opts)])
    (when (string? (:crew opts)) (:crew opts)) (conj ["crew" (:crew opts)])
    (:session opts) (conj ["session" (:session opts)])
    (:resume opts) (conj ["resume" "true"])))

(defn- remote-url [opts]
  (let [base   (:remote opts)
        params (remote-query-params opts)]
    (if (empty? params)
      base
      (str base
           (if (str/includes? base "?") "&" "?")
           (str/join "&" (map (fn [[k v]] (str k "=" (url-encode v))) params))))))

(defn- connect-remote! [factory url token]
  (factory url {:headers (remote-headers token)}))

(defn- start-input-reader! [opts]
  (let [reader       (java.io.BufferedReader. *in*)
        read-line-fn (or (:acp-read-line-fn opts) #(.readLine reader))
        queue        (java.util.concurrent.LinkedBlockingQueue.)]
    (future
      (loop []
        (if-let [line (read-line-fn)]
          (do
            (.put queue {:type :stdin :line line})
            (recur))
          (.put queue {:type :stdin-closed}))))
    queue))

(defn- start-remote-reader! [conn]
  (let [queue (java.util.concurrent.LinkedBlockingQueue.)]
    (future
      (loop []
        (let [message-line (ws/ws-receive! conn)]
          (cond
            (nil? message-line)
            (.put queue {:type :connection-lost})

            (:error message-line)
            (.put queue {:type :connection-error :error (:error message-line)})

            :else
            (do
              (.put queue {:type :message :line message-line})
              (recur))))))
    queue))

(declare send-request! safe-close!)

(def ^:private reconnect-task-id :acp-proxy/reconnect)

(defn- reconnect-handler
  "Scheduler handler for one reconnect attempt. Scheduler invokes with
   one ctx-map arg (ignored). Returns nil on success so the task
   completes; throws on failure so the scheduler's :retry policy
   reschedules with exponential backoff.

   `bound-fn` captures the caller's dynamic bindings — most importantly
   `*out*` — so write-status-notification! writes to the proxy's stdout
   writer rather than the scheduler thread's default *out*."
  [_scheduler active? conn* remote-queue* disconnected? session-id*
   pending-request* factory url token opts]
  (bound-fn [_run-ctx]
    (when @active?
      (log/info :acp-proxy/reconnect-attempt :url url)
      (let [new-conn (connect-remote! factory url token)]
        (reset! conn* new-conn)
        (reset! remote-queue* (start-remote-reader! new-conn))
        (reset! disconnected? false)
        (write-status-notification! session-id* opts "reconnected to remote")
        (doseq [{:keys [line]} @pending-request*]
          (try (send-request! @conn* session-id* url line)
               (catch Exception _ nil)))
        (log/debug :acp-proxy/connected :url url)))))

(defn- schedule-reconnect!
  "Schedules a one-shot reconnect attempt against scheduler-instance. On
   failure the scheduler's :retry policy reschedules with exponential
   backoff (base→max) — no manual loop. cancel! before reschedule so a
   second drop while still trying doesn't error on duplicate id."
  [scheduler-instance active? conn* remote-queue* disconnected? session-id*
   pending-request* factory url token opts]
  ;; Scheduler requires :pos? for delays — clamp to 1ms minimum so legacy
  ;; opts that pass 0 still work (the old hand-rolled loop accepted 0 fine).
  (let [base-delay (max 1 (or (:acp-proxy-reconnect-delay-ms opts) 1000))
        max-delay  (max 1 (or (:acp-proxy-reconnect-max-delay-ms opts) 5000))]
    (scheduler/cancel! scheduler-instance reconnect-task-id)
    (scheduler/schedule!
      scheduler-instance
      {:id             reconnect-task-id
       :trigger        {:kind :delay :ms base-delay}
       :handler        (reconnect-handler scheduler-instance active? conn* remote-queue*
                                          disconnected? session-id* pending-request*
                                          factory url token opts)
       :on-error       :retry
       :backoff-ms     base-delay
       :max-backoff-ms max-delay
       :retry-attempts Long/MAX_VALUE})))

(defn- connection-lost! [scheduler-instance active? conn* remote-queue* disconnected?
                          session-id* pending-request* factory url token opts]
  (when-not @disconnected?
    (reset! disconnected? true)
    (write-status-notification! session-id* opts "remote connection lost")
    (log/debug :acp-proxy/disconnected :url url)
    (safe-close! @conn*)
    (reset! conn* nil)
    (schedule-reconnect! scheduler-instance active? conn* remote-queue* disconnected?
                         session-id* pending-request* factory url token opts)))

(defn- poll-event [queue timeout-ms]
  (.poll queue timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))

(defn- send-request! [conn session-id* url line]
  (cache-session-id! session-id* line)
  (log-proxy-message! url line)
  (ws/ws-send! conn line))

(defn- safe-close! [conn]
  (try
    (some-> conn ws/ws-close!)
    (catch Exception _
      nil)))

(defn- await-connected! [active? disconnected?]
  (loop []
    (cond
      (not @active?)        false
      (not @disconnected?)  true
      :else                 (do
                              (Thread/sleep 10)
                              (recur)))))

(defn- forward-input-line! [scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts line]
  (loop []
    (cond
      (not @active?) nil
      @disconnected? (when (await-connected! active? disconnected?) (recur))
      :else
      (let [sent? (try
                    (send-request! @conn* session-id* url line)
                    (when-let [id (request-id line)]
                      (swap! pending-request* conj {:id id :line line}))
                    true
                    (catch Exception _
                      (connection-lost! scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts)
                      false))]
        (when-not sent?
          (when (await-connected! active? disconnected?)
            (recur)))))))

(defn- remote-proxy-defaults [opts]
  (let [home       (or (:home opts) (System/getProperty "user.home"))
        config-acp (:acp (config/load-config {:home home}))]
    (merge {:acp-proxy-reconnect-delay-ms     (or (:acp-proxy-reconnect-delay-ms opts)
                                                  (:proxy-reconnect-delay-ms config-acp)
                                                  1000)
            :acp-proxy-reconnect-max-delay-ms (or (:acp-proxy-reconnect-max-delay-ms opts)
                                                  (:proxy-reconnect-max-delay-ms config-acp)
                                                  5000)}
           opts)))

(defn- run-stdin-thread! [scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts input-queue]
  (future
    (loop []
      (when @active?
        (let [event (poll-event input-queue 50)]
          (cond
            (nil? event)
            (recur)

            (= :stdin-closed (:type event))
            nil

            (= :stdin (:type event))
            (do (forward-input-line! scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts (:line event))
                (recur))

            :else
            (recur)))))))

(defn- run-remote-thread! [scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts]
  (future
    (loop []
      (when @active?
        (let [event (poll-event @remote-queue* 10)]
          (when event
            (case (:type event)
              :message
              (do
                (cache-session-id! session-id* (:line event))
                (write-line! (:line event))
                (when-let [response-id (request-id (:line event))]
                  (swap! pending-request* (fn [reqs] (vec (remove #(= response-id (:id %)) reqs))))))

              :connection-error
              (connection-lost! scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts)

              :connection-lost
              (connection-lost! scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts)

              nil))
          (recur))))))

(defn- run-remote [opts]
  (let [opts    (remote-proxy-defaults opts)
        url     (remote-url opts)
        token   (:token opts)
        factory (or (:ws-connection-factory opts) ws/connect!)]
    (when-let [state-dir (:state-dir opts)]
      (system/register! :state-dir state-dir)
      (store/register! (or (config/snapshot) {}) state-dir))
    (try
      (let [conn*               (atom (connect-remote! factory url token))
            remote-queue*       (atom (start-remote-reader! @conn*))
            disconnected?       (atom false)
            session-id*         (atom (default-session-id opts))
            pending-request*    (atom [])
            active?             (atom true)
            input-queue         (start-input-reader! opts)
            ;; Private 2-thread scheduler dedicated to this proxy invocation
            ;; (one for the tick loop, one for the reconnect handler — so a
            ;; long connect attempt doesn't starve the tick). tick-ms 1 so
            ;; test backoffs (1ms) don't get delayed by the default 50ms tick.
            scheduler-instance  (-> (scheduler/create {:pool-size 2})
                                    (assoc :tick-ms 1)
                                    (scheduler/start!))
            eof-grace-ms        (or (:acp-proxy-eof-grace-ms opts) 50)
            pending-timeout-ms  (or (:acp-proxy-pending-timeout-ms opts) 2000)]
        (log/debug :acp-proxy/connected :url url)
        (let [stdin-fut  (run-stdin-thread! scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts input-queue)
              remote-fut (run-remote-thread! scheduler-instance active? conn* remote-queue* disconnected? session-id* pending-request* factory url token opts)]
          (try
            @stdin-fut
            (let [pending-deadline (+ (System/currentTimeMillis) pending-timeout-ms)]
              (loop []
                (when (and (seq @pending-request*)
                           (< (System/currentTimeMillis) pending-deadline))
                  (Thread/sleep 1)
                  (recur))))
            (let [grace-deadline (+ (System/currentTimeMillis) eof-grace-ms)]
              (loop []
                (when (< (System/currentTimeMillis) grace-deadline)
                  (Thread/sleep 1)
                  (recur))))
            0
             (finally
               (reset! active? false)
               (future-cancel remote-fut)
               (try @remote-fut (catch Exception _))
               (scheduler/shutdown! scheduler-instance)
               (log/debug :acp-proxy/disconnected :url url)
               (safe-close! @conn*)))))
      (catch Exception e
        (print-error! (if (authentication-error? e)
                        "authentication failed"
                        (str "could not connect to remote ACP endpoint: " url)))
        1))))

(defn- resolve-attach-key [session-key resumed-key]
  (let [attached-key (some-> (or session-key resumed-key)
                             (#(store/get-session (session-store) %))
                             :id)]
    (or attached-key session-key resumed-key)))

(defn- run-local [opts crew-id model-alias session-key resume?]
  (let [server-opts (build-server-opts opts)]
    (system/register! :state-dir (:state-dir server-opts))
    (store/register! (or (config/snapshot) {}) (:state-dir server-opts))
    (let [resumed-key (when resume? (resumed-session-key crew-id))
          attach-key  (resolve-attach-key session-key resumed-key)]
      (cond
        (and model-alias (not (valid-model? server-opts model-alias)))
        (do (print-error! (str "unknown model: " model-alias)) 1)

        (and session-key (not (session-exists? session-key)))
        (do (print-error! (str "session not found: " session-key)) 1)

        :else
        (let [server-opts' (cond-> server-opts
                             model-alias (assoc :model-override model-alias))
              handlers     (cond-> (server/handlers server-opts')
                             attach-key (attach-session-handler attach-key))]
          (builtin/register-all!)
          (print-error! "isaac acp ready")
          (if (:verbose opts)
            (run-loop-verbose handlers)
            (run-loop handlers))
          0)))))

(defn run [opts]
  (let [crew-id     (or (when (string? (:crew opts)) (:crew opts)) "main")
        remote-url  (:remote opts)
        model-alias (:model opts)
        session-key (:session opts)
        resume?     (:resume opts)]
    (cond
      (and resume? model-alias)
      (do (print-error! "cannot combine --resume with --model") 1)

      remote-url
      (run-remote opts)

      (= false (ensure-local-config! opts))
      1

      :else
      (run-local opts crew-id model-alias session-key resume?))))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (let [{:keys [options errors]} (parse-option-map (or _raw-args []))]
    (cond
      (:help options)
      (do
        (println (registry/command-help (registry/get-command "acp")))
        0)

      (seq errors)
      (do
        (doseq [error errors]
          (println error))
        1)

      :else
      (run (merge (dissoc opts :_raw-args) options)))))

(defn make-command
  "Factory used by the module loader's :cli extension kind. Returns the
   full command spec including :name; the loader registers it via
   isaac.cli/register-module-command!."
  []
  {:name        "acp"
   :usage       "acp [options]"
   :desc        "Run Isaac as an ACP agent over stdio"
   :option-spec option-spec
   :run-fn      run-fn})
