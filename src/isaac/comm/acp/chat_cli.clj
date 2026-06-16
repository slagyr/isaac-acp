(ns isaac.comm.acp.chat-cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.registry :as registry]
    [isaac.config.loader :as config]
    [isaac.config.root :as root]
    [isaac.util.shell :as shell]))

;; region ----- Toad -----

(defn build-toad-command [& [{:keys [crew model remote resume session token]}]]
  (let [acp-cmd (cond-> "isaac acp"
                   crew (str " --crew " crew)
                   model (str " --model " model)
                   resume (str " --resume")
                   session (str " --session " session)
                   remote (str " --remote " remote)
                   token (str " --token " token))]
    {:command "toad"
     :args    ["acp" acp-cmd "."]
     :env     {}}))

(defn format-toad-command [& [opts]]
  (let [{:keys [command args]} (build-toad-command opts)]
    (str/join " " (cons command (map #(if (str/includes? % " ") (str "\"" % "\"") %) args)))))

(defn spawn-toad! [& [opts]]
  (let [{:keys [command args]} (build-toad-command opts)
        pb (ProcessBuilder. (into [command] args))]
    (.inheritIO pb)
    (-> (.start pb) .waitFor)))

;; endregion ^^^^^ Toad ^^^^^

;; region ----- Chat Command -----

(def option-spec
  [["-c" "--crew NAME"   "Use a named crew member (default: main)"]
   ["-m" "--model ALIAS" "Override the agent's default model"]
   ["-R" "--remote URL"  "Proxy ACP over a remote WebSocket endpoint"]
   ["-r" "--resume"      "Resume the most recent session"]
   ["-s" "--session KEY" "Resume a specific session by key"]
   ["-T" "--token TOKEN" "Bearer token for remote ACP authentication"]
   ["-d" "--dry-run"     "Print the Toad launch command without spawning"]
   ["-h" "--help"        "Show help"]])

(defn- parse-option-map [raw-args]
  (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options
                   (remove (comp nil? val))
                   (into {}))
     :errors  errors}))

(defn- print-error! [message]
  (binding [*out* *err*]
    (println message)))

(defn- state-dir [opts]
  (or (:state-dir opts)
      (:root opts)
      (some-> (:home opts) (str "/.isaac"))
      (root/current-root)))

(defn- missing-local-config? [opts]
  (let [result (config/load-config-result {:root (state-dir opts)})]
    (when (:missing-config? result)
      (print-error! (get-in result [:errors 0 :value]))
      true)))

(defn- run-toad! [opts]
  (cond
    (and (not (:remote opts)) (missing-local-config? opts))
    1

    (not (shell/cmd-available? "toad"))
    (do (println "Toad not found. Install it at batrachian.ai/install")
        1)

    (:dry-run opts)
    (do (println (format-toad-command opts))
        0)

    :else
    (spawn-toad! opts)))

(defn run [opts]
  (run-toad! opts))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (let [{:keys [options errors]} (parse-option-map (or _raw-args []))]
    (cond
      (:help options)
      (do
        (println (registry/command-help (registry/get-command "chat")))
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
  {:name        "chat"
   :usage       "chat [options]"
   :desc        "Launch Toad chat UI"
   :option-spec option-spec
   :run-fn      run-fn})

;; endregion ^^^^^ Chat Command ^^^^^

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :chat [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :chat [_id]
  option-spec)
