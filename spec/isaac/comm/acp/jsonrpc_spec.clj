(ns isaac.comm.acp.jsonrpc-spec
  (:require
    [isaac.comm.acp.jsonrpc :as jsonrpc]
    [speclj.core :refer :all]))

(describe "acp jsonrpc"
  (describe "session-update"
    (it "builds a session/update notification"
      (should= {:jsonrpc "2.0"
                :method  "session/update"
                :params  {:sessionId "session-1"
                          :update    {:sessionUpdate "agent_message_chunk"
                                      :content {:type "text" :text "hello"}}}}
               (jsonrpc/session-update "session-1"
                                       {:sessionUpdate "agent_message_chunk"
                                        :content {:type "text" :text "hello"}})))))
