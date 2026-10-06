package fun.fengwk.kkstudio.harness.runtime.model.cache;

import java.util.Objects;

/**
 * Provider 请求要携带的最终缓存控制指令。
 *
 * <p>只保留两个稳定事实：留存档位 {@link PromptCacheRetention} 与会话级 {@code key}。{@code key} 直接使用 Session
 * UUID 文本，不再派生哈希；{@code retention = NONE} 时 {@code key} 为 {@code null}，表示不启用任何 Provider 端缓存。
 * 具体到各 Provider 协议的 cache 参数映射由 Adapter 完成。
 */
public record ProviderCacheControl(PromptCacheRetention retention, String key) {

  public ProviderCacheControl {
    retention = Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      if (key != null && !key.isEmpty()) {
        throw new IllegalArgumentException("key must be null or empty when retention is NONE");
      }
      key = null;
    } else if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must be non-blank when retention is " + retention);
    }
  }

  /** 工厂：禁用 Provider 端缓存控制。 */
  public static ProviderCacheControl none() {
    return new ProviderCacheControl(PromptCacheRetention.NONE, null);
  }

  /**
   * 工厂：以 Session 身份作为缓存 key。
   *
   * @param retention 非 NONE 的留存档位
   * @param sessionId durable Session UUID 文本；不可为空白
   */
  public static ProviderCacheControl session(PromptCacheRetention retention, String sessionId) {
    Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      return none();
    }
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    return new ProviderCacheControl(retention, sessionId);
  }
}
