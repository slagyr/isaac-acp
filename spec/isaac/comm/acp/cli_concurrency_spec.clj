(ns isaac.comm.acp.cli-concurrency-spec
  "The stdio loop must keep reading while a session/prompt runs, so a
   session/cancel reaches the running turn (ACP: the prompt then answers
   stopReason \"cancelled\")."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.comm.acp.cli :as sut]
    [isaac.comm.acp.system :as system]
    [isaac.foundation.fs :as fs]
    [isaac.agent.session.spec-helper :as session-helper]
    [isaac.agent.util.jsonrpc :as dispatch]
    [isaac.agent.util.jsonrpc :as jrpc]
    [speclj.core :refer :all])
  (:import
    (java.io BufferedReader StringReader StringWriter)))

(def ^:private base-opts
  {:state-dir        "/test/acp-concurrency"
   :crew             "main"
   :agents           {"main" {:name "main" :soul "You are Isaac." :model "grover"}}
   :models           {"grover" {:alias "grover" :model "echo" :provider "grover" :context-window 32768}}
   :provider-configs {"grover" {:api "grover" :auth "none"}}})

(def ^:private watchdog-ms 5000)

(defn- lines [& messages]
  (str (str/join "\n" messages) "\n"))

(defn- prompt-line [id session text]
  (jrpc/request-line id "session/prompt" {:sessionId session :prompt [{:type "text" :text text}]}))

(defn- cancel-line [session]
  (json/generate-string (jrpc/notification "session/cancel" {:sessionId session})))

(defn- run-with-stdin
  "Runs the stdio loop on stdin in a background thread. If it has not
   finished within the watchdog, `unstick!` is called (so a blocked loop
   can drain) and :timed-out? is reported."
  [stdin opts unstick!]
  (let [out   (StringWriter.)
        err   (StringWriter.)
        run   (bound-fn []
                (binding [*in*  (BufferedReader. (StringReader. stdin))
                          *out* out
                          *err* err]
                  (sut/run opts)))
        start (System/currentTimeMillis)
        exit  (future (run))
        done  (deref exit watchdog-ms ::timeout)
        timed-out? (= ::timeout done)]
    (when timed-out? (unstick!))
    {:exit       (if timed-out? @exit done)
     :timed-out? timed-out?
     :elapsed-ms (- (System/currentTimeMillis) start)
     :messages   (->> (str/split-lines (str out))
                      (remove str/blank?)
                      (mapv #(json/parse-string % true)))}))

(defn- response [messages id]
  (first (filter #(= id (:id %)) messages)))

(describe "ACP CLI concurrency"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (system/with-system {:config (atom nil)}
      (session-helper/with-memory-store
        (system/with-nested-system {:fs (fs/mem-fs)}
          (it)))))

  (it "handles session/cancel while session/prompt is still running"
    (let [cancelled (promise)]
      (with-redefs [dispatch/handle-line
                    (fn [_ line]
                      (let [{:keys [id method]} (json/parse-string line true)]
                        (case method
                          "session/prompt" (jrpc/result id {:stopReason (if (deref cancelled 2000 false) "cancelled" "end_turn")})
                          "session/cancel" (do (deliver cancelled true) nil)
                          nil)))]
        (let [{:keys [messages timed-out? exit elapsed-ms]}
              (run-with-stdin (lines (prompt-line 3 "cancel-me" "think for a long time")
                                     (cancel-line "cancel-me"))
                              base-opts
                              (fn []))]
          (should-not timed-out?)
          (should= 0 exit)
          (should= "cancelled" (get-in (response messages 3) [:result :stopReason]))
          (should (< elapsed-ms 1500))))))

  (it "keeps responses for one session in request order"
    (with-redefs [dispatch/handle-line (fn [_ line]
                                         (let [{:keys [id method]} (json/parse-string line true)]
                                           (when (= 2 id) (Thread/sleep 200))
                                           (when id (jrpc/result id {:method method}))))]
      (let [{:keys [messages]} (run-with-stdin (lines (prompt-line 2 "s1" "slow")
                                                      (prompt-line 3 "s1" "fast"))
                                               base-opts
                                               (fn []))]
        (should= [2 3] (mapv :id messages)))))

  (it "writes every message as one whole JSON line when prompts overlap"
    (with-redefs [dispatch/handle-line (fn [_ line]
                                         (let [{:keys [id params]} (json/parse-string line true)
                                               sid (:sessionId params)]
                                           (dotimes [i 200]
                                             (jrpc/write-message! *out* (jrpc/notification "session/update"
                                                                                           {:sessionId sid :n i})))
                                           (jrpc/result id {:sessionId sid})))]
      (let [{:keys [messages]} (run-with-stdin (lines (prompt-line 2 "s1" "a") (prompt-line 3 "s2" "b"))
                                               base-opts
                                               (fn []))]
        (should= 402 (count messages))
        (should= #{2 3} (set (keep :id messages))))))

  (it "finishes a running prompt before exiting at end of input"
    (with-redefs [dispatch/handle-line (fn [_ line]
                                         (let [{:keys [id]} (json/parse-string line true)]
                                           (Thread/sleep 150)
                                           (jrpc/result id {:stopReason "end_turn"})))]
      (let [{:keys [messages exit]} (run-with-stdin (lines (prompt-line 2 "s1" "x")) base-opts (fn []))]
        (should= 0 exit)
        (should= "end_turn" (get-in (response messages 2) [:result :stopReason]))))))
