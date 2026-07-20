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

  Scenario: /status returns structured data via chat/status notification
    # Server emits format-neutral status data (chat/status), not markdown
    # session/update chunks — CLI and MD-capable clients render themselves.
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
      | method      | params.crew | params.model | params.provider | params.session-key |
      | chat/status | main        | echo         | grover          | cmd-test           |

  Scenario: slash command is not added to the transcript
    When the ACP client sends request 2:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | cmd-test       |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | /status        |
    Then session "cmd-test" has no transcript entries with role "user"

  Scenario: a config-defined prompt-template command is advertised with an argument hint
    Given the isaac file "prompts/commands/work.md" exists with:
      """
      ---
      type: command
      description: Start work on a ready bean
      params: [bean]
      ---
      Start work on bean {{bean}}.
      """
    When the ACP client sends request 2:
      | key         | value       |
      | method      | session/new |
      | params.name | cmd-test    |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.availableCommands[5].name | params.update.availableCommands[5].description | params.update.availableCommands[5].input.hint |
      | session/update | available_commands_update   | work                                    | Start work on a ready bean                     | bean                                          |
