@wip
Feature: Turns the ACP client didn't start
  While a client has a session attached, turns that run on that session
  without a session/prompt — subagent results, timers, hails — stream to the
  client like its own: the turn's input as a user message, the reply as agent
  chunks. (isaac-jjca)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name        |
      | stream-test |
    And the ACP client has initialized

  Scenario: a turn the ACP client didn't start streams to the attached session (isaac-jjca)
    Given the following model responses are queued:
      | type | content                           | model |
      | text | Aye, scout sent.                  | echo  |
      | text | The scout counted three moorings. | echo  |
    When the ACP client sends request 20:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | stream-test    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Send a scout.  |
    Then the ACP agent sends response 20:
      | key               | value    |
      | result.stopReason | end_turn |
    When a turn is submitted with:
      | key         | value           |
      | id          | scout-result-1  |
      | input       | Three moorings. |
      | session     | stream-test     |
      | origin.kind | subagent        |
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then the ACP agent sends notifications:
      | method         | params.sessionId | params.update.sessionUpdate | params.update.content.text        |
      | session/update | stream-test      | user_message_chunk          | Three moorings.                   |
      | session/update | stream-test      | agent_message_chunk         | The scout counted three moorings. |
