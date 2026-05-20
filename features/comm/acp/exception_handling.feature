Feature: ACP Unexpected Exception Handling
  When an unexpected exception occurs during a turn,
  the error must be sent as an agent_message_chunk notification
  with stopReason: end_turn — not as a JSON-RPC internal error.

  Background:
    Given default Grover setup
    And the ACP client has initialized

  Scenario: unexpected exception is sent as agent_message_chunk with end_turn
    Given the following sessions exist:
      | name           |
      | exception-test |
    And the following model responses are queued:
      | model | type      | content         |
      | echo  | exception | something broke |
    When the ACP client sends request 2:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | exception-test |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | hello          |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate |
      | session/update | agent_message_chunk         |
    And the ACP agent sends response 2:
      | key               | value    |
      | result.stopReason | end_turn |
