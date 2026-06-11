(ns isaac.comm.acp.cli.queue-spec
  (:require
    [cheshire.core :as json]
    [isaac.comm.acp.cli.queue :as sut]
    [speclj.core :refer :all]))

(defn- prompt-line [session-id text]
  (json/generate-string {:jsonrpc "2.0"
                         :id      1
                         :method  "session/prompt"
                         :params  {:sessionId session-id
                                   :prompt    [{:type "text" :text text}]}}))

(defn- cancel-line [session-id]
  (json/generate-string {:jsonrpc "2.0"
                         :method  "session/cancel"
                         :params  {:sessionId session-id}}))

(defn- end-turn-line []
  (json/generate-string {:jsonrpc "2.0" :id 1 :result {:stopReason "end_turn"}}))

(describe "isaac.comm.acp.cli.queue"

  (context "handle-prompt"

    (it "forwards the first prompt and marks the session in-flight"
      (let [state* (atom (sut/fresh-state))
            line   (prompt-line "tidy-comet" "first")]
        (should= [:forward line]
                 (sut/handle-prompt state* "tidy-comet" line))
        (should (sut/in-flight? @state* "tidy-comet"))
        (should= 0 (sut/pending-count @state* "tidy-comet"))))

    (it "queues subsequent prompts while in-flight, in FIFO order"
      (let [state* (atom (sut/fresh-state))
            p1     (prompt-line "tidy-comet" "first")
            p2     (prompt-line "tidy-comet" "second")
            p3     (prompt-line "tidy-comet" "third")]
        (sut/handle-prompt state* "tidy-comet" p1)
        (should= [:queue p2] (sut/handle-prompt state* "tidy-comet" p2))
        (should= [:queue p3] (sut/handle-prompt state* "tidy-comet" p3))
        (should= 2 (sut/pending-count @state* "tidy-comet"))
        (should= [p2 p3] (:queue (sut/session-state @state* "tidy-comet")))))

    (it "rejects an over-cap prompt without queuing it"
      (let [state* (atom (sut/fresh-state))]
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "in-flight"))
        (dotimes [i sut/queue-cap]
          (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" (str "q" i))))
        (let [over (prompt-line "tidy-comet" "one too many")]
          (should= [:reject over] (sut/handle-prompt state* "tidy-comet" over)))
        (should= sut/queue-cap (sut/pending-count @state* "tidy-comet"))))

    (it "tracks state per session independently"
      (let [state* (atom (sut/fresh-state))
            a1     (prompt-line "alpha" "a1")
            b1     (prompt-line "beta"  "b1")]
        (sut/handle-prompt state* "alpha" a1)
        ;; Beta is fresh — first prompt forwards, doesn't queue.
        (should= [:forward b1] (sut/handle-prompt state* "beta" b1))
        (should (sut/in-flight? @state* "alpha"))
        (should (sut/in-flight? @state* "beta")))))

  (context "handle-cancel"

    (it "forwards the first cancel of a round"
      (let [state* (atom (sut/fresh-state))
            line   (cancel-line "tidy-comet")]
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "in-flight"))
        (should= [:forward line] (sut/handle-cancel state* "tidy-comet" line))))

    (it "clears the local queue on a second cancel when the queue is non-empty"
      (let [state* (atom (sut/fresh-state))]
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "in-flight"))
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "q1"))
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "q2"))
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "q3"))
        (let [c1 (cancel-line "tidy-comet")
              c2 (cancel-line "tidy-comet")]
          (should= [:forward c1]     (sut/handle-cancel state* "tidy-comet" c1))
          (should= 3                  (sut/pending-count @state* "tidy-comet"))
          (should= [:clear-queue c2]  (sut/handle-cancel state* "tidy-comet" c2))
          (should= 0                  (sut/pending-count @state* "tidy-comet")))))

    (it "forwards a second cancel when the queue is empty"
      (let [state* (atom (sut/fresh-state))
            c1     (cancel-line "tidy-comet")
            c2     (cancel-line "tidy-comet")]
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "in-flight"))
        (should= [:forward c1] (sut/handle-cancel state* "tidy-comet" c1))
        (should= [:forward c2] (sut/handle-cancel state* "tidy-comet" c2)))))

  (context "handle-turn-end"

    (it "returns :idle when nothing is queued"
      (let [state* (atom (sut/fresh-state))]
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "in-flight"))
        (should= [:idle] (sut/handle-turn-end state* "tidy-comet"))
        (should-not (sut/in-flight? @state* "tidy-comet"))))

    (it "drains the FIFO head and stays in-flight when the queue is non-empty"
      (let [state* (atom (sut/fresh-state))
            p2     (prompt-line "tidy-comet" "second")
            p3     (prompt-line "tidy-comet" "third")]
        (sut/handle-prompt state* "tidy-comet" (prompt-line "tidy-comet" "first"))
        (sut/handle-prompt state* "tidy-comet" p2)
        (sut/handle-prompt state* "tidy-comet" p3)
        (should= [:drain p2] (sut/handle-turn-end state* "tidy-comet"))
        (should (sut/in-flight? @state* "tidy-comet"))
        (should= 1 (sut/pending-count @state* "tidy-comet"))
        (should= [:drain p3] (sut/handle-turn-end state* "tidy-comet"))
        (should= [:idle]     (sut/handle-turn-end state* "tidy-comet")))))

  (context "frame predicates"

    (it "prompt-line? detects session/prompt frames with a sessionId"
      (should (sut/prompt-line? (prompt-line "tidy-comet" "hi")))
      (should-not (sut/prompt-line? (cancel-line "tidy-comet")))
      (should-not (sut/prompt-line? "{}"))
      (should-not (sut/prompt-line? "not json")))

    (it "cancel-line? detects session/cancel frames with a sessionId"
      (should (sut/cancel-line? (cancel-line "tidy-comet")))
      (should-not (sut/cancel-line? (prompt-line "tidy-comet" "hi"))))

    (it "turn-end? matches responses carrying stopReason end_turn"
      (should (sut/turn-end? (end-turn-line)))
      (should-not (sut/turn-end? (json/generate-string {:jsonrpc "2.0" :id 1 :result {:stopReason "cancelled"}})))
      (should-not (sut/turn-end? "{}")))

    (it "message-session-id extracts from params and result"
      (should= "tidy-comet" (sut/message-session-id (prompt-line "tidy-comet" "hi")))
      (should= "tidy-comet" (sut/message-session-id (json/generate-string {:jsonrpc "2.0"
                                                                            :id      1
                                                                            :result  {:sessionId "tidy-comet"}})))
      (should-be-nil (sut/message-session-id (end-turn-line))))))
