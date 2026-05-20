Feature: ACP Proxy Reconnect
  The --remote ACP proxy survives server restarts by reconnecting
  indefinitely with capped exponential backoff. Nothing written
  to stdout violates ACP; disconnect and reconnect are surfaced
  to the client via session/update notifications with
  agent_thought_chunk content. Requests arriving while the proxy
  is disconnected receive ACP-standard JSON-RPC error responses.

  Background:
    Given default Grover setup
    And config:
      | key                              | value    | #comment                            |
      | acp.proxy-transport              | loopback | in-memory, supports simulated drops |
      | acp.proxy-reconnect-delay-ms     | 1        | tiny base delay for tests           |
      | acp.proxy-reconnect-max-delay-ms | 2        | tiny cap for tests                  |

  Scenario: a dropped connection emits an ACP-conformant disconnect notification
    Given the acp proxy is running with "acp --remote ws://loopback"
    And the ACP client has initialized
    And the following sessions exist:
      | name |
      | s1   |
    When the loopback connection drops
    Then the ACP agent sends notifications:
      | method         | params.sessionId | params.update.sessionUpdate | params.update.content.text |
      | session/update | s1               | agent_thought_chunk         | #"remote connection lost\s*"     |

  Scenario: a restored connection emits an ACP-conformant reconnect notification
    Given the acp proxy is running with "acp --remote ws://loopback"
    And the ACP client has initialized
    And the following sessions exist:
      | name |
      | s1   |
    When the loopback connection drops
    And the loopback connection is restored
    Then the ACP agent sends notifications:
      | method         | params.sessionId | params.update.sessionUpdate | params.update.content.text |
      | session/update | s1               | agent_thought_chunk         | #"remote connection lost\s*"     |
      | session/update | s1               | agent_thought_chunk         | #"reconnected to remote\s*"      |

  Scenario: a request arriving during disconnect waits for reconnect and then completes
    Given the acp proxy is running with "acp --remote ws://loopback"
    And the ACP client has initialized
    And the following sessions exist:
      | name |
      | s1   |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the loopback connection drops
    And the ACP client sends request 42 asynchronously:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | s1             |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | hello          |
    And the loopback connection is restored
    Then the ACP agent sends notifications:
      | method         | params.sessionId | params.update.sessionUpdate | params.update.content.text          |
      | session/update | s1               | agent_thought_chunk         | #"remote connection lost\s*"       |
      | session/update | s1               | agent_thought_chunk         | #"reconnected to remote\s*"        |
    And the ACP agent sends response 42:
      | key               | value    |
      | result.stopReason | end_turn |

  Scenario: the proxy keeps trying after the connection can no longer be restored
    Given the acp proxy is running with "acp --remote ws://loopback"
    And the ACP client has initialized
    And the following sessions exist:
      | name |
      | s1   |
    When the loopback connection drops permanently
    And 10 loopback reconnect attempts have failed
    Then the acp proxy is still running
    And the log has no entries matching:
      | event              |
      | :acp-proxy/gave-up |
