package fun.fengwk.kkstudio.harness.daemon.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 固定 MCP 桥接工具：list/call/错误/取消的确定性行为。 */
class McpBridgeCapabilitiesTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void closeExecutor() {
    executor.shutdownNow();
  }

  @Test
  void listToolsReportsAllServerStatusesAndReadySchemas() throws Exception {
    EnvironmentCapabilityExecutionRequest request = request("mcp.list", "{}");
    FakeClient client = new FakeClient("fs");
    McpListCapability capability = new McpListCapability(registry(client), executor);
    client.tools = List.of(new McpToolSpec("late_tool", "Late tool", "{\"name\":\"late_tool\"}"));

    EnvironmentCapabilityResult result = new RecordingListener().execute(capability, request);

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
    assertEquals(1, client.listCalls.get());
  }

  @Test
  void listToolsFiltersByExactServerAndFailsUnknown() throws Exception {
    McpListCapability capability = new McpListCapability(registry(new FakeClient("fs")), executor);

    EnvironmentCapabilityResult requested =
        new RecordingListener().execute(capability, request("mcp.list", "{\"server\":\"fs\"}"));
    assertFalse(requested.error());
    assertEquals(
        1,
        MAPPER
            .readTree(((JsonToolContent) requested.contents().get(0)).json())
            .path("servers")
            .size());

    EnvironmentCapabilityResult unknown =
        new RecordingListener()
            .execute(capability, request("mcp.list", "{\"server\":\"missing\"}"));
    assertTrue(unknown.error());
    assertTrue(((TextToolContent) unknown.contents().get(0)).text().contains("unknown MCP server"));
  }

  @Test
  void listToolsDoesNotExposeFrozenSchemaRenderingFailures() throws Exception {
    FakeClient client = new FakeClient("fs");
    client.tools = List.of(new McpToolSpec("read_file", "Read a file", "credential=secret-value"));
    McpListCapability capability = new McpListCapability(registry(client), executor);

    EnvironmentCapabilityResult result =
        new RecordingListener().execute(capability, request("mcp.list", "{}"));

    assertTrue(result.error());
    assertEquals(
        "Error: MCP tool catalog rendering failed.",
        ((TextToolContent) result.contents().get(0)).text());
    assertFalse(((TextToolContent) result.contents().get(0)).text().contains("secret-value"));
    assertEquals(1, client.listCalls.get());
  }

  @Test
  void callToolPreservesTextAndStructuredJsonResults() throws Exception {
    FakeClient client = new FakeClient("fs");
    client.outcomes.put("echo", new McpCallOutcome(false, "plain text"));
    client.outcomes.put("sum", new McpCallOutcome(false, "{\"total\":3}"));
    McpCallCapability capability = new McpCallCapability(registry(client), executor);

    EnvironmentCapabilityResult textResult =
        new RecordingListener()
            .execute(
                capability,
                request(
                    "mcp.call", "{\"server\":\"fs\",\"tool\":\"echo\",\"arguments\":{\"x\":1}}"));
    assertFalse(textResult.error());
    assertEquals("plain text", ((TextToolContent) textResult.contents().get(0)).text());

    EnvironmentCapabilityResult jsonResult =
        new RecordingListener()
            .execute(
                capability,
                request(
                    "mcp.call",
                    "{\"server\":\"fs\",\"tool\":\"sum\",\"arguments\":{\"a\":1,\"b\":2}}"));
    assertFalse(jsonResult.error());
    assertEquals("{\"total\":3}", ((JsonToolContent) jsonResult.contents().get(0)).json());
    assertEquals("{\"a\":1,\"b\":2}", client.lastArguments);
  }

  @Test
  void callToolPreservesUpstreamIsError() throws Exception {
    FakeClient client = new FakeClient("fs");
    client.outcomes.put("boom", new McpCallOutcome(true, "upstream failure text"));
    McpCallCapability capability = new McpCallCapability(registry(client), executor);

    EnvironmentCapabilityResult result =
        new RecordingListener()
            .execute(
                capability,
                request("mcp.call", "{\"server\":\"fs\",\"tool\":\"boom\",\"arguments\":{}}"));
    assertTrue(result.error());
    assertTrue(
        ((TextToolContent) result.contents().get(0)).text().contains("upstream failure text"));
  }

  @Test
  void callToolFailsDeterministicallyForUnknownServerAndTool() throws Exception {
    FakeClient client = new FakeClient("fs");
    McpCallCapability capability = new McpCallCapability(registry(client), executor);

    EnvironmentCapabilityResult unknownServer =
        new RecordingListener()
            .execute(
                capability,
                request("mcp.call", "{\"server\":\"missing\",\"tool\":\"t\",\"arguments\":{}}"));
    assertTrue(unknownServer.error());
    assertTrue(
        ((TextToolContent) unknownServer.contents().get(0))
            .text()
            .contains("MCP server is unknown or not ready"));

    // 未知工具是本地精确校验的确定性错误：client.call 绝不触达。
    EnvironmentCapabilityResult unknownTool =
        new RecordingListener()
            .execute(
                capability,
                request("mcp.call", "{\"server\":\"fs\",\"tool\":\"nope\",\"arguments\":{}}"));
    assertTrue(unknownTool.error());
    assertTrue(
        ((TextToolContent) unknownTool.contents().get(0)).text().contains("unknown MCP tool"));
    assertEquals(0, client.calls.get());

    // READY 冻结列表中的工具仍正常调用。
    client.outcomes.put("read_file", new McpCallOutcome(false, "ok"));
    EnvironmentCapabilityResult known =
        new RecordingListener()
            .execute(
                capability,
                request("mcp.call", "{\"server\":\"fs\",\"tool\":\"read_file\",\"arguments\":{}}"));
    assertFalse(known.error());
    assertEquals(1, client.calls.get());
  }

  @Test
  void callToolRejectsNonObjectArgumentsAtRequestBoundary() {
    // arguments 必须是真实 JSON 对象；非对象在 ToolExecutionRequest 构造边界被拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> request("mcp.call", "{\"server\":\"fs\",\"tool\":\"t\",\"arguments\":\"string\"}"));
  }

  @Test
  void cancelProducesExactlyOneTerminalCallback() throws Exception {
    McpCallCapability capability = new McpCallCapability(registry(new FakeClient("fs")), executor);
    EnvironmentCapabilityExecutionRequest request =
        request("mcp.call", "{\"server\":\"fs\",\"tool\":\"echo\",\"arguments\":{}}");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle = capability.execute(request, listener);
    handle.cancel();
    handle.cancel();
    EnvironmentCapabilityResult result = listener.awaitComplete();
    assertTrue(result.error());
    assertTrue(((TextToolContent) result.contents().get(0)).text().contains("Operation cancelled"));
    assertEquals(1, listener.terminalCount.get());
  }

  /** MCP 基座必须把共享 executor 上的阻塞任务中断为单一取消终态，并在提交前校验 descriptor。 */
  @Test
  void abstractBridgeExecutionUsesInjectedExecutorAndInterruptsOnCancel() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AbstractMcpBridgeCapability blocking =
        new AbstractMcpBridgeCapability(
            registry(new FakeClient("fs")),
            executor,
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LIST)) {
          @Override
          EnvironmentCapabilityResult run(
              EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
            started.countDown();
            new CountDownLatch(1).await();
            throw new IllegalStateException("unreachable");
          }
        };
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        blocking.execute(request("mcp.list", "{}"), listener);
    assertTrue(started.await(5, TimeUnit.SECONDS));

    handle.cancel();
    handle.cancel();

    EnvironmentCapabilityResult result = listener.awaitComplete();
    assertTrue(result.error());
    assertTrue(((TextToolContent) result.contents().get(0)).text().contains("Operation cancelled"));
    assertEquals(1, listener.terminalCount.get());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            blocking.execute(
                request("mcp.call", "{\"server\":\"fs\",\"tool\":\"echo\",\"arguments\":{}}"),
                new RecordingListener()));
  }

  /** 未覆盖 failureMessage 的桥接基座异常仍必须转为不抛出的 Tool error。 */
  @Test
  void abstractBridgeConvertsUnhandledExceptionToToolError() {
    AbstractMcpBridgeCapability failing =
        new AbstractMcpBridgeCapability(
            registry(new FakeClient("fs")),
            executor,
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LIST)) {
          @Override
          EnvironmentCapabilityResult run(
              EnvironmentCapabilityExecutionRequest request, Execution execution) {
            throw new IllegalStateException("bridge failed");
          }
        };

    EnvironmentCapabilityResult result =
        new RecordingListener().execute(failing, request("mcp.list", "{}"));

    assertTrue(result.error());
    assertTrue(((TextToolContent) result.contents().get(0)).text().contains("bridge failed"));
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

  private static EnvironmentCapabilityExecutionRequest request(
      String capabilityId, String argumentsJson) {
    return new EnvironmentCapabilityExecutionRequest(
        EnvironmentCapabilityCatalog.require(
            switch (capabilityId) {
              case "mcp.list" -> EnvironmentCapabilityIds.MCP_LIST;
              case "mcp.call" -> EnvironmentCapabilityIds.MCP_CALL;
              default -> throw new IllegalArgumentException(
                  "unknown test capability: " + capabilityId);
            }),
        new EnvironmentCapabilityCall("call-1", argumentsJson),
        Duration.ofSeconds(30),
        null);
  }

  private static final class FakeClient implements McpServerClient {

    private final String name;
    private final Map<String, McpCallOutcome> outcomes = new LinkedHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger listCalls = new AtomicInteger();
    private volatile List<McpToolSpec> tools =
        List.of(
            new McpToolSpec(
                "read_file",
                "Read a file",
                "{\"name\":\"read_file\",\"description\":\"Read a file\","
                    + "\"parameters\":{\"type\":\"object\",\"properties\":{}}}"),
            new McpToolSpec("echo", "Echo", "{\"name\":\"echo\"}"),
            new McpToolSpec("sum", "Sum", "{\"name\":\"sum\"}"),
            new McpToolSpec("boom", "Boom", "{\"name\":\"boom\"}"));
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
      listCalls.incrementAndGet();
      return tools;
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

  private static final class RecordingListener implements EnvironmentCapabilityExecutionListener {

    private final CountDownLatch terminal = new CountDownLatch(1);
    private final AtomicReference<EnvironmentCapabilityResult> result = new AtomicReference<>();
    private final AtomicInteger terminalCount = new AtomicInteger();

    private EnvironmentCapabilityResult execute(
        EnvironmentCapability capability, EnvironmentCapabilityExecutionRequest request) {
      capability.execute(request, this);
      return awaitComplete();
    }

    private EnvironmentCapabilityResult awaitComplete() {
      try {
        assertTrue(
            terminal.await(5, TimeUnit.SECONDS), "capability must produce a terminal callback");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
      return result.get();
    }

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {}

    @Override
    public void onComplete(EnvironmentCapabilityResult complete) {
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
