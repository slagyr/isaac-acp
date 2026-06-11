@wip
Feature: ACP proxy client-side prompt queue
  When a user sends a session/prompt while the previous turn is still
  in flight, the isaac-acp proxy parks the new prompt on a per-session
  FIFO queue locally and emits an agent_thought_chunk so the UI shows
  a "queued" state. When the in-flight turn's stopReason: end_turn
  arrives, the proxy automatically pops and forwards the next queued
  prompt. Queue cap: 10. Double session/cancel clears the queue
  locally.

  Background:
    Given default Grover setup
    And config:
      | key                 | value    | #comment                            |
      | acp.proxy-transport | loopback | in-memory, supports the holds dance |
      | log.output          | memory   |                                     |
    And the following sessions exist:
      | name       |
      | tidy-comet |

  Scenario: A prompt sent while the previous turn is in-flight is queued and drains on end_turn
    Given the loopback holds the final response
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"first prompt"}]}}
      {"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"do the next thing"}]}}
      """
    When isaac is run with "acp --remote ws://test/acp"
    Then the log has entries matching:
      | level  | event                       | session    | #comment                          |
      | :debug | :acp-proxy/prompt-forwarded | tidy-comet | first prompt forwarded            |
      | :info  | :acp-proxy/prompt-queued    | tidy-comet | second is held back               |
    And the stdout lines contain in order:
      | pattern                            | #comment                                |
      | "sessionUpdate":"agent_thought_chunk" | the user-visible "queued" notification |
      | (?i)queued                         |                                         |
    When the loopback releases the final response
    Then the log has entries matching:
      | level  | event                       | session    | #comment                              |
      | :debug | :acp-proxy/prompt-forwarded | tidy-comet | queued prompt drains after end_turn   |
    And the exit code is 0

  Scenario: Multiple queued prompts drain in FIFO order
    Given the loopback holds the final response
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"first prompt"}]}}
      {"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue A"}]}}
      {"jsonrpc":"2.0","id":4,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue B"}]}}
      {"jsonrpc":"2.0","id":5,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue C"}]}}
      """
    When isaac is run with "acp --remote ws://test/acp"
    Then the log has entries matching:
      | level  | event                       | session    | text          |
      | :debug | :acp-proxy/prompt-forwarded | tidy-comet | first prompt  |
      | :info  | :acp-proxy/prompt-queued    | tidy-comet | queue A       |
      | :info  | :acp-proxy/prompt-queued    | tidy-comet | queue B       |
      | :info  | :acp-proxy/prompt-queued    | tidy-comet | queue C       |
    When the loopback releases the final response
    Then the log has entries matching:
      | level  | event                       | session    | text     | #comment                  |
      | :debug | :acp-proxy/prompt-forwarded | tidy-comet | queue A  | FIFO: A goes first        |
      | :debug | :acp-proxy/prompt-forwarded | tidy-comet | queue B  | then B                    |
      | :debug | :acp-proxy/prompt-forwarded | tidy-comet | queue C  | then C                    |
    And the exit code is 0

  Scenario: A prompt beyond the queue cap is rejected with a "queue full" thought chunk
    Given the loopback holds the final response
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"first prompt"}]}}
      {"jsonrpc":"2.0","id":10,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 01"}]}}
      {"jsonrpc":"2.0","id":11,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 02"}]}}
      {"jsonrpc":"2.0","id":12,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 03"}]}}
      {"jsonrpc":"2.0","id":13,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 04"}]}}
      {"jsonrpc":"2.0","id":14,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 05"}]}}
      {"jsonrpc":"2.0","id":15,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 06"}]}}
      {"jsonrpc":"2.0","id":16,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 07"}]}}
      {"jsonrpc":"2.0","id":17,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 08"}]}}
      {"jsonrpc":"2.0","id":18,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 09"}]}}
      {"jsonrpc":"2.0","id":19,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue 10"}]}}
      {"jsonrpc":"2.0","id":20,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"one too many"}]}}
      """
    When isaac is run with "acp --remote ws://test/acp"
    Then the log has entries matching:
      | level  | event                       | session    | #comment                            |
      | :debug | :acp-proxy/prompt-forwarded | tidy-comet | the original "first" prompt         |
      | :info  | :acp-proxy/prompt-queued    | tidy-comet | 10x — once per queued prompt        |
      | :warn  | :acp-proxy/queue-full       | tidy-comet | rejection for the 11th queued prompt |
    And the stdout lines contain in order:
      | pattern                          |
      | (?i)queue full                   |
    And the exit code is 0

  Scenario: First session/cancel cancels the in-flight turn; the next session/cancel clears the queue
    Given the loopback holds the final response
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"first prompt"}]}}
      {"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue A"}]}}
      {"jsonrpc":"2.0","id":4,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue B"}]}}
      {"jsonrpc":"2.0","id":5,"method":"session/prompt","params":{"sessionId":"tidy-comet","prompt":[{"type":"text","text":"queue C"}]}}
      {"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"tidy-comet"}}
      {"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"tidy-comet"}}
      """
    When isaac is run with "acp --remote ws://test/acp"
    Then the log has entries matching:
      | level  | event                        | session    | #comment                          |
      | :debug | :acp-proxy/cancel-forwarded  | tidy-comet | 1st cancel hits the live turn     |
      | :info  | :acp-proxy/queue-cleared     | tidy-comet | 2nd cancel clears 3 queued prompts |
    And the stdout lines contain in order:
      | pattern                          |
      | (?i)queue cleared                |
    And the exit code is 0
