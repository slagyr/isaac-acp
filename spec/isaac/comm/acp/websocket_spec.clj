(ns isaac.comm.acp.websocket-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.comm.acp.server :as acp-server]
    [isaac.util.jsonrpc :as jrpc]
    [isaac.comm.acp.websocket :as sut]
    [isaac.config.loader :as config]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.spec-helper :as helper]
    [isaac.session.spec-helper :as session-helper]
    [isaac.system :as system]
    [org.httpkit.server :as httpkit]
    [speclj.core :refer :all]))

(describe "ACP WebSocket endpoint"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it] (session-helper/with-memory-store (it)))

  (describe "send-dispatch-result!"

    (it "sends a response as one JSON line"
      (let [sent (atom [])]
        (sut/send-dispatch-result! #(swap! sent conj %) (jrpc/result 1 {:ok true}))
        (should= (jrpc/result 1 {:ok true})
                 (json/parse-string (first @sent) true))))

    (it "sends notifications before the response when given an envelope"
      (let [sent   (atom [])
            notif  (jrpc/notification "session/update" {:sessionId "agent:main:acp:direct:user1"})
            result {:notifications [notif]
                    :response      (jrpc/result 2 {:stopReason "end_turn"})}]
        (sut/send-dispatch-result! #(swap! sent conj %) result)
        (should= notif (json/parse-string (first @sent) true))
        (should= 2 (:id (json/parse-string (second @sent) true)))))

    )

  (describe "dispatch-line"

    (it "reuses the most recent session for session/new when resume=true is set"
      (system/with-nested-system {:fs (fs/mem-fs)}
        (let [state-dir (str "/test/acp-ws-resume-" (random-uuid))
              older     "older"
              recent    "recent"
              _         (session-helper/create-session! state-dir older)
              _         (session-helper/create-session! state-dir recent)
              _         (session-helper/update-session! state-dir older {:updated-at "2026-04-10T10:00:00"})
              _         (session-helper/update-session! state-dir recent {:updated-at "2026-04-12T15:00:00"})
              result    (sut/dispatch-line {:cfg          {}
                                            :state-dir    state-dir
                                            :query-params {"resume" "true"}}
                                            {:headers {} :uri "/acp"}
                                            (str/trim-newline (jrpc/request-line 2 "session/new" {})))]
          (should= recent (get-in result [:result :sessionId]))
          (should= 2 (count (session-helper/list-sessions state-dir "main"))))))

    (it "reuses the most recent session when state-dir and crew are provided by handler inputs"
      (system/with-nested-system {:fs (fs/mem-fs)}
        (let [state-dir (str "/test/acp-home-" (random-uuid) "/.isaac")
              older     "older"
              recent    "recent"
              _         (session-helper/create-session! state-dir older {:crew "marvin"})
              _         (session-helper/create-session! state-dir recent {:crew "marvin"})
              _         (session-helper/update-session! state-dir older {:updated-at "2026-04-10T10:00:00"})
              _         (session-helper/update-session! state-dir recent {:updated-at "2026-04-12T15:00:00"})
              result    (sut/dispatch-line {:cfg          {}
                                            :state-dir    state-dir
                                            :query-params {"resume" "true"
                                                           "crew"   "marvin"}}
                                            {:headers {} :uri "/acp"}
                                            (str/trim-newline (jrpc/request-line 2 "session/new" {})))]
          (should= recent (get-in result [:result :sessionId]))
          (should= 2 (count (session-helper/list-sessions state-dir "marvin"))))))

    (it "attaches a requested session and replays its transcript on session/new"
      (system/with-nested-system {:fs (fs/mem-fs)}
        (let [state-dir     (str "/test/acp-home-" (random-uuid) "/.isaac")
              session-key   "tidy-comet"
              notifications (atom [])
              _             (session-helper/create-session! state-dir session-key {:crew "marvin"})
              _             (session-helper/append-message! state-dir session-key {:role "user" :content "Howdy."})
              _             (session-helper/append-message! state-dir session-key {:role "assistant" :content "Howdy."})
              result        (sut/dispatch-line {:cfg          {}
                                                :state-dir    state-dir
                                                :output-writer #(swap! notifications conj (json/parse-string % true))
                                                :query-params {"session" "tidy-comet"
                                                               "crew"    "marvin"}}
                                               {:headers {} :uri "/acp"}
                                               (str/trim-newline (jrpc/request-line 2 "session/new" {})))]
          (should= session-key (get-in result [:result :sessionId]))
          (should= ["user_message_chunk" "agent_message_chunk"]
                   (mapv #(get-in % [:params :update :sessionUpdate]) @notifications))
          (should= ["Howdy." "Howdy."]
                   (mapv #(get-in % [:params :update :content :text]) @notifications)))))

    )

  (describe "handler"

    (it "returns websocket required for non-websocket requests"
      (system/with-system {}
        (config/set-snapshot! {} "ACP websocket-spec non-websocket request")
        (let [response (sut/handler {:websocket? false :headers {}})]
          (should= 400 (:status response))
          (should= "websocket required" (:body response)))))

    (it "upgrades websocket requests without channel-specific auth"
      (let [captured (atom nil)]
        (with-redefs [httpkit/as-channel (fn [request opts]
                                           (reset! captured [request opts])
                                           {:body :channel})]
          (system/with-system {}
            (config/set-snapshot! {} "ACP websocket-spec websocket upgrade")
            (let [response (sut/handler {:websocket? true
                                         :headers    {}})]
              (should= :channel (:body response))
              (should-not-be-nil @captured)
              (should (fn? (:on-receive (second @captured)))))))))

    (it "logs connection lifecycle events"
      (let [captured (atom nil)]
        (with-redefs [httpkit/as-channel (fn [_request opts]
                                           (reset! captured opts)
                                           :ok)]
          (log/capture-logs
            (system/with-system {}
              (config/set-snapshot! {} "ACP websocket-spec lifecycle logs")
              (sut/handler {:websocket? true
                            :uri        "/acp"
                            :headers    {"x-forwarded-for" "127.0.0.1"}}))
            ((:on-open @captured) :channel)
            ((:on-close @captured) :channel 1000 "bye")
            (should= [:acp-ws/connection-opened :acp-ws/connection-closed]
                     (->> @log/captured-logs
                          (mapv :event)
                          (remove #{:config/set-snapshot})
                          vec))))))

    (it "logs initialize dispatch"
      (let [captured (atom nil)]
        (with-redefs [httpkit/as-channel           (fn [_request opts]
                                                     (reset! captured opts)
                                                     :ok)
                      httpkit/send!                (fn [_channel _line] nil)
                      acp-server/dispatch-line (fn [_opts _line]
                                                 (jrpc/result 1 {:ok true}))]
          (log/capture-logs
            (system/with-system {}
              (config/set-snapshot! {} "ACP websocket-spec initialize logs")
              (sut/handler {:websocket? true
                            :uri        "/acp"
                            :headers    {}}))
            ((:on-receive @captured) :channel (str/trim-newline (jrpc/request-line 1 "initialize" {})))
            (should= [:acp-ws/initialize]
                     (->> @log/captured-logs
                          (mapv :event)
                          (remove #{:acp-ws/frame-received :config/set-snapshot})
                          vec))))))

    (it "logs session/new with returned session id"
      (let [captured (atom nil)]
        (with-redefs [httpkit/as-channel           (fn [_request opts]
                                                     (reset! captured opts)
                                                     :ok)
                      httpkit/send!                (fn [_channel _line] nil)
                      acp-server/dispatch-line (fn [_opts _line]
                                                 (jrpc/result 2 {:sessionId "agent:main:acp:direct:user1"}))]
          (log/capture-logs
            (system/with-system {}
              (config/set-snapshot! {} "ACP websocket-spec session-new logs")
              (sut/handler {:websocket? true
                            :uri        "/acp"
                            :headers    {}}))
            ((:on-receive @captured) :channel (str/trim-newline (jrpc/request-line 2 "session/new" {})))
            (should= [{:event :acp-ws/session-new :sessionId "agent:main:acp:direct:user1"}]
                     (->> @log/captured-logs
                          (remove #(#{:acp-ws/frame-received :config/set-snapshot} (:event %)))
                          (mapv #(select-keys % [:event :sessionId]))))))))

    (it "applies query params as websocket handler overrides"
      (with-redefs [httpkit/as-channel           (fn [_request opts]
                                                     ((:on-receive opts) :channel (str/trim-newline (jrpc/request-line 1 "initialize" {})))
                                                     :ok)
                    httpkit/send!                (fn [_channel _line] nil)
                    acp-server/dispatch-line (fn [opts _line]
                                               (should= "ketch" (:crew-id opts))
                                               (should= "grover2" (:model-override opts))
                                               (jrpc/result 1 {:ok true}))]
        (system/with-system {}
          (config/set-snapshot! {} "ACP websocket-spec query param overrides")
          (sut/handler {:websocket?  true
                        :uri         "/acp"
                        :query-string "crew=ketch&model=grover2&resume=true"}))))

    (it "reads the current config snapshot on every websocket request"
      (let [cfg*     (atom {:v 1})
            captured (atom [])
            channel* (atom nil)
            frame    (str/trim-newline (jrpc/request-line 1 "initialize" {}))]
        (with-redefs [httpkit/as-channel (fn [_request opts]
                                           (reset! channel* opts)
                                           :ok)
                      httpkit/send!      (fn [_ch _line] nil)
                      acp-server/dispatch-line (fn [opts _line]
                                                 (swap! captured conj (:cfg opts))
                                                 (jrpc/result 1 {:ok true}))]
          (system/with-system {}
            (config/set-snapshot! @cfg* "ACP websocket-spec first snapshot")
            (sut/handler {:websocket? true :uri "/acp" :headers {}})
            ((:on-receive @channel*) :channel frame)
            (reset! cfg* {:v 2})
            (config/set-snapshot! @cfg* "ACP websocket-spec second snapshot")
            ((:on-receive @channel*) :channel frame)
            (should= {:v 1} (first @captured))
            (should= {:v 2} (second @captured))))))

    (it "does not block session/cancel behind an in-flight session/prompt"
      (let [captured     (atom nil)
            prompt-start (promise)
            prompt-done  (promise)
            release      (promise)
            cancel-seen  (promise)
            prompt-line  (str/trim-newline (jrpc/request-line 2 "session/prompt"
                                                              {:sessionId "agent:main:acp:direct:user1"
                                                               :prompt [{:type "text" :text "Long task"}]}))
            cancel-line  (str/trim-newline (jrpc/notification-line "session/cancel"
                                                                   {:sessionId "agent:main:acp:direct:user1"}))]
        (with-redefs [httpkit/as-channel           (fn [_request opts]
                                                     (reset! captured opts)
                                                     :ok)
                      httpkit/send!                (fn [_channel _line]
                                                     (deliver prompt-done true)
                                                     nil)
                      acp-server/dispatch-line (fn [_opts line]
                                                 (let [message (json/parse-string line true)]
                                                   (case (:method message)
                                                     "session/prompt" (do
                                                                        (deliver prompt-start true)
                                                                        @release
                                                                        (jrpc/result 2 {:stopReason "end_turn"}))
                                                      "session/cancel" (do
                                                                         (deliver cancel-seen true)
                                                                         nil)
                                                      nil)))]
          (system/with-system {}
            (config/set-snapshot! {} "ACP websocket-spec prompt cancel ordering")
            (sut/handler {:websocket? true
                          :uri        "/acp"
                          :headers    {}}))
          (let [start-ms (System/currentTimeMillis)]
            ((:on-receive @captured) :channel prompt-line)
            (should (< (- (System/currentTimeMillis) start-ms) 1000)))
          (should= true (deref prompt-start 1000 nil))
          ((:on-receive @captured) :channel cancel-line)
          (should= true (deref cancel-seen 1000 nil))
          (deliver release true)
          (should= true (deref prompt-done 1000 nil)))))

    (it "flushes tool notifications before the final response completes"
      (let [captured (atom nil)
            sent     (atom [])
            release* (promise)]
        (with-redefs [httpkit/as-channel                 (fn [_request opts]
                                                           (reset! captured opts)
                                                           :ok)
                      httpkit/send!                      (fn [_channel line]
                                                           (swap! sent conj line))
                      isaac.comm.acp.websocket/async-prompt? (constantly false)
                      isaac.comm.acp.websocket/dispatch-line
                      (fn [opts _request _line]
                        ((:output-writer opts) (str/trim-newline (jrpc/notification-line "session/update" {:tool "exec"})))
                        @release*
                        (jrpc/result 2 {:stopReason "end_turn"}))]
            (system/with-system {}
              (config/set-snapshot! {} "ACP websocket-spec malformed request")
              (sut/handler {:websocket? true
                            :uri        "/acp"
                            :headers    {}}))
            (future ((:on-receive @captured) :channel (str/trim-newline (jrpc/request-line 2 "session/prompt" {}))))
            (helper/await-condition #(<= 1 (count @sent)))
            (should= "session/update" (:method (json/parse-string (first @sent) true)))
            (deliver release* :ok)
            (helper/await-condition #(<= 2 (count @sent)))
            (should= 2 (count @sent)))))

    )

  )
