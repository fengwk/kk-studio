package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;

/** Provider 输出中尚无通用语义投影的原生 block。 */
public record ProviderOpaqueBlock(String blockType, ProviderBlockData providerBlockData)
    implements ProviderContentBlock {

  public ProviderOpaqueBlock {
    if (blockType == null || blockType.isBlank()) {
      throw new IllegalArgumentException("blockType must not be blank");
    }
    providerBlockData = Objects.requireNonNull(providerBlockData, "providerBlockData");
  }
}
