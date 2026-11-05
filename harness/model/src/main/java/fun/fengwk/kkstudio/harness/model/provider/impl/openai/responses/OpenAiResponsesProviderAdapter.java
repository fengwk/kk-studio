package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.JdkHttpSseTransport;

import java.net.URI;
import java.util.Objects;

/** OpenAI Responses 流式协议适配器。 */
public final class OpenAiResponsesProviderAdapter implements ProviderAdapter {

  private final JdkHttpSseTransport transport;
  private final String apiKey;

  /** 与 {@link #create(ProviderDescriptor)} 的 Provider 使用同一 encoder 的请求编码器，供无网络预览复用。 */
  private final OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

  public OpenAiResponsesProviderAdapter(JdkHttpSseTransport transport, String apiKey) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.apiKey = apiKey;
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.OPENAI_RESPONSES;
  }

  @Override
  public ProviderMediaCapabilities mediaCapabilities() {
    return providerType().mediaCapabilities();
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != ProviderType.OPENAI_RESPONSES) {
      throw new IllegalArgumentException(
          "descriptor type mismatch: expected OPENAI_RESPONSES but was " + descriptor.type());
    }
    URI responsesUri = OpenAiResponsesEndpoints.resolveResponsesUri(descriptor.endpoint());
    return new OpenAiResponsesModelProvider(transport, descriptor, apiKey, responsesUri);
  }

  @Override
  public byte[] encodeRequestBody(ProviderRequest request, ProviderDescriptor descriptor) {
    return encoder.encode(request, descriptor).bodyUtf8Bytes();
  }

  @Override
  public String toString() {
    return "OpenAiResponsesProviderAdapter[providerType=" + providerType() + "]";
  }
}
