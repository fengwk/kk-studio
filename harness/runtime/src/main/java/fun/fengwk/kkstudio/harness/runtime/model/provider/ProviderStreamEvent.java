package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** Provider 产生的增量事件。最终响应由 {@link ProviderStreamHandler#onComplete} 单独交付。 */
public sealed interface ProviderStreamEvent
    permits ProviderStreamEvent.TextDelta,
        ProviderStreamEvent.ThinkingDelta,
        ProviderStreamEvent.ToolCallDelta {

  /** 文本增量。 */
  record TextDelta(String text) implements ProviderStreamEvent {
    public TextDelta {
      Objects.requireNonNull(text, "text");
    }
  }

  /** 思考内容增量。 */
  record ThinkingDelta(String text) implements ProviderStreamEvent {
    public ThinkingDelta {
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * 以源顺序索引标识的工具调用增量。
   *
   * <p>id、name、argumentsJson 都是可选的增量片段，至少一个必须存在；完整调用只由 {@link ProviderResponse} 提供。
   */
  record ToolCallDelta(int index, String id, String name, String argumentsJson)
      implements ProviderStreamEvent {
    public ToolCallDelta {
      if (index < 0) {
        throw new IllegalArgumentException("index must not be negative");
      }
      if (id == null && name == null && argumentsJson == null) {
        throw new IllegalArgumentException("tool call delta must contain at least one field");
      }
      if (id != null && id.isBlank()) {
        throw new IllegalArgumentException("id must not be blank when present");
      }
      if (name != null && name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank when present");
      }
    }
  }
}
