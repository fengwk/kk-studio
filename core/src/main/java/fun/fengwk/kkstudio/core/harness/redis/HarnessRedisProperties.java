package fun.fengwk.kkstudio.core.harness.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis adapter 配置：activation channel 与 realtime stream key 前缀。
 *
 * <p>默认：{@code signalChannel=kk-studio:harness:signal}、{@code
 * realtimeKeyPrefix=kk-studio:harness:realtime:}。channel 与 prefix 不得为 blank；realtime Stream 的 max
 * length 由全局数据库策略提供。
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.redis")
public class HarnessRedisProperties {

  private String signalChannel = "kk-studio:harness:signal";
  private String realtimeKeyPrefix = "kk-studio:harness:realtime:";

  public String requireSignalChannel() {
    if (signalChannel == null || signalChannel.isBlank()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.redis.signal-channel must not be blank");
    }
    return signalChannel;
  }

  public String requireRealtimeKeyPrefix() {
    if (realtimeKeyPrefix == null || realtimeKeyPrefix.isBlank()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.redis.realtime-key-prefix must not be blank");
    }
    return realtimeKeyPrefix;
  }

  /** 拼接 realtime stream key：{@code prefix + threadId}。 */
  public String realtimeKey(long threadId) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    return requireRealtimeKeyPrefix() + Long.toString(threadId);
  }
}
