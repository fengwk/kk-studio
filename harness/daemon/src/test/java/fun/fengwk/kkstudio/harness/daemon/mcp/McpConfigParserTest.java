package fun.fengwk.kkstudio.harness.daemon.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** {@code --mcp-config} 严格解析：未知字段、transport 不适用字段、URL 规范性与重复名称。 */
class McpConfigParserTest {

  @Test
  void parsesStdioServer() {
    McpConfig config =
        McpConfigParser.parseJson(
            "{"
                + "\"servers\":[{\"name\":\"fs\",\"transport\":\"stdio\","
                + "\"timeoutSeconds\":30,"
                + "\"command\":[\"npx\",\"-y\",\"@modelcontextprotocol/server-filesystem\"],"
                + "\"environment\":{\"LANG\":\"C\"}}]}");

    McpServerConfig server = config.servers().get(0);
    assertEquals("fs", server.name());
    assertEquals(McpTransportType.STDIO, server.transport());
    assertEquals(30, server.timeoutSeconds());
    assertEquals(List.of("npx", "-y", "@modelcontextprotocol/server-filesystem"), server.command());
    assertEquals(Map.of("LANG", "C"), server.environment());
    assertNull(server.url());
    assertNull(server.headers());
  }

  @Test
  void parsesHttpAndWebsocketServers() {
    McpConfig config =
        McpConfigParser.parseJson(
            "{"
                + "\"servers\":["
                + "{\"name\":\"remote\",\"transport\":\"streamable-http\","
                + "\"url\":\"https://mcp.example.com/mcp\",\"headers\":{\"Authorization\":\"Bearer x\"}},"
                + "{\"name\":\"ws-server\",\"transport\":\"websocket\","
                + "\"url\":\"wss://mcp.example.com/mcp\"}]}");

    assertEquals(2, config.servers().size());
    assertEquals(McpTransportType.STREAMABLE_HTTP, config.servers().get(0).transport());
    assertEquals("https://mcp.example.com/mcp", config.servers().get(0).url());
    assertEquals(Map.of("Authorization", "Bearer x"), config.servers().get(0).headers());
    assertNull(config.servers().get(0).command());
    assertEquals(McpTransportType.WEBSOCKET, config.servers().get(1).transport());
    assertNull(config.servers().get(1).headers());
  }

  @Test
  void parsesEmptyServers() {
    assertEquals(0, McpConfigParser.parseJson("{\"servers\":[]}").servers().size());
    // servers 是必填键：{} 不是合法格式。
    assertThrows(IllegalArgumentException.class, () -> McpConfigParser.parseJson("{}"));
  }

  @Test
  void rejectsUnknownFieldsAndDuplicateKeys() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"x\"],\"extra\":1}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpConfigParser.parseJson("{\"servers\":[],\"unknown\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"name\":\"b\",\"transport\":\"stdio\",\"command\":[\"x\"]}]}"));
  }

  @Test
  void rejectsDuplicateServerNames() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":["
                    + "{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"x\"]},"
                    + "{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"y\"]}]}"));
  }

  @Test
  void rejectsNonCanonicalNamesAndTransports() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"A_b\",\"transport\":\"stdio\",\"command\":[\"x\"]}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"sse\",\"command\":[\"x\"]}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpConfigParser.parseJson("{\"servers\":[{\"name\":\"a\",\"transport\":1}]}"));
  }

  @Test
  void rejectsServerNameBeyond64Chars() {
    String tooLong = "a".repeat(65);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\""
                    + tooLong
                    + "\",\"transport\":\"stdio\",\"command\":[\"x\"]}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new McpServerConfig(
                tooLong, McpTransportType.STDIO, null, List.of("x"), null, null, null));
    // 64 字符边界仍合法。
    String maxLength = "a".repeat(64);
    assertEquals(
        maxLength,
        McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\""
                    + maxLength
                    + "\",\"transport\":\"stdio\",\"command\":[\"x\"]}]}")
            .servers()
            .get(0)
            .name());
  }

  @Test
  void rejectsBlankEnvironmentAndHeaderKeys() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"fs\",\"transport\":\"stdio\",\"command\":[\"x\"],"
                    + "\"environment\":{\"\":\"value\"}}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"remote\",\"transport\":\"streamable-http\","
                    + "\"url\":\"https://mcp.example.com/mcp\",\"headers\":{\" \":\"x\"}}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new McpServerConfig(
                "fs",
                McpTransportType.STDIO,
                null,
                List.of("x"),
                Map.of("  ", "value"),
                null,
                null));
  }

  @Test
  void rejectsTransportInapplicableFields() {
    // stdio 不允许 url/headers。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"x\"],\"url\":\"https://x\"}]}"));
    // http 不允许 command/environment。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"streamable-http\",\"url\":\"https://x\",\"command\":[\"x\"]}]}"));
    // websocket 不允许 environment。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"websocket\",\"url\":\"wss://x\",\"environment\":{\"A\":\"b\"}}]}"));
    // stdio 必须携带非空 command。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson("{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[]}]}"));
  }

  @Test
  void rejectsInvalidUrlsForScheme() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"streamable-http\",\"url\":\"wss://x\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"websocket\",\"url\":\"https://x\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"streamable-http\",\"url\":\"x\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"websocket\",\"url\":\"wss://x#frag\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"streamable-http\",\"url\":\"https://\"}]}"));
  }

  @Test
  void rejectsInvalidTimeoutAndNonStringValues() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"x\"],\"timeoutSeconds\":0}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"x\"],\"timeoutSeconds\":-1}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"x\"],\"environment\":{\"A\":1}}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            McpConfigParser.parseJson(
                "{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"x\",\"\"]}]}"));
  }

  @Test
  void rejectsNonStrictJsonAndUnreadableFile(@TempDir Path dir) {
    assertThrows(
        IllegalArgumentException.class,
        () -> McpConfigParser.parseJson("{\"servers\":[]} trailing"));
    assertThrows(IllegalArgumentException.class, () -> McpConfigParser.parseJson("not json"));
    assertThrows(IllegalArgumentException.class, () -> McpConfigParser.parseJson("[]"));
    Path missing = dir.resolve("missing.json");
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> McpConfigParser.parse(missing));
    assertTrue(error.getMessage().contains("cannot read mcp-config"));
  }

  @Test
  void readsStrictUtf8File(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("mcp.json");
    Files.writeString(
        file, "{\"servers\":[{\"name\":\"a\",\"transport\":\"stdio\",\"command\":[\"x\"]}]}");
    assertEquals(1, McpConfigParser.parse(file).servers().size());
  }
}
