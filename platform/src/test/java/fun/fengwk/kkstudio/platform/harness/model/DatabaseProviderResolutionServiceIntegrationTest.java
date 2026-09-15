package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 PostgreSQL 的 attempt-time Provider 解析证据：冻结 Provider type 与 connection generation 必须和当前 {@code
 * agent_provider} 行一致；连接配置更新、删除或同名重建后，旧 invocation 确定性拒绝，重新规划的 invocation 才能使用当前
 * credential/endpoint/timeout 与 {@link ProviderFactory}。
 */
class DatabaseProviderResolutionServiceIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService providerService;
  @Autowired private AgentProviderRepository providerRepository;
  @Autowired private AgentProviderConfigurationCodec configurationCodec;
  @Autowired private ProviderResourceMaterializer providerResourceMaterializer;

  @Test
  void resolveRejectsStaleGenerationAndAcceptsReplannedRequestAfterConnectionUpdate() {
    String name = "latest-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    CapturingFactory openAiResponses = new CapturingFactory(ProviderType.OPENAI_RESPONSES);
    DatabaseProviderResolutionService resolution = resolution(openAi, openAiResponses);

    AgentProviderDTO created =
        createProvider(name, "openai", "https://original.example/v1", "original-secret", 12_000L);
    UUID firstGen = connectionGenerationId(name);
    ProviderRequest request =
        request(name, ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-stable"));

    ProviderResolutionService.ResolvedExecution first =
        resolution.resolve(ProviderType.OPENAI, firstGen, request);
    assertEquals(
        ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-stable"),
        first.effectiveRequest().cacheControl());
    assertEquals(Duration.ofSeconds(12), first.timeoutPolicy().modelCallTimeout());
    first.openProvider(first.timeoutPolicy());
    assertEquals("original-secret", openAi.credential);
    assertEquals("https://original.example/v1", openAi.descriptor.endpoint());
    assertEquals(firstGen, openAi.descriptor.connectionGenerationId());

    AgentProviderUpdateDTO liveUpdate = new AgentProviderUpdateDTO();
    liveUpdate.setProviderType("openai");
    liveUpdate.setBaseUrl("https://updated.example/v2");
    liveUpdate.setCredential("updated-secret");
    liveUpdate.setModelCallTimeoutMillis(60_000L);
    liveUpdate.setExpectedVersion(created.getVersion());
    AgentProviderDTO updated = providerService.updateProvider(name, liveUpdate);

    IllegalArgumentException generationDrift =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolution.resolve(ProviderType.OPENAI, firstGen, request));
    assertTrue(generationDrift.getMessage().contains("provider connection generation drift"));
    assertEquals(1, openAi.openCount, "generation 漂移不得打开新 Provider");

    UUID secondGen = connectionGenerationId(name);
    ProviderResolutionService.ResolvedExecution second =
        resolution.resolve(ProviderType.OPENAI, secondGen, request);
    assertEquals(Duration.ofSeconds(60), second.timeoutPolicy().modelCallTimeout());
    second.openProvider(second.timeoutPolicy());
    assertEquals("updated-secret", openAi.credential);
    assertEquals("https://updated.example/v2", openAi.descriptor.endpoint());
    assertNotEquals(firstGen, secondGen, "凭据与 endpoint 更新必须轮换 generationId");
    assertEquals(secondGen, openAi.descriptor.connectionGenerationId());

    AgentProviderUpdateDTO typeUpdate = new AgentProviderUpdateDTO();
    typeUpdate.setProviderType("openai_response");
    typeUpdate.setExpectedVersion(updated.getVersion());
    providerService.updateProvider(name, typeUpdate);
    IllegalArgumentException typeDrift =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolution.resolve(ProviderType.OPENAI, secondGen, request));
    assertEquals(
        "provider type drift: frozen=OPENAI current=OPENAI_RESPONSES", typeDrift.getMessage());
    assertEquals(0, openAiResponses.openCount, "协议漂移不得打开新 factory");
  }

  @Test
  void resolveFailsDeterministicallyWhenCurrentRowIsDeleted() {
    String name = "deleted-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    DatabaseProviderResolutionService resolution = resolution(openAi);

    AgentProviderDTO created =
        createProvider(name, "openai", "https://original.example/v1", "secret", 12_000L);
    UUID generationId = connectionGenerationId(name);
    providerService.deleteProvider(name, created.getVersion());

    ProviderRequest request =
        request(name, ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-key"));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolution.resolve(ProviderType.OPENAI, generationId, request));
    assertTrue(
        failure.getMessage().contains("provider not found: " + name),
        "当前行删除后必须确定性 not found，不得使用其他连接配置");
  }

  @Test
  void sameNameRecreateRejectsOldGenerationAndAcceptsReplannedRequest() {
    String name = "recreated-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    DatabaseProviderResolutionService resolution = resolution(openAi);

    AgentProviderDTO created =
        createProvider(name, "openai", "https://first.example/v1", "first-secret", 12_000L);
    UUID firstGenerationId = connectionGenerationId(name);
    providerService.deleteProvider(name, created.getVersion());

    AgentProviderDTO recreated =
        createProvider(name, "openai", "https://second.example/v1", "second-secret", 30_000L);
    ProviderRequest request = request(name, ProviderCacheControl.none());

    IllegalArgumentException generationDrift =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolution.resolve(ProviderType.OPENAI, firstGenerationId, request));
    assertTrue(generationDrift.getMessage().contains("provider connection generation drift"));

    UUID recreatedGenerationId = connectionGenerationId(name);
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(ProviderType.OPENAI, recreatedGenerationId, request);
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
    UUID generationId = connectionGenerationId(name);
    ProviderRequest request =
        new ProviderRequest(
            descriptor(name),
            new ModelVariant("default"),
            1024,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("leading system")))),
            List.of(),
            ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-key"));

    // 当前 capability 是 BREAKPOINTS：affinity hint 被改写为按请求实际内容求交集的 breakpoints。
    ProviderResolutionService.ResolvedExecution first =
        resolution.resolve(ProviderType.ANTHROPIC, generationId, request);
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

    IllegalArgumentException drift =
        assertThrows(
            IllegalArgumentException.class,
            () -> resolution.resolve(ProviderType.ANTHROPIC, generationId, request));
    assertEquals("provider type drift: frozen=ANTHROPIC current=GOOGLE", drift.getMessage());
    assertEquals(0, google.openCount, "协议漂移不得打开新 factory");
  }

  /** 意图：验证数据库中 provider 行配置更新后，动态能力工厂在下一次 attempt 立即读取最新 config 并生效。 */
  @Test
  void resolveUsesDynamicConfigAwarePromptCacheCapabilityFromDatabase() {
    String name = "dynamic-cache-provider-" + System.nanoTime();
    ProviderFactory dynamicFactory =
        ProviderFactory.of(
            ProviderType.OPENAI,
            configJson -> {
              if (configJson != null && configJson.contains("LEGACY")) {
                return PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT));
              }
              return PromptCacheCapability.automatic();
            },
            (cred, cfg) -> new CapturingFactory(ProviderType.OPENAI).create(cred, cfg));
    DatabaseProviderResolutionService resolution = resolution(dynamicFactory);

    createProvider(name, "openai", "https://example.com/v1", "secret", 10_000L);
    UUID firstGenerationId = connectionGenerationId(name);
    ProviderRequest request =
        request(name, ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-key"));

    // 默认配置下能力为 AUTOMATIC，无法表达显式 AFFINITY，故规范化降级为 none()
    ProviderResolutionService.ResolvedExecution first =
        resolution.resolve(ProviderType.OPENAI, firstGenerationId, request);
    assertEquals(ProviderCacheControl.none(), first.effectiveRequest().cacheControl());

    // 更新 provider 配置为 LEGACY 缓存模式
    AgentProvider provider = providerRepository.getByName(name);
    provider.setConfigJson("{\"openAiPromptCacheMode\":\"LEGACY\"}");
    provider.setConnectionGenerationId(UUID.randomUUID());
    providerRepository.updateByName(provider, provider.getVersion());

    assertThrows(
        IllegalArgumentException.class,
        () -> resolution.resolve(ProviderType.OPENAI, firstGenerationId, request));

    // 重新规划后按新 generation 解析：动态能力变为 AFFINITY，有效请求成功保留 AFFINITY 缓存控制
    ProviderResolutionService.ResolvedExecution second =
        resolution.resolve(ProviderType.OPENAI, connectionGenerationId(name), request);
    assertEquals(
        ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "pc1-key"),
        second.effectiveRequest().cacheControl());
  }

  private DatabaseProviderResolutionService resolution(ProviderFactory... factories) {
    return new DatabaseProviderResolutionService(
        providerRepository,
        configurationCodec,
        new ProviderFactories(List.of(factories)),
        providerResourceMaterializer);
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

  private UUID connectionGenerationId(String providerName) {
    return providerRepository.getByName(providerName).getConnectionGenerationId();
  }

  private static ProviderRequest request(String providerName, ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        descriptor(providerName),
        new ModelVariant("default"),
        1024,
        List.of(),
        List.of(),
        cacheControl);
  }

  private static ModelDescriptor descriptor(String providerName) {
    return new ModelDescriptor(
        providerName,
        "model",
        "model",
        Set.of(ModelInputModality.TEXT),
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
            Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
            EnumSet.allOf(PromptCacheBreakpoint.class));
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

        /**
         * 显式声明测试 adapter 的内联媒体能力：避免依赖 {@link ProviderMediaCapabilities#NONE} 默认值而让 Resource
         * 物化静默退化为文本，掩盖能力传递的真实行为。
         */
        @Override
        public ProviderMediaCapabilities mediaCapabilities() {
          return new ProviderMediaCapabilities(
              Set.of(ModelInputModality.IMAGE), Set.of(ModelInputModality.IMAGE));
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
