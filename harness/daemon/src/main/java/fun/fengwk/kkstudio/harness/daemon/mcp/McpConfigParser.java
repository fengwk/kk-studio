package fun.fengwk.kkstudio.harness.daemon.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 严格解析 {@code --mcp-config} 的 UTF-8 JSON。
 *
 * <p>格式：{@code {"servers":[...]}}。无兼容别名、无未知字段、无重复键/重复 server 名；transport 不适用字段直接拒绝。 URL 必须是其 scheme
 * 的规范绝对 URI（http/https 或 ws/wss，无 fragment）；headers/environment 只做结构校验，值绝不外报。
 */
public final class McpConfigParser {

  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private McpConfigParser() {}

  /** 读取并严格解析配置文件；任何读取/解析失败都抛出 {@link IllegalArgumentException}。 */
  public static McpConfig parse(Path path) {
    Objects.requireNonNull(path, "path");
    String json;
    try {
      json = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalArgumentException("cannot read mcp-config: " + path, error);
    }
    return parseJson(json);
  }

  /** 严格解析 JSON 文本（测试与生产共用同一入口）。 */
  public static McpConfig parseJson(String json) {
    JsonNode value;
    try {
      value = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("mcp-config must be strict UTF-8 JSON", error);
    }
    if (!(value instanceof ObjectNode root)) {
      throw new IllegalArgumentException("mcp-config must be a JSON object");
    }
    rejectUnknown(root, Set.of("servers"), "mcp-config");
    JsonNode serversNode = root.get("servers");
    if (serversNode == null || !serversNode.isArray()) {
      throw new IllegalArgumentException("mcp-config.servers must be an array");
    }
    List<McpServerConfig> servers = new ArrayList<>();
    int index = 0;
    for (JsonNode element : serversNode) {
      if (!(element instanceof ObjectNode node)) {
        throw new IllegalArgumentException("mcp-config.servers[" + index + "] must be an object");
      }
      servers.add(parseServer(node, index));
      index++;
    }
    try {
      return new McpConfig(servers);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("invalid mcp-config: " + error.getMessage(), error);
    }
  }

  private static McpServerConfig parseServer(ObjectNode node, int index) {
    String context = "mcp-config.servers[" + index + "]";
    rejectUnknown(
        node,
        Set.of("name", "transport", "timeoutSeconds", "command", "environment", "url", "headers"),
        context);
    String name = requiredText(node, "name", context);
    String transportText = requiredText(node, "transport", context);
    McpTransportType transport;
    try {
      transport = McpTransportType.fromWire(transportText);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + ".transport must be one of stdio/streamable-http/websocket", error);
    }
    Integer timeoutSeconds = optionalPositiveInt(node, "timeoutSeconds", context);
    List<String> command = optionalStringArray(node, "command", context);
    Map<String, String> environment = optionalStringMap(node, "environment", context);
    String url = optionalText(node, "url", context);
    Map<String, String> headers = optionalStringMap(node, "headers", context);
    McpServerConfig server;
    try {
      server =
          new McpServerConfig(name, transport, timeoutSeconds, command, environment, url, headers);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(context + " is invalid: " + error.getMessage(), error);
    }
    validateUrl(server);
    return server;
  }

  private static void validateUrl(McpServerConfig server) {
    if (server.url() == null) {
      return;
    }
    URI uri;
    try {
      uri = URI.create(server.url());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("invalid server url for " + server.name(), error);
    }
    if (!uri.isAbsolute() || uri.getHost() == null || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "server url must be a canonical absolute URL without fragment: " + server.name());
    }
    String expectedScheme;
    switch (server.transport()) {
      case STREAMABLE_HTTP -> expectedScheme = "http";
      case WEBSOCKET -> expectedScheme = "ws";
      default -> throw new IllegalStateException("url is only valid for http/websocket transports");
    }
    String scheme = uri.getScheme();
    boolean valid =
        expectedScheme.equals(scheme)
            || (expectedScheme.equals("http") && "https".equals(scheme))
            || (expectedScheme.equals("ws") && "wss".equals(scheme));
    if (!valid) {
      throw new IllegalArgumentException(
          "server url scheme must be "
              + expectedScheme
              + " or its secure variant: "
              + server.name());
    }
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(context + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static String optionalText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return value.textValue();
  }

  private static Integer optionalPositiveInt(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a positive integer");
    }
    return value.intValue();
  }

  private static List<String> optionalStringArray(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isArray() || value.isEmpty()) {
      throw new IllegalArgumentException(context + "." + field + " must be a non-empty array");
    }
    List<String> result = new ArrayList<>();
    int index = 0;
    for (JsonNode element : value) {
      if (!element.isTextual() || element.textValue().isBlank()) {
        throw new IllegalArgumentException(
            context + "." + field + "[" + index + "] must be non-blank text");
      }
      result.add(element.textValue());
      index++;
    }
    return List.copyOf(result);
  }

  private static Map<String, String> optionalStringMap(
      ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(context + "." + field + " must be an object");
    }
    Map<String, String> result = new LinkedHashMap<>();
    Iterator<String> names = value.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      JsonNode entry = value.get(name);
      if (!entry.isTextual()) {
        throw new IllegalArgumentException(
            context + "." + field + "." + name + " must be a string");
      }
      result.put(name, entry.textValue());
    }
    return Map.copyOf(result);
  }

  private static void rejectUnknown(ObjectNode node, Set<String> expected, String context) {
    node.fieldNames()
        .forEachRemaining(
            field -> {
              if (!expected.contains(field)) {
                throw new IllegalArgumentException(context + " has unknown field: " + field);
              }
            });
  }
}
