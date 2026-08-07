package fun.fengwk.kkstudio.core.ai.environment.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 不受信任的 Environment Daemon gateway payload 的限制。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.environment-gateway")
public class EnvironmentGatewayProperties {

  /** 单个 daemon 资源（SKILL.md 内容、工具结果中的二进制资源等）的大小上限（字节），默认 8 MiB；协议解码时 超限确定性拒绝。 */
  private long maxResourceBytes = 8L * 1024 * 1024;

  /** 单条 daemon 文本帧消息的大小上限（字节），默认 16 MiB；web 层据此配置 WebSocket 文本缓冲区，须不超过 Integer.MAX_VALUE。 */
  private long maxMessageBytes = 16L * 1024 * 1024;

  /** 部署级共享密钥，敏感字段：HELLO 握手时用于校验 daemon 身份（常量时间比较），不允许出现在日志或公共输出 中。 */
  private String daemonToken;

  /**
   * 心跳过期超时：READY 后若 {@code lastSeenAt} 超过该时长未刷新，环境按不可用处理（查询投影与远程工具调用都不得使用）。默认 60 秒，约为 daemon
   * 默认心跳间隔（15 秒）的 4 倍。
   */
  private Duration heartbeatTimeout = Duration.ofSeconds(60);

  /** 返回心跳过期超时（必须为正）。 */
  public Duration requireHeartbeatTimeout() {
    if (heartbeatTimeout == null || heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.heartbeat-timeout must be positive");
    }
    return heartbeatTimeout;
  }

  public long requireMaxResourceBytes() {
    if (maxResourceBytes <= 0) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.max-resource-bytes must be positive");
    }
    return maxResourceBytes;
  }

  /** 返回 WebSocket transport 所需的有界文本帧缓冲区大小。 */
  public int requireMaxMessageBytes() {
    if (maxMessageBytes <= 0 || maxMessageBytes > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.max-message-bytes must be between 1 and "
              + Integer.MAX_VALUE);
    }
    return (int) maxMessageBytes;
  }

  /** 返回附加 Environment Daemon 所需的部署级密钥。 */
  public String requireDaemonToken() {
    if (daemonToken == null || daemonToken.isBlank()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.environment-gateway.daemon-token must not be blank");
    }
    return daemonToken;
  }
}
