package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;

/** 文本内容。 */
public record ProviderTextBlock(String text) implements ProviderContentBlock {

  public ProviderTextBlock {
    text = Objects.requireNonNull(text, "text");
  }
}
