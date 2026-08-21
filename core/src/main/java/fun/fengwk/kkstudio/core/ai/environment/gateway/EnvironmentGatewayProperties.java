package fun.fengwk.kkstudio.core.ai.environment.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment Daemon gateway 的部署级密钥与 WebSocket 单帧上限。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.environment-gateway")
public class EnvironmentGatewayProperties {

  static final long DEFAULT_MAX_MESSAGE_BYTES = 16L * 1024 * 1024;

  /** 部署级共享密钥，敏感字段：HELLO 握手时用于校验 daemon 身份（常量时间比较），不允许出现在日志或公共输出 中。 */
  private String daemonToken;

  /**
   * Daemon WebSocket 单帧上限（字节）。协议安全边界，不进 SystemSettings。可由 {@code
   * KK_STUDIO_ENVIRONMENT_GATEWAY_MAX_MESSAGE_BYTES} 覆盖。
   */
  private long maxMessageBytes = DEFAULT_MAX_MESSAGE_BYTES;

  /** 返回附加 Environment Daemon 所需的部署级密钥。 */
  public String requireDaemonToken() {
    if (daemonToken == null || daemonToken.isBlank()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.daemon-token must not be blank");
    }
    return daemonToken;
  }

  /** 返回可用于 JSR-356 / Spring WebSocket 缓冲的单帧上限。 */
  public int requireMaxMessageBytes() {
    if (maxMessageBytes <= 0L || maxMessageBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.max-message-bytes must be between 1 and "
              + Integer.MAX_VALUE);
    }
    return (int) maxMessageBytes;
  }
}
