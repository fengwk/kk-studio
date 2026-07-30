package fun.fengwk.kkstudio.core.ai.runtime.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis realtime projection configuration.
 *
 * <p>The realtime key prefix must be non-blank. Stream maximum length is resolved from the global
 * database policy.
 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.redis")
public class HarnessRedisProperties {

  private String realtimeKeyPrefix = "kk-studio:harness:realtime:";

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
