package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import java.util.Objects;
import java.util.UUID;

/**
 * Redis realtime overlay 的不可变配置。
 *
 * <p>默认 prefix 为 {@code kk-studio:harness:realtime:}；prefix 必须非 blank。每个 Thread 使用独立的 Redis Pub/Sub
 * channel，channel 名为 {@code prefix + threadId}（canonical UUID 字符串）。
 */
public record RedisRealtimeConfig(String prefix) {

  public static final String DEFAULT_PREFIX = "kk-studio:harness:realtime:";

  /** 使用默认 prefix 构造。 */
  public RedisRealtimeConfig() {
    this(DEFAULT_PREFIX);
  }

  public RedisRealtimeConfig {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("prefix must not be blank");
    }
  }

  /** 返回 Thread 对应的 Redis Pub/Sub channel：{@code prefix + threadId}。 */
  public String channel(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return prefix + threadId;
  }
}
