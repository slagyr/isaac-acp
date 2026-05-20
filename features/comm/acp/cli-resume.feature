Feature: ACP Resume
  `isaac acp --resume` attaches to the most recent session for
  the crew member. If no session exists, a new one is created.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |

  Scenario: --resume finds the most recent session for the default crew member
    Given the following sessions exist:
      | name       | updated-at           |
      | resume-old | 2026-04-10T10:00:00 |
      | resume-new | 2026-04-12T15:00:00 |
      | resume-oldest | 2026-04-08T10:00:00 |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --resume"
    Then the stdout has a JSON-RPC response for id 2:
      | key              | value      |
      | result.sessionId | resume-new |
    And the exit code is 0

  Scenario: --resume with --crew finds the most recent session for that crew member
    Given the following sessions exist:
      | name             | updated-at           |
      | ketch-old        | 2026-04-10T10:00:00 |
      | ketch-recent     | 2026-04-12T15:00:00 |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --resume --crew ketch"
    Then the stdout has a JSON-RPC response for id 2:
      | key              | value        |
      | result.sessionId | ketch-recent |
    And the exit code is 0

  Scenario: --resume creates a new session when crew member has no existing sessions
    Given stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --resume --crew ketch"
    Then the stdout has a JSON-RPC response for id 2:
      | key              | value |
      | result.sessionId | #*    |
    And the exit code is 0

  Scenario: --resume --model is rejected as ambiguous
    When isaac is run with "acp --resume --model grok"
    Then the stderr contains "cannot combine --resume with --model"
    And the exit code is 1
