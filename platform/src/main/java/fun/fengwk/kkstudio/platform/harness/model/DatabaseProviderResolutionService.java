package fun.fengwk.kkstudio.platform.harness.model;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 按当前 {@code agent_provider} 行解析每次 Model attempt 的 Provider。
 *
 * <p>每次 {@link #resolve} 都按 {@code request.model().providerName()} 读取最新一行，并在创建 adapter 前校验
 * providerType 与 connection generation 均等于 invocation 冻结值。endpoint、credential 或协议配置变化以及同名重建都会轮换
 * generation，使既有 invocation 确定性拒绝；不轮换 generation 的 timeout-only 更新保持 attempt-time live，删除后确定性 not
 * found。
 *
 * <p>持久 request 中已有的 {@link ProviderCacheControl} 按当前 {@code provider.configJson} 下 factory 的
 * {@link ProviderFactory#promptCacheCapability(String)} 规范化：当前 capability 无法表达时降级为 {@code
 * none()}，否则按当前 capability 重求形态、retention 与断点。
 */
@Component
public final class DatabaseProviderResolutionService implements ProviderResolutionService {

  private final AgentProviderRepository providerRepository;
  private final AgentProviderConfigurationCodec providerConfigurationCodec;
  private final ProviderFactories providerFactories;
  private final ProviderResourceMaterializer resourceMaterializer;

  public DatabaseProviderResolutionService(
      AgentProviderRepository providerRepository,
      AgentProviderConfigurationCodec providerConfigurationCodec,
      ProviderFactories providerFactories,
      ObjectProvider<ProviderResourceMaterializer> resourceMaterializerProvider) {
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.providerConfigurationCodec =
        Objects.requireNonNull(providerConfigurationCodec, "providerConfigurationCodec");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
    ProviderResourceMaterializer materializer =
        resourceMaterializerProvider == null ? null : resourceMaterializerProvider.getIfAvailable();
    // Storage 不可用（S3 未启用）时降级为 no-op 物化端口：Resource 块只投影为确定性文本回退，模型仍可感知资源存在。
    this.resourceMaterializer =
        materializer == null ? ProviderResourceMaterializer.withoutStorage() : materializer;
  }

  @Override
  public ResolvedExecution resolve(
      ProviderType frozenType, UUID frozenConnectionGenerationId, ProviderRequest request) {
    Objects.requireNonNull(frozenType, "frozenType");
    Objects.requireNonNull(frozenConnectionGenerationId, "frozenConnectionGenerationId");
    Objects.requireNonNull(request, "request");
    String providerName = request.model().providerName();
    AgentProvider provider = providerRepository.getByName(providerName);
    if (provider == null) {
      throw new IllegalArgumentException("provider not found: " + providerName);
    }
    ProviderType providerType = provider.getProviderType();
    if (providerType == null) {
      throw new IllegalArgumentException("provider type must not be null");
    }
    if (providerType != frozenType) {
      throw new IllegalArgumentException(
          "provider type drift: frozen=" + frozenType + " current=" + providerType);
    }
    ProviderFactory factory =
        providerFactories
            .lookup(providerType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "ProviderFactory is not registered for " + providerType));
    ModelCallTimeoutPolicy timeoutPolicy;
    try {
      timeoutPolicy = providerConfigurationCodec.readTimeoutPolicy(provider.getConfigJson());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("cannot parse provider configuration for " + providerName);
    }
    String baseUrl = provider.getBaseUrl();
    UUID connectionGenerationId = provider.getConnectionGenerationId();
    if (connectionGenerationId == null) {
      throw new IllegalStateException(
          "provider " + providerName + " connectionGenerationId must not be null");
    }
    if (!connectionGenerationId.equals(frozenConnectionGenerationId)) {
      throw new IllegalArgumentException(
          "provider connection generation drift: frozen="
              + frozenConnectionGenerationId
              + " current="
              + connectionGenerationId);
    }
    // 准入期验证 endpoint 非空白：确定性失败在 resolve 阶段暴露，而不是推迟到 transport。
    new ProviderDescriptor(
        providerName, providerType, baseUrl, timeoutPolicy, connectionGenerationId);
    ProviderAdapter adapter;
    try {
      adapter = factory.create(provider.getCredential(), provider.getConfigJson());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("cannot create provider adapter for " + providerName);
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
    PromptCacheCapability promptCacheCapability;
    try {
      promptCacheCapability = factory.promptCacheCapability(provider.getConfigJson());
      if (promptCacheCapability == null) {
        throw new IllegalStateException(
            "ProviderFactory returned null promptCacheCapability for " + providerName);
      }
    } catch (RuntimeException error) {
      // Provider 扩展可能在异常中携带原始配置；此边界只暴露稳定、安全的定位信息，不保留不可信 cause。
      throw new IllegalArgumentException(
          "cannot resolve prompt cache capability for " + providerName);
    }
    ProviderRequest effectiveRequest =
        new ProviderRequest(
            request.model(),
            request.variant(),
            request.outputTokens(),
            // 每次 attempt 物化 durable Resource：durable 请求只含 blobId/name/preview，media URL 仅存在于有效请求。
            resourceMaterializer.materialize(request.messages(), request.model().inputModalities()),
            request.tools(),
            normalizeCacheControl(request, promptCacheCapability));
    return new ResolvedExecution(
        effectiveRequest,
        timeoutPolicy,
        effectiveTimeoutPolicy -> {
          ProviderDescriptor descriptor =
              new ProviderDescriptor(
                  providerName,
                  providerType,
                  baseUrl,
                  effectiveTimeoutPolicy,
                  connectionGenerationId);
          ModelProvider modelProvider;
          try {
            modelProvider = adapter.create(descriptor);
          } catch (RuntimeException error) {
            throw new IllegalArgumentException("cannot create ModelProvider for " + providerName);
          }
          if (modelProvider == null) {
            throw new IllegalArgumentException(
                "cannot create ModelProvider for " + providerName + ": null provider");
          }
          return modelProvider;
        });
  }

  /**
   * 把持久 request 的 cache control 按当前 capability 规范化。仅保留当前 capability 可表达的 retention 与 affinity
   * key，并按当前 capability 重建形态与断点；无法表达时降级为 {@code none()}。
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
    if (supported.contains(PromptCacheBreakpoint.CONVERSATION) && hasConversationContent(request)) {
      resolved.add(PromptCacheBreakpoint.CONVERSATION);
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

  private static boolean hasConversationContent(ProviderRequest request) {
    for (ProviderMessage message : request.messages()) {
      if (message.role() != ProviderMessageRole.SYSTEM && !message.contents().isEmpty()) {
        return true;
      }
    }
    return false;
  }
}
