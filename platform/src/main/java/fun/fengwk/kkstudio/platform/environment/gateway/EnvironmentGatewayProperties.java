package fun.fengwk.kkstudio.platform.environment.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Environment Daemon gateway 的传输边界配置。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.environment-gateway")
public class EnvironmentGatewayProperties {

  static final long DEFAULT_MAX_MESSAGE_BYTES = 16L * 1024 * 1024;
  static final int DEFAULT_QUEUE_CAPACITY = 256;
  static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;
  static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Daemon WebSocket 单帧上限（字节）。协议安全边界，不进 SystemSettings。可由 {@code
   * KK_STUDIO_ENVIRONMENT_GATEWAY_MAX_MESSAGE_BYTES} 覆盖。
   */
  private long maxMessageBytes = DEFAULT_MAX_MESSAGE_BYTES;

  /** 每连接出站待发送帧数上限（含正在发送的帧），不进 SystemSettings。 */
  private int queueCapacity = DEFAULT_QUEUE_CAPACITY;

  /** 每连接出站待发送 UTF-8 总字节上限（含正在发送的帧），不进 SystemSettings。 */
  private long maxBytes = DEFAULT_MAX_BYTES;

  /** 单帧 WebSocket 发送超时，超时后连接按传输失败关闭，不进 SystemSettings。 */
  private Duration sendTimeout = DEFAULT_SEND_TIMEOUT;

  /** 返回可用于 JSR-356 / Spring WebSocket 缓冲的单帧上限。 */
  public int requireMaxMessageBytes() {
    if (maxMessageBytes <= 0L || maxMessageBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.max-message-bytes must be between 1 and "
              + Integer.MAX_VALUE);
    }
    return (int) maxMessageBytes;
  }

  /** 返回每连接出站待发送帧数上限。 */
  public int requireQueueCapacity() {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.queue-capacity must be positive");
    }
    return queueCapacity;
  }

  /** 返回每连接出站待发送 UTF-8 总字节上限。 */
  public int requireMaxBytes() {
    if (maxBytes <= 0L || maxBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.max-bytes must be between 1 and "
              + Integer.MAX_VALUE);
    }
    return (int) maxBytes;
  }

  /** 返回可用于 Spring WebSocket 发送限制的正整数毫秒超时。 */
  public int requireSendTimeoutMillis() {
    if (sendTimeout == null
        || sendTimeout.isZero()
        || sendTimeout.isNegative()
        || sendTimeout.toMillis() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.send-timeout must be between 1ms and "
              + Integer.MAX_VALUE
              + "ms");
    }
    return Math.toIntExact(sendTimeout.toMillis());
  }
}
