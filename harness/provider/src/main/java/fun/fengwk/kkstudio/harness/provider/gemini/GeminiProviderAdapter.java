package fun.fengwk.kkstudio.harness.provider.gemini;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.Objects;

/** Google AI Gemini GenerateContent 流式协议适配器。 */
public final class GeminiProviderAdapter implements ProviderAdapter {

  private final JdkHttpSseTransport transport;
  private final String apiKey;

  /** 与 {@link #create(ProviderDescriptor)} 的 Provider 使用同一 encoder 的请求编码器，供无网络预览复用。 */
  private final GeminiRequestEncoder encoder = new GeminiRequestEncoder();

  public GeminiProviderAdapter(JdkHttpSseTransport transport, String apiKey) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.apiKey = apiKey;
  }

  public GeminiProviderAdapter(JdkHttpSseTransport transport) {
    this(transport, null);
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.GOOGLE;
  }

  @Override
  public ProviderMediaCapabilities mediaCapabilities() {
    return providerType().mediaCapabilities();
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != ProviderType.GOOGLE) {
      throw new IllegalArgumentException(
          "descriptor type mismatch: expected GOOGLE but was " + descriptor.type());
    }
    // 只校验 endpoint 合法性（与其它协议 adapter 同契约），不保留解析结果：请求 URL 由请求期 resolveStreamUri 解析。
    GeminiEndpoints.resolveBaseUri(descriptor.endpoint());
    return new GeminiModelProvider(transport, descriptor, apiKey);
  }

  @Override
  public byte[] encodeRequestBody(ProviderRequest request, ProviderDescriptor descriptor) {
    return encoder.encode(request, descriptor).bodyUtf8Bytes();
  }

  @Override
  public String toString() {
    return "GeminiProviderAdapter[providerType=" + providerType() + "]";
  }
}
