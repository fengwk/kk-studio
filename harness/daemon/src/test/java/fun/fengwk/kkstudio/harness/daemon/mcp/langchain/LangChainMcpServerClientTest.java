package fun.fengwk.kkstudio.harness.daemon.mcp.langchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.daemon.mcp.McpCallOutcome;

/** LangChain MCP adapter 的模型可见错误不得携带 transport 配置或凭据。 */
class LangChainMcpServerClientTest {

  @Test
  void usesAConstantNonSensitiveClientFailure() {
    McpCallOutcome outcome = LangChainMcpServerClient.failedCall();

    assertTrue(outcome.isError());
    assertEquals(LangChainMcpServerClient.CALL_FAILED_MESSAGE, outcome.text());
  }
}
