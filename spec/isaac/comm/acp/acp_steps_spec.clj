(ns isaac.comm.acp.acp-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.comm.acp.acp-steps :as sut]
    [speclj.core :refer :all])
  (:import
    (java.io StringWriter)))

(describe "acp feature steps"

  (around [it]
    (g/reset!)
    (it)
    (g/reset!))

  (it "preserves interleaved responses while matching notifications from output"
    (let [writer (StringWriter.)]
      (.write writer "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"text\":\"remote connection lost\\n\\n\"}}}}\n")
      (.write writer "{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"stopReason\":\"end_turn\"}}\n")
      (.write writer "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"text\":\"reconnected to remote\\n\\n\"}}}}\n")
      (g/assoc! :live-output-writer writer)
      (g/assoc! :acp-output-offset 0)
      (should= "remote connection lost\n\n"
               (get-in (@#'sut/await-message #(contains? % :method)) [:params :update :content :text]))
      (should= "reconnected to remote\n\n"
               (get-in (@#'sut/await-message #(contains? % :method)) [:params :update :content :text]))
      (should= 42 (:id (@#'sut/await-message #(= 42 (:id %)))))))

  (it "matches the later successful response when an earlier response with the same id does not fit"
    (let [writer (StringWriter.)]
      (.write writer "{\"jsonrpc\":\"2.0\",\"id\":42,\"error\":{\"code\":-32099,\"message\":\"remote connection lost, reconnecting\"}}\n")
      (.write writer "{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"stopReason\":\"end_turn\"}}\n")
      (g/assoc! :live-output-writer writer)
      (g/assoc! :acp-output-offset 0)
      (@#'sut/acp-agent-sends-response 42 {:rows [["result.stopReason" "end_turn"]]}))))
