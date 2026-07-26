package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** Assistant 返回的推理内容。 */
public record ProviderThinkingBlock(String thinking) implements ProviderContentBlock {

  public ProviderThinkingBlock {
    thinking = Objects.requireNonNull(thinking, "thinking");
  }
}
