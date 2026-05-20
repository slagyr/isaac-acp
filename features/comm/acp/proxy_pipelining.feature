Feature: ACP Proxy Pipelining
  The --remote ACP proxy forwards stdin messages concurrently with in-flight
  responses. Notifications such as session/cancel are forwarded to the remote
  WS immediately, without waiting for any pending response to complete.

  Background:
    Given default Grover setup
    And config:
      | key                 | value    | #comment                                |
      | acp.proxy-transport | loopback | in-memory, supports concurrent messages |

  Scenario: cancel notification sent during an in-flight prompt is forwarded to the remote
    Given the loopback holds the final response
    And the acp proxy is running with "acp --remote ws://loopback"
    And the ACP client has initialized
    And the following sessions exist:
      | name   |
      | cancel |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    And config:
      | key        | value  |
      | log.output | memory |
    When stdin receives:
      """
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"cancel","prompt":[{"type":"text","text":"hi"}]}}
      """
    And stdin receives:
      """
      {"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"cancel"}}
      """
    And the loopback releases the final response
    Then the ACP agent sends response 2:
      | key               | value    |
      | result.stopReason | end_turn |
    And the log has entries matching:
      | level | event                        | sessionId |
      | :info | :acp/session-cancel-received | cancel    |
