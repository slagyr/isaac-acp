;; mutation-tested: 2026-05-06
(ns isaac.comm.acp.cli
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.api :as cli-api]
    [isaac.cli.registry :as registry]
    [isaac.comm.acp.server :as server]
    [isaac.config.loader :as config]
    [isaac.config.resolve :as config-resolve]
    [isaac.nexus :as nexus]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.session.frequencies :as frequencies]
    [isaac.session.frequencies-cli :as frequencies-cli]
    [isaac.session.store.spi :as store]
    [isaac.tool.builtin :as builtin]
    [isaac.util.jsonrpc :as dispatch]))

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
      (config/set-snapshot! raw-config "ACP stdio run config refresh"))
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
    (let [result (config/load-config-result {:root (state-dir opts)})]
      (when (:missing-config? result)
        (print-error! (get-in result [:errors 0 :value]))
        false))))

(defn- run-local [opts]
  (let [server-opts (build-server-opts opts)]
    (nexus/register! [:state-dir] (:state-dir server-opts))
    (store/register! (or (config/snapshot "ACP CLI local session-store bootstrap") {}) (:state-dir server-opts))
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
        ;; Attach session/new to the resolved key when one exists; when the
        ;; policy resolves to create, let the server open a fresh session.
        (let [attach-key   (when-not (:create? target) (:session-key target))
              server-opts' (cond-> server-opts
                             model-alias           (assoc :model-override model-alias)
                             (:with-crew override) (assoc :crew-id (:with-crew override)))
              handlers     (cond-> (server/handlers server-opts')
                             attach-key (attach-session-handler attach-key))]
          (builtin/register-all!)
          (print-error! "isaac acp ready")
          (if (:verbose opts)
            (run-loop-verbose handlers)
            (run-loop handlers))
          0)))))

(defn run [opts]
  (let [errors (frequencies-cli/validate-frequencies-options opts)]
    (cond
      (seq errors)
      (do (doseq [error errors] (print-error! error)) 1)

      (= false (ensure-local-config! opts))
      1

      :else
      (run-local opts))))

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
   isaac.cli.registry/register-module-command!."
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
