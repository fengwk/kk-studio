package fun.fengwk.kkstudio.platform.catalog.mcp;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Platform MCP server（{@code mcp_server} 行）：{@code name} 即主键与不可变身份。
 *
 * <p>{@code headers} 是自定义请求 header，可能内嵌凭据，因此 {@link #toString()} 不输出它。{@code timeoutMillis} 为正整数毫秒；
 * {@code version} 是乐观锁 CAS 依据，新建未持久化的实例为 null。
 */
@Builder(toBuilder = true)
public record McpServer(
    String name,
    String url,
    Map<String, String> headers,
    boolean enabled,
    Long timeoutMillis,
    McpDiscoveryStatus discoveryStatus,
    Long version,
    Instant createTime,
    Instant updateTime) {

  @Override
  public String toString() {
    return "McpServer[name="
        + name
        + ", url="
        + url
        + ", enabled="
        + enabled
        + ", timeoutMillis="
        + timeoutMillis
        + ", discoveryStatus="
        + discoveryStatus
        + ", version="
        + version
        + "]";
  }
}
