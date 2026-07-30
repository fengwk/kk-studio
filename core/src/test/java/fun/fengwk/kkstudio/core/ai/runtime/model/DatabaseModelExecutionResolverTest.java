package fun.fengwk.kkstudio.core.ai.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionListener;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionRequest;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionResource;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Validates that the production {@link DatabaseModelExecutionResolver} routes only through the
 * {@link AgentProviderRepository#getById(long)} path, mirrors the persisted {@link
 * AgentProviderType} onto the frozen {@link ProviderType}, fails when the persisted type or the
 * Harness factory is missing or the persisted configuration is invalid, and produces a {@link
 * ModelExecutionResource} carrying the shared executor plus the frozen {@link
 * ModelCallTimeoutPolicy} read from the persisted JSON.
 */
class DatabaseModelExecutionResolverTest {

  private static final long PROVIDER_ID = 22L;
  private static final long MODEL_ID = 11L;
  private static final String CONFIG_JSON =
      "{\"modelCallTimeoutMillis\":45000,\"modelCallIdleTimeoutMillis\":3000}";
  private static final ProviderRequest REQUEST =
      request(MODEL_ID, PROVIDER_ID, ProviderType.OPENAI);

  @Test
  void resolvesFrozenProviderRequestUsingOnlyProviderIdLookup() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            adapter -> adapter);
    try (Fixture fixture = new Fixture(factory)) {
      fixture.provider(provider(PROVIDER_ID, AgentProviderType.openai));

      ModelExecutionResource resource = fixture.resolver.resolve(REQUEST);

      assertNotNull(resource.executor());
      assertEquals(
          new ModelCallTimeoutPolicy(Duration.ofSeconds(45), Duration.ofSeconds(3)),
          resource.timeoutPolicy());
      assertEquals(1, factory.invocations, "factory must be invoked exactly once");
      assertEquals("secret", factory.lastCredential);
      assertEquals(CONFIG_JSON, factory.lastConfigJson);
      assertNull(factory.lastDescriptor, "ModelProvider creation belongs to the I/O executor");

      resource
          .executor()
          .execute(
              new ModelExecutionRequest(1L, 1, REQUEST, Instant.now().plus(Duration.ofMinutes(1L))),
              NoopExecutionListener.INSTANCE);
      await(factory.streamStarted, "Provider stream start");

      assertEquals(Long.toString(PROVIDER_ID), factory.lastDescriptor.providerId());
      assertEquals(ProviderType.OPENAI, factory.lastDescriptor.type());
      assertEquals("https://provider.test/v1", factory.lastDescriptor.endpoint());
      verify(fixture.providers, atLeastOnce()).getById(PROVIDER_ID);
      // Other AgentProviderRepository methods must never be called from this slice.
      verify(fixture.providers, never()).page(any());
      verify(fixture.providers, never()).create(any());
      verify(fixture.providers, never()).updateById(any(), anyLong());
      verify(fixture.providers, never()).deleteById(anyLong(), anyLong());
      verify(fixture.providers, never()).hasModels(anyLong());
    }
  }

  @Test
  void mapsEveryPersistedProviderTypeToFrozenHarnessType() {
    assertMaps(ProviderType.OPENAI, AgentProviderType.openai);
    assertMaps(ProviderType.OPENAI_RESPONSES, AgentProviderType.openai_response);
    assertMaps(ProviderType.ANTHROPIC, AgentProviderType.anthropic);
    assertMaps(ProviderType.GOOGLE, AgentProviderType.google);
  }

  @Test
  void rejectsPersistedTypeThatDoesNotMatchFrozenProviderRequestType() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            adapter -> adapter);
    try (Fixture fixture = new Fixture(factory)) {
      fixture.provider(provider(PROVIDER_ID, AgentProviderType.anthropic));

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST));
      assertTrue(error.getMessage().contains("persisted provider type"));
      assertEquals(0, factory.invocations, "factory must not be called on type mismatch");
    }
  }

  @Test
  void rejectsMissingPersistedProviderRow() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            adapter -> adapter);
    try (Fixture fixture = new Fixture(factory)) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST));
      assertTrue(error.getMessage().contains("persisted provider not found"));
    }
  }

  @Test
  void rejectsMissingProviderFactoryForFrozenType() {
    try (Fixture fixture = new Fixture(null)) {
      fixture.provider(provider(PROVIDER_ID, AgentProviderType.openai));

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST));
      assertTrue(error.getMessage().contains("ProviderFactory"));
    }
  }

  @Test
  void rejectsInvalidPersistedConfiguration() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            adapter -> adapter);
    try (Fixture fixture = new Fixture(factory)) {
      AgentProvider invalid = provider(PROVIDER_ID, AgentProviderType.openai);
      invalid.setConfigJson("{\"modelCallTimeoutMillis\":0}");
      fixture.provider(invalid);

      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST));
      assertTrue(error.getMessage().contains("modelCallTimeoutMillis"));
    }
  }

  @Test
  void rejectsInvalidPersistedProviderAndAdapterShapes() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            adapter -> adapter);
    try (Fixture fixture = new Fixture(factory)) {
      fixture.provider(provider(PROVIDER_ID, null));
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST))
              .getMessage()
              .contains("type must not be null"));
    }

    try (Fixture fixture = new Fixture(factory)) {
      AgentProvider missingEndpoint = provider(PROVIDER_ID, AgentProviderType.openai);
      missingEndpoint.setBaseUrl(" ");
      fixture.provider(missingEndpoint);
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST))
              .getMessage()
              .contains("baseUrl"));
    }

    CapturingProviderFactory failingFactory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            adapter -> {
              throw new IllegalStateException("cannot decrypt credential");
            });
    try (Fixture fixture = new Fixture(failingFactory)) {
      fixture.provider(provider(PROVIDER_ID, AgentProviderType.openai));
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST))
              .getMessage()
              .contains("cannot create provider adapter"));
    }

    CapturingProviderFactory nullAdapterFactory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            ignored -> null);
    try (Fixture fixture = new Fixture(nullAdapterFactory)) {
      fixture.provider(provider(PROVIDER_ID, AgentProviderType.openai));
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST))
              .getMessage()
              .contains("null adapter"));
    }

    ProviderAdapter wrongType = mock(ProviderAdapter.class);
    when(wrongType.providerType()).thenReturn(ProviderType.ANTHROPIC);
    CapturingProviderFactory wrongAdapterFactory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            ignored -> wrongType);
    try (Fixture fixture = new Fixture(wrongAdapterFactory)) {
      fixture.provider(provider(PROVIDER_ID, AgentProviderType.openai));
      assertTrue(
          assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(REQUEST))
              .getMessage()
              .contains("returned adapter"));
    }
  }

  @Test
  void rejectsModelProviderCreationFailureInsideShortLivedResource() {
    ProviderAdapter nullProviderAdapter = mock(ProviderAdapter.class);
    when(nullProviderAdapter.providerType()).thenReturn(ProviderType.OPENAI);
    when(nullProviderAdapter.create(any())).thenReturn(null);
    CapturingProviderFactory nullProviderFactory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            ignored -> nullProviderAdapter);
    try (Fixture fixture = new Fixture(nullProviderFactory)) {
      fixture.provider(provider(PROVIDER_ID, AgentProviderType.openai));
      ProviderResolutionService.ResolvedExecution resolved = fixture.resolution.resolve(REQUEST);
      assertTrue(
          assertThrows(
                  IllegalArgumentException.class,
                  () -> resolved.openProvider(resolved.timeoutPolicy()))
              .getMessage()
              .contains("cannot create ModelProvider"));
    }

    ProviderAdapter failingProviderAdapter = mock(ProviderAdapter.class);
    when(failingProviderAdapter.providerType()).thenReturn(ProviderType.OPENAI);
    when(failingProviderAdapter.create(any()))
        .thenThrow(new IllegalStateException("SDK client construction failed"));
    CapturingProviderFactory failingProviderFactory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            ignored -> failingProviderAdapter);
    try (Fixture fixture = new Fixture(failingProviderFactory)) {
      fixture.provider(provider(PROVIDER_ID, AgentProviderType.openai));
      ProviderResolutionService.ResolvedExecution resolved = fixture.resolution.resolve(REQUEST);
      assertTrue(
          assertThrows(
                  IllegalArgumentException.class,
                  () -> resolved.openProvider(resolved.timeoutPolicy()))
              .getMessage()
              .contains("cannot create ModelProvider"));
    }
  }

  @Test
  void readsPersistedTimeoutPolicyIntoResourceWithoutModifyingRequest() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            adapter -> adapter);
    try (Fixture fixture = new Fixture(factory)) {
      AgentProvider persisted = provider(PROVIDER_ID, AgentProviderType.openai);
      persisted.setConfigJson("{}");
      fixture.provider(persisted);

      ModelExecutionResource resource = fixture.resolver.resolve(REQUEST);

      assertEquals(ModelCallTimeoutPolicy.DEFAULT, resource.timeoutPolicy());
      assertNotNull(resource.executor());
      assertNull(
          factory.lastRequest,
          "resolver must not invoke the provider; identity preservation belongs to the executor");
    }
  }

  private void assertMaps(ProviderType frozenType, AgentProviderType persistedType) {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(frozenType, capabilityFor(frozenType), adapter -> adapter);
    try (Fixture fixture = new Fixture(factory)) {
      fixture.provider(provider(PROVIDER_ID, persistedType));

      ModelExecutionResource resource =
          fixture.resolver.resolve(request(MODEL_ID, PROVIDER_ID, frozenType));

      assertNotNull(resource.executor());
      assertEquals(
          new ModelCallTimeoutPolicy(Duration.ofSeconds(45), Duration.ofSeconds(3)),
          resource.timeoutPolicy());
    }
  }

  private static void await(CountDownLatch latch, String description) {
    try {
      assertTrue(latch.await(2L, TimeUnit.SECONDS), description);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for " + description, failure);
    }
  }

  // -- fixture / helpers ---------------------------------------------------

  private static PromptCacheCapability capabilityFor(ProviderType type) {
    return switch (type) {
      case OPENAI, OPENAI_RESPONSES -> PromptCacheCapability.affinity(
          Set.of(PromptCacheRetention.SHORT));
      case ANTHROPIC -> PromptCacheCapability.breakpoints(
          Set.of(PromptCacheRetention.SHORT),
          EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS));
      case GOOGLE -> PromptCacheCapability.automatic();
    };
  }

  private static AgentProvider provider(long id, AgentProviderType type) {
    AgentProvider provider = new AgentProvider();
    provider.setId(id);
    provider.setName("provider");
    provider.setProviderType(type);
    provider.setBaseUrl("https://provider.test/v1");
    provider.setCredential("secret");
    provider.setConfigJson(CONFIG_JSON);
    return provider;
  }

  private static ProviderRequest request(long modelId, long providerId, ProviderType type) {
    ModelVariant variant =
        new ModelVariant("quality", 1024, 0.7, null, null, null, null, List.of(), null);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            providerId,
            modelId,
            type,
            "provider-api-model",
            true,
            false,
            new ModelPricing(
                "USD",
                "batch",
                "priority",
                BigDecimal.valueOf(1L),
                "price-v1",
                BigDecimal.valueOf(1L),
                BigDecimal.valueOf(2L),
                BigDecimal.valueOf(0L),
                BigDecimal.valueOf(0L),
                BigDecimal.valueOf(0L),
                BigDecimal.valueOf(0L)),
            capabilityFor(type).mode() == PromptCacheMode.AUTOMATIC
                ? PromptCachePolicy.automatic(capabilityFor(type))
                : capabilityFor(type).mode() == PromptCacheMode.AFFINITY
                    ? PromptCachePolicy.affinityShort(capabilityFor(type))
                    : PromptCachePolicy.breakpointsShort(capabilityFor(type)));
    return new ProviderRequest(
        descriptor,
        variant,
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hello")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static final class Fixture implements AutoCloseable {

    private final AgentProviderRepository providers = mock(AgentProviderRepository.class);
    private final DatabaseProviderResolutionService resolution;
    private final ExecutorService executor;
    private final DatabaseModelExecutionResolver resolver;

    private Fixture(CapturingProviderFactory providerFactory) {
      List<ProviderFactory> factories =
          providerFactory == null ? List.of() : List.of(providerFactory);
      ProviderFactories providerFactories = new ProviderFactories(factories);
      ObjectMapper objectMapper = new ObjectMapper();
      AgentProviderConfigurationCodec codec = new AgentProviderConfigurationCodec(objectMapper);
      resolution = new DatabaseProviderResolutionService(providers, codec, providerFactories);
      executor = Executors.newSingleThreadExecutor();
      resolver = new DatabaseModelExecutionResolver(resolution, executor, Clock.systemUTC());
    }

    private void provider(AgentProvider provider) {
      when(providers.getById(provider.getId())).thenReturn(provider);
    }

    @Override
    public void close() {
      executor.shutdownNow();
    }
  }

  private static final class CapturingProviderFactory implements ProviderFactory {

    private final ProviderType type;
    private final PromptCacheCapability capability;
    private final AdapterCustomiser customiser;
    private int invocations;
    private String lastCredential;
    private String lastConfigJson;
    private ProviderDescriptor lastDescriptor;
    private ProviderRequest lastRequest;
    private final CountDownLatch streamStarted = new CountDownLatch(1);

    private CapturingProviderFactory(
        ProviderType type, PromptCacheCapability capability, AdapterCustomiser customiser) {
      this.type = type;
      this.capability = capability;
      this.customiser = customiser;
    }

    @Override
    public ProviderType providerType() {
      return type;
    }

    @Override
    public PromptCacheCapability promptCacheCapability() {
      return capability;
    }

    @Override
    public ProviderAdapter create(String credential, String configJson) {
      invocations++;
      lastCredential = credential;
      lastConfigJson = configJson;
      ProviderAdapter adapter =
          new ProviderAdapter() {
            @Override
            public ProviderType providerType() {
              return type;
            }

            @Override
            public ModelProvider create(ProviderDescriptor descriptor) {
              lastDescriptor = descriptor;
              return new RecordingModelProvider(
                  CapturingProviderFactory.this::recordRequest, streamStarted);
            }
          };
      return customiser.customise(adapter);
    }

    private void recordRequest(ProviderRequest request) {
      lastRequest = request;
    }
  }

  private static final class RecordingModelProvider implements ModelProvider {

    private final Consumer<ProviderRequest> requestSink;
    private final CountDownLatch streamStarted;

    private RecordingModelProvider(
        Consumer<ProviderRequest> requestSink, CountDownLatch streamStarted) {
      this.requestSink = requestSink;
      this.streamStarted = streamStarted;
    }

    @Override
    public ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
      requestSink.accept(request);
      streamStarted.countDown();
      return new RecordingStream();
    }
  }

  private static final class RecordingStream implements ProviderStream {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }

  private enum NoopExecutionListener implements ModelExecutionListener {
    INSTANCE;

    @Override
    public void onDelta(ProviderStreamEvent delta) {}

    @Override
    public void onComplete(ProviderResponse response) {}

    @Override
    public void onError(ProviderException error) {}
  }

  @FunctionalInterface
  private interface AdapterCustomiser {
    ProviderAdapter customise(ProviderAdapter adapter);
  }
}
