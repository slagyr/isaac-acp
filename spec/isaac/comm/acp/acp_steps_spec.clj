(ns isaac.comm.acp.acp-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.comm.acp.acp-steps :as steps]
    [isaac.llm.api.grover :as grover]
    [speclj.core :refer :all]))

(def ^:private async-prompt-table
  {:headers ["key" "value"]
   :rows    [["method" "session/prompt"]
             ["params.sessionId" "cancel-test"]
             ["params.prompt[0].type" "text"]
             ["params.prompt[0].text" "Long task"]]})

(defn- delay-started-atom []
  @(ns-resolve 'isaac.llm.api.grover 'delay-started*))

(defn- stub-blocking-dispatch! [release]
  (g/assoc! :acp-dispatch-fn (fn [_]
                               (deref release 2000 ::timeout)
                               {:jsonrpc "2.0" :id 30 :result {:stopReason "end_turn"}})))

(describe "ACP notification steps"
  (before (g/reset!))

  (it "rejects an unexpected trailing session/update after the expected sequence"
    (let [table {:headers ["method" "params.update.sessionUpdate" "params.update.content.text"]
                 :rows [["session/update" "user_message_chunk" "a"]
                        ["session/update" "agent_message_chunk" "b"]]}
          n1    {:jsonrpc "2.0" :method "session/update" :params {:update {:sessionUpdate "user_message_chunk" :content {:type "text" :text "a"}}}}
          n2    {:jsonrpc "2.0" :method "session/update" :params {:update {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "b"}}}}
          thrown? (atom false)]
      (steps/enqueue-notification-for-test! n1)
      (steps/enqueue-notification-for-test! n2)
      (steps/enqueue-spurious-session-update!)
      (try
        (steps/acp-agent-sends-notifications table)
        (catch Exception _ (reset! thrown? true)))
      (should @thrown?)))
  )

(describe "ACP async prompt cancellation gate"
  (before (g/reset!)
          (grover/reset-queue!))

  (it "does not return from async session/prompt until grover delay has started"
    (let [release (promise)
          started* (delay-started-atom)]
      (stub-blocking-dispatch! release)
      (grover/enable-delay!)
      (reset! started* nil)
      (let [caller (future (steps/acp-client-sends-request-async 30 async-prompt-table))]
        (should= :timeout (deref caller 50 :timeout))
        (reset! started* (doto (promise) (deliver true)))
        (should-not= :timeout (deref caller 1000 :timeout))
        (deliver release true))))

  (it "returns from async session/prompt without waiting when grover delay is not enabled"
    (let [release (promise)
          started* (delay-started-atom)]
      (stub-blocking-dispatch! release)
      (reset! started* nil)
      (let [caller (future (steps/acp-client-sends-request-async 30 async-prompt-table))]
        (should-not= :timeout (deref caller 1000 :timeout))
        (deliver release true))))
  )
