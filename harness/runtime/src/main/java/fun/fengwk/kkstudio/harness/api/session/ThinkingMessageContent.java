package fun.fengwk.kkstudio.harness.api.session;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;

import java.util.Objects;

/** 模型思考内容。 */
public record ThinkingMessageContent(String text, ProviderBlockData providerBlockData)
    implements AgentMessageContent {
  public ThinkingMessageContent {
    text = Objects.requireNonNull(text, "text");
  }

  public ThinkingMessageContent(String text) {
    this(text, null);
  }
}
