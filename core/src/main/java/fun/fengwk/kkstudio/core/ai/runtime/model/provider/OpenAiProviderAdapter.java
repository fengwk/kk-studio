package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
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
   * OpenAI 兼容 adapter 的凭据。{@code null} 或空白表示 adapter 必须不带 {@code Authorization} 头 调用端点（当 builder
   * {@code apiKey} 未设置时 {@code DefaultOpenAiClient} 支持）；任何提供的值都会由 SDK 原样转发为 {@code Bearer …}。
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
        // SDK DefaultOpenAiClient 只在 apiKey != null 时发出 Authorization。null/blank 时完全不调用
        // setter，使未认证的 OpenAI 兼容端点可用。
        if (apiKey != null) {
          builder.apiKey(apiKey);
        }
        return builder.build();
      }

      @Override
      protected void validateRequest(ProviderRequest request) {
        prepareCacheControl(request);
      }

      @Override
      protected boolean extractsThinkTags(ProviderRequest request) {
        // OpenAI 兼容代理常把 reasoning 折叠进纯文本的 <think> 块。
        // 当模型被标记为 reasoning-capable 时总是拆分标签，无论宿主是否为 MiniMax，
        // thinking 都不会泄漏进 assistant 文本。
        return request.model().reasoning();
      }

      @Override
      protected boolean reportsToolCallsWithOtherFinishReason(ProviderRequest request) {
        // MiniMax 兼容端点对携带可执行 tool call 的回合上报 OTHER/STOP。该特例只在本 adapter 的 MiniMax
        // 宿主上生效；公共映射绝不根据 hasToolCalls 覆盖 generation stop reason。
        return minimax;
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
}
