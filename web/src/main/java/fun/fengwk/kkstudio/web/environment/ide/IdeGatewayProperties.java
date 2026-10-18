package fun.fengwk.kkstudio.web.environment.ide;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Duration;
import java.util.Objects;

/**
 * IDE 数据面（Studio 同源 Gateway）的部署配置。
 *
 * <p>网关为每个 exchange 铸造一次性 ticket 并生成 Studio 根相对的受控 streamUrl（形如 {@code
 * /api/harness/environment-stream/v1/{ticket}?environment={id}}），由 Daemon 按已配置的 Studio 地址解析并回连。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.ide-gateway")
public class IdeGatewayProperties {

  /** 受控 stream endpoint 的固定路径；Daemon 侧同样要求该前缀。 */
  static final String STREAM_PATH = "/api/harness/environment-stream/v1";

  private static final Duration DEFAULT_PAIRING_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(12);

  /** 等待 Daemon 反向连接的超时；超时即关闭浏览器侧并放弃在途 OPEN_STREAM。 */
  private Duration pairingTimeout = DEFAULT_PAIRING_TIMEOUT;

  /** 单条浏览器 exchange 的整体上限（含请求体与响应体转发）。 */
  private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

  /** 浏览器 IDE 会话 cookie 有效期（path 隔离、HttpOnly）。 */
  private Duration sessionTtl = DEFAULT_SESSION_TTL;

  /** 浏览器 IDE 会话 cookie 名。 */
  private String sessionCookieName = "kk_ide_session";

  /**
   * 生成某 ticket 的受控 Studio 根相对 streamUrl，附带 {@code environment} 查询，使非 owner 节点可据此定位 READY owner
   * 做单跳转发。
   */
  public String streamUrl(EnvironmentId environmentId, String ticket) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(ticket, "ticket");
    return STREAM_PATH + "/" + ticket + "?environment=" + environmentId;
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
