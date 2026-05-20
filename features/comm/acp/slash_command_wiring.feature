Feature: ACP Slash Command Wiring
  Slash commands must work through the ACP path. The bridge
  needs crew-members and models in its context to resolve
  /crew and /model commands.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/models/grok.edn" exists with:
      | path | value |
      | model | grok-4-1-fast |
      | provider | grok |
      | context-window | 32768 |
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the ACP client has initialized

  Scenario: /crew switches crew member through ACP
    Given the following sessions exist:
      | name      |
      | crew-test |
    When the ACP client sends request 2:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | crew-test      |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | /crew ketch    |
    Then the ACP agent sends response 2:
      | key               | value    |
      | result.stopReason | end_turn |
    And the ACP agent sends notifications:
      | method         | params.update.sessionUpdate |
      | session/update | agent_message_chunk         |

  Scenario: /model switches model through ACP
    Given the following sessions exist:
      | name       |
      | model-test |
    When the ACP client sends request 2:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | model-test     |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | /model grok    |
    Then the ACP agent sends response 2:
      | key               | value    |
      | result.stopReason | end_turn |
    And the ACP agent sends notifications:
      | method         | params.update.sessionUpdate |
      | session/update | agent_message_chunk         |
