package fun.fengwk.kkstudio.harness.provider.gemini;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/** Google AI Gemini GenerateContent 流式协议适配器。 */
public final class GeminiProviderAdapter implements ProviderAdapter {

  /** 当前编码器的内联媒体能力：用户消息与工具结果均支持 IMAGE/AUDIO/VIDEO/DOCUMENT。这是本编码器实际可编码的 schema， 不构成厂商能力承诺。 */
  private static final ProviderMediaCapabilities MEDIA_CAPABILITIES =
      new ProviderMediaCapabilities(
          Set.of(
              ModelInputModality.IMAGE,
              ModelInputModality.AUDIO,
              ModelInputModality.VIDEO,
              ModelInputModality.DOCUMENT),
          Set.of(
              ModelInputModality.IMAGE,
              ModelInputModality.AUDIO,
              ModelInputModality.VIDEO,
              ModelInputModality.DOCUMENT));

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
  public ProviderMediaCapabilities mediaCapabilities() {
    return MEDIA_CAPABILITIES;
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
