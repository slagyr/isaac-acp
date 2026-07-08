(ns isaac.comm.acp.cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g]
    [isaac.comm.acp.acp-steps :as acp-steps]
    [isaac.comm.acp.cli :as sut]
    [isaac.config.root :as home]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.util.jsonrpc :as dispatch]
    [isaac.cli.registry :as registry]
    [isaac.fs :as fs]
    [isaac.main :as main]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.spec-helper :as session-helper]
    [isaac.system :as system]
    [speclj.core :refer :all])
  (:import
    (java.io BufferedReader StringReader StringWriter)
    ))

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

(defn- write-root-config! [root data]
  (let [path (str root "/config/isaac.edn")
        fs*  (system/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (pr-str data))))

(defn- with-user-home [path f]
  (let [original (System/getProperty "user.home")]
    (try
      (System/setProperty "user.home" path)
      (f)
      (finally
        (System/setProperty "user.home" original)))))

(defn- run-main! [argv opts]
  (let [stdin-content  (or (:stdin opts) "")
        output-writer  (StringWriter.)
        error-writer   (StringWriter.)
        home-override  (:home opts)
        extra-opts     (dissoc opts :stdin :home)]
    (registry/register! (sut/make-command))
    (binding [*in*               (BufferedReader. (StringReader. stdin-content))
              *out*              output-writer
              *err*              error-writer
              home/*user-home*   (or home-override home/*user-home*)
              main/*extra-opts*  extra-opts]
      {:exit   (main/run argv)
       :output (str output-writer)
       :stderr (str error-writer)})))

(describe "ACP CLI"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (system/with-system {:config (atom nil)}
      (session-helper/with-memory-store (mem-run it))))

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

  (it "prefers the explicit --root over user-home-derived defaults via main/run"
    (let [explicit-root "/tmp/acp-explicit-root/.isaac"
          state-dir     "/tmp/acp-ignored-home/.isaac"
          session-id    "root-wins"]
      (delete-tree! "/tmp/acp-explicit-root")
      (delete-tree! "/tmp/acp-ignored-home")
      (with-user-home "/tmp/acp-ignored-home"
        (fn []
          (write-root-config! explicit-root {:defaults  {:crew "main" :model "grover"}
                                             :crew      {"main" {:soul "You are Isaac." :model "grover"}}
                                             :models    {"grover" {:model "echo" :provider "grover"}}
                                             :providers {"grover" {}}})
          (session-helper/create-session! explicit-root session-id {:crew "main"})
          (session-helper/create-session! state-dir session-id {:crew "main"})
          (let [server-opts (#'sut/build-server-opts {:root explicit-root})]
            (should= explicit-root (:state-dir server-opts)))
          (let [{:keys [output exit]} (run-main! ["--root" explicit-root "acp" "--session" session-id]
                                                 {:stdin (str (jrpc/request-line 1 "initialize" {:protocolVersion 1})
                                                              (jrpc/request-line 2 "session/prompt" {:sessionId session-id
                                                                                                     :prompt [{:type "text" :text "hi"}]}))})]
            (should= 0 exit)
            (should-not (str/includes? output "no model configured for crew: main")))))))

  (it "returns 0 when stdin is empty"
    (should= 0 (:exit (run-with-stdin "" base-opts))))

  (it "returns a no-model error via main/run when crew resolution yields no model"
    (let [home-dir   "/tmp/acp-main-no-model"
          state-dir  (str home-dir "/.isaac")
          session-id "no-model"]
      (delete-tree! home-dir)
      (write-config! home-dir {:crew {:defaults {}}})
      (session-helper/create-session! state-dir session-id)
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
        (session-helper/create-session! state-dir session-id)
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

  (it "replays transcript on stdout for attached session/new when --session exists"
    (let [state-dir    (str "/test/acp-attached-" (random-uuid))
          session-key  "user1"
          _            (session-helper/create-session! state-dir session-key)
          _            (session-helper/append-message! state-dir session-key {:role "user" :content "hi"})
          _            (session-helper/append-message! state-dir session-key {:role "assistant" :content "there"})
          request      (jrpc/request-line 2 "session/new" {})
          {:keys [output exit]} (run-with-stdin request (assoc base-opts :state-dir state-dir :session session-key))]
      (should= 0 exit)
      (should (str/includes? output "user_message_chunk"))
      (should (str/includes? output "agent_message_chunk"))
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
          (should= {:home "/tmp/home" :resume true} @captured))))


  )
