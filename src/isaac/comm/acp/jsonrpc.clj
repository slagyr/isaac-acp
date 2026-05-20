(ns isaac.comm.acp.jsonrpc
  "ACP-specific JSON-RPC helpers. Generic JSON-RPC lives in
   isaac.util.jsonrpc — prefer that for any non-ACP code."
  (:require
    [isaac.util.jsonrpc :as jrpc]))

(defn session-update [session-id update]
  (jrpc/notification "session/update" {:sessionId session-id
                                       :update    update}))
