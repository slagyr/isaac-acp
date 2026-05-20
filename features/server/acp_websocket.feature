@slow
Feature: ACP WebSocket Endpoint
  The Isaac server exposes an /acp WebSocket endpoint. Authentication
  is handled at the HTTP layer before the connection is upgraded.

  Integration tests use an ephemeral port (0) to avoid colliding
  with a real server. The step definition must shut down the server
  after each scenario.

  Background:
    Given default Grover setup

  Scenario: server rejects WebSocket connection without valid token
    Given config:
      | key               | value     | #comment                   |
      | server.host       | 0.0.0.0   |                            |
      | server.auth.token | secret123 |                            |
      | server.port       | 0         | OS assigns ephemeral port  |
    And the Isaac server is started
    And stdin is empty
    When isaac is run with "acp --remote ws://localhost:${server.port}/acp"
    Then the stderr contains "authentication failed"
    And the exit code is 1

  Scenario: server accepts WebSocket connection with valid token
    Given config:
      | key               | value     | #comment                   |
      | server.host       | 0.0.0.0   |                            |
      | server.auth.token | secret123 |                            |
      | server.port       | 0         | OS assigns ephemeral port  |
    And the Isaac server is started
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      """
    When isaac is run with "acp --remote ws://localhost:${server.port}/acp --token secret123"
    Then the stdout has a JSON-RPC response for id 1:
      | key                    | value |
      | result.protocolVersion | 1     |
    And the exit code is 0
