package fun.fengwk.kkstudio.harness.api.session;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;

import java.util.Objects;

/** Provider 返回的不可读、不可跨模型投影的 redacted thinking block。 */
public record RedactedThinkingMessageContent(String data, ProviderBlockData providerBlockData)
    implements AgentMessageContent {

  public RedactedThinkingMessageContent {
    data = Objects.requireNonNull(data, "data");
  }

  public RedactedThinkingMessageContent(String data) {
    this(data, null);
  }
}
