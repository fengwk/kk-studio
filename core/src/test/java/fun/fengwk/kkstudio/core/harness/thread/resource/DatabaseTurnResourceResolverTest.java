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
import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
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
import fun.fengwk.kkstudio.harness.runtime.context.SelectedSkillMetadata;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtension;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionRegistry;
import fun.fengwk.kkstudio.harness.runtime.extension.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.extension.ToolFactory;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnResources;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
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
 * Covers {@link DatabaseTurnResourceResolver}: short-name platform-first tool resolution and
 * offline selected Environment failure.
 */
class DatabaseTurnResourceResolverTest {

  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
  private static final long SESSION_ID = 7L;
  private static final long THREAD_ID = 9L;
  private static final String MODEL_CONFIG =
      "{\"limit\":{\"context\":128000,\"output\":8192},"
          + "\"abilities\":{\"tools\":true,\"reasoning\":false,"
          + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
          + "\"defaultVariant\":\"quality\","
          + "\"variants\":[{\"id\":\"quality\",\"maxOutputTokens\":4096},"
          + "{\"id\":\"fast\",\"maxOutputTokens\":1024}],"
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
      AgentRuntimeConfig config = runtimeConfig("11", "quality", null, List.of("read"));
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));

      TurnResources resources = fixture.resolver.resolve(SESSION_ID, THREAD_ID, config);

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
    AgentRuntimeConfig config = runtimeConfig("11", "quality", null, List.of());
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, config));

      fixture.session();
      AgentRuntimeConfig missingVariant = config.withModel("11", "missing");
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, missingVariant));

      AgentRuntimeConfig longName = config.withTools(List.of("read@1"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, longName));
      AgentRuntimeConfig missingTool = config.withTools(List.of("missing"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, missingTool));

      fixture.model(model(11L, 22L, "{\"variants\":[]}"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, config));
    }

    try (Fixture fixture = new Fixture(null, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, config));
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
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, config));
    }
  }

  /**
   * Missing model/provider rows, invalid provider config, and ambiguous tools fail
   * deterministically.
   */
  @Test
  void rejectsInvalidProviderRowsAndAmbiguousOrEnvironmentOnlyTools() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    AgentRuntimeConfig config = runtimeConfig("11", "quality", null, List.of());
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.session();
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, config));
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, config));

      AgentProvider defaulted = provider(22L, AgentProviderType.openai);
      defaulted.setConfigJson("{}");
      fixture.provider(defaulted);
      TurnResources defaultedResources = fixture.resolver.resolve(SESSION_ID, THREAD_ID, config);
      assertEquals(ModelCallTimeoutPolicy.DEFAULT, defaultedResources.modelCallTimeoutPolicy());

      AgentProvider invalid = provider(22L, AgentProviderType.openai);
      invalid.setConfigJson("{\"modelCallTimeoutMillis\":0}");
      fixture.provider(invalid);
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, config));
      invalid.setConfigJson(
          "{\"modelCallTimeoutMillis\":45000,\"modelCallIdleTimeoutMillis\":3000}");
      invalid.setBaseUrl(" ");
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, config));
    }

    AgentRuntimeConfig namedTool = config.withTools(List.of("read"));
    try (Fixture fixture = new Fixture(factory, List.of(tool("read", "1"), tool("read", "2")))) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(SESSION_ID, THREAD_ID, namedTool));
    }

    // Registered ENVIRONMENT tools are not platform tools; short name needs selected Environment.
    AgentRuntimeConfig environmentTool = config.withTools(List.of("shell"));
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

  /** Goal tools are auto-injected; load_skill only when selected skills exist. */
  @Test
  void autoInjectsGoalToolsAndConditionalLoadSkill() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    Tool createGoal = tool("create_goal", "1", ToolExecutionMode.CONTROL);
    Tool getGoal = tool("get_goal", "1", ToolExecutionMode.CONTROL);
    Tool updateGoal = tool("update_goal", "1", ToolExecutionMode.CONTROL);
    Tool loadSkill = tool("load_skill", "1", ToolExecutionMode.CONTROL);
    Tool read = tool("read", "1");

    try (Fixture fixture =
        new Fixture(factory, List.of(createGoal, getGoal, updateGoal, loadSkill, read))) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));

      TurnResources withoutSkills =
          fixture.resolver.resolve(
              SESSION_ID, THREAD_ID, runtimeConfig("11", "quality", null, List.of("read")));
      assertEquals(
          List.of("read", "create_goal", "get_goal", "update_goal"),
          withoutSkills.toolBindings().stream().map(b -> b.descriptor().name()).toList());

      TurnResources withSkills =
          fixture.resolver.resolve(
              SESSION_ID,
              THREAD_ID,
              runtimeConfig(
                  "11",
                  "quality",
                  null,
                  List.of(),
                  List.of("dev"),
                  List.of(new SelectedSkillMetadata("dev", "Developer rules", "platform"))));
      assertEquals(
          List.of("create_goal", "get_goal", "update_goal", "load_skill"),
          withSkills.toolBindings().stream().map(b -> b.descriptor().name()).toList());
      assertTrue(
          withSkills.toolBindings().stream()
              .allMatch(binding -> binding.targetType() == ToolTargetType.CONTROL));
    }
  }

  /** Short names resolve platform-first, then selected Environment fallback. */
  @Test
  void resolvesShortNamesPlatformFirstThenEnvironmentFallback() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    Tool read = tool("read", "1");
    ToolDescriptor shell =
        new ToolDescriptor(
            "shell",
            "1",
            "shell tool",
            "renderer",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolExecutionMode.ENVIRONMENT,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));
    ToolDescriptor envRead =
        new ToolDescriptor(
            "read",
            "9",
            "env read",
            "read",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolExecutionMode.ENVIRONMENT,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));

    try (Fixture fixture = new Fixture(factory, List.of(read))) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      fixture.readyEnvironment("local-dev", List.of(shell, envRead), List.of());

      TurnResources resources =
          fixture.resolver.resolve(
              SESSION_ID,
              THREAD_ID,
              runtimeConfig("11", "quality", "local-dev", List.of("read", "shell")));

      assertEquals(2, resources.toolBindings().size());
      assertEquals(ToolTargetType.CLOUD, resources.toolBindings().get(0).targetType());
      assertEquals("read", resources.toolBindings().get(0).descriptor().name());
      assertEquals("1", resources.toolBindings().get(0).descriptor().version());
      assertEquals(ToolTargetType.ENVIRONMENT, resources.toolBindings().get(1).targetType());
      assertEquals("local-dev", resources.toolBindings().get(1).environmentName());
      assertEquals("shell", resources.toolBindings().get(1).descriptor().name());
    }
  }

  /** Selected Environment offline at turn setup fails clearly without fallback. */
  @Test
  void rejectsOfflineSelectedEnvironmentWithoutFallback() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    Tool read = tool("read", "1");
    try (Fixture fixture = new Fixture(factory, List.of(read))) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));

      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  fixture.resolver.resolve(
                      SESSION_ID,
                      THREAD_ID,
                      runtimeConfig("11", "quality", "offline-env", List.of("read"))));
      assertTrue(error.getMessage().contains("offline or missing"));
      assertTrue(error.getMessage().contains("offline-env"));
    }
  }

  /** Unique model-visible descriptor names are preserved across bindings. */
  @Test
  void rejectsDuplicateDescriptorNamesAcrossBindings() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    Tool shellCloud = tool("shell", "1", ToolExecutionMode.CLOUD);
    ToolDescriptor shellEnv =
        new ToolDescriptor(
            "shell",
            "2",
            "env shell",
            "shell",
            new ToolParamsSchema("", Map.of(), Set.of(), false),
            ToolExecutionMode.ENVIRONMENT,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(5));
    try (Fixture fixture = new Fixture(factory, List.of(shellCloud))) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, AgentProviderType.openai));
      fixture.readyEnvironment("local-dev", List.of(shellEnv), List.of());
      // Platform wins for "shell"; selecting it twice still collides on descriptor name.
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  SESSION_ID,
                  THREAD_ID,
                  runtimeConfig("11", "quality", "local-dev", List.of("shell", "shell"))));
    }
  }

  private void assertCachePolicy(
      ProviderType type,
      AgentProviderType persistedType,
      PromptCacheCapability capability,
      PromptCacheMode expectedMode,
      PromptCacheRetention expectedRetention) {
    CapturingProviderFactory factory = new CapturingProviderFactory(type, capability);
    AgentRuntimeConfig config = runtimeConfig("11", "quality", null, List.of());
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.session();
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, persistedType));

      TurnResources resources = fixture.resolver.resolve(SESSION_ID, THREAD_ID, config);

      assertEquals(type, resources.model().providerType());
      assertEquals(expectedMode, resources.model().promptCachePolicy().capability().mode());
      assertEquals(expectedRetention, resources.model().promptCachePolicy().retention());
    }
  }

  private static AgentRuntimeConfig runtimeConfig(
      String modelId, String variant, String environmentName, List<String> tools) {
    return runtimeConfig(modelId, variant, environmentName, tools, List.of(), List.of());
  }

  private static AgentRuntimeConfig runtimeConfig(
      String modelId,
      String variant,
      String environmentName,
      List<String> tools,
      List<String> skills,
      List<SelectedSkillMetadata> selectedSkills) {
    return new AgentRuntimeConfig(
        1L,
        "system",
        modelId,
        variant,
        environmentName,
        tools,
        skills,
        selectedSkills,
        List.of(),
        "{}",
        false);
  }

  private static AgentModel model(long id, long providerId, String config) {
    AgentModel model = new AgentModel();
    model.setId(id);
    model.setProviderId(providerId);
    model.setName("provider-api-model");
    model.setDescription("Provider API model");
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

  private static final class Fixture implements AutoCloseable {
    private final SessionStore sessions = mock(SessionStore.class);
    private final AgentModelRepository models = mock(AgentModelRepository.class);
    private final AgentProviderRepository providers = mock(AgentProviderRepository.class);
    private final LiveEnvironmentRegistry environments =
        new LiveEnvironmentRegistry(new DaemonToolCapabilitiesCodec());
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
              environments);
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

    private void readyEnvironment(
        String name, List<ToolDescriptor> tools, List<DaemonSkillDescriptor> skills) {
      EnvironmentDaemonConnection connection =
          new EnvironmentDaemonConnection() {
            @Override
            public String connectionId() {
              return "test-" + name;
            }

            @Override
            public boolean isOpen() {
              return true;
            }

            @Override
            public void sendText(String text) {}

            @Override
            public void close() {}
          };
      Instant now = NOW;
      environments.tryBind(name, connection, now);
      environments.updateCapabilities(
          name,
          connection,
          new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(tools, skills),
          now);
      environments.markReady(name, connection, now);
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
