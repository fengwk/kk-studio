package fun.fengwk.kkstudio.web.environment.ide;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * IDE 数据面（独立 Origin Gateway）的部署配置。
 *
 * <p>{@link #streamUrlTemplate} 是 Daemon 可达的受控反向 stream endpoint 基址（{@code ws}/{@code wss}）；Gateway
 * 为每个 exchange 铸造一次性 ticket 并把它作为最后一段追加。缺省时 IDE 数据面明确不可用，绝不回退到 Studio 同源或任意 proxy。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.ide-gateway")
public class IdeGatewayProperties {

  /** 受控 stream endpoint 的固定路径；Daemon 侧同样要求该前缀。 */
  static final String STREAM_PATH = "/api/harness/environment-stream/v1";

  private static final Duration DEFAULT_PAIRING_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(12);

  /** 形如 {@code wss://gateway.example.com/api/harness/environment-stream/v1}；留空表示不提供 IDE 数据面。 */
  private String streamUrlTemplate;

  /** 等待 Daemon 反向连接的超时；超时即关闭浏览器侧并放弃在途 OPEN_STREAM。 */
  private Duration pairingTimeout = DEFAULT_PAIRING_TIMEOUT;

  /** 单条浏览器 exchange 的整体上限（含请求体与响应体转发）。 */
  private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

  /** 浏览器 IDE 会话 cookie 有效期（host-only、HttpOnly）。 */
  private Duration sessionTtl = DEFAULT_SESSION_TTL;

  /** 浏览器 IDE 会话 cookie 名。 */
  private String sessionCookieName = "kk_ide_session";

  public boolean configured() {
    return streamUrlTemplate != null && !streamUrlTemplate.isBlank();
  }

  /** 生成某 ticket 的受控 streamUrl；未配置或非法时抛 {@link IllegalStateException}（fail-closed）。 */
  public String streamUrl(String ticket) {
    Objects.requireNonNull(ticket, "ticket");
    if (!configured()) {
      throw new IllegalStateException(
          "kk-studio.harness.ide-gateway.stream-url-template is not configured");
    }
    URI uri;
    try {
      uri = new URI(streamUrlTemplate);
    } catch (URISyntaxException error) {
      throw new IllegalStateException("stream-url-template is not a valid URI", error);
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      throw new IllegalStateException("stream-url-template must declare a scheme");
    }
    String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
    if (!normalizedScheme.equals("ws") && !normalizedScheme.equals("wss")) {
      throw new IllegalStateException("stream-url-template scheme must be ws or wss");
    }
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(
          "stream-url-template must be a bare ws(s) endpoint without userinfo/query/fragment");
    }
    String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
    if (!STREAM_PATH.equals(path)) {
      throw new IllegalStateException("stream-url-template path must be " + STREAM_PATH);
    }
    return normalizedScheme + "://" + uri.getAuthority() + STREAM_PATH + "/" + ticket;
  }

  public Duration requirePairingTimeout() {
    return requirePositive(pairingTimeout, "pairing-timeout");
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
