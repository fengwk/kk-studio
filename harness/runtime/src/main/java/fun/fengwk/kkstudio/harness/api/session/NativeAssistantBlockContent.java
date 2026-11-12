package fun.fengwk.kkstudio.harness.api.session;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;

/** Provider 原生协议产生、没有通用语义投影的 assistant block。 */
public record NativeAssistantBlockContent(String blockType, ProviderBlockData providerBlockData)
    implements AgentMessageContent {

  public NativeAssistantBlockContent {
    if (blockType == null || blockType.isBlank()) {
      throw new IllegalArgumentException("blockType must not be blank");
    }
    if (providerBlockData == null) {
      throw new IllegalArgumentException("providerBlockData must not be null");
    }
  }
}
