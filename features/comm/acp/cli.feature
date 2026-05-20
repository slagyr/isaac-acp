Feature: ACP command
  `isaac acp` starts Isaac as an ACP agent over stdio. It reads
  JSON-RPC messages from stdin, writes responses to stdout, and
  loops until stdin closes. Method-level behavior is covered by
  features/acp/*.feature via direct handler dispatch; this feature
  only verifies the CLI loop plumbs stdin and stdout correctly.

  Background:
    Given default Grover setup

  Scenario: acp command is registered and has help
    When isaac is run with "help acp"
    Then the stdout contains "Usage: isaac acp"
    And the exit code is 0

  Scenario: acp command reads a request from stdin and writes a response to stdout
    Given stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientInfo":{"name":"test","version":"0.1"}}}
      """
    When isaac is run with "acp"
    Then the stdout contains "protocolVersion"
    And the stdout contains "agentInfo"
    And the exit code is 0

  Scenario: acp command loops over multiple stdin requests
    Given stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/tmp/test"}}
      """
    When isaac is run with "acp"
    Then the stdout contains "protocolVersion"
    And the stdout contains "sessionId"
    And the exit code is 0

  Scenario: acp command exits cleanly on stdin EOF
    Given stdin is empty
    When isaac is run with "acp"
    Then the exit code is 0

  Scenario: acp command prints a ready signal to stderr on startup
    Given stdin is empty
    When isaac is run with "acp"
    Then the stderr contains "isaac acp ready"
    And the exit code is 0

  Scenario: --verbose enables debug logging to stderr
    Given stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      """
    When isaac is run with "acp --verbose"
    Then the stderr contains "initialize"
    And the exit code is 0

  Scenario: --session attaches the acp command to an existing session
    Given the following sessions exist:
      | name          |
      | earlier-chat  |
    And session "earlier-chat" has transcript:
      | type    | message.role | message.content |
      | message | user         | earlier         |
      | message | assistant    | earlier reply   |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --session earlier-chat"
    Then the stdout contains "\"sessionId\":\"earlier-chat\""
    And the exit code is 0

  Scenario: --session fails if the session does not exist
    When isaac is run with "acp --session nonexistent"
    Then the stderr contains "session not found"
    And the stderr contains "nonexistent"
    And the exit code is 1

  Scenario: acp resolves main crew member from config defaults when no crew list is configured
    Given isaac home "target/test-home" contains config:
      """
      {:crew {:defaults {:model "grover/echo"}}
       :models {:providers [{:name "grover" :base-url "http://fake"}]}}
      """
    And the following sessions exist:
      | name          |
      | defaults-test |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"defaults-test","prompt":[{"type":"text","text":"hi"}]}}
      """
    When isaac is run with "acp --session defaults-test"
    Then the stdout contains "\"stopReason\":\"end_turn\""
    And the exit code is 0

  Scenario: acp fails clearly when no config exists
    Given isaac home "target/test-home" has no config file
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      """
    When isaac is run with "acp"
    Then the stderr contains "no config found"
    And the stderr contains "target/test-home/.isaac/config/isaac.edn"
    And the exit code is 1

  Scenario: acp returns an error when crew resolution yields no model
    Given isaac home "target/test-home" contains config:
      """
      {:crew {:defaults {}}}
      """
    And the following sessions exist:
      | name       |
      | no-model   |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"no-model","prompt":[{"type":"text","text":"hi"}]}}
      """
    When isaac is run with "acp --session no-model"
    Then the stdout contains "no model configured for crew"
    And the exit code is 0

  Scenario: --model overrides the crew member's default model
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path | value |
      | model | echo |
      | provider | grover |
      | context-window | 32768 |
    And the isaac EDN file "config/models/grover2.edn" exists with:
      | path | value |
      | model | echo-alt |
      | provider | grover |
      | context-window | 16384 |
    And the following sessions exist:
      | name           |
      | model-override |
    And the following model responses are queued:
      | type | content | model    |
      | text | Hello   | echo-alt |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"model-override","prompt":[{"type":"text","text":"hi"}]}}
      """
    When isaac is run with "acp --session model-override --model grover2"
    Then the stdout contains "\"stopReason\":\"end_turn\""
    And the exit code is 0
    And session "model-override" has transcript matching:
      | type    | message.model |
      | message | echo-alt      |

  Scenario: --model with unknown alias fails with clear error
    Given stdin is empty
    When isaac is run with "acp --model nonexistent"
    Then the stderr contains "unknown model"
    And the stderr contains "nonexistent"
    And the exit code is 1

  Scenario: --crew selects a different crew member's model and soul
    Given the isaac EDN file "config/crew/bosun.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name       |
      | bosun-chat |
    And the following model responses are queued:
      | type | content | model |
      | text | Ahoy    | echo  |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"bosun-chat","prompt":[{"type":"text","text":"hi"}]}}
      """
    When isaac is run with "acp --crew bosun --session bosun-chat"
    Then the stdout contains "\"stopReason\":\"end_turn\""
    And the exit code is 0

  Scenario: tool notifications arrive before the final response in stdout
    Given the built-in tools are registered
    And the following model responses are queued:
      | tool_call | arguments              |
      | exec      | {"command": "echo hi"} |
    And the following sessions exist:
      | name      |
      | tool-test |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"tool-test","prompt":[{"type":"text","text":"run echo"}]}}
      """
    When isaac is run with "acp --session tool-test"
    Then the stdout lines contain in order:
      | pattern          |
      | tool_call        |
      | tool_call_update |
      | end_turn         |
    And the exit code is 0

  Scenario: acp uses workspace SOUL.md when no soul in crew config
    Given isaac home "target/test-home" contains config:
      """
      {:crew {:defaults {:model "grover/echo"}}
       :models {:providers [{:name "grover" :base-url "http://fake"}]}}
      """
    And workspace "main" in "target/test-home" has SOUL.md:
      """
      You are Dr. Prattlesworth, a Victorian recluse.
      """
    And the following sessions exist:
      | name      |
      | soul-test |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"soul-test","prompt":[{"type":"text","text":"hi"}]}}
      """
    When isaac is run with "acp --session soul-test"
    Then the stdout contains "\"stopReason\":\"end_turn\""
    And the exit code is 0

  Scenario: acp falls back to default soul when no SOUL.md exists
    Given isaac home "target/test-home" contains config:
      """
      {:crew {:defaults {:model "grover/echo"}}
       :models {:providers [{:name "grover" :base-url "http://fake"}]}}
      """
    And the following sessions exist:
      | name             |
      | soul-default-test |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/prompt","params":{"sessionId":"soul-default-test","prompt":[{"type":"text","text":"hi"}]}}
      """
    When isaac is run with "acp --session soul-default-test"
    Then the stdout contains "\"stopReason\":\"end_turn\""
    And the exit code is 0
