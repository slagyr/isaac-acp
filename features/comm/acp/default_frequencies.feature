Feature: ACP connects through the agent's default frequencies
  The session resolver reads the config itself. A blank `acp` adds no
  frequencies. Built-in :prefer :recent and :create :if-missing
  sit under :defaults :frequencies, and only flags the operator passed sit
  on top. Decision (2026-09-27, Micah), isaac-asik.

  Background:
    Given default Grover setup
    And the ACP commands are registered
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | echo             |
      | soul  | You are Cordelia |
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path  | value             |
      | model | echo              |
      | soul  | You are a pirate. |

  Scenario: a blank acp resumes the configured crew's session
    Given the isaac config path "defaults.frequencies.crew" is "cordelia"
    And the following sessions exist:
      | name   | crew     |
      | harbor | cordelia |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp"
    Then the exit code is 0
    And the stdout has a JSON-RPC response for id 2:
      | key              | value  |
      | result.sessionId | harbor |
    And the session count is 1

  Scenario: a blank acp with nothing to select fails
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                      | value   |
      | defaults.frequencies.crew | #delete |
    When isaac is run with "acp"
    Then the exit code is 1
    And the stderr contains "no session selected"
    And the session count is 0

  Scenario: an explicit session wins over the configured crew
    Given the isaac config path "defaults.frequencies.crew" is "cordelia"
    And the following sessions exist:
      | name    | crew  |
      | mooring | ketch |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --session mooring"
    Then the exit code is 0
    And the stdout has a JSON-RPC response for id 2:
      | key              | value   |
      | result.sessionId | mooring |
    And the session count is 1

  Scenario: a configured create always outranks the built-in if-missing
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                        | value    |
      | defaults.frequencies.crew   | cordelia |
      | defaults.frequencies.create | :always  |
    And the following sessions exist:
      | name   | crew     |
      | harbor | cordelia |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp"
    Then the exit code is 0
    And the session count is 2
    And the stdout has a JSON-RPC response for id 2:
      | key              | value |
      | result.sessionId | #*    |
    And the stdout does not contain "harbor"

  Scenario: an explicit --create never outranks a configured create always
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                        | value    |
      | defaults.frequencies.crew   | cordelia |
      | defaults.frequencies.create | :always  |
    And the following sessions exist:
      | name   | crew     |
      | harbor | cordelia |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --create never"
    Then the exit code is 0
    And the session count is 1
    And the stdout has a JSON-RPC response for id 2:
      | key              | value  |
      | result.sessionId | harbor |
