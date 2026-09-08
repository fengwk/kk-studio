package fun.fengwk.kkstudio.harness.provider.anthropic;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.URI;
import java.util.Objects;

/** Anthropic 消息流式协议适配器。 */
public final class AnthropicProviderAdapter implements ProviderAdapter {

  private final JdkHttpSseTransport transport;
  private final String apiKey;
  private final AnthropicConfiguration configuration;

  public AnthropicProviderAdapter(JdkHttpSseTransport transport, String apiKey) {
    this(transport, apiKey, AnthropicConfiguration.defaults());
  }

  public AnthropicProviderAdapter(JdkHttpSseTransport transport, String apiKey, String configJson) {
    this(transport, apiKey, AnthropicConfiguration.parse(configJson));
  }

  public AnthropicProviderAdapter(
      JdkHttpSseTransport transport, String apiKey, AnthropicConfiguration configuration) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.apiKey = apiKey;
    this.configuration = Objects.requireNonNull(configuration, "configuration");
  }

  public AnthropicConfiguration configuration() {
    return configuration;
  }

  public static AnthropicConfiguration parseConfig(String configJson) {
    return AnthropicConfiguration.parse(configJson);
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.ANTHROPIC;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != ProviderType.ANTHROPIC) {
      throw new IllegalArgumentException(
          "descriptor type mismatch: expected ANTHROPIC but was " + descriptor.type());
    }
    URI messagesUri = AnthropicEndpoints.resolveMessagesUri(descriptor.endpoint());
    return new AnthropicModelProvider(transport, descriptor, apiKey, messagesUri, configuration);
  }

  @Override
  public String toString() {
    return "AnthropicProviderAdapter[providerType=" + providerType() + "]";
  }
}
