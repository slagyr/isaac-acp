(ns isaac.comm.acp.cli.queue
  "Per-session prompt queue for the ACP proxy (`isaac chat --remote` /
   `isaac acp --remote`). When a session has a turn in flight, new
   `session/prompt` lines from stdin hold on a local FIFO queue instead
   of being forwarded to the server (which would silently refuse them).
   When the in-flight turn's `stopReason: end_turn` arrives back, the
   head of the queue is popped and forwarded as the next real prompt.

   Cancellation: the first `session/cancel` for a session forwards as
   today (cancels the live turn). A second `session/cancel` arriving
   while the queue still has prompts clears the queue locally and
   emits a `queue cleared` thought chunk without forwarding.

   Cap: 10 prompts per session. Over-cap prompts are rejected with a
   distinct thought chunk and not queued.

   The state lives in a single atom keyed by session id; this ns
   exposes pure transitions over that atom (read-modify-decide-return)
   so the proxy's stdin / remote threads can call in without holding
   any external state."
  (:require
    [cheshire.core :as json]))

(def queue-cap
  "Maximum number of prompts held in the per-session FIFO."
  10)

(def queued-thought-text
  "Text content of the agent_thought_chunk emitted when a prompt is
   parked on the local queue."
  "queued: held until the current turn finishes")

(def queue-full-thought-text
  "Text content of the agent_thought_chunk emitted when a prompt is
   rejected because the queue is at cap."
  "queue full: prompt dropped (cap is 10)")

(def queue-cleared-thought-text
  "Text content of the agent_thought_chunk emitted when a double cancel
   clears the local queue."
  "queue cleared")

(defn fresh-state
  "Initial proxy-queue state. Use as the atom seed."
  []
  {})

(defn- empty-session []
  {:in-flight? false :queue [] :cancel-count 0})

(defn session-state
  "Read-only view of the per-session entry. Returns the empty-session
   default when the session hasn't been seen."
  [state session-id]
  (get state session-id (empty-session)))

(defn pending-count
  "How many prompts are currently parked for `session-id`."
  [state session-id]
  (count (:queue (session-state state session-id))))

(defn in-flight?
  "Whether a turn is in flight for `session-id`."
  [state session-id]
  (boolean (:in-flight? (session-state state session-id))))

(defn- update-session [state session-id f]
  (let [entry (f (session-state state session-id))]
    (assoc state session-id entry)))

(defn- decide-prompt [entry]
  (cond
    (not (:in-flight? entry))
    [(assoc entry :in-flight? true :cancel-count 0) :forward]

    (< (count (:queue entry)) queue-cap)
    [(-> entry
         (update :queue conj nil) ;; placeholder; line filled below
         (assoc :cancel-count 0))
     :queue]

    :else
    [entry :reject]))

(defn handle-prompt
  "Decide what to do with a new `session/prompt` line. Mutates `state*`
   accordingly and returns one of:

   - `[:forward line]`  — proxy should forward `line` to the server.
   - `[:queue line]`    — proxy should NOT forward; should emit the
                          queued thought chunk via stdout.
   - `[:reject line]`   — proxy should NOT forward; should emit the
                          queue-full thought chunk."
  [state* session-id line]
  (let [decision-atom (atom :forward)]
    (swap! state*
           (fn [state]
             (update-session state session-id
                             (fn [entry]
                               (let [[entry' decision] (decide-prompt entry)]
                                 (reset! decision-atom decision)
                                 (case decision
                                   :queue   (assoc entry' :queue
                                                   (conj (vec (butlast (:queue entry'))) line))
                                   entry'))))))
    [@decision-atom line]))

(defn- decide-cancel [entry]
  (let [cancel-count' (inc (:cancel-count entry 0))]
    (cond
      (and (>= cancel-count' 2) (seq (:queue entry)))
      [(assoc entry :queue [] :cancel-count 0) :clear-queue]

      :else
      [(assoc entry :cancel-count cancel-count') :forward])))

(defn handle-cancel
  "Decide what to do with a new `session/cancel` line. Returns one of:

   - `[:forward line]`      — proxy should forward (cancels live turn).
   - `[:clear-queue line]`  — proxy should NOT forward; should clear
                              its local queue and emit the queue-cleared
                              thought chunk."
  [state* session-id line]
  (let [decision-atom (atom :forward)]
    (swap! state*
           (fn [state]
             (update-session state session-id
                             (fn [entry]
                               (let [[entry' decision] (decide-cancel entry)]
                                 (reset! decision-atom decision)
                                 entry')))))
    [@decision-atom line]))

(defn handle-turn-end
  "Signal that the in-flight turn for `session-id` has ended (server
   returned `stopReason: end_turn` or equivalent). Pops the head of
   the per-session queue if any; returns one of:

   - `[:idle]`            — no queued prompt; session is now idle.
   - `[:drain next-line]` — `next-line` should be forwarded to the
                            server as the next real `session/prompt`."
  [state* session-id]
  (let [popped (atom nil)]
    (swap! state*
           (fn [state]
             (update-session state session-id
                             (fn [entry]
                               (if-let [next-line (first (:queue entry))]
                                 (do (reset! popped next-line)
                                     {:in-flight?   true
                                      :queue        (vec (rest (:queue entry)))
                                      :cancel-count 0})
                                 {:in-flight?   false
                                  :queue        []
                                  :cancel-count 0})))))
    (if-let [line @popped]
      [:drain line]
      [:idle])))

;; ----- helpers proxies can use to recognize relevant frames ---------

(defn- safe-parse [line]
  (try (json/parse-string line true)
       (catch Exception _ nil)))

(defn message-session-id [line-or-message]
  (let [m (if (map? line-or-message) line-or-message (safe-parse line-or-message))]
    (some-> (or (get-in m [:params :sessionId])
                (get-in m [:result :sessionId]))
            str)))

(defn prompt-line?
  "True iff `line` is a JSON-RPC `session/prompt` request that carries
   a sessionId in its params."
  [line]
  (let [m (safe-parse line)]
    (and (= "session/prompt" (:method m))
         (some? (get-in m [:params :sessionId])))))

(defn cancel-line?
  "True iff `line` is a JSON-RPC `session/cancel` notification that
   carries a sessionId."
  [line]
  (let [m (safe-parse line)]
    (and (= "session/cancel" (:method m))
         (some? (get-in m [:params :sessionId])))))

(defn turn-end?
  "True iff `line` is a server-side message carrying
   `stopReason: end_turn`. The session id isn't always present in the
   response — proxy callers resolve which session this belongs to
   from the inbound request id or their session-id cache."
  [line]
  (= "end_turn" (get-in (safe-parse line) [:result :stopReason])))
