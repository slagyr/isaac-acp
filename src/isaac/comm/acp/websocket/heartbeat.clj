(ns isaac.comm.acp.websocket.heartbeat
  "WebSocket keepalive for ACP connections. A single shared scheduled
   task (one across the whole server, not one per channel) snapshots
   the open-channel registry every `heartbeat-interval-ms` and writes
   a small JSON-RPC notification to each channel.

   Why app-layer instead of a WebSocket PING control frame:

   Babashka's SCI allowlist exposes `org.httpkit.server.Frame` and
   `AsyncChannel` but excludes the inner classes (`Frame$PingFrame`,
   `Frame$PongFrame`, etc.), so we cannot construct a real protocol-
   level PING from this runtime. The next-best path is a tiny JSON-RPC
   notification — well-behaved JSON-RPC peers MUST ignore unknown
   methods, and the bytes-on-the-wire have the same effect on NAT,
   reverse-proxy, and Tailscale idle-timeouts that a real PING would.

   Method name uses the LSP `$/` convention reserved for utility
   notifications that the receiver is free to ignore.

   Failure isolation: a closed channel throwing inside `send!` must
   not abort the rest of the tick — wrapping each send lets a single
   dead channel get reaped without disturbing the others."
  (:require
    [cheshire.core :as json]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler :as scheduler]
    [org.httpkit.server :as httpkit]))

(def default-heartbeat-interval-ms
  "Standard pick — well under typical NAT/proxy idle timeouts (which
   start at 60s in the worst case)."
  30000)

(def ^:private heartbeat-notification
  ;; LSP-style utility notification: receivers MUST ignore unknown
  ;; methods per JSON-RPC 2.0 §4.1, so any well-behaved ACP client
  ;; treats this as a no-op. The frame itself is the keepalive.
  (json/generate-string {:jsonrpc "2.0" :method "$/heartbeat"}))

(defonce ^:private open-channels* (atom #{}))
(defonce ^:private task-id* (atom nil))

(defn open-channels
  "Snapshot of currently-registered ACP channel handles. Public for
   testing and observability."
  []
  @open-channels*)

(defn register-channel!
  "Adds `channel` to the open-channel set. Called from on-open."
  [channel]
  (swap! open-channels* conj channel)
  channel)

(defn deregister-channel!
  "Removes `channel` from the open-channel set. Called from on-close."
  [channel]
  (swap! open-channels* disj channel)
  channel)

(defn- send-heartbeat! [channel]
  (try
    (httpkit/send! channel heartbeat-notification)
    (catch Throwable t
      ;; A closed-channel write is normal during the close-detect race
      ;; window. Don't log loudly.
      (log/debug :acp-ws/heartbeat-failed :error (.getMessage t)))))

(defn beat-all!
  "Send a heartbeat to every currently-registered channel. Snapshots
   the registry first so a concurrent `on-close` deregistering a
   channel mid-iteration doesn't fight with the snapshot."
  []
  (doseq [channel (open-channels)]
    (send-heartbeat! channel)))

(defn- task-running? []
  (some? @task-id*))

(defn ensure-started!
  "Idempotent: schedule the shared heartbeat task if not already
   running. Reads `[:acp :heartbeat-interval-ms]` from `cfg`, falling
   back to `default-heartbeat-interval-ms`. Returns the task id (or
   nil if no scheduler is registered in the nexus, e.g. in stdio-only
   mode)."
  [cfg]
  (when-not (task-running?)
    (when-let [sched (nexus/get :scheduler)]
      (let [interval (or (get-in cfg [:acp :heartbeat-interval-ms])
                         default-heartbeat-interval-ms)
            new-id   (scheduler/every! sched interval beat-all!)]
        (if (compare-and-set! task-id* nil new-id)
          (do (log/info :acp-ws/heartbeat-scheduled :interval-ms interval)
              new-id)
          ;; lost the race; another caller already scheduled — drop ours
          (do (scheduler/cancel! sched new-id) @task-id*))))))

(defn stop!
  "Cancel the shared heartbeat task and clear the open-channel set.
   Intended for test isolation and server shutdown."
  []
  (when-let [id @task-id*]
    (when-let [sched (nexus/get :scheduler)]
      (scheduler/cancel! sched id))
    (reset! task-id* nil))
  (reset! open-channels* #{})
  nil)
