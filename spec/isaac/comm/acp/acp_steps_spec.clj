(ns isaac.comm.acp.acp-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.comm.acp.acp-steps :as steps]
    [speclj.core :refer :all]))

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
      (should @thrown?))))
