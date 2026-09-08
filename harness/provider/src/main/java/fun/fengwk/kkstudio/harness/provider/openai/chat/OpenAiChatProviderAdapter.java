package fun.fengwk.kkstudio.harness.provider.openai.chat;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.URI;
import java.util.Objects;

/** OpenAI Chat Completions 协议适配器。 */
public final class OpenAiChatProviderAdapter implements ProviderAdapter {

  private final JdkHttpSseTransport transport;
  private final String apiKey;
  private final OpenAiChatConfiguration configuration;

  public OpenAiChatProviderAdapter(JdkHttpSseTransport transport, String apiKey) {
    this(transport, apiKey, OpenAiChatConfiguration.defaults());
  }

  public OpenAiChatProviderAdapter(
      JdkHttpSseTransport transport, String apiKey, String configJson) {
    this(transport, apiKey, OpenAiChatConfiguration.parse(configJson));
  }

  public OpenAiChatProviderAdapter(
      JdkHttpSseTransport transport, String apiKey, OpenAiChatConfiguration configuration) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.apiKey = apiKey;
    this.configuration = Objects.requireNonNull(configuration, "configuration");
  }

  public static PromptCacheCapability promptCacheCapability(String configJson) {
    return OpenAiChatConfiguration.parse(configJson).promptCacheCapability();
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.OPENAI;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != ProviderType.OPENAI) {
      throw new IllegalArgumentException(
          "descriptor type mismatch: expected OPENAI but was " + descriptor.type());
    }
    URI chatUri = OpenAiChatEndpoints.resolveChatCompletionsUri(descriptor.endpoint());
    return new OpenAiChatModelProvider(transport, descriptor, apiKey, chatUri, configuration);
  }

  @Override
  public String toString() {
    return "OpenAiChatProviderAdapter[providerType=" + providerType() + "]";
  }
}
