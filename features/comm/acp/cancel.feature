Feature: ACP Turn Cancellation
  The ACP session/cancel notification triggers bridge cancellation
  and returns the appropriate response to the client.

  Background:
    Given default Grover setup
    And the crew "main" allows tools: "exec"
    And the following sessions exist:
      | name        |
      | cancel-test |
    And the built-in tools are registered
    And the ACP client has initialized

  Scenario: session/cancel interrupts a running exec and returns cancelled
    Given the following model responses are queued:
      | tool_call | arguments               |
      | exec      | {"command": "sleep 30"} |
    When the ACP client sends request 2 asynchronously:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | cancel-test    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | run it         |
    And the ACP client sends notification:
      | key              | value          |
      | method           | session/cancel |
      | params.sessionId | cancel-test    |
    Then the ACP agent sends response 2:
      | key               | value     |
      | result.stopReason | cancelled |

  Scenario: session/cancel interrupts a running LLM request and returns cancelled
    Given the LLM response is delayed by 30 seconds
    When the ACP client sends request 2 asynchronously:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | cancel-test    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | think hard     |
    And the ACP client sends notification:
      | key              | value          |
      | method           | session/cancel |
      | params.sessionId | cancel-test    |
    Then the ACP agent sends response 2:
      | key               | value     |
      | result.stopReason | cancelled |

  Scenario: session remains usable after ACP cancel
    Given the following model responses are queued:
      | tool_call | arguments               |
      | exec      | {"command": "sleep 30"} |
    When the ACP client sends request 2 asynchronously:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | cancel-test    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | run it         |
    And the ACP client sends notification:
      | key              | value          |
      | method           | session/cancel |
      | params.sessionId | cancel-test    |
    Then the ACP agent sends response 2:
      | key               | value     |
      | result.stopReason | cancelled |
    Given the following model responses are queued:
      | type | content     | model |
      | text | Still here! | echo  |
    When the ACP client sends request 3:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | cancel-test    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | you ok?        |
    Then the ACP agent sends response 3:
      | key               | value    |
      | result.stopReason | end_turn |
