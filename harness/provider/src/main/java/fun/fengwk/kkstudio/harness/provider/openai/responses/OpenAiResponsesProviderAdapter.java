package fun.fengwk.kkstudio.harness.provider.openai.responses;

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

/** OpenAI Responses 流式协议适配器。 */
public final class OpenAiResponsesProviderAdapter implements ProviderAdapter {

  /**
   * 当前编码器的内联媒体能力：用户消息与工具结果都支持 IMAGE 与 DOCUMENT（{@code input_image.image_url}、{@code
   * input_file.file_data/file_url}）。工具结果的 {@code function_call_output.output} 数组明确支持 {@code
   * input_image} 与 {@code input_file}；音频、视频与任意其他模态都不声明，并由编码器以 {@code INVALID_REQUEST}
   * 拒绝。这是本编码器实际可编码的 schema，不构成厂商能力承诺。
   */
  private static final ProviderMediaCapabilities MEDIA_CAPABILITIES =
      new ProviderMediaCapabilities(
          Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
          Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT));

  private final JdkHttpSseTransport transport;
  private final String apiKey;
  private final OpenAiResponsesConfig config;

  public OpenAiResponsesProviderAdapter(JdkHttpSseTransport transport, String apiKey) {
    this(transport, apiKey, OpenAiResponsesConfig.defaultConfig());
  }

  public OpenAiResponsesProviderAdapter(
      JdkHttpSseTransport transport, String apiKey, String configJson) {
    this(transport, apiKey, OpenAiResponsesConfig.parse(configJson));
  }

  public OpenAiResponsesProviderAdapter(
      JdkHttpSseTransport transport, String apiKey, OpenAiResponsesConfig config) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.apiKey = apiKey;
    this.config = config != null ? config : OpenAiResponsesConfig.defaultConfig();
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.OPENAI_RESPONSES;
  }

  @Override
  public ProviderMediaCapabilities mediaCapabilities() {
    return MEDIA_CAPABILITIES;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != ProviderType.OPENAI_RESPONSES) {
      throw new IllegalArgumentException(
          "descriptor type mismatch: expected OPENAI_RESPONSES but was " + descriptor.type());
    }
    URI responsesUri = OpenAiResponsesEndpoints.resolveResponsesUri(descriptor.endpoint());
    return new OpenAiResponsesModelProvider(transport, descriptor, apiKey, responsesUri, config);
  }

  /**
   * 基于持久化配置 JSON 得到对应的提示缓存能力。
   *
   * @param configJson 配置 JSON 字符串
   * @return 对应的 PromptCacheCapability
   */
  public static PromptCacheCapability resolvePromptCacheCapability(String configJson) {
    return OpenAiResponsesConfig.resolvePromptCacheCapability(configJson);
  }

  /**
   * 解析持久化配置 JSON。
   *
   * @param configJson 配置 JSON 字符串
   * @return 解析后的 OpenAiResponsesConfig
   */
  public static OpenAiResponsesConfig parseConfig(String configJson) {
    return OpenAiResponsesConfig.parse(configJson);
  }

  /** 返回当前适配器实例生效的提示缓存能力。 */
  public PromptCacheCapability promptCacheCapability() {
    return config.promptCacheCapability();
  }

  @Override
  public String toString() {
    return "OpenAiResponsesProviderAdapter[providerType=" + providerType() + "]";
  }
}
