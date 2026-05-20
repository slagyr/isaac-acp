Feature: ACP Slash Commands
  The agent advertises available commands via available_commands_update
  after session creation. Clients render them in their UI. Users invoke
  them with a / prefix, and the client sends the text as a normal prompt.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name     |
      | cmd-test |
    And the ACP client has initialized

  Scenario: agent advertises available commands after session creation
    When the ACP client sends request 2:
      | key         | value       |
      | method      | session/new |
      | params.name | cmd-test    |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.availableCommands[0].name | params.update.availableCommands[1].name | params.update.availableCommands[2].name |
      | session/update | available_commands_update   | status                                  | model                                   | crew                                    |

  Scenario: /status returns formatted markdown via ACP notification
    When the ACP client sends request 2:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | cmd-test       |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | /status        |
    Then the ACP agent sends response 2:
      | key               | value    |
      | result.stopReason | end_turn |
    And the ACP agent sends notifications:
      | method         | params.update.sessionUpdate |
      | session/update | agent_message_chunk         |
    And the notification content matches:
      | pattern                               |
      | Session Status                        |
      | Crew .* main                          |
      | ─+                                    |
      | Model .* echo \(grover\)              |
      | Session .* cmd-test                   |
      | Soul .*                                 |
      | Tools .* \d+                          |
    And the notification content does not contain "SOUL.md"

  Scenario: slash command is not added to the transcript
    When the ACP client sends request 2:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | cmd-test       |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | /status        |
    Then session "cmd-test" has no transcript entries with role "user"
