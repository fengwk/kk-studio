package fun.fengwk.kkstudio.harness.daemon.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 单条 MCP server 的本地配置。
 *
 * <p>传输相关字段互斥：stdio 只允许 {@code command}/{@code environment}；streamable-http/websocket 只允许 {@code
 * url}/{@code headers}。不属于当前 transport 的字段在解析期被拒绝，绝不上报 headers/environment 值。
 */
public record McpServerConfig(
    String name,
    McpTransportType transport,
    Integer timeoutSeconds,
    List<String> command,
    Map<String, String> environment,
    String url,
    Map<String, String> headers) {

  private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  public McpServerConfig {
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "server name must be canonical lowercase hyphenated (1-64 chars): " + name);
    }
    transport = Objects.requireNonNull(transport, "transport");
    if (timeoutSeconds != null && timeoutSeconds <= 0) {
      throw new IllegalArgumentException("timeoutSeconds must be positive: " + name);
    }
    requireNonBlankKeys(environment, "environment", name);
    requireNonBlankKeys(headers, "headers", name);
    switch (transport) {
      case STDIO -> {
        if (command == null || command.isEmpty()) {
          throw new IllegalArgumentException("stdio server requires a non-empty command: " + name);
        }
        if (url != null || headers != null) {
          throw new IllegalArgumentException("stdio server must not carry url or headers: " + name);
        }
      }
      case STREAMABLE_HTTP, WEBSOCKET -> {
        if (url == null || url.isBlank()) {
          throw new IllegalArgumentException(
              transport.wireName() + " server requires a url: " + name);
        }
        if (command != null || environment != null) {
          throw new IllegalArgumentException(
              transport.wireName() + " server must not carry command or environment: " + name);
        }
      }
      default -> throw new IllegalArgumentException("unsupported transport: " + transport);
    }
  }

  /** 拒绝空白/空 key：headers 与 environment 的键必须是可用的非空字符串。 */
  private static void requireNonBlankKeys(Map<String, String> map, String field, String name) {
    if (map == null) {
      return;
    }
    for (String key : map.keySet()) {
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException(field + " keys must be non-blank: " + name);
      }
    }
  }
}
