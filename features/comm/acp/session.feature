Feature: ACP Session Lifecycle
  ACP sessions map to Isaac's persistent session storage so users can
  resume conversations across TUI restarts.

  Background:
    Given default Grover setup
    And the ACP client has initialized

  Scenario: session/new creates an Isaac session
    When the ACP client sends request 2:
      | key        | value        |
      | method     | session/new  |
      | params.cwd | /tmp/project |
    Then the ACP agent sends response 2:
      | key              | value |
      | result.sessionId | #".+" |
    And the following sessions match:
      | id    |
      | #".+" |

  Scenario: session/load resumes a prior session
    Given the following sessions exist:
      | name          |
      | prior-session |
    And session "prior-session" has transcript:
      | type    | message.role | message.content |
      | message | user         | What's up?      |
      | message | assistant    | All good        |
    When the ACP client sends request 3:
      | key              | value         |
      | method           | session/load  |
      | params.sessionId | prior-session |
      | params.cwd       | /tmp/project  |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.content.text |
      | session/update | user_message_chunk          | What's up?                 |
      | session/update | agent_message_chunk         | All good                   |
    Then the ACP agent sends response 3:
      | key    | value |
      | result |       |

  Scenario: session/load replays the transcript as session/update notifications
    Given the following sessions exist:
      | name        |
      | resume-test |
    And session "resume-test" has transcript:
      | type    | message.role | message.content     |
      | message | user         | Hello there         |
      | message | assistant    | Hi, how can I help? |
      | message | user         | Tell me a joke      |
      | message | assistant    | Knock knock         |
    When the ACP client sends request 5:
      | key              | value        |
      | method           | session/load |
      | params.sessionId | resume-test  |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.content.text |
      | session/update | user_message_chunk          | Hello there                |
      | session/update | agent_message_chunk         | Hi, how can I help?        |
      | session/update | user_message_chunk          | Tell me a joke             |
      | session/update | agent_message_chunk         | Knock knock                |

  Scenario: session/load replays only the active transcript when compaction offset is set
    Given the following sessions exist:
      | name         |
      | compact-head |
    And session "compact-head" has transcript:
      | type    | message.role | message.content |
      | message | user         | old question    |
      | message | assistant    | old answer      |
      | message | user         | what next?      |
      | message | assistant    | let's tackle Y. |
    And compaction is spliced into session "compact-head" with:
      | key               | value             |
      | summary           | Compacted earlier |
      | firstKeptIndex    | 2                 |
      | compactedIndexes  | [0 1]             |
      | tokensBefore      | 10                |
    When the ACP client sends request 5:
      | key              | value        |
      | method           | session/load |
      | params.sessionId | compact-head |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.content.text |
      | session/update | agent_message_chunk         | Compacted earlier          |
      | session/update | user_message_chunk          | what next?                 |
      | session/update | agent_message_chunk         | let's tackle Y.            |

  Scenario: session/load replays the compaction summary at the active head boundary
    Given the following sessions exist:
      | name        |
      | resume-test |
    And session "resume-test" has transcript:
      | type       | message.role | message.content | summary                 |
      | compaction |              |                 | Earlier we discussed X. |
      | message    | user         | what next?      |                         |
      | message    | assistant    | let's tackle Y. |                         |
    When the ACP client sends request 5:
      | key              | value        |
      | method           | session/load |
      | params.sessionId | resume-test  |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.content.text |
      | session/update | agent_message_chunk         | Earlier we discussed X.    |
      | session/update | user_message_chunk          | what next?                 |
      | session/update | agent_message_chunk         | let's tackle Y.            |

  Scenario: session/load completes when a tool call result landed before the active offset
    Given the following sessions exist:
      | name        |
      | resume-test |
    And session "resume-test" has transcript:
      | type       | id   | message.role | message.content | name | arguments     |
      | message    |      | user         | check the logs  |      |               |
      | toolCall   | tc-1 |              |                 | grep | {"q":"error"} |
      | toolResult | tc-1 |              | 3 matches       |      |               |
      | message    |      | assistant    | found 3 errors  |      |               |
    And compaction is spliced into session "resume-test" with:
      | key               | value        |
      | summary           | Tool compact |
      | firstKeptIndex    | 3            |
      | compactedIndexes  | [0 1 2]      |
      | tokensBefore      | 10           |
    When the ACP client sends request 5:
      | key              | value        |
      | method           | session/load |
      | params.sessionId | resume-test  |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.content.text |
      | session/update | agent_message_chunk         | found 3 errors             |

  Scenario: session/load replays tool calls with their results
    Given the following sessions exist:
      | name        |
      | resume-test |
    And session "resume-test" has transcript:
      | type       | id   | message.role | message.content | name | arguments     |
      | message    |      | user         | check the logs  |      |               |
      | toolCall   | tc-1 |              |                 | grep | {"q":"error"} |
      | toolResult | tc-1 |              | 3 matches       |      |               |
      | message    |      | assistant    | found 3 errors  |      |               |
    When the ACP client sends request 5:
      | key              | value        |
      | method           | session/load |
      | params.sessionId | resume-test  |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.toolCallId | params.update.status | params.update.content.text |
      | session/update | user_message_chunk          |                          |                      | check the logs             |
      | session/update | tool_call                   | tc-1                     | completed            |                            |
      | session/update | agent_message_chunk         |                          |                      | found 3 errors             |

  Scenario: session/load replays a string tool result as ACP content and rawOutput
    Given the following sessions exist:
      | name        |
      | resume-test |
    And session "resume-test" has transcript:
      | type       | id   | message.role | message.content     | name | arguments               |
      | message    |      | user         | inspect the lantern |      |                         |
      | toolCall   | tc-1 |              |                     | read | {"file_path":"lantern"} |
      | toolResult | tc-1 |              | wick trimmed        |      |                         |
      | message    |      | assistant    | Lantern looks ready |      |                         |
    When the ACP client sends request 6:
      | key              | value        |
      | method           | session/load |
      | params.sessionId | resume-test  |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.toolCallId | params.update.status | params.update.rawOutput | params.update.content.text |
      | session/update | user_message_chunk          |                          |                      |                         | inspect the lantern        |
      | session/update | tool_call                   | tc-1                     | completed            | wick trimmed            |                            |
      | session/update | agent_message_chunk         |                          |                      |                         | Lantern looks ready        |