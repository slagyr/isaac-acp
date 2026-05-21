(ns isaac.comm.acp-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.comm :as comm]
    [isaac.comm.acp :as sut]
    [isaac.comm.acp.jsonrpc :as jsonrpc]
    [isaac.module.loader :as module-loader]
    [isaac.comm.registry :as registry]
    [isaac.server.routes :as routes]
    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(defn- parsed-output [writer]
  (->> (str/split-lines (str writer))
       (remove str/blank?)
       (mapv #(json/parse-string % true))))

(describe "ACP channel"

  (it "registers the /acp WebSocket route when the ACP module activates"
    (binding [registry/*registry* (atom (registry/fresh-registry))
              routes/*registry*    (atom (routes/fresh-registry))]
      (module-loader/clear-activations!)
      (should-not (routes/route-registered? :get "/acp"))
      (module-loader/activate! :isaac.comm.acp
                               {:isaac.comm.acp
                                {:manifest {:id      :isaac.comm.acp
                                            :version "0.1.0"
                                            :route   {[:get "/acp"]
                                                      'isaac.comm.acp.websocket/handler}}}})
      (should (routes/route-registered? :get "/acp"))))

  (it "exposes the AcpComm constructor and no longer exposes AcpChannel"
    (should-not-throw (requiring-resolve 'isaac.comm.acp/->AcpComm))
    (should-not (resolve 'isaac.comm.acp/->AcpChannel)))

  (it "builds session/update messages through comm.acp.jsonrpc/session-update"
    (let [calls    (atom [])
          writer   (StringWriter.)
          tool-call {:id "tc-1" :name "exec" :arguments {:command "echo hi"}}
          commands [{:name "status"}]
          expected (fn [session-id update]
                     {:jsonrpc "2.0"
                      :method  "session/update"
                      :params  {:sessionId session-id
                                :update    update}})]
      (with-redefs [jsonrpc/session-update (fn [session-id update]
                                             (swap! calls conj [session-id update])
                                             (expected session-id update))]
        (should= (expected "session-1" {:sessionUpdate "agent_message_chunk"
                                         :content       {:type "text" :text "hello"}})
                 (sut/text-update "session-1" "hello"))
        (should= (expected "session-1" {:sessionUpdate "user_message_chunk"
                                         :content       {:type "text" :text "hi"}})
                 (sut/user-text-update "session-1" "hi"))
        (should= (expected "session-1" {:sessionUpdate "agent_thought_chunk"
                                         :content       {:type "text" :text "thinking"}})
                 (sut/thought-update "session-1" "thinking"))
        (should= (expected "session-1" {:sessionUpdate     "available_commands_update"
                                         :availableCommands commands})
                 (sut/available-commands-update "session-1" commands))
        (comm/on-tool-call (sut/channel writer) "session-1" tool-call)
        (should= [["session-1" {:sessionUpdate "agent_message_chunk"
                                 :content       {:type "text" :text "hello"}}]
                  ["session-1" {:sessionUpdate "user_message_chunk"
                                 :content       {:type "text" :text "hi"}}]
                  ["session-1" {:sessionUpdate "agent_thought_chunk"
                                 :content       {:type "text" :text "thinking"}}]
                  ["session-1" {:sessionUpdate     "available_commands_update"
                                 :availableCommands commands}]
                  ["session-1" {:sessionUpdate "tool_call"
                                 :status        "pending"
                                 :toolCallId    "tc-1"
                                 :title         "exec: echo hi"
                                 :kind          "execute"
                                 :rawInput      {:command "echo hi"}}]]
                 @calls))))

  (it "preserves whitespace-bearing text chunks in session/update notifications"
    (let [writer (StringWriter.)
          ch     (sut/channel writer)]
      (comm/on-text-chunk ch "agent:main:acp:direct:user1" "Once ")
      (comm/on-text-chunk ch "agent:main:acp:direct:user1" " ")
      (comm/on-text-chunk ch "agent:main:acp:direct:user1" " upon")
      (let [notifications (parsed-output writer)]
        (should= 3 (count notifications))
        (should= "agent_message_chunk" (get-in (first notifications) [:params :update :sessionUpdate]))
        (should= "Once " (get-in (first notifications) [:params :update :content :text]))
        (should= " " (get-in (second notifications) [:params :update :content :text]))
        (should= " upon" (get-in (nth notifications 2) [:params :update :content :text])))))

  (it "writes compaction start session/update notifications to the output writer"
    (let [writer (StringWriter.)
          ch     (sut/channel writer)]
      (comm/on-compaction-start ch "agent:main:acp:direct:user1" {:provider "grover"
                                                                   :model "echo"
                                                                   :total-tokens 95
                                                                   :context-window 100})
      (let [notifications (parsed-output writer)]
        (should= 1 (count notifications))
        (should= "agent_thought_chunk" (get-in (first notifications) [:params :update :sessionUpdate]))
        (should= "compacting..." (get-in (first notifications) [:params :update :content :text])))))

  (it "writes pending and completed tool notifications with sessionId"
    (let [writer    (StringWriter.)
          tool-call {:id "tc-1" :name "exec" :arguments {:command "echo hi"}}
          ch        (sut/channel writer)]
      (comm/on-tool-call ch "agent:main:acp:direct:user1" tool-call)
      (comm/on-tool-result ch "agent:main:acp:direct:user1" tool-call "hi")
      (let [notifications (parsed-output writer)]
        (should= ["tool_call" "tool_call_update"]
                 (mapv #(get-in % [:params :update :sessionUpdate]) notifications))
        (should= "agent:main:acp:direct:user1" (get-in (first notifications) [:params :sessionId]))
        (should= "agent:main:acp:direct:user1" (get-in (second notifications) [:params :sessionId]))
        (should= "pending" (get-in (first notifications) [:params :update :status]))
        (should= "completed" (get-in (second notifications) [:params :update :status]))))

  (it "writes cancelled tool notifications with sessionId"
    (let [writer    (StringWriter.)
          tool-call {:id "tc-1" :name "exec" :arguments {:command "sleep 30"}}
          ch        (sut/channel writer)]
      (comm/on-tool-call ch "agent:main:acp:direct:user1" tool-call)
      (comm/on-tool-cancel ch "agent:main:acp:direct:user1" tool-call)
      (let [notifications (parsed-output writer)]
        (should= ["tool_call" "tool_call_update"]
                 (mapv #(get-in % [:params :update :sessionUpdate]) notifications))
        (should= "pending" (get-in (first notifications) [:params :update :status]))
        (should= "cancelled" (get-in (second notifications) [:params :update :status]))
        (should= "tc-1" (get-in (second notifications) [:params :update :toolCallId])))))

  (it "formats available commands update notifications"
    (let [notification (sut/available-commands-update "cmd-test" [{:name "status"} {:name "model"} {:name "crew"}])]
      (should= "session/update" (:method notification))
      (should= "cmd-test" (get-in notification [:params :sessionId]))
      (should= "available_commands_update" (get-in notification [:params :update :sessionUpdate]))
      (should= ["status" "model" "crew"]
               (mapv :name (get-in notification [:params :update :availableCommands])))))

  (it "orders built-in slash commands by hard-coded priority then alphabetical others"
    (let [notification (sut/available-commands-update "cmd-test"
                                                       [{:name "zebra"}
                                                        {:name "cwd"}
                                                        {:name "effort"}
                                                        {:name "status"}
                                                        {:name "alpha"}
                                                        {:name "crew"}
                                                        {:name "model"}])]
      (should= ["status" "model" "crew" "cwd" "effort" "alpha" "zebra"]
               (mapv :name (get-in notification [:params :update :availableCommands])))))

  )
  )
