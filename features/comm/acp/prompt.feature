Feature: ACP Prompt Turn
  session/prompt drives a full chat turn through Isaac's existing
  chat flow, storing messages in the session transcript.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name        |
      | prompt-chat |
    And the ACP client has initialized

  Scenario: A prompt turn stores user and assistant messages
    Given the following model responses are queued:
      | type | content       | model |
      | text | Four, I think | echo  |
    When the ACP client sends request 10:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | prompt-chat    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | What is 2+2?   |
    Then the ACP agent sends response 10:
      | key               | value    |
      | result.stopReason | end_turn |
    And session "prompt-chat" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | What is 2+2?    |
      | message | assistant    | Four, I think   |

  Scenario: Prompt uses the session's configured model and provider
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the ACP client sends request 11:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | prompt-chat    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Hi             |
    Then the ACP agent sends response 11:
      | key               | value    |
      | result.stopReason | end_turn |
    And session "prompt-chat" has transcript matching:
      | type    | message.role | message.model | message.provider |
      | message | assistant    | echo          | grover           |

  Scenario: ACP prompt turn triggers compaction when context is full
    # Compaction now triggers off the estimated prompt size vs the model's
    # context-window (not a stored token counter). A tiny context-window plus an
    # existing transcript pushes the estimate past the threshold.
    Given the isaac EDN file "config/models/cramped.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 100    |
    And the isaac EDN file "config/crew/tight.edn" exists with:
      | path | value          |
      | model | cramped       |
      | soul  | You are Isaac. |
    And the following sessions exist:
      | name       | crew  |
      | tight-chat | tight |
    And session "tight-chat" has transcript:
      | type    | message.role | message.content                                          |
      | message | user         | Walk me through the whole compaction subsystem in detail |
      | message | assistant    | It summarizes older transcript entries to free context   |
    And the following model responses are queued:
      | type | content                | model |
      | text | Summary of prior chat  | echo  |
      | text | Here is my answer      | echo  |
    When the ACP client sends request 12:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | tight-chat     |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Continue       |
    Then the ACP agent sends response 12:
      | key               | value    |
      | result.stopReason | end_turn |
    And session "tight-chat" has transcript matching:
      | type       |
      | compaction |
