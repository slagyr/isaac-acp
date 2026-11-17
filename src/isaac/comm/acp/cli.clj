;; mutation-tested: 2026-05-06
(ns isaac.comm.acp.cli
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.foundation.cli.api :as cli-api]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.cli.registry :as registry]
    [isaac.comm.acp.server :as server]
        [isaac.agent.config.defaults :as defaults]
    [isaac.foundation.config.loader :as config]
    [isaac.agent.config.resolve :as config-resolve]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.frequencies :as frequencies]
    [isaac.agent.frequencies-cli :as frequencies-cli]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.builtin :as builtin]
    [isaac.agent.util.jsonrpc :as dispatch]
    [isaac.agent.util.jsonrpc :as jrpc]))

(def option-spec
  ;; Session selection (--session/--crew/--session-tag/--resume/--create/--prefer)
  ;; and per-turn overrides (--with-*/-M) come from the shared frequencies-cli
  ;; adapter, the same set the prompt command uses. ACP attaches to ONE resolved
  ;; session (no --reach).
  (concat
    [["-v" "--verbose" "Log inbound method names to stderr"]
     ["-h" "--help"    "Show help"]]
    frequencies-cli/frequencies-option-spec
    frequencies-cli/override-option-spec))

(defn- parse-option-map [raw-args]
  (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)
        options (cond-> options
                  (:create options) (update :create frequencies-cli/parse-create))]
    {:options (->> options
                   (remove (comp nil? val))
                   (into {}))
     :errors  errors}))

(defn- home-dir [{:keys [home state-dir]}]
  (or home state-dir (System/getProperty "user.home")))

(defn- state-dir [opts]
  (or (:state-dir opts)
      (:root opts)
      (some-> (:home opts) (str "/.isaac"))
      (str (home-dir opts) "/.isaac")))

(defn- valid-model? [server-opts model-alias]
  (if-let [models (:models server-opts)]
    (contains? models model-alias)
    (let [cfg          (:cfg server-opts)
          named-models (:models (config/normalize-config cfg))]
      (boolean (or (get named-models model-alias)
                   (config-resolve/parse-model-ref model-alias))))))

(defn- build-server-opts [opts]
  (let [home           (home-dir opts)
        requested-sdir (state-dir opts)
        raw-config     (:config (config/load-config-result {:root requested-sdir}))
        cfg            (config/normalize-config raw-config)
        sdir           (or (:state-dir cfg) (:stateDir cfg) requested-sdir)
        out            (or (:output-writer opts) *out*)
        crew-members   (or (when (map? (:crew opts)) (:crew opts)) (:agents opts))
        models         (:models opts)
        prov-cfgs      (:provider-configs opts)
        crew-id        (when (string? (:crew opts)) (:crew opts))]
    ;; Refresh the process-wide config snapshot from the config we just loaded
    ;; for this run. The per-turn handlers (e.g. session-prompt-handler) read
    ;; config/snapshot as their config base, so without committing here a stdio
    ;; run would reuse a stale snapshot left by a previous run in the same JVM
    ;; (notably across test scenarios), resolving the wrong crew model.
    (when (nil? crew-members)
      (host/ensure-runtime!
        {:install! (fn [] (config/set-snapshot! raw-config "ACP stdio run config refresh"))}))
    (cond-> {:state-dir sdir :home home :output-writer out}
      crew-members        (assoc :crew-members crew-members)
      models              (assoc :models models)
      prov-cfgs           (assoc :provider-configs prov-cfgs)
      crew-id             (assoc :crew-id crew-id)
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
  (or (nexus/get :session-store)
      (store/registered-store)
      (store/create (nexus/get :state-dir))))

(defn- attach-session-handler [handlers output-writer session-key]
  (assoc handlers "session/new" (fn [_ _] (server/attach-session-result! output-writer session-key))))

(defn- create-once-handler [handlers output-writer]
  (let [created (atom nil)
        original (get handlers "session/new")]
    (assoc handlers "session/new"
           (fn [params message]
             (if-let [session-key @created]
               (server/attach-session-result! output-writer session-key)
               (let [result (original params message)]
                 (when-let [session-key (get-in result [:result :sessionId])]
                   (reset! created session-key))
                 result))))))

(def ^:dynamic *verbose-methods?* false)

(defn- line-writer
  "Wraps `out` so each newline-terminated line reaches it whole. Prompt turns
   and the reader thread share stdout, and jrpc/write-message! writes a line
   and its newline as separate calls, so lines are buffered per thread and
   written under one lock."
  [^java.io.Writer out]
  (let [buffer (ThreadLocal.)
        sb     (fn [] (or (.get buffer) (let [b (StringBuilder.)] (.set buffer b) b)))
        emit!  (fn [^StringBuilder b]
                 (when (pos? (.length b))
                   (locking out
                     (.write out (str b))
                     (.flush out))
                   (.setLength b 0)))
        append (fn [x]
                 (let [b (sb)]
                   (.append b (if (int? x) (str (char x)) (str x)))
                   (when (str/ends-with? (str b) "\n") (emit! b))))]
    (proxy [java.io.Writer] []
      (write
        ([x] (append (if (string? x) x (if (int? x) x (String. ^chars x)))))
        ([x off len] (append (if (string? x) (subs x off (+ off len)) (String. ^chars x (int off) (int len))))))
      (flush [] (emit! (sb)))
      (close [] (emit! (sb))))))

(defn- parse-message [line]
  (try (json/parse-string line true) (catch Exception _ nil)))

(defn- prompt-session
  "The session a session/prompt line runs against, or nil for every other
   message. Prompts run off the reader thread; the rest run inline."
  [message]
  (when (= "session/prompt" (:method message))
    (or (get-in message [:params :sessionId]) ::no-session)))

(defn- await-quietly [turn]
  (when turn
    (try @turn (catch Exception _ nil))))

(defn- run-loop
  "Reads one JSON-RPC line at a time. session/prompt runs in a future so the
   reader keeps going and a session/cancel reaches the running turn; prompts
   for one session chain, so each session has one prompt at a time and its
   responses stay in request order. Each prompt is registered with the bridge
   before its future starts, so a cancel read right after it still reaches
   that prompt. Waits for running prompts at end of input."
  [handlers]
  (let [reader  (java.io.BufferedReader. (host/in))
        handle! (bound-fn [line] (write-result! (dispatch/handle-line handlers line)))]
    (loop [turns {}]
      (if-let [line (.readLine reader)]
        (if (str/blank? line)
          (recur turns)
          (let [message (parse-message line)]
            (when *verbose-methods?*
              (when-let [method (:method message)]
                (binding [*out* *err*]
                  (println method))))
            (if-let [session (prompt-session message)]
              (let [previous (get turns session)
                    token    (bridge-cancel/expect-turn! session)]
                (recur (assoc turns session (future
                                              (try
                                                (await-quietly previous)
                                                (handle! line)
                                                (finally
                                                  (bridge-cancel/settle-turn! session token)))))))

              (do (handle! line)
                  (recur turns)))))
        (run! await-quietly (vals turns))))))

(defn- run-loop-verbose [handlers]
  (binding [*verbose-methods?* true]
    (run-loop handlers)))

(defn- print-error! [message]
  (binding [*out* *err*]
    (println message)))

(defn- ensure-local-config! [opts]
  (when-not (or (map? (:crew opts))
                (map? (:agents opts)))
    (let [result (config/load-config-result {:root (state-dir opts)})]
      (when (:missing-config? result)
        (print-error! (get-in result [:errors 0 :value]))
        false))))

(defn- run-local [opts]
  (let [server-opts (build-server-opts opts)]
    (host/ensure-runtime!
      {:install!
       (fn []
         (nexus/register! [:state-dir] (:state-dir server-opts))
         (store/register! (or (config/snapshot "ACP CLI local session-store bootstrap") {}) (:state-dir server-opts)))})
    (let [override    (frequencies-cli/build-override opts)
          model-alias (:with-model override)
          ;; Resolve the single session ACP attaches to from the shared
          ;; selection core (--session/--crew/--session-tag/--resume/--prefer/--create).
          target      (frequencies/resolve-session-targets
                        (frequencies-cli/build-frequencies opts)
                        (session-store))]
      (cond
        (and model-alias (not (valid-model? server-opts model-alias)))
        (do (print-error! (str "unknown model: " model-alias)) 1)

        ;; An explicit --session must already exist; the resolver marks it
        ;; create? when missing rather than erroring.
        (and (:session opts) (:create? target))
        (do (print-error! (str "session not found: " (:session opts))) 1)

        (:error target)
        (do (print-error! (:message target)) 1)

        :else
        ;; Attach session/new to the single resolver target.
        (let [attached-ids (atom #{})
              cfg          (or (config/snapshot "ACP CLI attach") {})
              crew-id      (or (:with-crew override) (:crew (:create-identity target))
                               (:crew (:session target)) (:crew opts) (defaults/crew-id cfg))
              attach-key   (when (and (not (:create? target)) (:session-key target)
                                      (store/get-session (session-store) (:session-key target)))
                             (:session-key target))
              server-opts' (cond-> server-opts
                             model-alias (assoc :model-override model-alias)
                             (or (:with-crew override) (:crew opts))
                             (assoc :crew-id crew-id))
              handlers     (cond-> (server/handlers server-opts')
                             attach-key (attach-session-handler (:output-writer server-opts') attach-key)
                             (:create? target) (create-once-handler (:output-writer server-opts')))
              handlers     (update handlers "session/new"
                                   (fn [handler]
                                     (fn [params message]
                                       (let [result (handler params message)]
                                         (when-let [id (or (get-in result [:result :sessionId])
                                                           (get-in result [:response :result :sessionId]))]
                                           (swap! attached-ids conj id))
                                         result))))]
          (host/ensure-runtime! {:install! builtin/register-all!})
          (print-error! "isaac acp ready")
          (try
            (with-redefs [server/attach-live-session! (let [attach! server/attach-live-session!]
                                                        (fn [writer id]
                                                          (let [channel (attach! writer id)]
                                                            (swap! attached-ids conj id)
                                                            channel)))]
              (if (:verbose opts)
                (run-loop-verbose handlers)
                (run-loop handlers)))
            (finally
              (doseq [id @attached-ids]
                (server/detach-live-session! (:output-writer server-opts') id))))
          0)))))

(defn run [opts]
  (let [errors (frequencies-cli/validate-frequencies-options opts)]
    (cond
      (seq errors)
      (do (doseq [error errors] (print-error! error)) 1)

      (= false (ensure-local-config! opts))
      1

      :else
      (binding [*out* (line-writer *out*)]
        (run-local opts)))))

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
   isaac.foundation.cli.registry/register-module-command!."
  []
  {:name        "acp"
   :usage       "acp [options]"
   :desc        "Run Isaac as an ACP agent over stdio"
   :option-spec option-spec
   :run-fn      run-fn})

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :acp [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :acp [_id]
  option-spec)
