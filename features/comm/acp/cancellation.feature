Feature: ACP Turn Cancellation
  Clients can interrupt an in-flight prompt turn via session/cancel.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name        |
      | cancel-test |
    And the ACP client has initialized

  Scenario: session/cancel during a turn stops processing
    When the ACP client sends request 30 asynchronously:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | cancel-test    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Long task      |
    And the ACP client sends notification:
      | key              | value          |
      | method           | session/cancel |
      | params.sessionId | cancel-test    |
    Then the ACP agent sends response 30:
      | key               | value     |
      | result.stopReason | cancelled |

  Scenario: session/cancel arrival is logged at info
    When the ACP client sends notification:
      | key              | value          |
      | method           | session/cancel |
      | params.sessionId | cancel-test    |
    Then the log has entries matching:
      | level | event                          | sessionId   |
      | :info | :acp/session-cancel-received   | cancel-test |
