package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;

import java.io.IOException;
import java.util.List;

/**
 * LangChain4j Streamable HTTP MCP 客户端适配器测试。
 *
 * <p>验证与轻量级 HTTP MCP Mock 的真实握手、工具发现、工具调用、Authorization Header 注入、 错误脱敏以及每调用即关闭（per-call create +
 * close）的生命周期。
 */
class LangChainMcpToolClientFactoryTest {

  private FakeStreamableHttpMcpServer fakeServer;
  private LangChainMcpToolClientFactory factory;

  @BeforeEach
  void setUp() throws IOException {
    fakeServer = new FakeStreamableHttpMcpServer();
    factory = new LangChainMcpToolClientFactory();
  }

  @AfterEach
  void tearDown() {
    if (fakeServer != null) {
      fakeServer.close();
    }
  }

  @Test
  void createsClientAndDiscoversToolsWithAuthorizationHeader() {
    // 意图：验证携带 Bearer Token 时正确发送 Authorization Header，并成功发现远端工具及其 schema
    fakeServer.addTool(
        "calculate_sum",
        "Add two integers",
        """
        {
          "type": "object",
          "properties": {
            "a": { "type": "integer" },
            "b": { "type": "integer" }
          },
          "required": ["a", "b"]
        }
        """);

    McpConnectionSpec spec =
        new McpConnectionSpec(fakeServer.endpointUrl(), "secret-test-token", 5000L);

    try (McpToolClient client = factory.create(spec)) {
      List<McpRemoteToolSpec> tools = client.listTools();
      assertNotNull(tools);
      assertEquals(1, tools.size());
      assertEquals("calculate_sum", tools.get(0).name());
      assertEquals("Add two integers", tools.get(0).description());
      assertTrue(tools.get(0).inputSchemaJson().contains("\"properties\""));

      assertTrue(fakeServer.receivedAuthHeaders().contains("Bearer secret-test-token"));
    }
  }

  @Test
  void createsClientWithoutBearerToken() {
    // 意图：验证无 Bearer Token 时不发送 Authorization Header
    fakeServer.addTool("ping", "Ping tool", "{}");

    McpConnectionSpec spec = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);

    try (McpToolClient client = factory.create(spec)) {
      List<McpRemoteToolSpec> tools = client.listTools();
      assertEquals(1, tools.size());
      assertEquals("ping", tools.get(0).name());
      assertTrue(fakeServer.receivedAuthHeaders().isEmpty());
    }
  }

  @Test
  void executesToolSuccessfully() {
    // 意图：验证远端工具调用成功返回结果
    fakeServer.addTool("echo", "Echo text", "{}");
    McpConnectionSpec spec = new McpConnectionSpec(fakeServer.endpointUrl(), null, 5000L);

    try (McpToolClient client = factory.create(spec)) {
      McpToolCallOutcome outcome = client.callTool("echo", "{\"message\":\"hello\"}", "call_123");
      assertNotNull(outcome);
      assertFalse(outcome.result().error());
      assertEquals("call_123", outcome.result().toolCallId());
      assertNotNull(outcome.result().contents());
      assertFalse(outcome.result().contents().isEmpty());
    }
    assertEquals(1, fakeServer.toolCallCount());
  }

  @Test
  void handlesToolCallFailureWithRedactedError() {
    // 意图：验证远端执行失败时返回统一脱敏错误，绝不泄露敏感信息
    fakeServer.addTool("failing_tool", "Always fails", "{}");
    fakeServer.setFailToolCall(true);

    McpConnectionSpec spec = new McpConnectionSpec(fakeServer.endpointUrl(), "secret-token", 5000L);

    try (McpToolClient client = factory.create(spec)) {
      McpToolCallOutcome outcome = client.callTool("failing_tool", "{}", "call_fail_1");
      assertNotNull(outcome);
      assertTrue(outcome.result().error());
      assertEquals("call_fail_1", outcome.result().toolCallId());
      // 确认错误信息为稳定统一文本
      TextResultContent textContent = (TextResultContent) outcome.result().contents().get(0);
      assertEquals(McpToolCallOutcome.failureMessage(), textContent.text());
      assertFalse(textContent.text().contains("secret-token"));
      assertFalse(textContent.text().contains("127.0.0.1"));
    }
  }

  @Test
  void throwsOnDiscoveryFailureAndCleansUp() {
    // 意图：验证发现阶段连接失败或 500 时直接抛出异常
    fakeServer.setFailDiscovery(true);
    McpConnectionSpec spec = new McpConnectionSpec(fakeServer.endpointUrl(), null, 1000L);

    assertThrows(RuntimeException.class, () -> factory.create(spec));
  }
}
