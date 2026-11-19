package fun.fengwk.kkstudio.harness.model.cache;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider 请求要携带的最终缓存控制指令。
 *
 * <p>包含留存档位、会话级 key 和可选的前缀复用候选。key 使用 Session UUID 文本，NONE 时不下发显式缓存控制。
 * 编码器根据协议、实际端点能力和时效决定是否采用候选；复用仅是尝试，不保证命中或阻止 Provider 写入。
 */
public record ProviderCacheControl(
    PromptCacheRetention retention, String key, PrefixReuse prefixReuse) {

  public ProviderCacheControl {
    retention = Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      if (key != null && !key.isEmpty()) {
        throw new IllegalArgumentException("key must be null or empty when retention is NONE");
      }
      if (prefixReuse != null) {
        throw new IllegalArgumentException("prefixReuse requires a non-NONE retention");
      }
      key = null;
    } else if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must be non-blank when retention is " + retention);
    }
  }

  /** 工厂：禁用 Provider 端缓存控制。 */
  public static ProviderCacheControl none() {
    return new ProviderCacheControl(PromptCacheRetention.NONE, null, null);
  }

  /**
   * 工厂：以 Session 身份作为缓存 key，并使用常规缓存标记。
   *
   * @param retention 非 NONE 的留存档位
   * @param sessionId durable Session UUID 文本；不可为空白
   */
  public static ProviderCacheControl session(PromptCacheRetention retention, String sessionId) {
    Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      // NONE 忽略调用方标识，归一化为完全不启用缓存。
      return none();
    }
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    return new ProviderCacheControl(retention, sessionId, null);
  }

  /** 逻辑 ProviderRequest.messages 下标、历史写入的 TTL 秒数与按原请求起始时间计算的到期时刻。 */
  public record PrefixReuse(int messageIndex, long writeTtlSeconds, Instant expiresAt) {
    public PrefixReuse {
      if (messageIndex < 0 || writeTtlSeconds <= 0) {
        throw new IllegalArgumentException(
            "prefixReuse requires a non-negative index and positive TTL");
      }
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }
}
