package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import java.util.Objects;
import java.util.UUID;

/**
 * Redis realtime overlay 的不可变配置。
 *
 * <p>默认 prefix 为 {@code kk-studio:harness:realtime:}，默认 maxLength 为 5000；prefix 必须非 blank，
 * maxLength 必须为正。每个 Thread 使用独立的 Redis Stream，key 为 {@code prefix + threadId}（canonical UUID 字符串）。
 */
public record RedisRealtimeConfig(String prefix, long maxLength) {

  public static final String DEFAULT_PREFIX = "kk-studio:harness:realtime:";
  public static final long DEFAULT_MAX_LENGTH = 5_000L;

  /** 使用默认 prefix 与默认 maxLength 构造。 */
  public RedisRealtimeConfig() {
    this(DEFAULT_PREFIX, DEFAULT_MAX_LENGTH);
  }

  public RedisRealtimeConfig {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("prefix must not be blank");
    }
    if (maxLength <= 0) {
      throw new IllegalArgumentException("maxLength must be positive");
    }
  }

  /** 返回 Thread 对应的 Redis Stream key：{@code prefix + threadId}。 */
  public String key(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return prefix + threadId;
  }
}
