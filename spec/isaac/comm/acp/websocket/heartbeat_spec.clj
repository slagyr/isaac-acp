(ns isaac.comm.acp.websocket.heartbeat-spec
  (:require
    [cheshire.core :as json]
    [isaac.comm.acp.websocket.heartbeat :as sut]
    [isaac.nexus :as nexus]
    [isaac.scheduler :as scheduler]
    [org.httpkit.server :as httpkit]
    [speclj.core :refer :all]))

(describe "isaac.comm.acp.websocket.heartbeat"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [it]
    ;; Clean defonce'd atoms before AND after — there's no test-isolation
    ;; harness for them otherwise.
    (sut/stop!)
    (try (it) (finally (sut/stop!))))

  (context "channel registration"

    (it "tracks open channels in the open-channels set"
      (should= #{} (sut/open-channels))
      (sut/register-channel! :chan-a)
      (sut/register-channel! :chan-b)
      (should= #{:chan-a :chan-b} (sut/open-channels)))

    (it "removes a channel on deregister"
      (sut/register-channel! :chan-a)
      (sut/register-channel! :chan-b)
      (sut/deregister-channel! :chan-a)
      (should= #{:chan-b} (sut/open-channels)))

    (it "deregistering an unknown channel is a no-op"
      (sut/register-channel! :chan-a)
      (sut/deregister-channel! :other)
      (should= #{:chan-a} (sut/open-channels))))

  (context "beat-all!"

    (it "sends a JSON-RPC $/heartbeat notification to every registered channel"
      (let [sent (atom [])]
        (with-redefs [httpkit/send! (fn [channel frame] (swap! sent conj [channel frame]))]
          (sut/register-channel! :chan-a)
          (sut/register-channel! :chan-b)
          (sut/beat-all!)
          (let [calls (set @sent)]
            (should= #{:chan-a :chan-b} (set (map first calls)))
            ;; Every frame is the same canonical heartbeat string.
            (let [frame (second (first calls))
                  msg   (json/parse-string frame true)]
              (should= "2.0"         (:jsonrpc msg))
              (should= "$/heartbeat" (:method msg))
              ;; All frames identical — the canonical string is precomputed
              ;; once, not re-rendered each tick.
              (should (apply = (map second calls))))))))

    (it "isolates per-channel send! failures so one dead channel doesn't abort the rest"
      (let [sent (atom [])]
        (with-redefs [httpkit/send! (fn [channel _frame]
                                      (if (= :dead channel)
                                        (throw (Exception. "boom"))
                                        (swap! sent conj channel)))]
          (sut/register-channel! :alive-a)
          (sut/register-channel! :dead)
          (sut/register-channel! :alive-b)
          (sut/beat-all!)
          ;; Both healthy channels still got beaten despite :dead throwing.
          (should= #{:alive-a :alive-b} (set @sent)))))

    (it "does nothing when no channels are registered"
      (with-redefs [httpkit/send! (fn [_ _] (throw (Exception. "should not be called")))]
        (should-not-throw (sut/beat-all!)))))

  (context "ensure-started!"

    (it "schedules a recurring beat task via isaac.scheduler/every!"
      (let [calls (atom [])]
        (with-redefs [scheduler/every! (fn [sched ms handler]
                                         (swap! calls conj {:scheduler sched :ms ms :handler handler})
                                         :task-id-1)
                      nexus/get        (fn [k] (when (= k :scheduler) :stub-scheduler))]
          (sut/ensure-started! {:acp {:heartbeat-interval-ms 5000}})
          (should= 1 (count @calls))
          (should= :stub-scheduler (:scheduler (first @calls)))
          (should= 5000            (:ms (first @calls))))))

    (it "falls back to default-heartbeat-interval-ms when :acp :heartbeat-interval-ms is absent"
      (let [interval (atom nil)]
        (with-redefs [scheduler/every! (fn [_ ms _] (reset! interval ms) :task-id-1)
                      nexus/get        (fn [k] (when (= k :scheduler) :stub-scheduler))]
          (sut/ensure-started! {})
          (should= sut/default-heartbeat-interval-ms @interval))))

    (it "is idempotent — repeat calls do not schedule a second task"
      (let [calls (atom 0)]
        (with-redefs [scheduler/every! (fn [_ _ _] (swap! calls inc) :task-id-1)
                      nexus/get        (fn [k] (when (= k :scheduler) :stub-scheduler))]
          (sut/ensure-started! {})
          (sut/ensure-started! {})
          (sut/ensure-started! {})
          (should= 1 @calls))))

    (it "is a no-op when no scheduler is registered in the nexus"
      (let [calls (atom 0)]
        (with-redefs [scheduler/every! (fn [_ _ _] (swap! calls inc) :task-id-1)
                      nexus/get        (constantly nil)]
          (sut/ensure-started! {:acp {:heartbeat-interval-ms 5000}})
          (should= 0 @calls))))))
