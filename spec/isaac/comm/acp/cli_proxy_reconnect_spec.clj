(ns isaac.comm.acp.cli-proxy-reconnect-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.comm.acp.cli :as sut]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.util.ws-client :as ws]
    [isaac.fs :as fs]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all])
  (:import
    (java.io BufferedReader StringReader StringWriter)
    (java.util.concurrent LinkedBlockingQueue)))

(def base-opts
  {:state-dir "/test/acp-proxy"
   :agents    {"main" {:name "main" :soul "You are Isaac." :model "grover"}}
   :models    {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}})

(defn- output-messages [output]
  (->> (str/split-lines output)
       (remove str/blank?)
       (mapv #(json/parse-string % true))))

(defn- queued-read-line [queue]
  (fn []
    (let [value (.take ^LinkedBlockingQueue queue)]
      (when-not (= ::eof value)
        value))))

(defn- run-with-queue [queue opts]
  (let [result        (atom nil)
        output-writer (StringWriter.)
        error-writer  (StringWriter.)
        fut           (future
                        (binding [*in*  (BufferedReader. (StringReader. ""))
                                  *out* output-writer
                                  *err* error-writer]
                          (reset! result (sut/run (assoc opts :acp-read-line-fn (queued-read-line queue)))))
                        {:exit   @result
                         :output (str output-writer)
                         :stderr (str error-writer)})]
    {:future fut :output-writer output-writer}))

(defn- await-lines
  "Block until the writer has at least n non-blank lines or 1 s elapses."
  [^StringWriter writer n]
  (helper/await-condition #(<= n (count (remove str/blank? (str/split-lines (str writer)))))))

(describe "ACP proxy reconnect"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (helper/with-memory-store
      (binding [fs/*fs* (fs/mem-fs)]
        (it))))

  (it "caps the exponential reconnect delay at the configured max"
    (should= 5 (#'sut/reconnect-delay-ms 4 {:acp-proxy-reconnect-delay-ms 1
                                            :acp-proxy-reconnect-max-delay-ms 5})))

  (it "emits ACP-conformant disconnect and reconnect notifications"
    (let [transport                  (ws/reconnectable-loopback)
          state-dir                  (str "/test/acp-proxy-reconnect-" (random-uuid))
          queue                      (LinkedBlockingQueue.)
          _                          (helper/create-session! state-dir "s1")
          {:keys [future
                  output-writer]}    (run-with-queue queue
                                                     (assoc base-opts
                                                       :remote "ws://test/acp"
                                                       :state-dir state-dir
                                                       :acp-proxy-reconnect-delay-ms 1
                                                       :acp-proxy-reconnect-max-delay-ms 2
:ws-connection-factory (fn [url _] (ws/connect-loopback! transport url))))]
      (ws/accept-loopback! transport)
      (ws/drop-loopback! transport)
      (await-lines output-writer 1)
      (ws/restore-loopback! transport)
      (ws/accept-loopback! transport)
      (await-lines output-writer 2)
      (.put queue ::eof)
      (let [result (deref future 2000 ::timeout)]
        (when (= ::timeout result)
          (future-cancel future))
        (should-not= ::timeout result)
        (let [messages (output-messages (:output result))]
          (should= ["session/update" "session/update"] (mapv :method messages))
          (should= ["s1" "s1"] (mapv #(get-in % [:params :sessionId]) messages))
          (should= ["agent_thought_chunk" "agent_thought_chunk"]
                   (mapv #(get-in % [:params :update :sessionUpdate]) messages))
          (should= ["remote connection lost\n\n" "reconnected to remote\n\n"]
                   (mapv #(get-in % [:params :update :content :text]) messages))))))

  (it "waits for reconnect and forwards a request that arrives during disconnect"
    (let [transport               (ws/reconnectable-loopback)
           state-dir               (str "/test/acp-proxy-disconnect-" (random-uuid))
           queue                   (LinkedBlockingQueue.)
           request                 (jrpc/request-line 42 "session/prompt" {:sessionId "s1"}
                                                     [{:type "text" :text "hello"}])
          {:keys [future
                  output-writer]} (run-with-queue queue
                                                  (assoc base-opts
                                                    :remote "ws://test/acp"
                                                    :state-dir state-dir
                                                    :acp-proxy-reconnect-delay-ms 1
                                                    :acp-proxy-reconnect-max-delay-ms 2
                                                    :acp-proxy-eof-grace-ms 0
                                                    :ws-connection-factory (fn [url _] (ws/connect-loopback! transport url))))]
      (helper/create-session! state-dir "s1")
      (ws/accept-loopback! transport)
      (ws/drop-loopback! transport)
      (await-lines output-writer 1)
      (.put queue request)
      (ws/restore-loopback! transport)
      (let [server-2 (ws/accept-loopback! transport)]
        (should= request (ws/ws-receive! server-2 50))
        (ws/ws-send! server-2 "{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"stopReason\":\"end_turn\"}}")
        (ws/ws-close! server-2))
      (await-lines output-writer 3)
      (.put queue ::eof)
      (let [result (deref future 2000 ::timeout)]
        (when (= ::timeout result)
          (future-cancel future))
        (should-not= ::timeout result)
        (let [messages (output-messages (:output result))
              response (some #(when (= 42 (:id %)) %) messages)]
          (should= ["remote connection lost\n\n" "reconnected to remote\n\n"]
                   (mapv #(get-in % [:params :update :content :text]) (take 2 messages)))
          (should-not-be-nil response)
          (should= 42 (:id response))
          (should= "end_turn" (get-in response [:result :stopReason])))))))
