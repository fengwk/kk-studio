package fun.fengwk.kkstudio.harness.model.cache;

import java.util.Objects;

/**
 * Provider 请求要携带的最终缓存控制指令。
 *
 * <p>只保留三个稳定事实：留存档位 {@link PromptCacheRetention}、会话级 {@code key} 与可选的只读消息断点。{@code key} 直接使用
 * Session UUID 文本；{@code retention = NONE} 时 {@code key} 为 {@code null}，表示不启用任何 Provider 端缓存。{@code
 * messageBreakpoint} 只在需要“只读已有前缀、不写入新缓存”的请求（压缩分支）中出现：它是最终 wire 消息列表中的下标，编码器只在该条消息上写一个断点，绝不再标记
 * tools/system 或其它消息。具体到各 Provider 协议的 cache 参数映射由 Adapter 完成。
 */
public record ProviderCacheControl(
    PromptCacheRetention retention, String key, Integer messageBreakpoint) {

  public ProviderCacheControl {
    retention = Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      if (key != null && !key.isEmpty()) {
        throw new IllegalArgumentException("key must be null or empty when retention is NONE");
      }
      if (messageBreakpoint != null) {
        throw new IllegalArgumentException("messageBreakpoint requires a non-NONE retention");
      }
      key = null;
    } else if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must be non-blank when retention is " + retention);
    }
    if (messageBreakpoint != null && messageBreakpoint < 0) {
      throw new IllegalArgumentException("messageBreakpoint must not be negative");
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

  /**
   * 工厂：只读缓存，仅在最终 wire 消息列表的指定下标写一个断点。
   *
   * @param retention 非 NONE 的留存档位
   * @param sessionId durable Session UUID 文本；不可为空白
   * @param messageIndex 应被标记的最终消息下标；不可为负
   */
  public static ProviderCacheControl readOnlyMessage(
      PromptCacheRetention retention, String sessionId, int messageIndex) {
    Objects.requireNonNull(retention, "retention");
    if (retention == PromptCacheRetention.NONE) {
      // NONE 忽略调用方标识，归一化为完全不启用缓存。
      return none();
    }
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    return new ProviderCacheControl(retention, sessionId, messageIndex);
  }
}
