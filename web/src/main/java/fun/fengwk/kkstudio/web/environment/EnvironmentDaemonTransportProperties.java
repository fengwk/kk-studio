package fun.fengwk.kkstudio.web.environment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import fun.fengwk.kkstudio.share.notification.NotificationLimits;

import java.time.Duration;

/**
 * Environment Daemon WebSocket 传输边界配置（出站队列容量/字节与发送超时）。
 *
 * <p>单帧不再有独立的 16 MiB 上限：所有物理帧都是共享 carrier 的片，物理上限固定为 {@link
 * fun.fengwk.kkstudio.share.notification.NotificationCarrier#PAYLOAD_LIMIT}，逻辑消息预算固定为 {@link
 * NotificationLimits#DEFAULT_MAX_MESSAGE_BYTES}。这里只保留仍实际生效的出站队列/字节预算与发送期限。
 *
 * <p>这些是传输层安全边界，不属于 Environment 会话状态，也不构成任何 Environment 并发配额：同一 Environment 的调用并发由会话核心按 {@code
 * invocationId} 独立持有。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.environment-gateway")
public class EnvironmentDaemonTransportProperties {

  static final int DEFAULT_QUEUE_CAPACITY = 256;
  static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;
  static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);

  /** 每连接出站待发送逻辑消息数上限（含在途消息），不进 SystemSettings。 */
  private int queueCapacity = DEFAULT_QUEUE_CAPACITY;

  /** 每连接出站待发送逻辑消息 UTF-8 总字节上限（含在途消息），不得低于单条逻辑消息上限。 */
  private long maxBytes = DEFAULT_MAX_BYTES;

  /** 单批 WebSocket 发送超时，超时后连接按传输失败关闭，不进 SystemSettings。 */
  private Duration sendTimeout = DEFAULT_SEND_TIMEOUT;

  /** 返回每连接出站待发送逻辑消息数上限。 */
  public int requireQueueCapacity() {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.queue-capacity must be positive");
    }
    return queueCapacity;
  }

  /** 返回每连接出站待发送逻辑消息 UTF-8 总字节上限；必须容纳单条逻辑消息上限。 */
  public int requireMaxBytes() {
    if (maxBytes < NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES || maxBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.max-bytes must be between "
              + NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES
              + " and "
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

  /** 逻辑 carrier/重组/出站预算：共享默认硬上限，仅队列容量与字节预算取自部署配置。 */
  public NotificationLimits requireNotificationLimits() {
    NotificationLimits defaults = NotificationLimits.defaults();
    return new NotificationLimits(
        defaults.maxMessageBytes(),
        requireMaxBytes(),
        requireQueueCapacity(),
        defaults.reassemblyBytes(),
        defaults.reassemblyMessages(),
        defaults.reassemblyTimeout(),
        defaults.sendBatchFrames());
  }
}
