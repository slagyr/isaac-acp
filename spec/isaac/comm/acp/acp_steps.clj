(ns isaac.comm.acp.acp-steps
  (:import
    (java.io StringWriter)
    (java.util.concurrent LinkedBlockingQueue TimeUnit))
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.cli.registry :as cli-registry]
    [isaac.comm.acp.cli :as acp-cli]
    [isaac.comm.acp.server :as acp-server]
    [isaac.config.loader :as config]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.http :as llm-http]
    [isaac.nexus :as nexus]
    [isaac.step-tables :as match]
    [isaac.util.jsonrpc :as dispatch]))

(helper! isaac.comm.acp.acp-steps)

;; Tests exercise the CLI command via main/run, which normally registers
;; module-contributed commands by reading the user's isaac.edn and running
;; module discovery. In feature tests there's no on-disk isaac.edn for the ACP
;; module, so we register the command directly at step-ns load time — the
;; equivalent of what production gets via the :cli manifest extension.
(cli-registry/register! (acp-cli/make-command))

(defn- acp-isaac-run-preflight! []
  (let [root      (g/get :root)
        root-home (when (and root (str/ends-with? root "/.isaac"))
                    (fs/parent root))]
    (g/update! :main-extra-opts
               (fn [opts]
                 (cond-> (or opts {})
                   root      (assoc :state-dir root)
                   root-home (assoc :home root-home))))))

(cli-steps/register-isaac-run-preflight! acp-isaac-run-preflight!)

(def ^:private await-timeout-ms 3000)

(defn- absolute-path [path]
  (if (str/starts-with? path "/")
    path
    (str (System/getProperty "user.dir") "/" path)))

(defn- isaac-home-root [home]
  (str (absolute-path home) "/.isaac"))

(defn- effective-state-dir []
  (or (g/get :state-dir)
      (g/get :root)))

(defn- close-acp-state! []
  (when-let [turn* (g/get :acp-turn-future)]
    (future-cancel turn*))
  (g/dissoc! :acp-turn-future)
  (g/dissoc! :live-output-writer)
  (g/dissoc! :acp-output-offset))

(g/after-scenario close-acp-state!)

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

(defn- dispatch-result [line]
  (let [state-dir        (effective-state-dir)
        mem-fs           (g/get :mem-fs)
        live-writer      (when state-dir (StringWriter.))
        llm-http-stub    (g/get :llm-http-stub)
        custom-fn        (g/get :acp-dispatch-fn)
        fallback-fn      (fn [input-line]
                           (dispatch/handle-line (or (g/get :acp-handlers) {}) input-line))
        connection-error (fn [url]
                           {:error :connection-refused :message (str "Could not connect to " url)})
        do-dispatch!     (fn []
                           (cond
                             custom-fn
                             (record-dispatch-result! (custom-fn line))

                             state-dir
                             (let [agents (g/get :agents)
                                   models (g/get :models)
                                   result (acp-server/dispatch-line
                                            (cond-> {:state-dir        state-dir
                                                     :provider-configs (g/get :provider-configs)
                                                     :output-writer    live-writer}
                                              agents (assoc :crew-members agents)
                                              models (assoc :models models)
                                              (and (nil? agents) (nil? models))
                                              (assoc :cfg (:config (config/load-config-result {:root state-dir}))))
                                            line)]
                               (enqueue-output-lines! live-writer)
                               (record-dispatch-result! result))

                             :else
                             (record-dispatch-result! (fallback-fn line))))
        run-dispatch!    (fn []
                           (let [run! #(if mem-fs
                                         (nexus/-with-nested-nexus {:fs mem-fs} (do-dispatch!))
                                         (do-dispatch!))]
                             (case llm-http-stub
                               :connection-refused
                               (with-redefs [llm-http/post-json!         (fn [url _headers _body & _] (connection-error url))
                                             llm-http/post-ndjson-stream! (fn [url _headers _body _on-chunk & _] (connection-error url))]
                                 (run!))
                               (run!))))]
    {:state-dir   state-dir
     :live-writer live-writer
     :run!        run-dispatch!}))

(defn- dispatch-message! [message async?]
  (let [line                         (json/generate-string message)
        {:keys [state-dir live-writer run!]} (dispatch-result line)]
    (cond
      (and async? (= "session/prompt" (:method message)))
      (let [turn* (future
                    (when (and state-dir live-writer)
                      (g/assoc! :live-output-writer live-writer)
                      (g/assoc! :acp-output-offset 0))
                    (try
                      (run!)
                      (finally
                        (g/dissoc! :live-output-writer))))]
        (g/assoc! :acp-turn-future turn*))

      (= "session/cancel" (:method message))
      (do
        (run!)
        (grover/release-delay!))

      :else
      (run!))))

(defn- send-client-line! [line async?]
  (dispatch-message! (json/parse-string line true) async?))

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

(defn enqueue-notification-for-test! [message]
  (enqueue-outgoing! message))

(defn enqueue-spurious-session-update! []
  (enqueue-outgoing! {:jsonrpc "2.0"
                      :method  "session/update"
                      :params  {:sessionId "spurious"
                                :update    {:sessionUpdate "agent_message_chunk"
                                            :content       {:type "text" :text "unexpected"}}}}))

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

(defn- listed-methods-from-table [table]
  (->> (:rows table)
       (map #(get (zipmap (:headers table) %) "method"))
       (remove str/blank?)
       set))

(defn- listed-session-updates-from-table [table]
  (when (some #(= "params.update.sessionUpdate" %) (:headers table))
    (->> (:rows table)
         (map #(get (zipmap (:headers table) %) "params.update.sessionUpdate"))
         (remove str/blank?)
         set)))

(defn- notification-message? [message]
  (and (contains? message :method) (not (contains? message :id))))

(defn- expected-session-update-counts [table]
  (when (some #(= "params.update.sessionUpdate" %) (:headers table))
    (frequencies
      (for [row (:rows table)
            :let [su (get (zipmap (:headers table) row) "params.update.sessionUpdate")]
            :when (not (str/blank? su))]
        su))))

(defn- strict-replay-notification-table? [expected-su-counts]
  (and expected-su-counts
       (or (contains? expected-su-counts "user_message_chunk")
           (contains? expected-su-counts "agent_message_chunk"))))

(defn- strict-trailing-listed-notification? [listed-methods expected-su-counts notification]
  (and (notification-message? notification)
       (contains? listed-methods (:method notification))
       (or (nil? expected-su-counts)
           (let [su (get-in notification [:params :update :sessionUpdate])]
             (contains? expected-su-counts su)))))

(defn- assert-no-trailing-listed-notifications! [listed-methods expected-su-counts matched-candidate]
  (when (strict-replay-notification-table? expected-su-counts)
    (let [matched-freq (frequencies
                         (keep #(get-in % [:params :update :sessionUpdate]) matched-candidate))
        q (outgoing-queue)]
      (sync-output-messages! q)
      (loop [skipped []]
        (if-let [msg (.poll q)]
          (if (strict-trailing-listed-notification? listed-methods expected-su-counts msg)
            (let [su (get-in msg [:params :update :sessionUpdate])
                  extra (inc (get matched-freq su 0))]
              (when (> extra (get expected-su-counts su 0))
                (doseq [m skipped] (.put q m))
                (.put q msg)
                (throw (ex-info "unexpected trailing notification for listed method"
                                {:method (:method msg) :notification msg})))
              (recur (conj skipped msg)))
            (recur (conj skipped msg)))
          (doseq [m skipped] (.put q m)))))))

(defn acp-agent-sends-notifications [table]
  (let [expected-count         (count (:rows table))
        listed-methods         (listed-methods-from-table table)
        expected-su-counts     (expected-session-update-counts table)
        matching-window (fn [notifications]
                          (let [notifications (vec notifications)]
                            (some (fn [start]
                                    (let [candidate (subvec notifications start (+ start expected-count))
                                          result    (match/match-entries table candidate)]
                                      (when (= [] (:failures result)) candidate)))
                                  (range 0 (inc (- (count notifications) expected-count))))))
        deadline        (+ (System/currentTimeMillis) await-timeout-ms)
        finalize!       (fn [candidate]
                          (g/assoc! :last-acp-notifications candidate)
                          (g/should= expected-count (count candidate))
                          (assert-no-trailing-listed-notifications! listed-methods
                                                                  expected-su-counts
                                                                  candidate))]
    (loop [notifications []]
      (if-let [candidate (when (<= expected-count (count notifications))
                           (matching-window notifications))]
        (finalize! candidate)
        (let [remaining (- deadline (System/currentTimeMillis))]
          (if (<= remaining 0)
            (g/should= [] (:failures (match/match-entries table (take expected-count notifications))))
            (if-let [notification (await-message notification-message?)]
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

(defn stdout-session-update-notifications [table]
  (let [expected-count (count (:rows table))
        lines          (->> (str/split-lines (or (g/get :output) ""))
                            (remove str/blank?)
                            (mapv #(json/parse-string % true)))
        notifications  (vec (filter #(and (= "session/update" (:method %))
                                          (not (contains? % :id))) lines))
        failures       (:failures (match/match-entries table notifications))]
    (g/should= expected-count (count notifications))
    (g/should= [] failures)))


(defn isaac-home-contains-config [home doc-string]
  (let [abs-home    (absolute-path home)
        root        (isaac-home-root home)
        config-dir  (str root "/config")
        config-file (str config-dir "/isaac.edn")]
    (if-let [mem-fs (g/get :mem-fs)]
      (nexus/-with-nested-nexus {:fs mem-fs}
        (fs/mkdirs mem-fs config-dir)
        (fs/spit mem-fs config-file (str/trim doc-string)))
      (do
        (.mkdirs (io/file config-dir))
        (spit config-file (str/trim doc-string))))
    (g/assoc! :root root)
    (g/update! :main-extra-opts #(merge (or % {}) {:home abs-home}))))

(defn isaac-home-has-no-config [home]
  (let [abs-home    (absolute-path home)
        root        (isaac-home-root home)
        config-file (str root "/config/isaac.edn")]
    (if-let [mem-fs (g/get :mem-fs)]
      (nexus/-with-nested-nexus {:fs mem-fs}
        (when (fs/exists? mem-fs config-file)
          (fs/delete mem-fs config-file))
        (fs/mkdirs mem-fs root))
      (do
        (when (.exists (io/file config-file))
          (.delete (io/file config-file)))
        (.mkdirs (io/file root))))
    (g/assoc! :root root)
    (g/update! :main-extra-opts #(merge (or % {}) {:home abs-home}))))

(defn workspace-has-soul-md
  "Writes SOUL.md under the crew workspace dir resolved by
   isaac.config.loader/resolve-workspace (prefers <root>/crew/<id>/)."
  [crew-id home doc-string]
  (let [root    (or (g/get :root) (isaac-home-root home))
        ws-dir  (str root "/crew/" crew-id)
        soul    (str ws-dir "/SOUL.md")
        content (str/trim doc-string)]
    (if-let [mem-fs (g/get :mem-fs)]
      (nexus/-with-nested-nexus {:fs mem-fs}
        (fs/mkdirs mem-fs ws-dir)
        (fs/spit mem-fs soul content))
      (do
        (.mkdirs (io/file ws-dir))
        (spit soul content)))))

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

(defn acp-commands-registered []
  true)

;; region ----- Step routing -----

(defwhen "the ACP client sends request {id:int}:" isaac.comm.acp.acp-steps/acp-client-sends-request)

(defwhen "the ACP client sends request {id:int} asynchronously:" isaac.comm.acp.acp-steps/acp-client-sends-request-async
  "Dispatches a direct ACP session/prompt request in a background future.
   Use only in scenarios that must send a follow-up cancel or otherwise
   interact before the prompt turn completes.")

(defwhen "a spurious session/update notification is enqueued" isaac.comm.acp.acp-steps/enqueue-spurious-session-update!)

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

(defthen "the stdout session/update notifications are:" isaac.comm.acp.acp-steps/stdout-session-update-notifications)

(defthen "the stdout has a JSON-RPC response for id {id:int}:" isaac.comm.acp.acp-steps/output-contains-json-rpc-response
  "Polls stdout until a JSON-RPC response matching the given id appears
   (or times out). Matches the response object against the table.")

(defgiven "the ACP client has initialized" isaac.comm.acp.acp-steps/acp-client-initialized)

(defgiven "the ACP commands are registered" isaac.comm.acp.acp-steps/acp-commands-registered
  "No-op step that forces gherclj to load ACP step namespaces so command
   registration is installed for CLI features.")

(defgiven "an in-memory Isaac state directory {path:string}" isaac.foundation.root-steps/in-memory-state
  "Compatibility route for older ACP features that still refer to an
   'in-memory Isaac state directory'. The current Isaac test harness
   calls this an 'Isaac root'.")

(defgiven "isaac home {home:string} contains config:" isaac.comm.acp.acp-steps/isaac-home-contains-config
  "Compatibility shim for ACP features that still model config under
   <home>/.isaac. Writes the config there, sets :root to <home>/.isaac
   for shared session/store helpers, and injects :home via :main-extra-opts.")

(defgiven "isaac home {home:string} has no config file" isaac.comm.acp.acp-steps/isaac-home-has-no-config
  "Compatibility shim for ACP features that still refer to an Isaac home
   rather than a root. Ensures <home>/.isaac exists without config and
   passes :home through main/*extra-opts* for the CLI under test.")

(defgiven "workspace {crew:string} in {home:string} has SOUL.md:" isaac.comm.acp.acp-steps/workspace-has-soul-md
  "Writes SOUL.md into <root>/crew/<crew>/ so resolve-workspace picks it up
   when the crew config has no inline :soul.")

;; endregion ^^^^^ Step routing ^^^^^
