Feature: ACP Streaming Updates
  As the LLM generates chunks, the agent emits one session/update
  notification per chunk so front-ends can render text incrementally.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name        |
      | stream-test |
    And the ACP client has initialized

  # NOTE: whitespace preservation across chunk boundaries is verified by
  # a unit spec in spec/isaac/comm/acp_spec.clj — Gherkin table cells
  # trim leading/trailing whitespace, so this scenario uses whitespace-
  # neutral chunks to avoid codifying the trim bug we used to have
  # (isaac-wzn6).
  Scenario: Provider text chunks are forwarded as session/update notifications
    Given the following model responses are queued:
      | type | content                           | model |
      | text | ["chunkA" "chunkB" "chunkC"]      | echo  |
    When the ACP client sends request 20:
      | key                   | value          |
      | method                | session/prompt |
      | params.sessionId      | stream-test    |
      | params.prompt[0].type | text           |
      | params.prompt[0].text | Tell me a story |
    Then the ACP agent sends notifications:
      | method         | params.update.sessionUpdate | params.update.content.type | params.update.content.text |
      | session/update | agent_message_chunk         | text                       | chunkA                     |
      | session/update | agent_message_chunk         | text                       | chunkB                     |
      | session/update | agent_message_chunk         | text                       | chunkC                     |
    And session "stream-test" has transcript matching:
      | type    | message.role | message.content    |
      | message | assistant    | chunkAchunkBchunkC |
