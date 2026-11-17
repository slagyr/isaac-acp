(ns isaac.comm.acp-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.agent.comm.protocol :as comm]
    [isaac.agent.comm.render :as render]
    [isaac.comm.acp :as sut]
    [isaac.comm.acp.jsonrpc :as jsonrpc]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [isaac.comm.acp.system :as system]
    [speclj.core :refer :all])
  (:import (java.io StringWriter)))

(defn- parsed-output [writer]
  (->> (str/split-lines (str writer))
       (remove str/blank?)
       (mapv #(json/parse-string % true))))

(def ^:private acp-v1-tool-kinds
  #{"read" "edit" "delete" "move" "search" "execute" "think" "fetch" "switch_mode" "other"})

(def ^:private acp-v1-tool-call-statuses #{"pending" "in_progress" "completed" "failed"})

;; Keep this independent of the implementation map: new agent built-ins must fail until classified.
(def ^:private expected-tool-kinds
  {"fs__read" "read" "fs__write" "edit" "fs__edit" "edit" "fs__multi_edit" "edit"
   "fs__grep" "search" "fs__glob" "search" "web__fetch" "fetch" "web__search" "search"
   "memory__write" "edit" "memory__get" "read" "memory__search" "search"
   "exec__run" "execute" "timer__set" "other" "timer__list" "read" "timer__cancel" "other"
   "subagent__spawn" "other" "subagent__list" "read" "subagent__cancel" "other"
   "turn__get" "read" "session__info" "read" "session__model" "other"
   "session__list" "read" "session__read" "read" "prompt__load" "read" "prompt__list" "read"
   "comm__send" "other" "hail__send" "other" "provider__quota" "fetch"})

(describe "ACP channel"

  (around [it]
    (let [original-output (log/output)]
      (try
        (log/set-output! :none)
        (it)
        (finally
          (log/set-output! original-output)))))

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
                                 :rawInput      {:command "echo hi"}
                                 :content       [{:type    "content"
                                                  :content {:type "text"
                                                            :text (pr-str {:command "echo hi"})}}]}]]
                 @calls))))

  (it "wraps preformatted slash blocks for markdown clients"
    (let [writer (StringWriter.)
          ch     (sut/channel writer)]
      (comm/on-chatter ch "agent:main:acp:direct:user1" nil
                       (render/preformatted-chunk "Session Status\nCrew main"))
      (let [text (get-in (first (parsed-output writer)) [:params :update :content :text])]
        (should (re-find #"```text" text))
        (should (re-find #"Session Status" text)))))

  (it "preserves whitespace-bearing text chunks in session/update notifications"
    (let [writer (StringWriter.)
          ch     (sut/channel writer)]
      (comm/on-chatter ch "agent:main:acp:direct:user1" nil "Once ")
      (comm/on-chatter ch "agent:main:acp:direct:user1" nil " ")
      (comm/on-chatter ch "agent:main:acp:direct:user1" nil " upon")
      (let [notifications (parsed-output writer)]
        (should= 3 (count notifications))
        (should= "agent_message_chunk" (get-in (first notifications) [:params :update :sessionUpdate]))
        (should= "Once " (get-in (first notifications) [:params :update :content :text]))
        (should= " " (get-in (second notifications) [:params :update :content :text]))
        (should= " upon" (get-in (nth notifications 2) [:params :update :content :text])))))

  (it "writes compaction start session/update notifications to the output writer"
    (let [writer (StringWriter.)
          ch     (sut/channel writer)]
      (comm/on-bulletin ch "agent:main:acp:direct:user1" {:kind    :compaction/start
                                                           :payload {:provider "grover"
                                                                     :model "echo"
                                                                     :total-tokens 95
                                                                     :context-window 100}})
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
        (should= "completed" (get-in (second notifications) [:params :update :status])))))

  (it "repeats title, kind, and rawInput on completed tool_call_update for thin clients"
    (let [writer    (StringWriter.)
          tool-call {:id "tc-1" :name "exec" :arguments {:command "echo tide-signal"}}
          ch        (sut/channel writer)]
      (comm/on-tool-call ch "tool-test" tool-call)
      (comm/on-tool-result ch "tool-test" tool-call "tide-signal")
      (let [update (get-in (second (parsed-output writer)) [:params :update])]
        (should= "exec: echo tide-signal" (:title update))
        (should= "execute" (:kind update))
        (should= {:command "echo tide-signal"} (:rawInput update))
        (should= "tide-signal" (:rawOutput update))
        (should= "tide-signal" (get-in update [:content 0 :content :text])))))

  (it "writes cancelled tool notifications with sessionId"
    (let [writer    (StringWriter.)
          tool-call {:id "tc-1" :name "exec" :arguments {:command "while :; do sleep 1; done"}}
          ch        (sut/channel writer)]
      (comm/on-tool-call ch "agent:main:acp:direct:user1" tool-call)
      (comm/on-tool-cancel ch "agent:main:acp:direct:user1" tool-call)
      (let [notifications (parsed-output writer)]
        (should= ["tool_call" "tool_call_update"]
                 (mapv #(get-in % [:params :update :sessionUpdate]) notifications))
        (should= "pending" (get-in (first notifications) [:params :update :status]))
        (should= "failed" (get-in (second notifications) [:params :update :status]))
        (should= "tc-1" (get-in (second notifications) [:params :update :toolCallId])))))

  (it "maps every built-in tool at the pinned isaac-agent sha to an explicit ACP v1 kind"
    (let [built-ins @(requiring-resolve 'isaac.agent.tool.builtin/ordered-built-in-tools)]
      (should= (set built-ins) (set (keys expected-tool-kinds)))
      (doseq [name built-ins]
        (let [writer (StringWriter.)]
          (comm/on-tool-call (sut/channel writer) "s1" {:id "tc-1" :name name :arguments {}})
          (let [kind (get-in (first (parsed-output writer)) [:params :update :kind])]
            (should-contain kind acp-v1-tool-kinds)
            (should= [name (expected-tool-kinds name)] [name kind]))))))

  (it "classifies legacy transcript names and module tools"
    (doseq [[name kind] {"grep" "search" "glob" "search" "skill__load" "read"
                        "memory_write" "edit" "web_fetch" "fetch" "run_terminal_cmd" "execute"
                        "dream__habit" "read"}]
      (let [writer (StringWriter.)]
        (comm/on-tool-call (sut/channel writer) "s1" {:id "tc-1" :name name :arguments {}})
        (should= kind (get-in (first (parsed-output writer)) [:params :update :kind])))))

  (it "reports flagged errors as failed even without a text prefix"
    (let [writer (StringWriter.)]
      (comm/on-tool-result (sut/channel writer) "s1"
                           {:id "tc-1" :name "exec__run" :arguments {:command "exit 3"} :error? true} "exit 3")
      (let [update (get-in (first (parsed-output writer)) [:params :update])]
        (should= "failed" (:status update))
        (should-not (contains? update :error?)))))

  (it "reports flagged successes as completed even when the text begins Error:"
    (let [writer (StringWriter.)]
      (comm/on-tool-result (sut/channel writer) "s1"
                           {:id "tc-1" :name "exec__run" :arguments {:command "echo 'Error: x'"} :error? false}
                           "Error: x")
      (should= "completed" (get-in (first (parsed-output writer)) [:params :update :status]))))

  (it "falls back to the Error: prefix only when the tool result has no error flag"
    (let [writer (StringWriter.)]
      (comm/on-tool-result (sut/channel writer) "s1" {:id "tc-1" :name "exec__run" :arguments {}}
                           "Error: unknown tool")
      (should= "failed" (get-in (first (parsed-output writer)) [:params :update :status]))))

  (it "replays stored isError without requiring a text prefix"
    (should= "failed" (get-in (sut/replay-tool-call-update "s1"
                                                           {:id "tc-1" :name "exec__run" :arguments {}}
                                                           "exit 3" true)
                              [:params :update :status])))

  (it "replays an explicit stored success instead of inferring failure from Error: text"
    (should= "completed" (get-in (sut/replay-tool-call-update "s1"
                                                              {:id "tc-1" :name "exec__run" :arguments {}}
                                                              "Error: x" false)
                                 [:params :update :status])))

  (it "reports cancellation using a v1 status and cancelled content"
    (let [writer (StringWriter.)]
      (comm/on-tool-cancel (sut/channel writer) "s1" {:id "tc-1" :name "exec__run" :arguments {}})
      (let [update (get-in (first (parsed-output writer)) [:params :update])]
        (should-contain (:status update) acp-v1-tool-call-statuses)
        (should= "failed" (:status update))
        (should= "cancelled" (get-in update [:content 0 :content :text])))))

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

  (it "maps command params into ACP input hints"
    (let [notification (sut/available-commands-update "cmd-test"
                                                       [{:name        "work"
                                                         :description "Start work on a ready bean"
                                                         :params      ["bean"]}])]
      (should= [{:name        "work"
                 :description "Start work on a ready bean"
                 :input       {:hint "bean"}}]
               (get-in notification [:params :update :availableCommands]))))

  )
