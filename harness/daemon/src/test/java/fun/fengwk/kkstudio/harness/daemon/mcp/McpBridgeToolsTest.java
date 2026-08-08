package fun.fengwk.kkstudio.harness.daemon.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 固定 MCP 桥接工具：list/call/错误/取消的确定性行为。 */
class McpBridgeToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void listToolsReportsAllServerStatusesAndReadySchemas() throws Exception {
    ToolExecutionRequest request = request("mcp_list_tools", "{}");
    McpListToolsTool tool = new McpListToolsTool(registry(new FakeClient("fs")));

    ToolResult result = new RecordingListener().execute(tool, request);

    assertFalse(result.error());
    JsonNode root = MAPPER.readTree(((JsonToolContent) result.contents().get(0)).json());
    assertEquals(2, root.path("servers").size());
    assertEquals("fs", root.path("servers").get(0).path("name").asText());
    assertEquals("READY", root.path("servers").get(0).path("status").asText());
    assertEquals(
        "read_file", root.path("servers").get(0).path("tools").get(0).path("name").asText());
    // 完整输入 schema 经 ToolSpecification.toJson() 原样嵌入。
    assertEquals(
        "read_file",
        root.path("servers").get(0).path("tools").get(0).path("schema").path("name").asText());
    assertEquals("broken", root.path("servers").get(1).path("name").asText());
    assertEquals("FAILED", root.path("servers").get(1).path("status").asText());
    assertTrue(root.path("servers").get(1).path("error").isTextual());
    assertEquals(0, root.path("servers").get(1).path("tools").size());
  }

  @Test
  void listToolsFiltersByExactServerAndFailsUnknown() throws Exception {
    McpListToolsTool tool = new McpListToolsTool(registry(new FakeClient("fs")));

    ToolResult requested =
        new RecordingListener().execute(tool, request("mcp_list_tools", "{\"server\":\"fs\"}"));
    assertFalse(requested.error());
    assertEquals(
        1,
        MAPPER
            .readTree(((JsonToolContent) requested.contents().get(0)).json())
            .path("servers")
            .size());

    ToolResult unknown =
        new RecordingListener()
            .execute(tool, request("mcp_list_tools", "{\"server\":\"missing\"}"));
    assertTrue(unknown.error());
    assertTrue(((TextToolContent) unknown.contents().get(0)).text().contains("unknown MCP server"));
  }

  @Test
  void callToolPreservesTextAndStructuredJsonResults() throws Exception {
    FakeClient client = new FakeClient("fs");
    client.outcomes.put("echo", new McpCallOutcome(false, "plain text"));
    client.outcomes.put("sum", new McpCallOutcome(false, "{\"total\":3}"));
    McpCallToolTool tool = new McpCallToolTool(registry(client));

    ToolResult textResult =
        new RecordingListener()
            .execute(
                tool,
                request(
                    "mcp_call_tool",
                    "{\"server\":\"fs\",\"tool\":\"echo\",\"arguments\":{\"x\":1}}"));
    assertFalse(textResult.error());
    assertEquals("plain text", ((TextToolContent) textResult.contents().get(0)).text());

    ToolResult jsonResult =
        new RecordingListener()
            .execute(
                tool,
                request(
                    "mcp_call_tool",
                    "{\"server\":\"fs\",\"tool\":\"sum\",\"arguments\":{\"a\":1,\"b\":2}}"));
    assertFalse(jsonResult.error());
    assertEquals("{\"total\":3}", ((JsonToolContent) jsonResult.contents().get(0)).json());
    assertEquals("{\"a\":1,\"b\":2}", client.lastArguments);
  }

  @Test
  void callToolPreservesUpstreamIsError() throws Exception {
    FakeClient client = new FakeClient("fs");
    client.outcomes.put("boom", new McpCallOutcome(true, "upstream failure text"));
    McpCallToolTool tool = new McpCallToolTool(registry(client));

    ToolResult result =
        new RecordingListener()
            .execute(
                tool,
                request("mcp_call_tool", "{\"server\":\"fs\",\"tool\":\"boom\",\"arguments\":{}}"));
    assertTrue(result.error());
    assertTrue(
        ((TextToolContent) result.contents().get(0)).text().contains("upstream failure text"));
  }

  @Test
  void callToolFailsDeterministicallyForUnknownServerAndTool() throws Exception {
    FakeClient client = new FakeClient("fs");
    McpCallToolTool tool = new McpCallToolTool(registry(client));

    ToolResult unknownServer =
        new RecordingListener()
            .execute(
                tool,
                request(
                    "mcp_call_tool", "{\"server\":\"missing\",\"tool\":\"t\",\"arguments\":{}}"));
    assertTrue(unknownServer.error());
    assertTrue(
        ((TextToolContent) unknownServer.contents().get(0))
            .text()
            .contains("MCP server is unknown or not ready"));

    // 未知工具是本地精确校验的确定性错误：client.call 绝不触达。
    ToolResult unknownTool =
        new RecordingListener()
            .execute(
                tool,
                request("mcp_call_tool", "{\"server\":\"fs\",\"tool\":\"nope\",\"arguments\":{}}"));
    assertTrue(unknownTool.error());
    assertTrue(
        ((TextToolContent) unknownTool.contents().get(0)).text().contains("unknown MCP tool"));
    assertEquals(0, client.calls.get());

    // READY 冻结列表中的工具仍正常调用。
    client.outcomes.put("read_file", new McpCallOutcome(false, "ok"));
    ToolResult known =
        new RecordingListener()
            .execute(
                tool,
                request(
                    "mcp_call_tool",
                    "{\"server\":\"fs\",\"tool\":\"read_file\",\"arguments\":{}}"));
    assertFalse(known.error());
    assertEquals(1, client.calls.get());
  }

  @Test
  void callToolRejectsNonObjectArgumentsAtRequestBoundary() {
    // arguments 必须是真实 JSON 对象；非对象在 ToolExecutionRequest 构造边界被拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                "mcp_call_tool", "{\"server\":\"fs\",\"tool\":\"t\",\"arguments\":\"string\"}"));
  }

  @Test
  void cancelProducesExactlyOneTerminalCallback() throws Exception {
    McpCallToolTool tool = new McpCallToolTool(registry(new FakeClient("fs")));
    ToolExecutionRequest request =
        request("mcp_call_tool", "{\"server\":\"fs\",\"tool\":\"echo\",\"arguments\":{}}");
    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle = tool.execute(request, listener);
    handle.cancel();
    handle.cancel();
    ToolResult result = listener.awaitComplete();
    assertTrue(result.error());
    assertTrue(((TextToolContent) result.contents().get(0)).text().contains("Operation cancelled"));
    assertEquals(1, listener.terminalCount.get());
  }

  private static McpServerRegistry registry(FakeClient readyClient) {
    McpServerRegistry registry =
        new McpServerRegistry(
            new McpConfig(List.of(stdio("fs"), stdio("broken"))),
            (config, timeout) -> {
              if (config.name().equals("broken")) {
                throw new IllegalStateException("cannot start broken");
              }
              return readyClient;
            },
            Duration.ofSeconds(10));
    registry.start();
    return registry;
  }

  private static McpServerConfig stdio(String name) {
    return new McpServerConfig(
        name, McpTransportType.STDIO, null, List.of("echo"), null, null, null);
  }

  private static ToolExecutionRequest request(String toolName, String argumentsJson) {
    return new ToolExecutionRequest(
        EnvironmentToolCatalog.require(toolName),
        new ToolCall("call-1", toolName, argumentsJson),
        Duration.ofSeconds(30));
  }

  private static final class FakeClient implements McpServerClient {

    private final String name;
    private final Map<String, McpCallOutcome> outcomes = new LinkedHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String lastArguments;

    private FakeClient(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<McpToolSpec> listTools() {
      return List.of(
          new McpToolSpec(
              "read_file",
              "Read a file",
              "{\"name\":\"read_file\",\"description\":\"Read a file\","
                  + "\"parameters\":{\"type\":\"object\",\"properties\":{}}}"),
          new McpToolSpec("echo", "Echo", "{\"name\":\"echo\"}"),
          new McpToolSpec("sum", "Sum", "{\"name\":\"sum\"}"),
          new McpToolSpec("boom", "Boom", "{\"name\":\"boom\"}"));
    }

    @Override
    public McpCallOutcome call(McpToolRequest request) {
      calls.incrementAndGet();
      lastArguments = request.argumentsJson();
      McpCallOutcome outcome = outcomes.get(request.toolName());
      if (outcome == null) {
        return new McpCallOutcome(true, "unknown tool: " + request.toolName());
      }
      return outcome;
    }

    @Override
    public void close() {}
  }

  private static final class RecordingListener implements ToolExecutionListener {

    private final CountDownLatch terminal = new CountDownLatch(1);
    private final AtomicReference<ToolResult> result = new AtomicReference<>();
    private final AtomicInteger terminalCount = new AtomicInteger();

    private ToolResult execute(Tool tool, ToolExecutionRequest request) {
      tool.execute(request, this);
      return awaitComplete();
    }

    private ToolResult awaitComplete() {
      try {
        assertTrue(terminal.await(5, TimeUnit.SECONDS), "tool must produce a terminal callback");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
      return result.get();
    }

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolResult complete) {
      terminalCount.incrementAndGet();
      result.set(complete);
      terminal.countDown();
    }

    @Override
    public void onError(Throwable error) {
      terminalCount.incrementAndGet();
      terminal.countDown();
    }
  }
}
