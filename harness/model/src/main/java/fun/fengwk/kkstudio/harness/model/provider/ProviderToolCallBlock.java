package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;

/** Assistant 消息中已完成的工具调用。 */
public record ProviderToolCallBlock(ProviderToolCall toolCall, ProviderBlockData providerBlockData)
    implements ProviderContentBlock {

  public ProviderToolCallBlock {
    toolCall = Objects.requireNonNull(toolCall, "toolCall");
  }

  public ProviderToolCallBlock(ProviderToolCall toolCall) {
    this(toolCall, null);
  }
}
