Feature: ACP Tool Calls
  Tool execution emits session/update notifications tracking state
  transitions (pending -> completed).

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name      |
      | tool-test |
    And the built-in tools are registered
    And the ACP client has initialized


  Scenario: Tool calls emit state updates
    Given the following model responses are queued:
      | tool_call | arguments              |
      | exec      | {"command": "echo hi"} |
    When the ACP client sends request 40:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | tool-test      |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Run echo       |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.status |
      | session/update | tool_call                   | pending              |
      | session/update | tool_call_update            | completed            |

  Scenario: Tool notifications include sessionId
    Given the following model responses are queued:
      | tool_call | arguments              |
      | exec      | {"command": "echo hi"} |
    When the ACP client sends request 41:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | tool-test      |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Run echo       |
    Then the ACP agent sends notifications:
      | method         | params.sessionId | params.update.sessionUpdate |
      | session/update | tool-test        | tool_call                   |
      | session/update | tool-test        | tool_call_update            |

  Scenario: Tool call notifications include title, kind, and rawInput per ACP spec
    Given the following model responses are queued:
      | tool_call | arguments              |
      | exec      | {"command": "echo hi"} |
    When the ACP client sends request 42:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | tool-test      |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Run echo       |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.title  | params.update.kind | params.update.rawInput.command |
      | session/update | tool_call                   | exec: echo hi        | execute            | echo hi                        |
      | session/update | tool_call_update            |                      |                    |                                |

  Scenario: Tool result includes toolCallId, rawOutput, and expandable content
    Given the following model responses are queued:
      | tool_call | arguments              |
      | exec      | {"command": "echo hi"} |
    When the ACP client sends request 43:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | tool-test      |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Run echo       |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.toolCallId | params.update.rawOutput | params.update.content[0].type |
      | session/update | tool_call                   | #*                       |                         |                               |
      | session/update | tool_call_update            | #*                       | #*                      | content                       |
