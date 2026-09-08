package fun.fengwk.kkstudio.harness.provider.gemini;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.URI;
import java.util.Objects;

/** Google AI Gemini GenerateContent 流式协议适配器。 */
public final class GeminiProviderAdapter implements ProviderAdapter {

  private final JdkHttpSseTransport transport;
  private final String apiKey;

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
  public ModelProvider create(ProviderDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != ProviderType.GOOGLE) {
      throw new IllegalArgumentException(
          "descriptor type mismatch: expected GOOGLE but was " + descriptor.type());
    }
    URI baseUri = GeminiEndpoints.resolveBaseUri(descriptor.endpoint());
    return new GeminiModelProvider(transport, descriptor, apiKey, baseUri);
  }

  /** Gemini 仅支持隐式 Prompt Cache（服务端自动评估并报告 cachedContentTokenCount，不发 cache hint）。 */
  public PromptCacheCapability promptCacheCapability() {
    return PromptCacheCapability.automatic();
  }

  @Override
  public String toString() {
    return "GeminiProviderAdapter[providerType=" + providerType() + "]";
  }
}
