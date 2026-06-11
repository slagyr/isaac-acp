@wip
Feature: ACP proxy client-side prompt queue
  When the user sends a prompt while the session has a turn in flight,
  the isaac-acp proxy (the `isaac chat --remote` / `isaac acp --remote`
  process bridging stdio ↔ remote WebSocket) holds the new prompt
  locally on a per-session FIFO queue instead of forwarding to the
  server (which would silently refuse it). The proxy synthesizes a
  thought-chunk `session/update` so the client UI (Toad et al.) shows
  the queued state. When the in-flight turn's `stopReason: end_turn`
  arrives, the proxy automatically pops the next queued prompt and
  forwards it as a real `session/prompt`.

  Queue cap: 10 prompts per session. Past the cap, new prompts are
  rejected with a distinct thought chunk and not queued.

  Cancellation: first `session/cancel` cancels the in-flight turn
  (forwarded to server as today). A second `session/cancel` arriving
  while the queue still has prompts clears the queue locally with
  a thought-chunk acknowledgement.

  Background:
    Given default Grover setup
    And the ACP commands are registered

  Scenario: A prompt sent while the session is in-flight is queued locally
    Given a session "tidy-comet" with a turn currently in flight
    When stdin sends a session/prompt for "tidy-comet" with text "do the next thing"
    Then no session/prompt frame is forwarded to the server
    And the stdout has a JSON-RPC notification matching:
      | key                            | value                         |
      | method                         | session/update                |
      | params.sessionId               | tidy-comet                    |
      | params.update.sessionUpdate    | agent_thought_chunk           |
      | params.update.content.text     | #"(?i).*queued.*"             |
    When the server emits a stopReason "end_turn" for "tidy-comet"
    Then the proxy forwards the queued session/prompt to the server
    And the forwarded prompt's params.prompt[0].text is "do the next thing"

  Scenario: Multiple queued prompts drain in FIFO order
    Given a session "tidy-comet" with a turn currently in flight
    When stdin sends a session/prompt for "tidy-comet" with text "first queued"
    And stdin sends a session/prompt for "tidy-comet" with text "second queued"
    And stdin sends a session/prompt for "tidy-comet" with text "third queued"
    Then 3 agent_thought_chunk notifications are written to stdout
    And no session/prompt frame is forwarded to the server
    When the server emits a stopReason "end_turn" for "tidy-comet"
    Then the proxy forwards 1 session/prompt to the server
    And the forwarded prompt's params.prompt[0].text is "first queued"
    When the server emits a stopReason "end_turn" for "tidy-comet"
    Then the proxy forwards 1 session/prompt to the server
    And the forwarded prompt's params.prompt[0].text is "second queued"
    When the server emits a stopReason "end_turn" for "tidy-comet"
    Then the proxy forwards 1 session/prompt to the server
    And the forwarded prompt's params.prompt[0].text is "third queued"

  Scenario: A prompt beyond the queue cap is rejected with a thought chunk
    Given a session "tidy-comet" with a turn currently in flight
    And the proxy already has 10 prompts queued for "tidy-comet"
    When stdin sends a session/prompt for "tidy-comet" with text "one too many"
    Then no session/prompt frame is forwarded to the server
    And the proxy still has 10 prompts queued for "tidy-comet"
    And the stdout has a JSON-RPC notification matching:
      | key                            | value                                |
      | method                         | session/update                       |
      | params.sessionId               | tidy-comet                           |
      | params.update.sessionUpdate    | agent_thought_chunk                  |
      | params.update.content.text     | #"(?i).*queue full.*"                |

  Scenario: First Esc cancels the in-flight turn; second Esc clears the queue
    Given a session "tidy-comet" with a turn currently in flight
    And the proxy already has 3 prompts queued for "tidy-comet"
    When stdin sends a session/cancel for "tidy-comet"
    Then the proxy forwards 1 session/cancel to the server
    And the proxy still has 3 prompts queued for "tidy-comet"
    When stdin sends a session/cancel for "tidy-comet"
    Then no session/cancel frame is forwarded to the server
    And the proxy has 0 prompts queued for "tidy-comet"
    And the stdout has a JSON-RPC notification matching:
      | key                            | value                                  |
      | method                         | session/update                         |
      | params.sessionId               | tidy-comet                             |
      | params.update.sessionUpdate    | agent_thought_chunk                    |
      | params.update.content.text     | #"(?i).*queue cleared.*"               |
