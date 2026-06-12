(ns isaac.comm.acp.cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g]
    [isaac.comm.acp.chat-cli :as chat-cli]
    [isaac.comm.acp.acp-steps :as acp-steps]
    [isaac.comm.acp.cli :as sut]
    [isaac.root :as home]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.util.jsonrpc.dispatch :as dispatch]
    [isaac.util.ws-client :as ws]
    [isaac.cli :as registry]
    [isaac.logger :as log]
    [isaac.fs :as fs]
    [isaac.main :as main]
    [isaac.scheduler :as scheduler]
    [isaac.server.cli.cli-steps :as cli-steps]
    [isaac.session.session-steps :as session-steps]
    [isaac.spec-helper :as helper]
    [isaac.system :as system]
    [speclj.core :refer :all])
  (:import
    (java.io BufferedReader StringReader StringWriter)
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def base-opts
  {:state-dir "/test/acp"
   :agents    {"main" {:name "main" :soul "You are Isaac." :model "grover"}}
   :models    {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}})

(defn- run-with-stdin [content opts]
  (binding [*in* (BufferedReader. (StringReader. content))]
    (let [result (atom nil)
          output-writer (StringWriter.)
          error-writer  (StringWriter.)]
      (binding [*out* output-writer
                *err* error-writer]
        (reset! result (sut/run opts)))
      {:output (str output-writer)
       :stderr (str error-writer)
       :exit   @result})))

(defn- mem-run [f]
  (system/with-nested-system {:fs (fs/mem-fs)}
    (f)))

(defn- delete-tree! [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defn- write-config! [home data]
  (let [path (str home "/.isaac/config/isaac.edn")
        fs*  (system/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (pr-str data))))

(defn- run-main! [argv opts]
  (let [stdin-content (or (:stdin opts) "")
        output-writer (StringWriter.)
        error-writer  (StringWriter.)]
    (registry/register! (sut/make-command))
    (registry/register! (chat-cli/make-command))
    (binding [*in*               (BufferedReader. (StringReader. stdin-content))
              *out*              output-writer
              *err*              error-writer
              home/*user-home*   (or (:home opts) home/*user-home*)
              main/*extra-opts*  (dissoc opts :stdin)]
      {:exit   (main/run argv)
       :output (str output-writer)
       :stderr (str error-writer)})))

(describe "ACP CLI"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (system/with-system {:config (atom nil)}
      (helper/with-memory-store (mem-run it))))

  (it "fails clearly when local config is missing"
    (let [{:keys [stderr exit]} (run-with-stdin "" {:home "/test/no-config"})]
      (should= 1 exit)
      (should (str/includes? stderr "no config found"))
      (should (str/includes? stderr "config/isaac.edn"))))

  (it "fails clearly when local config is missing via main/run"
    (let [home-dir "/tmp/acp-main-no-config"]
      (delete-tree! home-dir)
      (let [{:keys [stderr exit]} (run-main! ["acp"] {:home home-dir})]
        (should= 1 exit)
        (should (str/includes? stderr "no config found"))
        (should (str/includes? stderr "config/isaac.edn")))))

  (it "fails clearly when local config is missing via main/run with an unrelated in-memory state"
    (system/with-nested-system {:fs (fs/mem-fs)}
      (write-config! "/test/acp-seeded" {})
      (let [{:keys [stderr exit]} (run-main! ["acp"] {:home "/test/acp-no-config-home"
                                                      :fs   (system/get :fs)})]
        (should= 1 exit)
        (should (str/includes? stderr "no config found"))
        (should (str/includes? stderr "config/isaac.edn")))))

  (it "returns 0 when stdin is empty"
    (should= 0 (:exit (run-with-stdin "" base-opts))))

  (it "returns a no-model error via main/run when crew resolution yields no model"
    (let [home-dir   "/tmp/acp-main-no-model"
          state-dir  (str home-dir "/.isaac")
          session-id "no-model"]
      (delete-tree! home-dir)
      (write-config! home-dir {:crew {:defaults {}}})
      (helper/create-session! state-dir session-id)
      (let [{:keys [output exit]}
            (run-main! ["acp" "--session" session-id]
                       {:home  home-dir
                        :stdin (str (jrpc/request-line 1 "initialize" {:protocolVersion 1})
                                    (jrpc/request-line 2 "session/prompt" {:sessionId session-id
                                                                           :prompt [{:type "text" :text "hi"}]}))})]
        (should= 0 exit)
        (should (str/includes? output "no model configured for crew: main")))))

  (it "returns a no-model error via main/run with config at home and session in the registered store"
    (system/with-nested-system {:fs (fs/mem-fs)}
      (let [home-dir   "/test/acp-home"
            state-dir  "/test/acp-state/.isaac"
            session-id "no-model"]
        (write-config! home-dir {:crew {:defaults {}}})
        (helper/create-session! state-dir session-id)
        (let [{:keys [output exit]}
              (run-main! ["acp" "--session" session-id]
                         {:home  home-dir
                          :fs    (system/get :fs)
                          :stdin (str (jrpc/request-line 1 "initialize" {:protocolVersion 1})
                                      (jrpc/request-line 2 "session/prompt" {:sessionId session-id
                                                                             :prompt [{:type "text" :text "hi"}]}))})]
          (should= 0 exit)
          (should (str/includes? output "no model configured for crew: main"))))))

  (describe "feature harness reproductions"

    (it "passes isaac-home through cli_steps as the main home override"
      (let [captured (atom nil)]
        (g/reset!)
        (session-steps/default-grover-setup)
        (acp-steps/isaac-home-has-no-config "target/test-home")
        (with-redefs [main/run (fn [_]
                                 (reset! captured main/*extra-opts*)
                                 0)]
          (cli-steps/isaac-run "acp"))
        (should= (str (System/getProperty "user.dir") "/target/test-home")
                 (:home @captured))
        (should= (str (System/getProperty "user.dir") "/target/test-home/.isaac")
                 (:root @captured))
        (should= (str (System/getProperty "user.dir") "/target/test-home/.isaac")
                 (:state-dir @captured))
        (should= (system/get :fs) (:fs @captured))))

    (it "reports missing config through the shared CLI feature steps"
      (g/reset!)
      (session-steps/default-grover-setup)
      (acp-steps/acp-commands-registered)
      (acp-steps/isaac-home-has-no-config "target/test-home")
      (cli-steps/stdin-is "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}")
      (cli-steps/isaac-run "acp")
      (should= 1 (g/get :exit-code))
      (should (str/includes? (g/get :stderr) "no config found")))

    (it "reports no-model through the shared CLI feature steps"
      (g/reset!)
      (session-steps/default-grover-setup)
      (acp-steps/acp-commands-registered)
      (acp-steps/isaac-home-contains-config "target/test-home" "{:crew {:defaults {}}}")
      (session-steps/sessions-exist {:headers ["name"] :rows [["no-model"]]})
      (cli-steps/stdin-is (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}\n"
                               "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"no-model\",\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"}]}}"))
      (cli-steps/isaac-run "acp --session no-model")
      (should= 0 (g/get :exit-code))
      (should (str/includes? (g/get :output) "no model configured for crew: main")))))

  (it "writes JSON response to stdout for each request"
    (with-redefs [dispatch/handle-line (fn [_ _] (jrpc/result 1 {:ok true}))]
      (let [{:keys [output exit]} (run-with-stdin "{}\n" base-opts)]
        (should= 0 exit)
        (should (str/includes? output "\"id\":1")))))

  (it "processes multiple requests in sequence"
    (let [call-count (atom 0)]
      (with-redefs [dispatch/handle-line (fn [_ _]
                                      (swap! call-count inc)
                                      (jrpc/result @call-count {}))]
        (run-with-stdin "{}\n{}\n" base-opts)
        (should= 2 @call-count))))

  (it "skips nil responses (notifications with no response)"
    (with-redefs [dispatch/handle-line (fn [_ _] nil)]
      (let [{:keys [output exit]} (run-with-stdin "{}\n" base-opts)]
        (should= 0 exit)
        (should= "" (str/trim output)))))

  (it "writes notification messages before the response in an envelope"
    (let [notif {:jsonrpc "2.0" :method "progress" :params {}}
          resp  (jrpc/result 1 {})]
      (with-redefs [dispatch/handle-line (fn [_ _] {:response resp :notifications [notif]})]
        (let [{:keys [output]} (run-with-stdin "{}\n" base-opts)]
          (should (str/includes? output "progress"))
          (should (str/includes? output "\"id\":1"))))))

  (it "prints a ready signal to stderr on startup"
    (let [{:keys [stderr exit]} (run-with-stdin "" base-opts)]
      (should= 0 exit)
      (should (str/includes? stderr "isaac acp ready"))))

  (it "logs inbound method names when --verbose is enabled"
    (let [{:keys [stderr exit]} (run-with-stdin (jrpc/request-line 1 "initialize" {:protocolVersion 1})
                                              (assoc base-opts :verbose true))]
      (should= 0 exit)
      (should (str/includes? stderr "initialize"))))

  (it "returns the attached session key for session/new when --session exists"
    (let [state-dir    (str "/test/acp-attached-" (random-uuid))
          session-key  "user1"
          _            (helper/create-session! state-dir session-key)
          request      (jrpc/request-line 1 "session/new" {})
          {:keys [output exit]} (run-with-stdin request (assoc base-opts :state-dir state-dir :session session-key))]
      (should= 0 exit)
      (should (str/includes? output "\"sessionId\":\"user1\""))))

  (it "fails when --session session does not exist"
    (let [missing "nonexistent"
          {:keys [stderr exit]} (run-with-stdin "" (assoc base-opts :session missing :state-dir "/test/acp-missing"))]
      (should= 1 exit)
      (should (str/includes? stderr "session not found"))
      (should (str/includes? stderr missing))))

  (it "uses --agent when creating a new session"
    (let [opts                (assoc base-opts :agent "bosun"
                                     :agents {"main" {:name "main" :soul "You are Isaac." :model "grover"}
                                               "bosun" {:name "bosun" :soul "You are a pirate." :model "grover"}})
          request             (jrpc/request-line 1 "session/new" {})
          {:keys [output exit]} (run-with-stdin request opts)]
      (should= 0 exit)
      (should (str/includes? output "sessionId"))))

  (it "fails when --model alias is unknown"
    (let [{:keys [stderr exit]} (run-with-stdin "" (assoc base-opts :model "nonexistent"))]
      (should= 1 exit)
      (should (str/includes? stderr "unknown model"))
      (should (str/includes? stderr "nonexistent"))))

  (it "proxies requests over a remote websocket connection"
    (let [request  (jrpc/request-line 1 "initialize" {:protocolVersion 1})
          rq       (LinkedBlockingQueue.)
          conn     (reify ws/WsConnection
                     (ws-send!    [_ _]
                       (.put rq "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}")
                       nil)
                     (ws-receive! [_]
                       (let [v (.take rq)]
                         (when (not= ::eof v) v)))
                     (ws-receive! [_ timeout-ms]
                       (let [v (.poll rq timeout-ms TimeUnit/MILLISECONDS)]
                         (when (and v (not= ::eof v)) v)))
                     (ws-close!   [_]   (.offer rq ::eof) nil))
          {:keys [output exit]} (run-with-stdin request
                                                (assoc base-opts
                                                  :remote "ws://test/acp"
                                                  :acp-proxy-eof-grace-ms 0
                                                  :ws-connection-factory (fn [_ _] conn)))]
      (should= 0 exit)
      (should (str/includes? output "\"id\":1"))))

  (it "fails with a clear error when the remote connection cannot be opened"
    (let [{:keys [stderr exit]} (run-with-stdin ""
                                                (assoc base-opts
                                                  :remote "ws://localhost:9999/acp"
                                                  :acp-proxy-max-reconnects 0
                                                  :acp-proxy-eof-grace-ms 0
                                                  :ws-connection-factory (fn [_ _]
                                                                           (throw (ex-info "boom" {})))))]
      (should= 1 exit)
      (should (str/includes? stderr "could not connect"))))

  (it "uses the most recent session when --resume is set"
    (let [state-dir    (str "/test/acp-resume-" (random-uuid))
          older        "older"
          recent       "recent"
          _            (helper/create-session! state-dir older)
          _            (helper/create-session! state-dir recent)
          _            (helper/update-session! state-dir older {:updated-at "2026-04-10T10:00:00"})
          _            (helper/update-session! state-dir recent {:updated-at "2026-04-12T15:00:00"})
          request      (jrpc/request-line 1 "session/new" {})
          {:keys [output exit]} (run-with-stdin request (assoc base-opts :state-dir state-dir :resume true))]
      (should= 0 exit)
      (should= recent (get-in (json/parse-string output true) [:result :sessionId]))))

  (it "rejects combining --resume with --model"
    (let [{:keys [stderr exit]} (run-with-stdin "" (assoc base-opts :resume true :model "grover"))]
      (should= 1 exit)
      (should (str/includes? stderr "cannot combine --resume with --model"))))

  (describe "run-fn"

    (it "prints command help and returns 0 when --help is requested"
      (with-redefs [sut/parse-option-map (fn [_] {:options {:help true} :errors []})
                    registry/get-command (fn [_] {:name "acp"})
                    registry/command-help (fn [_] "acp help")]
        (let [output (with-out-str (should= 0 (sut/run-fn {:_raw-args ["--help"]})))]
          (should (str/includes? output "acp help")))))

    (it "prints parse errors and returns 1"
      (with-redefs [sut/parse-option-map (fn [_] {:options {} :errors ["bad arg"]})]
        (let [output (with-out-str (should= 1 (sut/run-fn {:_raw-args ["--bogus"]})))]
          (should (str/includes? output "bad arg")))))

    (it "delegates to run with parsed options merged into opts"
      (let [captured (atom nil)]
        (with-redefs [sut/parse-option-map (fn [_] {:options {:resume true} :errors []})
                      sut/run              (fn [opts]
                                             (reset! captured opts)
                                             0)]
          (should= 0 (sut/run-fn {:_raw-args ["--resume"] :home "/tmp/home"}))
          (should= {:home "/tmp/home" :resume true} @captured)))))

  (it "adds model and crew query params when proxying to a remote server"
    (let [captured-url (atom nil)
          {:keys [exit]} (run-with-stdin ""
                                         (assoc base-opts
                                           :remote "ws://test/acp"
                                           :crew "ketch"
                                           :model "grover2"
                                           :acp-proxy-eof-grace-ms 0
                                           :ws-connection-factory (fn [url _]
                                                                    (reset! captured-url url)
                                                                    (reify ws/WsConnection
                                                                      (ws-send! [_ _] nil)
                                                                      (ws-receive! [_] nil)
                                                                      (ws-receive! [_ _] nil)
                                                                      (ws-close! [_] nil)))))]
      (should= 0 exit)
      (should= "ws://test/acp?model=grover2&crew=ketch" @captured-url)))

  (it "adds resume query param when proxying to a remote server"
    (let [captured-url (atom nil)
          {:keys [exit]} (run-with-stdin ""
                                         (assoc base-opts
                                           :remote "ws://test/acp"
                                           :resume true
                                           :acp-proxy-eof-grace-ms 0
                                           :ws-connection-factory (fn [url _]
                                                                    (reset! captured-url url)
                                                                    (reify ws/WsConnection
                                                                      (ws-send! [_ _] nil)
                                                                      (ws-receive! [_] nil)
                                                                      (ws-receive! [_ _] nil)
                                                                      (ws-close! [_] nil)))))]
        (should= 0 exit)
        (should= "ws://test/acp?resume=true" @captured-url)))

  (it "adds session query param when proxying to a remote server"
    (let [captured-url (atom nil)
          {:keys [exit]} (run-with-stdin ""
                                         (assoc base-opts
                                           :remote "ws://test/acp"
                                           :session "tidy-comet"
                                           :acp-proxy-eof-grace-ms 0
                                           :ws-connection-factory (fn [url _]
                                                                    (reset! captured-url url)
                                                                    (reify ws/WsConnection
                                                                      (ws-send! [_ _] nil)
                                                                      (ws-receive! [_] nil)
                                                                      (ws-receive! [_ _] nil)
                                                                      (ws-close! [_] nil)))))]
      (should= 0 exit)
      (should= "ws://test/acp?session=tidy-comet" @captured-url)))

  (it "logs proxy lifecycle and forwarded initialize requests"
    (let [request (jrpc/request-line 1 "initialize" {:protocolVersion 1})
          rq      (LinkedBlockingQueue.)
          conn    (reify ws/WsConnection
                    (ws-send!    [_ _]
                      (.put rq "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}")
                      nil)
                    (ws-receive! [_]
                      (let [v (.take rq)]
                        (when (not= ::eof v) v)))
                    (ws-receive! [_ timeout-ms]
                      (let [v (.poll rq timeout-ms TimeUnit/MILLISECONDS)]
                        (when (and v (not= ::eof v)) v)))
                    (ws-close!   [_]   (.offer rq ::eof) nil))]
      (log/capture-logs
        (let [{:keys [exit]} (run-with-stdin request
                                             (assoc base-opts
                                               :remote "ws://test/acp"
                                               :acp-proxy-eof-grace-ms 0
                                               :ws-connection-factory (fn [_ _] conn)))]
          (should= 0 exit)
          (should= [:acp-proxy/connected :acp-proxy/initialize :acp-proxy/disconnected]
                   (mapv :event @log/captured-logs))))))

  (it "swallows server-side $/heartbeat notifications instead of forwarding them to Toad"
    ;; The server emits a tiny $/heartbeat keepalive every 30s. It's
    ;; meant for the proxy ↔ server WebSocket layer; it should not
    ;; reach Toad. If it does, Toad doesn't recognize the method and
    ;; bounces back a JSON-RPC "Method not found" error per frame —
    ;; useless noise that also goes back over the wire to the server.
    (let [request (jrpc/request-line 1 "initialize" {:protocolVersion 1})
          rq      (LinkedBlockingQueue.)
          conn    (reify ws/WsConnection
                    (ws-send!    [_ _]
                      (.put rq "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}")
                      (.put rq "{\"jsonrpc\":\"2.0\",\"method\":\"$/heartbeat\"}")
                      nil)
                    (ws-receive! [_]
                      (let [v (.take rq)]
                        (when (not= ::eof v) v)))
                    (ws-receive! [_ timeout-ms]
                      (let [v (.poll rq timeout-ms TimeUnit/MILLISECONDS)]
                        (when (and v (not= ::eof v)) v)))
                    (ws-close!   [_]   (.offer rq ::eof) nil))]
      (let [{:keys [exit output]} (run-with-stdin request
                                                  (assoc base-opts
                                                    :remote "ws://test/acp"
                                                    :acp-proxy-eof-grace-ms 0
                                                    :ws-connection-factory (fn [_ _] conn)))]
        (should= 0 exit)
        ;; Initialize response still reaches Toad…
        (should (str/includes? output "protocolVersion"))
        ;; …but the heartbeat must not leak through.
        (should-not (str/includes? output "$/heartbeat")))))

  (it "reconnects after a dropped remote connection and emits status notifications"
    (let [transport (ws/reconnectable-loopback)
          state-dir (str "/test/acp-proxy-status-" (random-uuid))
          request-1 (jrpc/request-line 1 "initialize" {:protocolVersion 1})
          request-2 (jrpc/request-line 2 "initialize" {:protocolVersion 1})
          _         (helper/create-session! state-dir "s1")
          runner*   (future
                      (run-with-stdin (str request-1 request-2)
                                      (assoc base-opts
                                        :state-dir state-dir
                                        :remote "ws://test/acp"
                                        :acp-proxy-reconnect-delay-ms 0
                                        :ws-connection-factory (fn [url _] (ws/connect-loopback! transport url)))))]
      (let [server-1 (ws/accept-loopback! transport)]
        (should= request-1 (str (ws/ws-receive! server-1 20) "\n"))
        (ws/ws-send! server-1 "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}")
        (ws/drop-loopback! transport))
      (ws/restore-loopback! transport)
      (let [server-2 (ws/accept-loopback! transport)]
        (should= request-2 (str (ws/ws-receive! server-2 50) "\n"))
         (ws/ws-send! server-2 "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"protocolVersion\":1}}")
         (ws/ws-close! server-2))
        (let [{:keys [output exit]} @runner*]
          (should= 0 exit)
          (should (str/includes? output "remote connection lost"))
          (should (str/includes? output "reconnected to remote"))
          (should (str/includes? output "\"id\":2")))))

  (it "does not exit reconnect mode when closing an already-dead remote socket throws"
    (let [scheduler-instance (-> (scheduler/create {:pool-size 1})
                                 (assoc :tick-ms 1)
                                 (scheduler/start!))
          active?            (atom false)
          conn*              (atom (reify ws/WsConnection
                                     (ws-send! [_ _] nil)
                                     (ws-receive! [_] nil)
                                     (ws-receive! [_ _] nil)
                                     (ws-close! [_]
                                       (throw (ex-info "socket already closed" {})))))
          remote-queue*      (atom nil)
          disconnected?      (atom false)
          session-id*        (atom nil)]
      (try
        (#'sut/connection-lost! scheduler-instance active? conn* remote-queue* disconnected? session-id* (atom nil)
                                (fn [_ _]
                                  (reify ws/WsConnection
                                    (ws-send! [_ _] nil)
                                    (ws-receive! [_] nil)
                                    (ws-receive! [_ _] nil)
                                    (ws-close! [_] nil)))
                                "ws://test/acp" nil {:acp-proxy-reconnect-delay-ms 0
                                                     :acp-proxy-reconnect-max-delay-ms 0})
        (should @disconnected?)
        (finally
          (scheduler/shutdown! scheduler-instance)))))
