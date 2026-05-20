Feature: ACP Initialization
  Isaac speaks the Agent Client Protocol over stdio so that ACP-aware
  front-ends (Zed, IntelliJ, Toad, etc.) can use it as an agent.

  Background:
    Given default Grover setup

  Scenario: Initialize returns protocol version and agent info
    When the ACP client sends request 1:
      | key                       | value      |
      | method                    | initialize |
      | params.protocolVersion    | 1          |
      | params.clientInfo.name    | toad       |
      | params.clientInfo.version | 0.1        |
    Then the ACP agent sends response 1:
      | key                                  | value |
      | result.protocolVersion               | 1     |
      | result.agentInfo.name                | isaac |
      | result.agentCapabilities.loadSession | true  |

  Scenario: Initialize includes model and provider in agentInfo
    When the ACP client sends request 1:
      | key                    | value      |
      | method                 | initialize |
      | params.protocolVersion | 1          |
    Then the ACP agent sends response 1:
      | key                       | value  |
      | result.agentInfo.name     | isaac  |
      | result.agentInfo.model    | echo   |
      | result.agentInfo.provider | grover |

  Scenario: Initialize advertises supported content types
    When the ACP client sends request 1:
      | key                    | value      |
      | method                 | initialize |
      | params.protocolVersion | 1          |
    Then the ACP agent sends response 1:
      | key                                              | value |
      | result.agentCapabilities.promptCapabilities.text | true  |
