@wip
Feature: ACP surface dispatches through the bridge — episode crews get episodes
  Every surface enters the turn engine at ONE seam: bridge dispatch, where the
  episode router, turnstiles, observers and finalization live (isaac-6yg0
  ruling). For a crew with :conversation :episodes the ACP sessionId is the
  THREAD (isaac-51xy decision 27): the first prompt opens an episode with
  recall-at-open, warm prompts append, and the client never learns episodes
  rotate beneath its handle. Chronicle crews are byte-identical to today.
  Attaching to an episode crew with --crew replays no chronicle transcript.

  Background:
    Given default Grover setup
    And the ACP commands are registered
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the current time is "2026-03-01T10:00:00Z"

  Scenario: session/prompt on an episodes crew opens an episode with the ACP session as thread
    Given the ACP client has initialized
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When the ACP client sends request 2:
      | key         | value       |
      | method      | session/new |
      | params.name | reef-chat   |
    And the ACP client sends request 3:
      | key                   | value                  |
      | method                | session/prompt         |
      | params.sessionId      | reef-chat              |
      | params.prompt[0].type | text                   |
      | params.prompt[0].text | Chart the reef passage |
    Then the ACP agent sends response 3:
      | key               | value    |
      | result.stopReason | end_turn |
    And the log has entries matching:
      | event            | crew     | thread    | origin |
      | :episodes/opened | cordelia | reef-chat | acp    |
    And the following sessions match:
      | id                             | crew     |
      | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" | cordelia |

  Scenario: a warm second prompt appends to the open episode
    Given the ACP client has initialized
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
      | text | Watches dogged     | echo  |
    When the ACP client sends request 2:
      | key         | value       |
      | method      | session/new |
      | params.name | reef-chat   |
    And the ACP client sends request 3:
      | key                   | value                  |
      | method                | session/prompt         |
      | params.sessionId      | reef-chat              |
      | params.prompt[0].type | text                   |
      | params.prompt[0].text | Chart the reef passage |
    Given the current time is "2026-03-01T10:20:00Z"
    When the ACP client sends request 4:
      | key                   | value                  |
      | method                | session/prompt         |
      | params.sessionId      | reef-chat              |
      | params.prompt[0].type | text                   |
      | params.prompt[0].text | Set the watch rotation |
    Then the ACP agent sends response 4:
      | key               | value    |
      | result.stopReason | end_turn |
    And the log has no entries matching:
      | event            |
      | :episodes/closed |
    And the following sessions match:
      | id                             | crew     |
      | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" | cordelia |

  Scenario: chronicle crews are unchanged — session/new creates the named session, no episode events
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path  | value             |
      | model | echo              |
      | soul  | You are a pirate. |
    And the ACP client has initialized
    And the following model responses are queued:
      | type | content | model |
      | text | Arr     | echo  |
    When the ACP client sends request 2:
      | key         | value       |
      | method      | session/new |
      | params.name | deck-chat   |
    And the ACP client sends request 3:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | deck-chat      |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Hoist the sail |
    Then the ACP agent sends response 3:
      | key               | value    |
      | result.stopReason | end_turn |
    And session "deck-chat" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Hoist the sail  |
      | message | assistant    | Arr             |
    And the log has no entries matching:
      | event            |
      | :episodes/opened |

  Scenario: --crew on an episode crew attaches to a fresh thread and replays nothing
    Given the following sessions exist:
      | name          | crew     | updated-at          |
      | cordelia-old  | cordelia | 2026-02-20T10:00:00 |
    And session "cordelia-old" has transcript:
      | type    | message.role | message.content |
      | message | user         | Old chart       |
      | message | assistant    | Old course      |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
      {"jsonrpc":"2.0","id":2,"method":"session/new","params":{}}
      """
    When isaac is run with "acp --crew cordelia"
    Then the stdout has a JSON-RPC response for id 2:
      | key              | value |
      | result.sessionId | #*    |
    And the stdout does not contain "Old chart"
    And the stdout does not contain "Old course"
    And the exit code is 0
