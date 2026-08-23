package fun.fengwk.kkstudio.platform.harness.model.provider;

import com.openai.client.OpenAIClient;
import com.openai.models.ReasoningEffort;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;
import dev.langchain4j.model.openaiofficial.setup.OpenAiOfficialSetup;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.Objects;

/** OpenAI Responses API Provider 适配器。 */
public final class OpenAiResponsesProviderAdapter implements ProviderAdapter {

  private final String apiKey;

  public OpenAiResponsesProviderAdapter(String apiKey) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.OPENAI_RESPONSES;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    OpenAiProviderAdapter.requireType(descriptor, providerType());
    return new LangChainModelProvider(providerType()) {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        String key = CacheRequestValidator.requireOpenAiAffinity(request.cacheControl());
        // LangChain4j 会直接读取 SDK 标为必填的 usage breakdown；兼容端点可能省略这些字段。
        OpenAIClient client =
            OpenAiOfficialSetup.setupSyncClient(
                    descriptor.endpoint(),
                    apiKey,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    request.model().modelName(),
                    descriptor.modelCallTimeoutPolicy().modelCallTimeout(),
                    null,
                    null,
                    null)
                .withOptions(
                    options -> options.jsonMapper(OpenAiResponsesUsageJsonMapper.instance()));
        OpenAiOfficialResponsesStreamingChatModel.Builder builder =
            OpenAiOfficialResponsesStreamingChatModel.builder()
                .client(client)
                .modelName(request.model().modelName());
        String reasoningEffort =
            request.model().reasoning() ? request.variant().reasoningEffort() : null;
        if (reasoningEffort != null) {
          builder.reasoningEffort(ReasoningEffort.of(reasoningEffort));
        }
        // SDK builder 不接受 null promptCacheKey，因此仅在非 NONE 时显式设置。
        if (key != null) {
          builder.promptCacheKey(key);
        }
        return builder.build();
      }

      @Override
      protected void validateRequest(ProviderRequest request) {
        // 校验与键解析与 chatModel 完全一致；幂等并提前抛错，避免在 stub 模型已建立后才 fail。
        CacheRequestValidator.requireOpenAiAffinity(request.cacheControl());
      }
    };
  }
}
