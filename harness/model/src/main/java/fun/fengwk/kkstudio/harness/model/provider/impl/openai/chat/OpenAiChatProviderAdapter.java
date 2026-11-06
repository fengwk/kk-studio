package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.JdkHttpSseTransport;

import java.net.URI;
import java.util.Objects;

/** OpenAI Chat Completions 协议适配器。 */
public final class OpenAiChatProviderAdapter implements ProviderAdapter {

  private final JdkHttpSseTransport transport;
  private final String apiKey;

  /** 与 {@link #create(ProviderDescriptor)} 的 Provider 使用同一 encoder 的请求编码器，供无网络预览复用。 */
  private final OpenAiChatRequestEncoder encoder = new OpenAiChatRequestEncoder();

  public OpenAiChatProviderAdapter(JdkHttpSseTransport transport, String apiKey) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.apiKey = apiKey;
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.OPENAI;
  }

  /**
   * Chat Completions 的用户内容 schema 原生支持图片、音频与文件；具体模型是否接受仍由模型输入模态约束。tool message 只接受文本，因此工具结果媒体能力为空。
   */
  @Override
  public ProviderMediaCapabilities mediaCapabilities() {
    return providerType().mediaCapabilities();
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != ProviderType.OPENAI) {
      throw new IllegalArgumentException(
          "descriptor type mismatch: expected OPENAI but was " + descriptor.type());
    }
    URI chatUri = OpenAiChatEndpoints.resolveChatCompletionsUri(descriptor.endpoint());
    return new OpenAiChatModelProvider(transport, descriptor, apiKey, chatUri);
  }

  @Override
  public byte[] encodeRequestBody(ProviderRequest request, ProviderDescriptor descriptor) {
    return encoder.encode(request, descriptor).bodyUtf8Bytes();
  }

  @Override
  public String toString() {
    return "OpenAiChatProviderAdapter[providerType=" + providerType() + "]";
  }
}
