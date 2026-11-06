package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.JdkHttpSseTransport;

import java.net.URI;
import java.util.Objects;

/** Anthropic 消息流式协议适配器。 */
public final class AnthropicProviderAdapter implements ProviderAdapter {

  private final JdkHttpSseTransport transport;
  private final String apiKey;

  /** 与 {@link #create(ProviderDescriptor)} 的 Provider 使用相同编码规则，供无网络预览复用。 */
  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();

  public AnthropicProviderAdapter(JdkHttpSseTransport transport, String apiKey) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.apiKey = apiKey;
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.ANTHROPIC;
  }

  @Override
  public ProviderMediaCapabilities mediaCapabilities() {
    return providerType().mediaCapabilities();
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != ProviderType.ANTHROPIC) {
      throw new IllegalArgumentException(
          "descriptor type mismatch: expected ANTHROPIC but was " + descriptor.type());
    }
    URI messagesUri = AnthropicEndpoints.resolveMessagesUri(descriptor.endpoint());
    return new AnthropicModelProvider(transport, descriptor, apiKey, messagesUri);
  }

  @Override
  public byte[] encodeRequestBody(ProviderRequest request, ProviderDescriptor descriptor) {
    return encoder.encode(request, descriptor).bodyUtf8Bytes();
  }

  @Override
  public String toString() {
    return "AnthropicProviderAdapter[providerType=" + providerType() + "]";
  }
}
