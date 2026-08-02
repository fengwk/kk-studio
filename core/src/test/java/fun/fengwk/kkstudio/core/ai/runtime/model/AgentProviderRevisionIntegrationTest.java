package fun.fengwk.kkstudio.core.ai.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRevisionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProviderRevision;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** PostgreSQL-backed evidence for immutable Provider revisions and exact version lookup. */
class AgentProviderRevisionIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private AgentProviderService providerService;
  @Autowired private AgentProviderRevisionRepository revisionRepository;
  @Autowired private AgentProviderConfigurationCodec configurationCodec;

  @Test
  void preservesEachConnectionRevisionAcrossUpdateAndSoftDelete() {
    String name = "revision-provider-" + System.nanoTime();
    AgentProviderCreateDTO create = new AgentProviderCreateDTO();
    create.setName(name);
    create.setProviderType("openai");
    create.setBaseUrl("https://original.example/v1");
    create.setCredential("original-secret");
    create.setModelCallTimeoutMillis(12_000L);
    create.setModelCallIdleTimeoutMillis(1_000L);

    AgentProviderDTO created = providerService.createProvider(create);
    AgentProviderRevision revision0 = revisionRepository.getByProviderNameAndVersion(name, 0L);
    assertNotNull(revision0);
    assertEquals(AgentProviderType.openai, revision0.getProviderType());
    assertEquals("https://original.example/v1", revision0.getBaseUrl());
    assertEquals("original-secret", revision0.getCredential());
    assertEquals(
        Duration.ofSeconds(12),
        configurationCodec.readTimeoutPolicy(revision0.getConfigJson()).modelCallTimeout());
    assertEquals(
        Duration.ofSeconds(1),
        configurationCodec.readTimeoutPolicy(revision0.getConfigJson()).modelCallIdleTimeout());

    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setProviderType("openai_response");
    update.setBaseUrl("https://updated.example/v2");
    update.setCredential("updated-secret");
    update.setModelCallTimeoutMillis(60_000L);
    update.setModelCallIdleTimeoutMillis(4_000L);
    update.setExpectedVersion(created.getVersion());
    providerService.updateProvider(name, update);

    AgentProviderRevision revision1 = revisionRepository.getByProviderNameAndVersion(name, 1L);
    assertNotNull(revision1);
    assertEquals(AgentProviderType.openai_response, revision1.getProviderType());
    assertEquals("https://updated.example/v2", revision1.getBaseUrl());
    assertEquals("updated-secret", revision1.getCredential());
    assertEquals(
        Duration.ofSeconds(60),
        configurationCodec.readTimeoutPolicy(revision1.getConfigJson()).modelCallTimeout());
    assertEquals(
        Duration.ofSeconds(4),
        configurationCodec.readTimeoutPolicy(revision1.getConfigJson()).modelCallIdleTimeout());
    assertNotEquals(revision0.getConfigJson(), revision1.getConfigJson());

    providerService.deleteProvider(name, "1");

    AgentProviderRevision persistedRevision0 =
        revisionRepository.getByProviderNameAndVersion(name, 0L);
    AgentProviderRevision persistedRevision1 =
        revisionRepository.getByProviderNameAndVersion(name, 1L);
    assertEquals(revision0.getProviderType(), persistedRevision0.getProviderType());
    assertEquals(revision0.getBaseUrl(), persistedRevision0.getBaseUrl());
    assertEquals(revision0.getCredential(), persistedRevision0.getCredential());
    assertEquals(revision0.getConfigJson(), persistedRevision0.getConfigJson());
    assertEquals(revision1.getProviderType(), persistedRevision1.getProviderType());
    assertEquals(revision1.getBaseUrl(), persistedRevision1.getBaseUrl());
    assertEquals(revision1.getCredential(), persistedRevision1.getCredential());
    assertEquals(revision1.getConfigJson(), persistedRevision1.getConfigJson());

    CapturingFactory originalFactory = new CapturingFactory(ProviderType.OPENAI);
    CapturingFactory updatedFactory = new CapturingFactory(ProviderType.OPENAI_RESPONSES);
    DatabaseProviderResolutionService resolution =
        new DatabaseProviderResolutionService(
            revisionRepository,
            configurationCodec,
            new ProviderFactories(List.of(originalFactory, updatedFactory)));

    ProviderResolutionService.ResolvedExecution original =
        resolution.resolve(request(name, 0L, ProviderType.OPENAI));
    original.openProvider(original.timeoutPolicy());
    assertEquals("original-secret", originalFactory.credential);
    assertEquals(revision0.getConfigJson(), originalFactory.configJson);
    assertEquals("https://original.example/v1", originalFactory.descriptor.endpoint());

    ProviderResolutionService.ResolvedExecution updated =
        resolution.resolve(request(name, 1L, ProviderType.OPENAI_RESPONSES));
    updated.openProvider(updated.timeoutPolicy());
    assertEquals("updated-secret", updatedFactory.credential);
    assertEquals(revision1.getConfigJson(), updatedFactory.configJson);
    assertEquals("https://updated.example/v2", updatedFactory.descriptor.endpoint());

    assertThrows(
        IllegalArgumentException.class,
        () -> resolution.resolve(request(name, 2L, ProviderType.OPENAI_RESPONSES)));
  }

  private static ProviderRequest request(
      String providerName, long providerVersion, ProviderType providerType) {
    ModelDescriptor descriptor =
        new ModelDescriptor(
            providerName,
            providerVersion,
            "model",
            providerType,
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
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    return new ProviderRequest(
        descriptor,
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static final class CapturingFactory implements ProviderFactory {

    private final ProviderType providerType;
    private String credential;
    private String configJson;
    private ProviderDescriptor descriptor;

    private CapturingFactory(ProviderType providerType) {
      this.providerType = providerType;
    }

    @Override
    public ProviderType providerType() {
      return providerType;
    }

    @Override
    public PromptCacheCapability promptCacheCapability() {
      return PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT));
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
