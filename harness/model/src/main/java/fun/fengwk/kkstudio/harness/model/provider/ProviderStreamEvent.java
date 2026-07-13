package fun.fengwk.kkstudio.harness.model.provider;

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

  /** 以源顺序索引标识的完整工具调用增量。 */
  record ToolCallDelta(int index, ProviderToolCall toolCall) implements ProviderStreamEvent {
    public ToolCallDelta {
      if (index < 0) {
        throw new IllegalArgumentException("index must not be negative");
      }
      toolCall = Objects.requireNonNull(toolCall, "toolCall");
    }
  }
}
