(ns isaac.comm.acp.cli-proxy-pipelining-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.comm.acp.cli :as sut]
    [isaac.fs :as fs]
    [isaac.spec-helper :as helper]
    [isaac.session.spec-helper :as session-helper]
    [isaac.system :as system]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.util.ws-client :as ws]
    [speclj.core :refer :all])
  (:import
    (java.io BufferedReader StringReader StringWriter)
    (java.util.concurrent LinkedBlockingQueue)))

(def base-opts
  {:state-dir "/test/acp-proxy"
   :agents    {"main" {:name "main" :soul "You are Isaac." :model "grover"}}
   :models    {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}})

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

(describe "ACP proxy pipelining"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (session-helper/with-memory-store
      (system/with-nested-system {:fs (fs/mem-fs)}
        (it))))

  (it "forwards a cancel notification to the remote WS without waiting for the prompt response"
    (let [transport    (ws/reconnectable-loopback)
          queue        (LinkedBlockingQueue.)
          prompt       (jrpc/request-line 1 "session/prompt" {:sessionId "s1"}
                                          [{:type "text" :text "hello"}])
          cancel       (jrpc/notification-line "session/cancel" {:sessionId "s1"})
          {:keys [future]} (run-with-queue queue
                                           (assoc base-opts
                                             :remote "ws://test/acp"
                                             :acp-proxy-eof-grace-ms 20
                                             :ws-connection-factory (fn [url _] (ws/connect-loopback! transport url))))]
      (let [server-conn (ws/accept-loopback! transport)]
        (.put queue prompt)
        (should= prompt (ws/ws-receive! server-conn 200))
        (.put queue cancel)
        (should= cancel (ws/ws-receive! server-conn 200))
        (ws/ws-send! server-conn "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"stopReason\":\"end_turn\"}}")
        (ws/ws-close! server-conn))
      (.put queue ::eof)
      (let [result (deref future 2000 ::timeout)]
        (when (= ::timeout result) (future-cancel future))
        (should-not= ::timeout result)
        (should= 0 (:exit result)))))

  (it "forwards multiple notifications in order without blocking on an in-flight request"
    (let [transport    (ws/reconnectable-loopback)
          queue        (LinkedBlockingQueue.)
          prompt       (jrpc/request-line 1 "session/prompt" {:sessionId "s1"}
                                          [{:type "text" :text "hello"}])
          cancel-1     (jrpc/notification-line "session/cancel" {:sessionId "s1"})
          cancel-2     (jrpc/notification-line "session/cancel" {:sessionId "s1"})
          {:keys [future]} (run-with-queue queue
                                           (assoc base-opts
                                             :remote "ws://test/acp"
                                             :acp-proxy-eof-grace-ms 20
                                             :ws-connection-factory (fn [url _] (ws/connect-loopback! transport url))))]
      (let [server-conn (ws/accept-loopback! transport)]
        (.put queue prompt)
        (.put queue cancel-1)
        (.put queue cancel-2)
        (should= prompt   (ws/ws-receive! server-conn 200))
        (should= cancel-1 (ws/ws-receive! server-conn 200))
        (should= cancel-2 (ws/ws-receive! server-conn 200))
        (ws/ws-send! server-conn "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"stopReason\":\"end_turn\"}}")
        (ws/ws-close! server-conn))
      (.put queue ::eof)
      (let [result (deref future 2000 ::timeout)]
        (when (= ::timeout result) (future-cancel future))
        (should-not= ::timeout result)
        (should= 0 (:exit result))))))
