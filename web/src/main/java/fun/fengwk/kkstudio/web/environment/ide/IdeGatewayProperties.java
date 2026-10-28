package fun.fengwk.kkstudio.web.environment.ide;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * IDE 数据面（Studio 同源 Gateway）的部署配置。
 *
 * <p>浏览器入口固定在 {@code /environment-ide/{environmentId}/{viewId}/...}；Daemon 的复用数据连接入口固定为 {@code
 * /api/harness/environment-data/v2/{environmentId}?ticket=<opaque>}。{@code dataStreamBaseUrl} 只用于生成
 * DATA_BIND 下发的受控绝对地址，必须由部署方配置为 Daemon 可达的 Studio 同源 origin。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.ide-gateway")
public class IdeGatewayProperties {

  /** 受控数据连接 endpoint 的固定路径；Daemon 侧同样要求该前缀。 */
  static final String DATA_PATH = "/api/harness/environment-data/v2";

  private static final Duration DEFAULT_DATA_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(12);
  private static final String DEFAULT_DATA_STREAM_BASE_URL = "http://127.0.0.1:8080";

  /** 等待 Daemon 回连数据连接的超时；超时即 fail-closed，不复用票据、不自动重试。 */
  private Duration dataConnectTimeout = DEFAULT_DATA_CONNECT_TIMEOUT;

  /** 单条浏览器 exchange 等待首个响应、以及传输中两次进展之间的最长空闲；持续有进展的长传输不受整体时长限制。 */
  private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

  /** 浏览器 IDE 会话 cookie 有效期（路径隔离、HttpOnly）。入口授权实现由浏览器会话切片维护。 */
  private Duration sessionTtl = DEFAULT_SESSION_TTL;

  /** 浏览器 IDE 会话 cookie 名。 */
  private String sessionCookieName = "kk_ide_session";

  /**
   * DATA_BIND 使用的受控 Studio 绝对 origin（可含 context path）；生产必须配置为 Daemon 可达的同源地址。
   *
   * <p>只接受不含 userinfo、query、fragment 的 HTTP(S) 地址。127.0.0.1 等只适合本机开发，不代表远程 Daemon 可回连。
   */
  private String dataStreamBaseUrl = DEFAULT_DATA_STREAM_BASE_URL;

  /** 生成当前数据代际的绝对 DATA_BIND 入口。ticket 只出现在查询中，URL 不写日志。 */
  public URI dataStreamUrl(EnvironmentId environmentId, long generation, String ticket) {
    Objects.requireNonNull(environmentId, "environmentId");
    if (generation <= 0) {
      throw new IllegalArgumentException("generation must be positive");
    }
    if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("ticket must be 43 base64url characters");
    }
    return URI.create(
        requireDataStreamBaseUrl() + DATA_PATH + "/" + environmentId + "?ticket=" + ticket);
  }

  /** 校验并返回不含结尾斜杠的受控数据 origin。 */
  public String requireDataStreamBaseUrl() {
    String value = dataStreamBaseUrl;
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "kk-studio.harness.ide-gateway.data-stream-base-url must not be blank");
    }
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "kk-studio.harness.ide-gateway.data-stream-base-url must be a valid URI", error);
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(
          "kk-studio.harness.ide-gateway.data-stream-base-url must be an HTTP(S) origin");
    }
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  public Duration requireDataConnectTimeout() {
    return requirePositive(dataConnectTimeout, "data-connect-timeout");
  }

  public Duration requireRequestTimeout() {
    return requirePositive(requestTimeout, "request-timeout");
  }

  public Duration requireSessionTtl() {
    return requirePositive(sessionTtl, "session-ttl");
  }

  public String requireSessionCookieName() {
    String name = sessionCookieName;
    if (name == null || name.isBlank() || !name.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalStateException(
          "kk-studio.harness.ide-gateway.session-cookie-name must be a simple cookie token");
    }
    return name;
  }

  private static Duration requirePositive(Duration value, String field) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(
          "kk-studio.harness.ide-gateway." + field + " must be positive");
    }
    return value;
  }
}
