package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 基于 PostgreSQL 的 attempt-time Provider 解析证据：保留 Provider type affinity，并在每次解析时使用当前连接配置。 */
class DatabaseProviderResolutionServiceIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private ProviderService providerService;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderConfigurationCodec configurationCodec;
  @Autowired private ProviderResourceMaterializer providerResourceMaterializer;

  @Test
  void connectionUpdateTakesEffectOnNextProviderResolution() {
    String name = "latest-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    CapturingFactory openAiResponses = new CapturingFactory(ProviderType.OPENAI_RESPONSES);
    DatabaseProviderResolutionService resolution = resolution(openAi, openAiResponses);

    ProviderDTO created =
        createProvider(name, "openai", "https://original.example/v1", "original-secret", 12_000L);
    ProviderRequest request =
        request(name, ProviderCacheControl.session(PromptCacheRetention.SHORT, "pc1-stable"));

    ProviderResolutionService.ResolvedExecution first =
        resolution.resolve(ProviderType.OPENAI, request);
    assertEquals(
        ProviderCacheControl.session(PromptCacheRetention.SHORT, "pc1-stable"),
        first.effectiveRequest().cacheControl());
    assertEquals(Duration.ofSeconds(12), first.timeoutPolicy().modelCallTimeout());
    first.openProvider(first.timeoutPolicy());
    assertEquals("original-secret", openAi.credential);
    assertEquals("https://original.example/v1", openAi.descriptor.endpoint());

    ProviderUpdateDTO liveUpdate = new ProviderUpdateDTO();
    liveUpdate.setBaseUrl("https://updated.example/v2");
    liveUpdate.setCredential(SecretUpdate.set("updated-secret"));
    liveUpdate.setModelCallTimeoutMillis(60_000L);
    liveUpdate.setModelCallIdleTimeoutMillis(120_000L);
    liveUpdate.setExpectedVersion(created.getVersion());
    providerService.updateProvider(name, liveUpdate);

    ProviderResolutionService.ResolvedExecution second =
        resolution.resolve(ProviderType.OPENAI, request);
    assertEquals(Duration.ofSeconds(60), second.timeoutPolicy().modelCallTimeout());
    second.openProvider(second.timeoutPolicy());
    assertEquals("updated-secret", openAi.credential);
    assertEquals("https://updated.example/v2", openAi.descriptor.endpoint());
    assertEquals(2, openAi.openCount);
  }

  @Test
  void resolveFailsDeterministicallyWhenCurrentRowIsDeleted() {
    String name = "deleted-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    DatabaseProviderResolutionService resolution = resolution(openAi);

    ProviderDTO created =
        createProvider(name, "openai", "https://original.example/v1", "secret", 12_000L);
    providerService.deleteProvider(name, created.getVersion());

    ProviderRequest request =
        request(name, ProviderCacheControl.session(PromptCacheRetention.SHORT, "pc1-key"));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> resolution.resolve(ProviderType.OPENAI, request));
    assertTrue(
        failure.getMessage().contains("provider not found: " + name),
        "当前行删除后必须确定性 not found，不得使用其他连接配置");
  }

  @Test
  void sameNameRecreateResolvesCurrentConnection() {
    String name = "recreated-provider-" + System.nanoTime();
    CapturingFactory openAi = new CapturingFactory(ProviderType.OPENAI);
    DatabaseProviderResolutionService resolution = resolution(openAi);

    ProviderDTO created =
        createProvider(name, "openai", "https://first.example/v1", "first-secret", 12_000L);
    providerService.deleteProvider(name, created.getVersion());

    ProviderDTO recreated =
        createProvider(name, "openai", "https://second.example/v1", "second-secret", 30_000L);
    ProviderRequest request = request(name, ProviderCacheControl.none());
    ProviderResolutionService.ResolvedExecution resolved =
        resolution.resolve(ProviderType.OPENAI, request);
    resolved.openProvider(resolved.timeoutPolicy());
    assertEquals("second-secret", openAi.credential, "同名重建后解析到新行 credential");
    assertEquals("https://second.example/v1", openAi.descriptor.endpoint(), "同名重建后解析到新行 baseUrl");
    assertEquals(
        Duration.ofSeconds(30), resolved.timeoutPolicy().modelCallTimeout(), "同名重建后解析到新行 config");
    assertEquals(0L, recreated.getVersion(), "重建行 version 从 0 重新开始");
  }

  private DatabaseProviderResolutionService resolution(ProviderFactory... factories) {
    return new DatabaseProviderResolutionService(
        providerRepository,
        configurationCodec,
        List.of(factories).stream()
            .collect(
                Collectors.toUnmodifiableMap(ProviderFactory::providerType, Function.identity())),
        providerResourceMaterializer);
  }

  private ProviderDTO createProvider(
      String name, String providerType, String baseUrl, String credential, long timeoutMillis) {
    ProviderCreateDTO create = new ProviderCreateDTO();
    create.setName(name);
    create.setProviderType(ProviderType.fromWireValue(providerType));
    create.setBaseUrl(baseUrl);
    create.setCredential(credential == null ? null : SecretUpdate.set(credential));
    create.setModelCallTimeoutMillis(timeoutMillis);
    return providerService.createProvider(create);
  }

  private static ProviderRequest request(String providerName, ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        descriptor(providerName),
        new ModelVariant("default", null, cacheControl.retention(), null),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        cacheControl);
  }

  private static ModelDescriptor descriptor(String providerName) {
    return new ModelDescriptor(
        providerName, "model", "model", Set.of(ModelInputModality.TEXT), false, false);
  }

  /** 记录每次 factory.create / adapter.create 收到的连接事实，证明解析使用当前行与当前 factory。 */
  private static final class CapturingFactory implements ProviderFactory {

    private final ProviderType providerType;
    private String credential;
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
    public ProviderAdapter create(String credential) {
      this.credential = credential;
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
          return (request, handler) -> {};
        }
      };
    }
  }
}
