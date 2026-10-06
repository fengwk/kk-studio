package fun.fengwk.kkstudio.platform.harness.model;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;

import java.util.Objects;
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
      ProviderResourceMaterializer resourceMaterializer) {
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.providerConfigurationCodec =
        Objects.requireNonNull(providerConfigurationCodec, "providerConfigurationCodec");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
    this.resourceMaterializer =
        Objects.requireNonNull(resourceMaterializer, "resourceMaterializer");
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
    ProviderRequest effectiveRequest =
        new ProviderRequest(
            request.model(),
            request.variant(),
            request.outputTokens(),
            request.systemInstruction(),
            // 每次 attempt 物化 durable Resource：durable 请求只含 blobId/name/preview，内联 media 仅存在于有效请求。
            // 内联位置能力来自当前 adapter：协议或连接配置不支持的媒体一律退化为文本回退。
            resourceMaterializer.materialize(
                request.messages(), request.model().inputModalities(), adapter.mediaCapabilities()),
            request.tools(),
            // cache control 已在规划期冻结（retention + session key）；执行期不再按当前 capability 重新规范化。
            request.cacheControl());
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
        },
        // 请求体预览与 openProvider 共用同一 adapter 与同一 descriptor 语义；未实现预览的 adapter 原样抛出
        // UnsupportedOperationException，由调用方显式拒绝。
        bodyRequest -> {
          ProviderDescriptor descriptor =
              new ProviderDescriptor(
                  providerName, providerType, baseUrl, timeoutPolicy, connectionGenerationId);
          return adapter.encodeRequestBody(bodyRequest, descriptor);
        });
  }

}
