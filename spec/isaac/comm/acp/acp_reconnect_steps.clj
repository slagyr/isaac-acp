(ns isaac.comm.acp.acp-reconnect-steps
  (:require
    [gherclj.core :as g :refer [defthen defwhen helper!]]
    [isaac.logger :as log]
    [isaac.spec-helper :as helper]))

(helper! isaac.comm.acp.acp-reconnect-steps)

(def ^:private await-timeout-ms 3000)

(defn reconnect-attempts-failed [n]
  (let [n         (if (string? n) (parse-long n) n)
        attempts* (atom 0)]
    (helper/await-condition
      (fn []
        (let [attempts (count (filter #(= :acp-proxy/reconnect-attempt (:event %)) (log/get-entries)))]
          (reset! attempts* attempts)
          (>= attempts n)))
      await-timeout-ms)
    (when (< @attempts* n)
      (throw (ex-info "Timed out waiting for reconnect attempts" {:expected n :actual @attempts*})))))

(defn acp-proxy-still-running []
  (g/should-not (future-done? (g/get :acp-proxy-runner))))

(defwhen "{int} loopback reconnect attempts have failed" isaac.comm.acp.acp-reconnect-steps/reconnect-attempts-failed
  "Polls the log (up to 3s) until at least N :acp-proxy/reconnect-attempt
   entries are observed; throws on timeout. Synchronizes on the proxy's
   retry loop without real-time sleeps.")

(defthen "the acp proxy is still running" isaac.comm.acp.acp-reconnect-steps/acp-proxy-still-running
  "Asserts the proxy future has not completed. Proves the 'never give up'
   reconnect contract — the proxy hasn't exited despite failed attempts.")
