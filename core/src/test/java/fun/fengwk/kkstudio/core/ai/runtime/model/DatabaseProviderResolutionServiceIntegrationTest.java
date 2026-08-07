package fun.fengwk.kkstudio.core.ai.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 PostgreSQL 的 attempt-time Provider 解析证据：同一个持久 {@link ProviderRequest} 在 Provider 更新 / 删除 / 同名
 * 重建后，下一次 {@code resolve/start} 始终使用 {@code agent_provider} 当前行与当前 {@link ProviderFactory}；类型变化时持久
 * cache hint 按当前 capability 安全降级。
 */
class DatabaseProviderResolutionServiceIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService providerService;
  @Autowired private AgentProviderRepository providerRepository;
  @Autowired private AgentProviderConfigurationCodec configurationCodec;

  @Test
  void resolveUsesLatestProviderRowAndFactoryAfterUpdate() {
    String name = "latest-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    CapturingFactory openAiResponses = new CapturingFactory(ProviderType.OPENAI_RESPONSES);
    DatabaseProviderResolutionService resolution = resolution(openAi, openAiResponses);

    AgentProviderDTO created =
        createProvider(name, "openai", "https://original.example/v1", "original-secret", 12_000L);
    ProviderRequest request =
        request(name, ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-stable"));

    // 第一次 resolve：使用创建行的 openai factory 与连接事实。
    ProviderResolutionService.ResolvedExecution first = resolution.resolve(request);
    assertEquals(
        ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-stable"),
        first.effectiveRequest().cacheControl(),
        "openai affinity capability 与持久 hint 匹配，retention 沿用 SHORT");
    assertEquals(
        Duration.ofSeconds(12), first.timeoutPolicy().modelCallTimeout(), "超时策略来自当前行 config");
    first.openProvider(first.timeoutPolicy());
    assertEquals("original-secret", openAi.credential, "adapter 使用创建行 credential");
    assertEquals(
        "https://original.example/v1", openAi.descriptor.endpoint(), "adapter 使用创建行 baseUrl");
    assertEquals(
        Duration.ofSeconds(12),
        configurationCodec.readTimeoutPolicy(openAi.configJson).modelCallTimeout(),
        "adapter 使用创建行 config");

    // Provider 更新 providerType/baseUrl/credential/config 后，同一持久 request 下一次 resolve 使用最新行与最新
    // factory。
    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai_response");
    update.setBaseUrl("https://updated.example/v2");
    update.setCredential("updated-secret");
    update.setModelCallTimeoutMillis(60_000L);
    update.setExpectedVersion(created.getVersion());
    providerService.updateProvider(name, update);

    ProviderResolutionService.ResolvedExecution second = resolution.resolve(request);
    assertEquals(
        ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-stable"),
        second.effectiveRequest().cacheControl(),
        "openai_response 同样支持 affinity SHORT，持久 hint 继续生效");
    assertEquals(Duration.ofSeconds(60), second.timeoutPolicy().modelCallTimeout(), "超时策略来自更新后的行");
    second.openProvider(second.timeoutPolicy());
    assertEquals("updated-secret", openAiResponses.credential, "adapter 使用更新行 credential");
    assertEquals(
        "https://updated.example/v2",
        openAiResponses.descriptor.endpoint(),
        "adapter 使用更新行 baseUrl");
    assertEquals(
        Duration.ofSeconds(60),
        configurationCodec.readTimeoutPolicy(openAiResponses.configJson).modelCallTimeout(),
        "adapter 使用更新行 config");
    assertEquals(1, openAiResponses.openCount, "更新后必须使用新 providerType 的 factory");
    assertEquals(1, openAi.openCount, "更新后不得再使用更新前 providerType 的 factory");
  }

  @Test
  void resolveFailsDeterministicallyWhenCurrentRowIsDeleted() {
    String name = "deleted-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    DatabaseProviderResolutionService resolution = resolution(openAi);

    AgentProviderDTO created =
        createProvider(name, "openai", "https://original.example/v1", "secret", 12_000L);
    providerService.deleteProvider(name, created.getVersion());

    ProviderRequest request =
        request(name, ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-key"));
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> resolution.resolve(request));
    assertTrue(
        failure.getMessage().contains("provider not found: " + name),
        "当前行删除后必须确定性 not found，不得使用其他连接配置");
  }

  @Test
  void sameNameRecreateResolvesToTheNewRow() {
    String name = "recreated-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    DatabaseProviderResolutionService resolution = resolution(openAi);

    AgentProviderDTO created =
        createProvider(name, "openai", "https://first.example/v1", "first-secret", 12_000L);
    providerService.deleteProvider(name, created.getVersion());

    AgentProviderDTO recreated =
        createProvider(name, "openai", "https://second.example/v1", "second-secret", 30_000L);
    ProviderRequest request = request(name, ProviderCacheControl.none());

    ProviderResolutionService.ResolvedExecution resolved = resolution.resolve(request);
    resolved.openProvider(resolved.timeoutPolicy());
    assertEquals("second-secret", openAi.credential, "同名重建后解析到新行 credential");
    assertEquals("https://second.example/v1", openAi.descriptor.endpoint(), "同名重建后解析到新行 baseUrl");
    assertEquals(
        Duration.ofSeconds(30), resolved.timeoutPolicy().modelCallTimeout(), "同名重建后解析到新行 config");
    assertEquals("0", recreated.getVersion(), "重建行 version 从 0 重新开始");
  }

  @Test
  void cacheHintUnsupportedByCurrentCapabilityDegradesAfterProviderTypeChange() {
    String name = "type-change-provider-" + System.nanoTime();
    CapturingFactory anthropic = new CapturingFactory(ProviderType.ANTHROPIC);
    CapturingFactory google = new CapturingFactory(ProviderType.GOOGLE);
    DatabaseProviderResolutionService resolution = resolution(anthropic, google);

    // 持久 request 携带 AFFINITY hint；当前 anthropic capability 会按请求内容重建 BREAKPOINTS 形态。
    AgentProviderDTO created =
        createProvider(name, "anthropic", "https://anthropic.example/v1", "secret", 12_000L);
    ProviderRequest request =
        new ProviderRequest(
            descriptor(name),
            new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("leading system")))),
            List.of(),
            ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-key"));

    // 当前 capability 是 BREAKPOINTS：affinity hint 被改写为按请求实际内容求交集的 breakpoints。
    ProviderResolutionService.ResolvedExecution first = resolution.resolve(request);
    assertEquals(
        ProviderCacheControl.breakpoints(
            PromptCacheRetention.SHORT, "pc1-key", EnumSet.of(PromptCacheBreakpoint.SYSTEM)),
        first.effectiveRequest().cacheControl(),
        "leading SYSTEM 与当前 BREAKPOINTS capability 求交集得到 SYSTEM");

    // Provider 类型切换到 google（AUTOMATIC）：当前 capability 不接受显式 hint，因此降级为 none()。
    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("google");
    update.setBaseUrl("https://google.example/v1");
    update.setExpectedVersion(created.getVersion());
    providerService.updateProvider(name, update);

    ProviderResolutionService.ResolvedExecution second = resolution.resolve(request);
    assertEquals(
        ProviderCacheControl.none(),
        second.effectiveRequest().cacheControl(),
        "AUTOMATIC capability 下持久 affinity hint 必须降级为 none()");
    second.openProvider(second.timeoutPolicy());
    assertEquals(
        "https://google.example/v1",
        google.descriptor.endpoint(),
        "降级后使用最新 providerType 的 factory");
  }

  private DatabaseProviderResolutionService resolution(ProviderFactory... factories) {
    return new DatabaseProviderResolutionService(
        providerRepository, configurationCodec, new ProviderFactories(List.of(factories)));
  }

  private AgentProviderDTO createProvider(
      String name, String providerType, String baseUrl, String credential, long timeoutMillis) {
    AgentProviderCreateDTO create = new AgentProviderCreateDTO();
    create.setName(name);
    create.setProviderType(providerType);
    create.setBaseUrl(baseUrl);
    create.setCredential(credential);
    create.setModelCallTimeoutMillis(timeoutMillis);
    return providerService.createProvider(create);
  }

  private static ProviderRequest request(String providerName, ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        descriptor(providerName),
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        cacheControl);
  }

  private static ModelDescriptor descriptor(String providerName) {
    return new ModelDescriptor(
        providerName,
        "model",
        false,
        false,
        new ModelPricing(
            "USD",
            "default",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  /** 记录每次 factory.create / adapter.create 收到的连接事实，证明解析使用当前行与当前 factory。 */
  private static final class CapturingFactory implements ProviderFactory {

    private final ProviderType providerType;
    private String credential;
    private String configJson;
    private ProviderDescriptor descriptor;
    private int openCount;

    private CapturingFactory(ProviderType providerType) {
      this.providerType = providerType;
    }

    @Override
    public ProviderType providerType() {
      return providerType;
    }

    @Override
    public PromptCacheCapability promptCacheCapability() {
      return switch (providerType) {
        case ANTHROPIC -> PromptCacheCapability.breakpoints(
            Set.of(PromptCacheRetention.SHORT),
            EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS));
        case GOOGLE -> PromptCacheCapability.automatic();
        default -> PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT));
      };
    }

    @Override
    public ProviderAdapter create(String credential, String configJson) {
      this.credential = credential;
      this.configJson = configJson;
      return new ProviderAdapter() {
        @Override
        public ProviderType providerType() {
          return CapturingFactory.this.providerType;
        }

        @Override
        public ModelProvider create(ProviderDescriptor descriptor) {
          CapturingFactory.this.descriptor = descriptor;
          openCount++;
          return new ModelProvider() {
            @Override
            public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
              return new ProviderStream() {
                @Override
                public void cancel() {}

                @Override
                public boolean isCancelled() {
                  return false;
                }
              };
            }
          };
        }
      };
    }
  }
}
