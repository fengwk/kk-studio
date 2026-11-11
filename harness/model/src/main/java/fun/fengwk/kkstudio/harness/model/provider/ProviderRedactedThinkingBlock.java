package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;

/** Provider 返回的不可读 redacted thinking block。 */
public record ProviderRedactedThinkingBlock(String data, ProviderBlockData providerBlockData)
    implements ProviderContentBlock {

  public ProviderRedactedThinkingBlock {
    data = Objects.requireNonNull(data, "data");
  }

  public ProviderRedactedThinkingBlock(String data) {
    this(data, null);
  }
}
