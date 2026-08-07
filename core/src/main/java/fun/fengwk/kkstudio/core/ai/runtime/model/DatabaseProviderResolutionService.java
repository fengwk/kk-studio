package fun.fengwk.kkstudio.core.ai.runtime.model;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 按当前 {@code agent_provider} 行解析每次 Model attempt 的 Provider。
 *
 * <p>每次 {@link #resolve} 都按 {@code request.model().providerName()} 读取最新一行，以该行当前的 providerType /
 * baseUrl / credential / config 选择当前 {@link ProviderFactory} 并构造 adapter；Provider 更新后下一次 attempt
 * 立即使用新连接事实，删除后确定性 not found，同名重建后解析到新行。
 *
 * <p>持久 request 中已有的 {@link ProviderCacheControl} 按当前 factory 的 {@link
 * ProviderFactory#promptCacheCapability()} 安全规范化：不兼容能力降级为 {@code none()}，兼容时按当前 capability 重求形态与断点。
 */
@Component
public final class DatabaseProviderResolutionService implements ProviderResolutionService {

  private final AgentProviderRepository providerRepository;
  private final AgentProviderConfigurationCodec providerConfigurationCodec;
  private final ProviderFactories providerFactories;

  public DatabaseProviderResolutionService(
      AgentProviderRepository providerRepository,
      AgentProviderConfigurationCodec providerConfigurationCodec,
      ProviderFactories providerFactories) {
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.providerConfigurationCodec =
        Objects.requireNonNull(providerConfigurationCodec, "providerConfigurationCodec");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
  }

  @Override
  public ResolvedExecution resolve(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    String providerName = request.model().providerName();
    AgentProvider provider = providerRepository.getByName(providerName);
    if (provider == null) {
      throw new IllegalArgumentException("provider not found: " + providerName);
    }
    ProviderType providerType = toProviderType(provider.getProviderType());
    ProviderFactory factory =
        providerFactories
            .lookup(providerType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "ProviderFactory is not registered for " + providerType));
    ModelCallTimeoutPolicy timeoutPolicy =
        providerConfigurationCodec.readTimeoutPolicy(provider.getConfigJson());
    String baseUrl = provider.getBaseUrl();
    // 准入期验证 endpoint 非空白：确定性失败在 resolve 阶段暴露，而不是推迟到 transport。
    new ProviderDescriptor(providerName, providerType, baseUrl, timeoutPolicy);
    ProviderAdapter adapter;
    try {
      adapter = factory.create(provider.getCredential(), provider.getConfigJson());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "cannot create provider adapter for " + providerName, error);
    }
    if (adapter == null) {
      throw new IllegalArgumentException(
          "ProviderFactory returned null adapter for " + providerName);
    }
    if (adapter.providerType() != providerType) {
      throw new IllegalArgumentException(
          "ProviderFactory returned adapter type "
              + adapter.providerType()
              + " for "
              + providerType);
    }
    ProviderRequest effectiveRequest =
        new ProviderRequest(
            request.model(),
            request.variant(),
            request.messages(),
            request.tools(),
            normalizeCacheControl(request, factory.promptCacheCapability()));
    return new ResolvedExecution(
        effectiveRequest,
        timeoutPolicy,
        effectiveTimeoutPolicy -> {
          ProviderDescriptor descriptor =
              new ProviderDescriptor(providerName, providerType, baseUrl, effectiveTimeoutPolicy);
          try {
            var modelProvider = adapter.create(descriptor);
            if (modelProvider == null) {
              throw new IllegalArgumentException(
                  "cannot create ModelProvider for " + providerName + ": null provider");
            }
            return modelProvider;
          } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException
                && error.getMessage() != null
                && error.getMessage().startsWith("cannot create ModelProvider")) {
              throw error;
            }
            throw new IllegalArgumentException(
                "cannot create ModelProvider for " + providerName, error);
          }
        });
  }

  /**
   * 把持久 request 的 cache control 按当前 capability 规范化。永远不构造 capability 不支持的 control；保留旧类型派生的 cache
   * hint 只会发生在它与当前 capability 兼容时，否则安全降级为 {@code none()}。
   */
  private static ProviderCacheControl normalizeCacheControl(
      ProviderRequest request, PromptCacheCapability capability) {
    ProviderCacheControl persisted = request.cacheControl();
    if (persisted.retention() == PromptCacheRetention.NONE) {
      return ProviderCacheControl.none();
    }
    PromptCacheMode mode = capability.mode();
    if (mode == PromptCacheMode.UNKNOWN
        || mode == PromptCacheMode.UNSUPPORTED
        || mode == PromptCacheMode.AUTOMATIC) {
      return ProviderCacheControl.none();
    }
    PromptCacheRetention retention = supportedRetention(persisted.retention(), capability);
    if (retention == PromptCacheRetention.NONE) {
      return ProviderCacheControl.none();
    }
    String affinityKey = persisted.affinityKey();
    if (mode == PromptCacheMode.AFFINITY) {
      // AFFINITY 形态只允许空 breakpoints；affinity key 沿用持久值（ProviderCacheControl 构造器已保证非空白）。
      return ProviderCacheControl.affinity(retention, affinityKey);
    }
    return breakpointControl(request, capability, retention, affinityKey);
  }

  /** BREAKPOINTS 形态：沿用已有 affinityKey（构造器已保证非空白），按当前 capability 与请求实际内容重新求 breakpoint 交集。 */
  private static ProviderCacheControl breakpointControl(
      ProviderRequest request,
      PromptCacheCapability capability,
      PromptCacheRetention retention,
      String affinityKey) {
    Set<PromptCacheBreakpoint> supported = capability.supportedBreakpoints();
    EnumSet<PromptCacheBreakpoint> resolved = EnumSet.noneOf(PromptCacheBreakpoint.class);
    if (supported.contains(PromptCacheBreakpoint.SYSTEM) && hasLeadingSystem(request)) {
      resolved.add(PromptCacheBreakpoint.SYSTEM);
    }
    if (supported.contains(PromptCacheBreakpoint.TOOLS) && !request.tools().isEmpty()) {
      resolved.add(PromptCacheBreakpoint.TOOLS);
    }
    if (resolved.isEmpty()) {
      return ProviderCacheControl.none();
    }
    return ProviderCacheControl.breakpoints(retention, affinityKey, resolved);
  }

  /** retention 优先沿用持久 control，其次 SHORT；都不支持时返回 NONE 使调用方整体降级。 */
  private static PromptCacheRetention supportedRetention(
      PromptCacheRetention preferred, PromptCacheCapability capability) {
    if (capability.supports(preferred)) {
      return preferred;
    }
    if (capability.supports(PromptCacheRetention.SHORT)) {
      return PromptCacheRetention.SHORT;
    }
    return PromptCacheRetention.NONE;
  }

  private static boolean hasLeadingSystem(ProviderRequest request) {
    if (request.messages().isEmpty()) {
      return false;
    }
    ProviderMessage first = request.messages().get(0);
    return first.role() == ProviderMessageRole.SYSTEM;
  }

  private static ProviderType toProviderType(AgentProviderType type) {
    if (type == null) {
      throw new IllegalArgumentException("provider type must not be null");
    }
    return switch (type) {
      case openai -> ProviderType.OPENAI;
      case openai_response -> ProviderType.OPENAI_RESPONSES;
      case anthropic -> ProviderType.ANTHROPIC;
      case google -> ProviderType.GOOGLE;
    };
  }
}
