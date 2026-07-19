package fun.fengwk.kkstudio.core.harness.thread.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.adapter.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtension;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionRegistry;
import fun.fengwk.kkstudio.harness.runtime.extension.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.extension.ToolFactory;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnResources;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.model.AgentProviderType;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Covers {@link DatabaseTurnResourceResolver} against the current AgentThread resolve contract:
 * frozen config IDs + extension factories + Environment references.
 */
class DatabaseTurnResourceResolverTest {

  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
  private static final long SESSION_ID = 7L;
  private static final long THREAD_ID = 9L;
  private static final String MODEL_CONFIG =
      "{\"contextWindow\":128000,\"maxOutputTokens\":8192,"
          + "\"inputModalities\":[\"TEXT\",\"IMAGE\"],"
          + "\"variants\":[{\"name\":\"quality\",\"maxOutputTokens\":4096},"
          + "{\"name\":\"fast\",\"maxOutputTokens\":1024}],"
          + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"batch\","
          + "\"serviceTier\":\"priority\",\"serviceTierMultiplier\":1.25,"
          + "\"version\":\"price-v7\",\"inputPerMillionTokens\":1.1,"
          + "\"outputPerMillionTokens\":2.2,\"cacheReadPerMillionTokens\":0.3,"
          + "\"cacheWritePerMillionTokens\":0.4,"
          + "\"cacheWriteLongPerMillionTokens\":0.5,"
          + "\"reasoningPerMillionTokens\":3.6}}";

  /** Frozen config drives trusted provider/model/variant/local-tool materialization. */
  @Test
  void resolvesFrozenConfigToTrustedProviderModelVariantAndTool() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    Tool read = tool("read", "1");
    try (Fixture fixture = new Fixture(factory, List.of(read))) {
      AgentSnapshot snapshot = snapshot("11", "quality", List.of("read"));
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));

      TurnResources resources =
          fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot));

      assertEquals(22L, resources.model().providerResourceId());
      assertEquals(11L, resources.model().modelResourceId());
      assertEquals("provider-api-model", resources.model().modelId());
      assertEquals("quality", resources.variant().name());
      assertEquals(new BigDecimal("1.1"), resources.model().pricing().inputPerMillionTokens());
      assertEquals(new BigDecimal("3.6"), resources.model().pricing().reasoningPerMillionTokens());
      assertEquals("price-v7", resources.model().pricing().version());
      assertEquals(
          PromptCacheMode.AFFINITY, resources.model().promptCachePolicy().capability().mode());
      assertEquals(PromptCacheRetention.SHORT, resources.model().promptCachePolicy().retention());
      assertEquals(List.of(read.descriptor()), resources.toolDescriptors());
      assertEquals(Path.of("/tmp/harness-root/repository"), resources.workdir());
      assertEquals(Path.of("/tmp/harness-root"), resources.environmentRoot());
      assertEquals("secret", factory.credential);
      assertEquals(
          "{\"modelCallTimeoutMillis\":45000,\"modelCallIdleTimeoutMillis\":3000}",
          factory.configJson);
      assertEquals("22", factory.descriptor.providerId());
      assertEquals(
          Duration.ofSeconds(45), factory.descriptor.modelCallTimeoutPolicy().modelCallTimeout());
      assertEquals(
          Duration.ofSeconds(3),
          factory.descriptor.modelCallTimeoutPolicy().modelCallIdleTimeout());
      assertEquals(factory.descriptor.modelCallTimeoutPolicy(), resources.modelCallTimeoutPolicy());
      assertSame(factory.provider, resources.provider());
      verify(fixture.models).getById(11L);
    }
  }

  /** Each persisted provider type maps to a capability-validated cache policy. */
  @Test
  void mapsEveryPersistedProviderTypeToVerifiedCachePolicy() {
    assertCachePolicy(
        ProviderType.OPENAI,
        AgentProviderType.openai,
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
        PromptCacheMode.AFFINITY,
        PromptCacheRetention.SHORT);
    assertCachePolicy(
        ProviderType.OPENAI_RESPONSES,
        AgentProviderType.openai_response,
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
        PromptCacheMode.AFFINITY,
        PromptCacheRetention.SHORT);
    assertCachePolicy(
        ProviderType.ANTHROPIC,
        AgentProviderType.anthropic,
        PromptCacheCapability.breakpoints(
            Set.of(PromptCacheRetention.SHORT),
            EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
        PromptCacheMode.BREAKPOINTS,
        PromptCacheRetention.SHORT);
    assertCachePolicy(
        ProviderType.GOOGLE,
        AgentProviderType.google,
        PromptCacheCapability.automatic(),
        PromptCacheMode.AUTOMATIC,
        PromptCacheRetention.NONE);
  }

  /**
   * Missing session/model rows, factory, variant, tool, and invalid model config fail before a
   * turn.
   */
  @Test
  void rejectsUnavailableFrozenResourcesAndInvalidPersistentConfiguration() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    AgentSnapshot snapshot = snapshot("11", "quality", List.of());
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot)));

      fixture.session();
      AgentRuntimeConfig missingVariant =
          AgentRuntimeConfig.from(snapshot).withModel("11", "missing");
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, missingVariant));

      AgentRuntimeConfig malformedTool =
          AgentRuntimeConfig.from(snapshot).withTools(List.of("read@"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, malformedTool));
      AgentRuntimeConfig missingTool =
          AgentRuntimeConfig.from(snapshot).withTools(List.of("missing@1"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, missingTool));

      fixture.model(model(11L, 22L, "{\"variants\":[]}"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot)));
    }

    try (Fixture fixture = new Fixture(null, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  fixture.resolver.resolve(
                      SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot)));
      assertTrue(error.getMessage().contains("ProviderFactory"));
    }

    CapturingProviderFactory mismatchedCapability =
        new CapturingProviderFactory(ProviderType.OPENAI, PromptCacheCapability.automatic());
    try (Fixture fixture = new Fixture(mismatchedCapability, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot)));
    }
  }

  /**
   * Missing model/provider rows, invalid provider config, and ambiguous tools fail
   * deterministically.
   */
  @Test
  void rejectsInvalidProviderRowsAndAmbiguousOrEnvironmentTools() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    AgentSnapshot snapshot = snapshot("11", "quality", List.of());
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.session();
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot)));
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot)));

      AgentProvider defaulted = provider(22L, AgentProviderType.openai);
      defaulted.setConfigJson("{}");
      fixture.provider(defaulted);
      TurnResources defaultedResources =
          fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot));
      assertEquals(ModelCallTimeoutPolicy.DEFAULT, defaultedResources.modelCallTimeoutPolicy());

      AgentProvider invalid = provider(22L, AgentProviderType.openai);
      invalid.setConfigJson("{\"modelCallTimeoutMillis\":0}");
      fixture.provider(invalid);
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot)));
      invalid.setConfigJson(
          "{\"modelCallTimeoutMillis\":45000,\"modelCallIdleTimeoutMillis\":3000}");
      invalid.setBaseUrl(" ");
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot)));
    }

    AgentRuntimeConfig namedTool = AgentRuntimeConfig.from(snapshot).withTools(List.of("read"));
    try (Fixture fixture = new Fixture(factory, List.of(tool("read", "1"), tool("read", "2")))) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, namedTool));
    }

    AgentRuntimeConfig environmentTool =
        AgentRuntimeConfig.from(snapshot).withTools(List.of("shell@1"));
    try (Fixture fixture =
        new Fixture(factory, List.of(tool("shell", "1", ToolExecutionMode.ENVIRONMENT)))) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, environmentTool));
    }
  }

  /**
   * {@code environment:<id>/<tool>@<version>} resolves to an ENVIRONMENT ToolBinding with
   * Environment id.
   */
  @Test
  void resolvesFrozenEnvironmentReferenceToEnvironmentBinding() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    AgentSnapshot snapshot = snapshot("11", "quality", List.of());
    DaemonToolCapabilitiesCodec codec = new DaemonToolCapabilitiesCodec();
    String capabilitiesJson =
        codec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(
                List.of(
                    new ToolDescriptor(
                        "shell",
                        "1",
                        "shell tool",
                        "renderer",
                        new ToolParamsSchema("", Map.of(), Set.of(), false),
                        ToolExecutionMode.ENVIRONMENT,
                        ToolSideEffect.READ_ONLY,
                        Duration.ofSeconds(5)))));
    ToolEnvironment environment = new ToolEnvironment();
    environment.setId(123L);
    environment.setName("env-123");
    environment.setCapabilitiesJson(capabilitiesJson);

    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      fixture.environment(environment);

      TurnResources resources =
          fixture.resolver.resolve(
              SESSION_ID,
              THREAD_ID,
              AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:123/shell@1")));

      assertEquals(1, resources.toolBindings().size());
      assertEquals(ToolTargetType.ENVIRONMENT, resources.toolBindings().get(0).targetType());
      assertEquals(123L, resources.toolBindings().get(0).environmentId());
      assertEquals("shell", resources.toolBindings().get(0).descriptor().name());
      assertEquals("1", resources.toolBindings().get(0).descriptor().version());
      assertEquals(
          ToolExecutionMode.ENVIRONMENT, resources.toolDescriptors().get(0).executionMode());
    }
  }

  /**
   * Environment reference failures: malformed grammar, unknown id/capability, non-ENVIRONMENT,
   * collision.
   */
  @Test
  void rejectsMalformedUnknownOrCollidingEnvironmentReferences() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    AgentSnapshot snapshot = snapshot("11", "quality", List.of());
    // Encode a CLOUD descriptor manually to test that the resolver rejects it without
    // triggering the codec's own non-ENVIRONMENT guard.
    String cloudDescriptorJson =
        "{\"name\":\"cloud-tool\",\"version\":\"1\",\"description\":\"cloud tool\","
            + "\"rendererKey\":\"renderer\",\"executionMode\":\"CLOUD\","
            + "\"sideEffect\":\"READ_ONLY\",\"timeoutMillis\":0,"
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}";
    String environmentDescriptorJson =
        "{\"name\":\"shell\",\"version\":\"1\",\"description\":\"shell tool\","
            + "\"rendererKey\":\"renderer\",\"executionMode\":\"ENVIRONMENT\","
            + "\"sideEffect\":\"READ_ONLY\",\"timeoutMillis\":0,"
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}";
    String capabilitiesJson =
        "{\"tools\":[" + environmentDescriptorJson + "," + cloudDescriptorJson + "]}";
    ToolEnvironment environment = new ToolEnvironment();
    environment.setId(123L);
    environment.setName("env-123");
    environment.setCapabilitiesJson(capabilitiesJson);

    ToolDescriptor localCloud =
        new ToolDescriptor(
            "shell",
            "1",
            "local cloud shell",
            "shell",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      fixture.environment(environment);

      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:123"))));

      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:123/shell"))));

      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:abc/shell@1"))));

      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:+123/shell@1"))));

      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:-123/shell@1"))));

      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:99999999999999999999/shell@1"))));

      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:999/shell@1"))));
    }

    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      fixture.environment(environment);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:123/missing@1"))));
    }

    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      fixture.environment(environment);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:123/cloud-tool@1"))));
    }

    try (Fixture fixture =
        new Fixture(factory, List.of(toolFromDescriptor(localCloud, ToolExecutionMode.CLOUD)))) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      fixture.environment(environment);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("shell@1", "environment:123/shell@1"))));
    }
  }

  private void assertCachePolicy(
      ProviderType type,
      AgentProviderType persistedType,
      PromptCacheCapability capability,
      PromptCacheMode expectedMode,
      PromptCacheRetention expectedRetention) {
    CapturingProviderFactory factory = new CapturingProviderFactory(type, capability);
    AgentSnapshot snapshot = snapshot("11", "quality", List.of());
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, persistedType));

      TurnResources resources =
          fixture.resolver.resolve(SESSION_ID, THREAD_ID, AgentRuntimeConfig.from(snapshot));

      assertEquals(type, resources.model().providerType());
      assertEquals(expectedMode, resources.model().promptCachePolicy().capability().mode());
      assertEquals(expectedRetention, resources.model().promptCachePolicy().retention());
    }
  }

  private static AgentSnapshot snapshot(String modelId, String variant, List<String> tools) {
    return new AgentSnapshot("system", modelId, variant, tools, List.of(), List.of(), "{}");
  }

  private static AgentModel model(long id, long providerId, String config) {
    AgentModel model = new AgentModel();
    model.setId(id);
    model.setProviderId(providerId);
    model.setName("provider-api-model");
    model.setDescription("Provider API model");
    model.setCapabilitiesJson("[\"TEXT\",\"TOOLS\"]");
    model.setConfigJson(config);
    return model;
  }

  private static AgentProvider provider(long id, AgentProviderType type) {
    AgentProvider provider = new AgentProvider();
    provider.setId(id);
    provider.setName("provider");
    provider.setProviderType(type);
    provider.setBaseUrl("https://provider.test/v1");
    provider.setCredential("secret");
    provider.setConfigJson(
        "{\"modelCallTimeoutMillis\":45000,\"modelCallIdleTimeoutMillis\":3000}");
    return provider;
  }

  private static Tool tool(String name, String version) {
    return tool(name, version, ToolExecutionMode.CLOUD);
  }

  private static Tool tool(String name, String version, ToolExecutionMode executionMode) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            version,
            "test tool",
            name,
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            executionMode,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        throw new UnsupportedOperationException("not executed by resolver test");
      }
    };
  }

  private static Tool toolFromDescriptor(
      ToolDescriptor descriptor, ToolExecutionMode executionMode) {
    ToolDescriptor effective =
        descriptor.executionMode() == executionMode
            ? descriptor
            : new ToolDescriptor(
                descriptor.name(),
                descriptor.version(),
                descriptor.description(),
                descriptor.rendererKey(),
                descriptor.inputSchema(),
                executionMode,
                descriptor.sideEffect(),
                descriptor.timeout());
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return effective;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        throw new UnsupportedOperationException("not executed by resolver test");
      }
    };
  }

  private static final class Fixture implements AutoCloseable {
    private final SessionStore sessions = mock(SessionStore.class);
    private final AgentModelRepository models = mock(AgentModelRepository.class);
    private final AgentProviderRepository providers = mock(AgentProviderRepository.class);
    private final ToolEnvironmentRepository environments = mock(ToolEnvironmentRepository.class);
    private final HarnessExtensionHost host;
    private final DatabaseTurnResourceResolver resolver;

    private Fixture(CapturingProviderFactory providerFactory, List<Tool> tools) {
      HarnessExtension extension =
          new HarnessExtension() {
            @Override
            public String id() {
              return "resolver.test";
            }

            @Override
            public int priority() {
              return 0;
            }

            @Override
            public void contribute(HarnessExtensionRegistry registry) {
              if (providerFactory != null) {
                registry.addProviderFactory(providerFactory);
              }
              tools.forEach(tool -> registry.addToolFactory(ToolFactory.singleton(tool)));
            }
          };
      host = new HarnessExtensionHost(List.of(extension));
      HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
      properties.setEnvironmentRoot(Path.of("/tmp/harness-root"));
      properties.setWorkdir(Path.of("repository"));
      ObjectMapper objectMapper = new ObjectMapper();
      resolver =
          new DatabaseTurnResourceResolver(
              sessions,
              models,
              providers,
              new AgentProviderConfigurationCodec(objectMapper),
              new AgentModelRuntimeConfigParser(objectMapper),
              host,
              properties,
              environments,
              new DaemonToolCapabilitiesCodec());
    }

    /** requireFrozenSnapshot only checks session existence; path snapshot is ThreadProcessor. */
    private void session() {
      when(sessions.find(SESSION_ID))
          .thenReturn(Optional.of(Session.root(SESSION_ID, 1L, "session", NOW)));
    }

    private void model(AgentModel model) {
      when(models.getById(model.getId())).thenReturn(model);
    }

    private void provider(AgentProvider provider) {
      when(providers.getById(provider.getId())).thenReturn(provider);
    }

    private void environment(ToolEnvironment environment) {
      when(environments.getById(environment.getId())).thenReturn(environment);
    }

    @Override
    public void close() {
      host.close();
    }
  }

  private static final class CapturingProviderFactory implements ProviderFactory {
    private final ProviderType type;
    private final PromptCacheCapability capability;
    private final ModelProvider provider = (request, handler) -> null;
    private String credential;
    private String configJson;
    private ProviderDescriptor descriptor;

    private CapturingProviderFactory(ProviderType type, PromptCacheCapability capability) {
      this.type = type;
      this.capability = capability;
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
      this.credential = credential;
      this.configJson = configJson;
      return new ProviderAdapter() {
        @Override
        public ProviderType providerType() {
          return type;
        }

        @Override
        public ModelProvider create(ProviderDescriptor descriptor) {
          CapturingProviderFactory.this.descriptor = descriptor;
          return provider;
        }
      };
    }
  }
}
