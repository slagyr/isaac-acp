Feature: ACP session selection and overrides
  `isaac acp` selects the session it attaches to and applies per-turn
  overrides through the shared session-frequencies flags
  (--session / --crew / --session-tag / --resume / --prefer / --create and
  the --with-* overrides) — the same reusable adapter the prompt command uses.
  ACP attaches to ONE resolved session; session/new returns that session, and
  when nothing matches the policy a fresh session is created.

  Background:
    Given default Grover setup
    And the ACP commands are registered
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |

  Scenario: --resume attaches to the most recent session
    Given the following sessions exist:
      | name          | updated-at          |
      | resume-old    | 2026-04-10T10:00:00 |
      | resume-new    | 2026-04-12T15:00:00 |
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

  Scenario: --crew attaches to the most recent session for that crew member
    Given the following sessions exist:
      | name         | crew  | updated-at          |
      | ketch-old    | ketch | 2026-04-10T10:00:00 |
      | ketch-recent | ketch | 2026-04-12T15:00:00 |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --crew ketch"
    Then the stdout has a JSON-RPC response for id 2:
      | key              | value        |
      | result.sessionId | ketch-recent |
    And the exit code is 0

  Scenario: --crew with no matching session creates a new one
    Given stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --crew ketch"
    Then the stdout has a JSON-RPC response for id 2:
      | key              | value |
      | result.sessionId | #*    |
    And the exit code is 0

  Scenario: --session combined with selection flags is rejected per shared rules
    When isaac is run with "acp --session some-id --crew ketch"
    Then the stderr contains "--session is mutually exclusive with --crew"
    And the exit code is 1

  Scenario: --with-model overrides the model on the attached turn
    Given the isaac EDN file "config/models/grover2.edn" exists with:
      | path           | value    |
      | model          | echo-alt |
      | provider       | grover   |
      | context-window | 16384    |
    And the following sessions exist:
      | name   | crew |
      | bridge | main |
    And the following model responses are queued:
      | type | content | model    |
      | text | On it.  | echo-alt |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"bridge","prompt":[{"type":"text","text":"Hi"}]}}
      """
    When isaac is run with "acp --session bridge --with-model grover2"
    Then session "bridge" has transcript matching:
      | type    | message.role | message.model |
      | message | assistant    | echo-alt      |
    And the exit code is 0
