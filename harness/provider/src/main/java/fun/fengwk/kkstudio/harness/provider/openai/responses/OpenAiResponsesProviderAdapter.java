package fun.fengwk.kkstudio.harness.provider.openai.responses;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
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

  /** 与 {@link #create(ProviderDescriptor)} 的 Provider 使用同一 encoder 的请求编码器，供无网络预览复用。 */
  private final OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

  public OpenAiResponsesProviderAdapter(JdkHttpSseTransport transport, String apiKey) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.apiKey = apiKey;
  }

  /** 以持久化配置 JSON 构造适配器；配置在构造期即严格校验，malformed 配置在此确定性失败，绝不推迟到请求执行。 */
  public OpenAiResponsesProviderAdapter(
      JdkHttpSseTransport transport, String apiKey, String configJson) {
    this(transport, apiKey);
    OpenAiResponsesConfig.parse(configJson);
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
    return new OpenAiResponsesModelProvider(transport, descriptor, apiKey, responsesUri);
  }

  @Override
  public byte[] encodeRequestBody(ProviderRequest request, ProviderDescriptor descriptor) {
    return encoder.encode(request, descriptor).bodyUtf8Bytes();
  }

  /**
   * 基于持久化配置 JSON 解析提示缓存留存档位。
   *
   * @param configJson 配置 JSON 字符串
   * @return 对应的 PromptCacheRetention
   */
  public static PromptCacheRetention resolvePromptCacheRetention(String configJson) {
    return OpenAiResponsesConfig.resolvePromptCacheRetention(configJson);
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

  @Override
  public String toString() {
    return "OpenAiResponsesProviderAdapter[providerType=" + providerType() + "]";
  }
}
