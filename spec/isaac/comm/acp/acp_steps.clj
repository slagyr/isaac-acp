(ns isaac.comm.acp.acp-steps
  (:import
    (java.io StringWriter)
    (java.util.concurrent LinkedBlockingQueue TimeUnit))
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [cheshire.core :as json]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.cli :as cli-registry]
    [isaac.comm.acp.chat-cli :as chat-cli]
    [isaac.comm.acp.cli :as acp-cli]
    [isaac.server.cli.cli-steps :as cli-steps]
    [isaac.comm.acp.server :as acp-server]
    [isaac.util.jsonrpc.dispatch :as dispatch]
    [isaac.comm.acp.websocket :as acp-websocket]
    [isaac.util.ws-client :as ws]
    [isaac.config.loader :as config]
    [isaac.step-tables :as match]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.http :as llm-http]
    [isaac.main :as main]
    [ring.util.codec :as codec]))

(helper! isaac.comm.acp.acp-steps)

;; Tests exercise the CLI commands via main/run, which normally registers
;; module-contributed commands by reading the user's isaac.edn and running
;; module discovery. In feature tests there's no on-disk isaac.edn for the
;; ACP module, so we register the commands directly at step-ns load time —
;; the equivalent of what production gets via the :cli manifest extension.
(cli-registry/register! (acp-cli/make-command))
(cli-registry/register! (chat-cli/make-command))

(declare ensure-loopback-proxy!)

;; isaac's cli_steps used to auto-detect `acp.proxy-transport = loopback` in
;; server config and call ensure-loopback-proxy! before invoking main. That
;; ACP-specific knowledge has moved here. We hook before-scenario so any
;; scenario whose Background sets the config gets the loopback transport +
;; the extra-opts (:ws-connection-factory, :acp-proxy-eof-grace-ms 0) wired
;; through isaac's generic :main-extra-opts seam.
;; Registered as :isaac-run-preflight so isaac.server.cli.cli-steps/isaac-run
;; calls us right before main/run — after the Background's `config:` step has
;; set :server-config but before any extra-opts get computed.
(defn- acp-isaac-run-preflight! []
  (when (= "loopback" (get-in (g/get :server-config) [:acp :proxy-transport]))
    (when (nil? (g/get :acp-remote-connection-factory))
      (ensure-loopback-proxy!))
    (g/update! :main-extra-opts
               #(merge (or % {})
                       {:ws-connection-factory  (g/get :acp-remote-connection-factory)
                        :acp-proxy-eof-grace-ms 0}))))

(cli-steps/register-isaac-run-preflight! acp-isaac-run-preflight!)

(defn- query-params [query-string]
  (codec/form-decode (or query-string "")))

(def ^:private await-timeout-ms 3000)

(defn- close-loopback! []
  (when-let [client (g/get :acp-loopback-client)]
    (ws/ws-close! client))
  (when-let [server (g/get :acp-loopback-server)]
    (ws/ws-close! server))
  (when-let [^LinkedBlockingQueue queue (g/get :proxy-stdin-queue)]
    (.put queue :closed))
  (when-let [runner (g/get :acp-proxy-runner)]
    (future-cancel runner))
  (when-let [server-runner (g/get :acp-loopback-server-runner)]
    (future-cancel server-runner))
  (when-let [turn* (g/get :acp-turn-future)]
    (future-cancel turn*)))

(g/after-scenario close-loopback!)

(defn- parse-value [value]
  (cond
    (nil? value) nil
    (re-matches #"-?\d+" value) (parse-long value)
    (= "true" value) true
    (= "false" value) false
    (= "null" value) nil
    (and (or (str/starts-with? value "{") (str/starts-with? value "["))
         (or (str/ends-with? value "}") (str/ends-with? value "]")))
    (try
      (edn/read-string value)
      (catch Exception _ value))
    :else value))

(defn- ensure-vector-size [v idx]
  (let [v (if (vector? v) v [])]
    (if (< idx (count v))
      v
      (into v (repeat (inc (- idx (count v))) nil)))))

(defn- assoc-path* [data segments value]
  (if (empty? segments)
    value
    (let [[tag segment] (first segments)
          more          (rest segments)]
      (case tag
        :key
        (let [k     (keyword segment)
              m     (if (map? data) data {})
              child (get m k)]
          (assoc m k (assoc-path* child more value)))

        :idx
        (let [idx   segment
              v     (ensure-vector-size data idx)
              child (nth v idx)]
          (assoc v idx (assoc-path* child more value)))))))

(defn- assoc-path [message path value]
  (assoc-path* message (match/parse-path path) value))

(defn- table-rows [table]
  (map (fn [row] (zipmap (:headers table) row))
       (:rows table)))

(defn- table->message [table]
  (reduce (fn [message row]
            (assoc-path message
                        (get row "key")
                        (parse-value (get row "value"))))
          {}
          (table-rows table)))

(defn- outgoing-queue ^LinkedBlockingQueue []
  (or (g/get :acp-outgoing)
      (let [q (LinkedBlockingQueue.)]
        (g/assoc! :acp-outgoing q)
        q)))

(defn- enqueue-outgoing! [message]
  (when message
    (.put (outgoing-queue) message)))

(defn- enqueue-output-lines! [writer]
  (doseq [line (->> (str/split-lines (str writer))
                    (remove str/blank?))]
    (enqueue-outgoing! (json/parse-string line true))))

(defn- enqueue-output-line! [line]
  (enqueue-outgoing! (json/parse-string line true)))

(defn- record-dispatch-result! [result]
  (cond
    (nil? result)
    nil

    (and (map? result)
         (or (contains? result :response) (contains? result :notifications)))
    (do
      (enqueue-outgoing! (:response result))
      (doseq [notification (:notifications result)]
        (enqueue-outgoing! notification)))

    (sequential? result)
    (doseq [message result]
      (enqueue-outgoing! message))

    :else
    (enqueue-outgoing! result)))

(defn- dispatch-message! [message async?]
  (let [line          (json/generate-string message)
        state-dir     (g/get :state-dir)
        mem-fs        (g/get :mem-fs)
        llm-http-stub (g/get :llm-http-stub)
        custom-fn     (g/get :acp-dispatch-fn)
        fallback-fn   (fn [input-line]
                        (dispatch/handle-line (or (g/get :acp-handlers) {}) input-line))
        connection-refused (fn [url]
                             {:error :connection-refused :message (str "Could not connect to " url)})
        do-dispatch!  (fn []
                        (cond
                          custom-fn
                          (record-dispatch-result! (custom-fn line))

                          state-dir
                          (let [agents (g/get :agents)
                                models (g/get :models)
                                result (acp-server/dispatch-line (cond-> {:state-dir        state-dir
                                                                           :provider-configs (g/get :provider-configs)
                                                                           :output-writer    enqueue-output-line!}
                                                                    agents (assoc :agents agents)
                                                                    models (assoc :models models)
                                                                    (and (nil? agents) (nil? models)) (assoc :cfg (config/load-config {:home state-dir})))
                                                                  line)]
                            (record-dispatch-result! result))

                          :else
                          (record-dispatch-result! (fallback-fn line))))
        run-dispatch! (fn []
                        (let [run! #(if mem-fs
                                      (binding [fs/*fs* mem-fs] (do-dispatch!))
                                      (do-dispatch!))]
                          (case llm-http-stub
                            :connection-refused
                            (with-redefs [llm-http/post-json!         (fn [url _headers _body & _] (connection-refused url))
                                          llm-http/post-ndjson-stream! (fn [url _headers _body _on-chunk & _] (connection-refused url))]
                              (run!))
                            (run!))))]
    (cond
      (and async? (= "session/prompt" (:method message)))
      (let [turn* (future
                    (run-dispatch!))]
        (g/assoc! :acp-turn-future turn*))

      (= "session/cancel" (:method message))
      (do
        (run-dispatch!)
        (grover/release-delay!))

       :else
       (run-dispatch!))))

(defn- send-client-line! [line async?]
  (if-let [^LinkedBlockingQueue queue (g/get :proxy-stdin-queue)]
    (.put queue line)
    (dispatch-message! (json/parse-string line true) async?)))

(declare output-messages)

(defn- sync-output-messages! [queue]
  (let [output-offset (or (g/get :acp-output-offset) 0)
        unseen        (drop output-offset (output-messages))]
    (when (seq unseen)
      (doseq [message unseen]
        (.put queue message))
      (g/assoc! :acp-output-offset (+ output-offset (count unseen))))))

(defn- await-message [predicate]
  (let [queue    (outgoing-queue)
        deadline (+ (System/currentTimeMillis) await-timeout-ms)
        skipped  (java.util.ArrayList.)]
    (try
      (loop []
        (sync-output-messages! queue)
        (let [remaining (- deadline (System/currentTimeMillis))
              poll-ms   (min remaining 20)]
          (if (<= remaining 0)
            nil
            (if-let [message (.poll queue poll-ms TimeUnit/MILLISECONDS)]
              (if (predicate message)
                message
                (do
                  (.add skipped message)
                  (recur)))
              (recur)))))
      (finally
        (doseq [m skipped]
          (.put queue m))))))

(defn- output-messages []
  (let [output (if-let [writer (g/get :live-output-writer)] (str writer) (g/get :output))]
    (->> (str/split-lines (or output ""))
       (remove str/blank?)
       (mapv #(json/parse-string % true)))))

(defn- await-output-response [id]
  (await-message #(= id (:id %))))

(defn- loopback-request []
  (or (g/get :acp-loopback-request) {}))

(defn- loopback-server-opts [state-dir agents models provider-cfgs]
  (let [request     (loopback-request)
        query       (query-params (:query-string request))
        agent-id    (or (get query "crew") (get query "agent") "main")
        cfg         (when (and state-dir (nil? agents) (nil? models))
                      (config/load-config {:home state-dir}))]
    {:request     {:headers      {"x-forwarded-for" "loopback"}
                   :query-string (:query-string request)
                   :uri          "/acp"}
     :server-opts (cond-> {:state-dir        state-dir
                           :query-params     query
                           :provider-configs provider-cfgs
                           :agent-id         agent-id
                           :model-override   (get query "model")}
                     agents (assoc :agents agents)
                    models (assoc :models models)
                    cfg    (assoc :cfg cfg))}))

(defn- loopback-result [state-dir agents models provider-cfgs writer line]
  (let [{:keys [request server-opts]} (loopback-server-opts state-dir agents models provider-cfgs)
        server-opts (assoc server-opts :output-writer writer)]
    (acp-websocket/dispatch-line server-opts request line)))

(defn- emit-loopback-result! [server-conn result]
  (when result
    (cond
      (contains? result :notifications)
      (do
        (doseq [notification (:notifications result)]
          (ws/ws-send! server-conn (json/generate-string notification)))
        (when-let [response (:response result)]
          (ws/ws-send! server-conn (json/generate-string response))))

      (contains? result :response)
      (ws/ws-send! server-conn (json/generate-string (:response result)))

      :else
      (ws/ws-send! server-conn (json/generate-string result)))))

(defn- await-release! []
  (when-let [release* (g/get :loopback-final-response-release)]
    @release*))

(defn- emit-final-response! [server-conn result]
  (await-release!)
  (emit-loopback-result! server-conn result))

(defn- hold-final-response? [line result]
  (and (g/get :loopback-hold-final-response?)
       (= "session/prompt" (:method (json/parse-string line true)))
       (or (contains? result :response)
           (contains? result :result))))

(defn- serve-loopback-connection! [server-conn state-dir agents models provider-cfgs]
  (loop []
    (when-let [line (ws/ws-receive! server-conn)]
      (let [writer (StringWriter.)
            result (loopback-result state-dir agents models provider-cfgs writer line)]
        (doseq [message-line (ws/written-lines writer)]
          (ws/ws-send! server-conn message-line))
        (if (hold-final-response? line result)
          (emit-final-response! server-conn result)
          (emit-loopback-result! server-conn result)))
      (recur))))

(defn- start-loopback-server! [transport state-dir agents models provider-cfgs mem-fs]
  (future
    (let [run-loop (fn []
                    (loop []
                      (if-let [server-conn (ws/accept-loopback! transport)]
                        (do
                          (serve-loopback-connection! server-conn state-dir agents models provider-cfgs)
                          (when-not @(:permanent? transport)
                            (recur)))
                        (when-not @(:permanent? transport)
                          (recur)))))]
      (if mem-fs
        (binding [fs/*fs* mem-fs] (run-loop))
        (run-loop)))))

(defn ensure-loopback-proxy! []
  (let [transport      (or (g/get :acp-reconnectable-loopback)
                           (let [t (ws/reconnectable-loopback)]
                             (g/assoc! :acp-reconnectable-loopback t)
                             t))
        state-dir      (g/get :state-dir)
        agents         (g/get :agents)
        models         (g/get :models)
        provider-cfgs  (g/get :provider-configs)
        mem-fs         (g/get :mem-fs)]
    (when-not (g/get :acp-loopback-server-runner)
      (g/assoc! :acp-loopback-server-runner (start-loopback-server! transport state-dir agents models provider-cfgs mem-fs)))
    (g/assoc! :acp-remote-connection-factory
              (fn [url _]
                (g/assoc! :acp-loopback-request {:query-string (when (str/includes? url "?")
                                                                 (subs url (inc (str/index-of url "?"))))})
                (ws/connect-loopback! transport url)))))

(defn- parse-argv [args]
  (if (str/blank? args)
    []
    (loop [s (str/trim args) tokens []]
      (if (str/blank? s)
        tokens
        (cond
          (str/starts-with? s "'")
          (let [end (str/index-of s "'" (long 1))]
            (if end
              (recur (str/trim (subs s (inc end))) (conj tokens (subs s 1 end)))
              (conj tokens (subs s 1))))

          (str/starts-with? s "\"")
          (let [end (str/index-of s "\"" (long 1))]
            (if end
              (recur (str/trim (subs s (inc end))) (conj tokens (subs s 1 end)))
              (conj tokens (subs s 1))))

          :else
          (let [[tok rest-s] (str/split s #"\s+" 2)]
            (recur (or rest-s "") (conj tokens tok))))))))

(defn- next-proxy-line []
  (let [^LinkedBlockingQueue queue (g/get :proxy-stdin-queue)]
    (when-let [line (.poll queue 5 TimeUnit/SECONDS)]
      (when-not (= :closed line)
        line))))

(defn acp-client-sends-request [id table]
  (send-client-line! (json/generate-string (assoc (table->message table)
                                                  :jsonrpc "2.0"
                                                  :id id))
                     false))

(defn acp-client-sends-request-async [id table]
  (send-client-line! (json/generate-string (assoc (table->message table)
                                                  :jsonrpc "2.0"
                                                  :id id))
                     true))

(defn acp-client-sends-notification [table]
  (send-client-line! (json/generate-string (assoc (table->message table)
                                                  :jsonrpc "2.0"))
                     false))

(defn acp-agent-sends-response [id table]
  (let [expected-match? (fn [message]
                          (and (= id (:id message))
                               (empty? (:failures (match/match-object table message)))))
        response        (or (await-message expected-match?)
                            (await-message #(= id (:id %))))]
    (g/should-not-be-nil response)
    (when response
      (let [result (match/match-object table response)]
        (g/should= [] (:failures result))))))

(defn acp-agent-sends-notifications [table]
  (let [expected-count   (count (:rows table))
        notification?    #(and (contains? % :method) (not (contains? % :id)))
        matching-window  (fn [notifications]
                           (let [notifications (vec notifications)]
                             (some (fn [start]
                                     (let [candidate (subvec notifications start (+ start expected-count))
                                           result    (match/match-entries table candidate)]
                                       (when (= [] (:failures result)) candidate)))
                                   (range 0 (inc (- (count notifications) expected-count))))))
        deadline         (+ (System/currentTimeMillis) await-timeout-ms)]
    (loop [notifications []]
      (if-let [candidate (when (<= expected-count (count notifications))
                           (matching-window notifications))]
        (do
          (g/assoc! :last-acp-notifications candidate)
          (g/should= expected-count (count candidate)))
        (let [remaining (- deadline (System/currentTimeMillis))]
          (if (<= remaining 0)
            (g/should= [] (:failures (match/match-entries table (take expected-count notifications))))
            (if-let [notification (await-message notification?)]
              (recur (conj notifications notification))
              (g/should= [] (:failures (match/match-entries table (take expected-count notifications)))))))))))

(defn- last-notification-content []
  (->> (or (g/get :last-acp-notifications) [])
       (map #(get-in % [:params :update :content :text]))
       (remove nil?)
       (str/join "\n")))

(defn notification-content-matches [table]
  (let [content  (last-notification-content)
        patterns (map (comp str/trim first) (:rows table))]
    (doseq [pattern patterns]
      (g/should (re-find (re-pattern pattern) content)))))

(defn notification-content-not-contains [text]
  (g/should-not (str/includes? (last-notification-content) text)))


(defn acp-proxy-running [args]
  (let [transport      (or (g/get :acp-reconnectable-loopback)
                           (let [t (ws/reconnectable-loopback)]
                             (g/assoc! :acp-reconnectable-loopback t)
                             t))
        stdin-queue    (LinkedBlockingQueue.)
        output-writer  (StringWriter.)
        error-writer   (StringWriter.)
        argv           (parse-argv args)
        state-dir      (g/get :state-dir)
        provider-cfgs  (g/get :provider-configs)
        mem-fs         (g/get :mem-fs)
        cfg            (or (g/get :server-config) {})
        server-runner* (start-loopback-server! transport state-dir (g/get :agents) (g/get :models) provider-cfgs mem-fs)
        run*           (future
                         (binding [*in*  (java.io.BufferedReader. (java.io.StringReader. ""))
                                    *out* output-writer
                                    *err* error-writer
                                    fs/*fs* (or mem-fs fs/*fs*)
                                    main/*extra-opts* {:state-dir state-dir
                                                       :provider-configs provider-cfgs
                                                       :acp-proxy-max-reconnects (get-in cfg [:acp :proxy-max-reconnects])
                                                       :acp-proxy-reconnect-delay-ms (get-in cfg [:acp :proxy-reconnect-delay-ms])
                                                      :acp-read-line-fn next-proxy-line
                                                      :ws-connection-factory (fn [url _]
                                                                               (g/assoc! :acp-loopback-request {:query-string (when (str/includes? url "?")
                                                                                                                        (subs url (inc (str/index-of url "?"))))})
                                                                               (ws/connect-loopback! transport url))}]
                           (g/assoc! :exit-code (main/run argv))))]
    (g/assoc! :acp-loopback-server-runner server-runner*)
    (g/assoc! :proxy-stdin-queue stdin-queue)
    (g/assoc! :live-output-writer output-writer)
    (g/assoc! :live-error-writer error-writer)
    (g/assoc! :acp-proxy-runner run*)))

(defn stdin-receives [content]
  (let [lines (str/split-lines (if (str/ends-with? content "\n") content (str content "\n")))
        ^LinkedBlockingQueue queue (g/get :proxy-stdin-queue)]
    (doseq [line lines]
      (.put queue line))))

(defn loopback-drops []
  (let [transport (g/get :acp-reconnectable-loopback)]
    (when-not (ws/await-loopback-connection! transport 1000)
      (throw (ex-info "loopback connection was not established before drop" {})))
    (ws/drop-loopback! transport)))

(defn loopback-restored []
  (ws/restore-loopback! (g/get :acp-reconnectable-loopback)))

(defn loopback-drops-permanently []
  (let [transport (g/get :acp-reconnectable-loopback)]
    (when-not (ws/await-loopback-connection! transport 1000)
      (throw (ex-info "loopback connection was not established before permanent drop" {})))
    (ws/drop-loopback-permanently! transport)))

(defn loopback-holds-final-response []
  (g/assoc! :loopback-hold-final-response? true)
  (g/assoc! :loopback-final-response-release (promise)))

(defn loopback-releases-final-response []
  (g/assoc! :loopback-hold-final-response? false)
  (when-let [release* (g/get :loopback-final-response-release)]
    (deliver release* :ok)))

(defn output-contains-json-rpc-response [id table]
  (let [response (await-output-response id)]
    (g/should-not-be-nil response)
    (when response
      (let [result (match/match-object table response)]
        (g/should= [] (:failures result))))))

(defn acp-client-initialized []
  (send-client-line! (json/generate-string {:jsonrpc "2.0"
                                            :id 0
                                            :method "initialize"
                                            :params {:protocolVersion 1}})
                     false)
  (when-not (await-message #(= 0 (:id %)))
    (throw (ex-info "ACP initialize did not return a response" {:id 0}))))

;; region ----- Step routing -----

(defwhen "the ACP client sends request {id:int}:" isaac.comm.acp.acp-steps/acp-client-sends-request)

(defwhen "the ACP client sends request {id:int} asynchronously:" isaac.comm.acp.acp-steps/acp-client-sends-request-async
  "Dispatches a direct ACP session/prompt request in a background future.
   Use only in scenarios that must send a follow-up cancel or otherwise
   interact before the prompt turn completes.")

(defwhen "the ACP client sends notification:" isaac.comm.acp.acp-steps/acp-client-sends-notification)

(defthen "the ACP agent sends response {id:int}:" isaac.comm.acp.acp-steps/acp-agent-sends-response)

(defthen "the ACP agent sends notifications:" isaac.comm.acp.acp-steps/acp-agent-sends-notifications
  "Polls up to await-timeout-ms for a consecutive window of N notifications
   matching the N table rows (in order). Stores the matching window in
   :last-acp-notifications for subsequent content assertions.")

(defthen "the notification content matches:" isaac.comm.acp.acp-steps/notification-content-matches
  "Reads content.text from each notification captured by the preceding
   'the ACP agent sends notifications:' step. Table rows are regex patterns
   searched across the joined content.")

(defthen "the notification content does not contain {text:string}" isaac.comm.acp.acp-steps/notification-content-not-contains)

(defgiven "the acp proxy is running with {args:string}" isaac.comm.acp.acp-steps/acp-proxy-running
  "Starts 'isaac acp ...' in a background future wired to a reconnectable
   loopback transport. Captures stdout/stderr for assertion, feeds stdin
   from :proxy-stdin-queue. Requires the loopback transport to be active
   (usually via config acp.proxy-transport=loopback).")

(defwhen "stdin receives:" isaac.comm.acp.acp-steps/stdin-receives
  "Pushes the heredoc content line-by-line onto the proxy's stdin queue.
   Pairs with 'the acp proxy is running with'.")

(defwhen "the loopback connection drops" isaac.comm.acp.acp-steps/loopback-drops
  "Simulates a connection drop after the loopback transport reports an
   established connection. The transport still accepts reconnects — use
   'drops permanently' to block them.")

(defwhen "the loopback connection is restored" isaac.comm.acp.acp-steps/loopback-restored)

(defwhen "the loopback connection drops permanently" isaac.comm.acp.acp-steps/loopback-drops-permanently
  "Drops the connection AND rejects all future reconnect attempts. Use
   when a scenario needs to prove the proxy keeps retrying without ever
   succeeding.")

(defgiven "the loopback holds the final response" isaac.comm.acp.acp-steps/loopback-holds-final-response
  "Makes the loopback server block before returning the final response
   to the client. Release it explicitly with 'the loopback releases the
   final response'. Used to test mid-response cancellation / timeout
   behavior.")

(defwhen "the loopback releases the final response" isaac.comm.acp.acp-steps/loopback-releases-final-response)

(defthen "the stdout has a JSON-RPC response for id {id:int}:" isaac.comm.acp.acp-steps/output-contains-json-rpc-response
  "Polls stdout until a JSON-RPC response matching the given id appears
   (or times out). Matches the response object against the table.")

(defgiven "the ACP client has initialized" isaac.comm.acp.acp-steps/acp-client-initialized)

;; endregion ^^^^^ Step routing ^^^^^
