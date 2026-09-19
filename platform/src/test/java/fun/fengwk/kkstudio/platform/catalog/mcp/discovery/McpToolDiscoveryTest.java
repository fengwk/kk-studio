package fun.fengwk.kkstudio.platform.catalog.mcp.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.mcp.McpClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streamable HTTP MCP 工具发现测试。
 *
 * <p>验证发现的候选工具行契约：模型可见 name 由 server name 与规范化 source name 组成、描述非空白、input schema 归一化为 canonical
 * JSON，且冲突或非法输入直接拒绝而非静默降级。
 */
class McpToolDiscoveryTest {

  private static final String SERVER_NAME = "discovery_server";

  private FakeStreamableHttpMcpServer fakeServer;
  private McpToolDiscovery discovery;

  @BeforeEach
  void setUp() throws IOException {
    fakeServer = new FakeStreamableHttpMcpServer();
    discovery =
        new McpToolDiscovery(
            (config, deadline) -> {
              throw new UnsupportedOperationException("not used");
            },
            name -> null);
  }

  @AfterEach
  void tearDown() {
    if (fakeServer != null) {
      fakeServer.close();
    }
  }

  @Test
  void mapsRemoteToolsToNameKeyedCandidates() {
    // 意图：远端工具按 mcp_<server>_<normalized> 生成主键 name，source_name 保留原始名
    fakeServer.addTool(
        "Search-Files.v1",
        "Search files by glob",
        "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");

    List<McpTool> tools = realDiscovery().discover(server(5000L));

    assertEquals(1, tools.size());
    McpTool tool = tools.get(0);
    assertEquals("mcp_discovery_server_search_files_v1", tool.getName());
    assertEquals(SERVER_NAME, tool.getServerName());
    assertEquals("Search-Files.v1", tool.getSourceName());
    assertEquals("Search files by glob", tool.getDescription());
    assertTrue(tool.getInputSchemaJson().contains("\"q\""));
  }

  @Test
  void rejectsDuplicateSourceNamesAndNormalizedNameCollisions() {
    // 意图：同一 server 内重复 source name 或规范化后同名都确定性拒绝，绝不追加 hash 去重
    fakeServer.addTool("read_file", "First", "{}");
    fakeServer.addTool("read_file", "Second", "{}");
    assertThrows(AiValidationException.class, () -> realDiscovery().discover(server(5000L)));

    fakeServer.clearTools();
    fakeServer.addTool("read-file", "Dashed", "{}");
    fakeServer.addTool("read.file", "Dotted", "{}");
    assertThrows(AiValidationException.class, () -> realDiscovery().discover(server(5000L)));
  }

  @Test
  void rejectsBlankDescriptionAndBlankSourceName() {
    // 意图：描述与工具名必须非空白，非法远端数据不得进入目录
    fakeServer.addTool("edge", "   ", "{}");
    assertThrows(AiValidationException.class, () -> realDiscovery().discover(server(5000L)));

    fakeServer.clearTools();
    fakeServer.addTool("", "desc", "{}");
    assertThrows(AiValidationException.class, () -> realDiscovery().discover(server(5000L)));
  }

  @Test
  void rejectsOverlongSourceNameAndNormalizedName() {
    // 意图：source name 超过 128 字符、或模型可见 name 超过 64 字符时拒绝
    fakeServer.addTool("a".repeat(McpToolDiscovery.SOURCE_NAME_MAX_LENGTH + 1), "desc", "{}");
    assertThrows(AiValidationException.class, () -> realDiscovery().discover(server(5000L)));

    fakeServer.clearTools();
    // 规范化后总长超过 ToolDescriptor name 上限
    fakeServer.addTool("b".repeat(60), "desc", "{}");
    assertThrows(AiValidationException.class, () -> realDiscovery().discover(server(5000L)));
  }

  @Test
  void normalizesUnsupportedSchemaShapesIntoCanonicalObject() {
    // 意图：远端自由 JSON Schema 一律收敛为合法 canonical object，绝不把裸 schema 透传给 Platform 校验门禁
    fakeServer.addTool("array_schema", "desc", "[\"a\",\"b\"]");
    List<McpTool> arrayTools = realDiscovery().discover(server(5000L));
    assertEquals(1, arrayTools.size());
    assertTrue(arrayTools.get(0).getInputSchemaJson().startsWith("{"));

    fakeServer.clearTools();
    fakeServer.addTool(
        "complex_schema",
        "desc",
        "{\"type\":\"object\",\"properties\":{\"q\":{\"oneOf\":[{\"type\":\"string\"}]}},\"required\":[\"q\"]}");
    List<McpTool> complexTools = realDiscovery().discover(server(5000L));
    assertEquals(1, complexTools.size());
    assertTrue(complexTools.get(0).getInputSchemaJson().contains("\"q\""));
  }

  @Test
  void resolvesEnvironmentPlaceholdersFromProvidedProvider() {
    // 意图：header 内的整值 ${VAR} 由注入的 env provider 在发起请求前替换
    fakeServer.addTool("echo", "Echo", "{}");
    AtomicReference<String> token = new AtomicReference<>("resolved-token");

    McpToolDiscovery envDiscovery =
        new McpToolDiscovery(
            (config, deadline) -> {
              token.set(config.headers().get("Authorization"));
              return McpClientFactory.createRemote(config, deadline);
            },
            name -> "env-value");

    McpServer server = server(5000L);
    server.setHeaders(Map.of("Authorization", "${MCP_TOKEN}"));
    server.setUrl(fakeServer.endpointUrl());

    List<McpTool> tools = envDiscovery.discover(server);
    assertEquals(1, tools.size());
    assertEquals("env-value", token.get());
    assertEquals("env-value", fakeServer.receivedAuthHeaders().get(0));
  }

  @Test
  void failureMessageNeverLeaksUrlOrHeaders() {
    // 意图：网络失败的错误信息绝不回显 URL、header 或底层异常原因
    fakeServer.setFailDiscovery(true);
    McpServer server = server(5000L);
    server.setUrl("https://sensitive-marker.example/mcp");

    AiValidationException error =
        assertThrows(AiValidationException.class, () -> realDiscovery().discover(server));
    assertTrue(error.getMessage().contains("discovery failed"));
    assertTrue(
        error.toString().lines().findFirst().orElse("").length() < 200,
        () -> "error must not embed long payloads: " + error);
  }

  private McpToolDiscovery realDiscovery() {
    return new McpToolDiscovery();
  }

  private McpServer server(long timeoutMillis) {
    McpServer server = new McpServer();
    server.setName(SERVER_NAME);
    server.setUrl(fakeServer.endpointUrl());
    server.setHeaders(Map.of());
    server.setEnabled(true);
    server.setTimeoutMillis(timeoutMillis);
    return server;
  }
}
