package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** OpenAI 兼容 Chat Completions Provider adapter。 */
public final class OpenAiProviderAdapter implements ProviderAdapter {

  private static final String PROMPT_CACHE_KEY = "prompt_cache_key";
  private static final String REASONING_SPLIT = "reasoning_split";

  /**
   * OpenAI-compatible adapter credential. {@code null} or blank means the adapter must call the
   * endpoint without an {@code Authorization} header (supported by {@code DefaultOpenAiClient} when
   * its builder {@code apiKey} is unset). Any present value is forwarded verbatim as {@code Bearer
   * …} by the SDK.
   */
  private final String apiKey;

  public OpenAiProviderAdapter(String apiKey) {
    this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
  }

  @Override
  public ProviderType providerType() {
    return ProviderType.OPENAI;
  }

  @Override
  public ModelProvider create(ProviderDescriptor descriptor) {
    requireType(descriptor, providerType());
    boolean minimax = isMiniMaxEndpoint(descriptor.endpoint());
    return new LangChainModelProvider(providerType()) {
      @Override
      protected StreamingChatModel chatModel(ProviderRequest request) {
        ProviderCacheControl control = prepareCacheControl(request);
        // 未开启 reasoning 时不请求 thinking、不传 reasoning_effort。
        boolean thinking = request.model().reasoning();
        String reasoningEffort = thinking ? request.variant().reasoningEffort() : null;
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder =
            OpenAiStreamingChatModel.builder()
                .baseUrl(descriptor.endpoint())
                .modelName(request.model().modelName())
                .timeout(descriptor.modelCallTimeoutPolicy().modelCallTimeout())
                .returnThinking(thinking)
                .customParameters(customParameters(control, minimax, reasoningEffort));
        // SDK DefaultOpenAiClient only emits Authorization when apiKey != null. Skip the setter
        // entirely on null/blank so unauthenticated OpenAI-compatible endpoints work.
        if (apiKey != null) {
          builder.apiKey(apiKey);
        }
        return builder.build();
      }

      @Override
      protected void validateRequest(ProviderRequest request) {
        requireProviderType(request, providerType());
        prepareCacheControl(request);
      }

      @Override
      protected boolean extractsThinkTags(ProviderRequest request) {
        // OpenAI-compatible proxies often fold reasoning into plain text as <think> blocks.
        // When the model is marked reasoning-capable, always split tags so thinking does not
        // leak into assistant text regardless of whether the host is MiniMax.
        return request.model().reasoning();
      }

      private ProviderCacheControl prepareCacheControl(ProviderRequest request) {
        // requireOpenAiAffinity 同时被 validateRequest 与 chatModel 调用，作为幂等校验与键解析；LONG 或携带
        // breakpoints 时抛 IllegalArgumentException，被 LangChainModelProvider.stream 转换为
        // ProviderException。
        CacheRequestValidator.requireOpenAiAffinity(request.cacheControl());
        return request.cacheControl();
      }
    };
  }

  static Map<String, Object> customParameters(
      ProviderCacheControl control, boolean minimax, String reasoningEffort) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (control.affinityKey() != null) {
      merged.put(PROMPT_CACHE_KEY, control.affinityKey());
    }
    if (minimax) {
      merged.put(REASONING_SPLIT, true);
    }
    // variant.reasoningEffort → OpenAI-compatible reasoning_effort（null/off 已在 ModelVariant 归一）。
    if (reasoningEffort != null && !reasoningEffort.isBlank()) {
      merged.put("reasoning_effort", reasoningEffort.trim().toLowerCase(Locale.ROOT));
    }
    return merged;
  }

  static Map<String, Object> customParameters(ProviderCacheControl control, boolean minimax) {
    return customParameters(control, minimax, null);
  }

  private static boolean isMiniMaxEndpoint(String endpoint) {
    try {
      String host = URI.create(endpoint).getHost();
      return host != null && host.toLowerCase(Locale.ROOT).contains("minimax");
    } catch (IllegalArgumentException ignored) {
      return endpoint.toLowerCase(Locale.ROOT).contains("minimax");
    }
  }

  static void requireType(ProviderDescriptor descriptor, ProviderType expected) {
    Objects.requireNonNull(descriptor, "descriptor");
    if (descriptor.type() != expected) {
      throw new IllegalArgumentException("provider descriptor type does not match adapter");
    }
  }

  /** 模型描述符上的 providerType 与 adapter 类型不一致时拒绝启动。 */
  static void requireProviderType(ProviderRequest request, ProviderType expected) {
    Objects.requireNonNull(request, "request");
    if (request.model().providerType() != expected) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          "model providerType does not match adapter type " + expected);
    }
  }
}
