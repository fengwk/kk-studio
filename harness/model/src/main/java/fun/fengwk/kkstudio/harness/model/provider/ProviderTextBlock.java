package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;

/** 文本内容。 */
public record ProviderTextBlock(String text, ProviderBlockData providerBlockData)
    implements ProviderContentBlock {

  public ProviderTextBlock {
    text = Objects.requireNonNull(text, "text");
  }

  public ProviderTextBlock(String text) {
    this(text, null);
  }
}
