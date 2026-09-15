package fun.fengwk.kkstudio.harness.provider.openai.chat;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.URI;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

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

  /**
   * 由连接配置 {@code openAiChatMediaTypes} 派生的内联媒体能力：配置的 IMAGE/AUDIO/PDF 分别映射为
   * IMAGE/AUDIO/DOCUMENT，未配置即为空（默认 {@link ProviderMediaCapabilities#NONE} 语义）；工具结果只接受文本与
   * JSON，因此永远为空。这是本编码器实际可编码的 schema，不构成厂商能力承诺。
   */
  @Override
  public ProviderMediaCapabilities mediaCapabilities() {
    Set<ModelInputModality> userModalities = EnumSet.noneOf(ModelInputModality.class);
    for (OpenAiChatConfiguration.MediaType mediaType : configuration.mediaTypes()) {
      switch (mediaType) {
        case IMAGE -> userModalities.add(ModelInputModality.IMAGE);
        case AUDIO -> userModalities.add(ModelInputModality.AUDIO);
        case PDF -> userModalities.add(ModelInputModality.DOCUMENT);
      }
    }
    return new ProviderMediaCapabilities(userModalities, Set.of());
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
