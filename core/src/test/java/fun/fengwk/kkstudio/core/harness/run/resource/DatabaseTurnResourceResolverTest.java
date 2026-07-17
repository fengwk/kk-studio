package fun.fengwk.kkstudio.core.harness.run.resource;

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
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
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
import fun.fengwk.kkstudio.harness.runtime.run.TurnResources;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;
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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

class DatabaseTurnResourceResolverTest {

  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
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

  /**
   * The active path snapshot selects persisted IDs while API model name, pricing, and tools freeze.
   */
  @Test
  void resolvesLatestFrozenSnapshotToTrustedProviderModelVariantAndTool() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    Tool read = tool("read", "1");
    try (Fixture fixture = new Fixture(factory, List.of(read))) {
      AgentSnapshot older = snapshot("10", "fast", List.of());
      AgentSnapshot latest = snapshot("11", "quality", List.of("read"));
      fixture.path(older, latest);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));

      TurnResources resources = fixture.resolver.resolve(7L, AgentRuntimeConfig.from(latest));

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
      assertEquals("{\"timeoutMillis\":45000}", factory.configJson);
      assertEquals("22", factory.descriptor.providerId());
      assertEquals(Duration.ofSeconds(45), factory.descriptor.timeout());
      assertSame(factory.provider, resources.provider());
      verify(fixture.models).getById(11L);
    }
  }

  /** Provider type determines a policy only when the registered factory capability validates it. */
  @Test
  void mapsEveryPersistedProviderTypeToVerifiedCachePolicy() {
    assertCachePolicy(
        ProviderType.OPENAI,
        "openai",
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
        PromptCacheMode.AFFINITY,
        PromptCacheRetention.SHORT);
    assertCachePolicy(
        ProviderType.OPENAI_RESPONSES,
        "openai_response",
        PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
        PromptCacheMode.AFFINITY,
        PromptCacheRetention.SHORT);
    assertCachePolicy(
        ProviderType.ANTHROPIC,
        "anthropic",
        PromptCacheCapability.breakpoints(
            Set.of(PromptCacheRetention.SHORT),
            EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
        PromptCacheMode.BREAKPOINTS,
        PromptCacheRetention.SHORT);
    assertCachePolicy(
        ProviderType.GOOGLE,
        "google",
        PromptCacheCapability.automatic(),
        PromptCacheMode.AUTOMATIC,
        PromptCacheRetention.NONE);
  }

  /**
   * Missing snapshots, factories, variants, tools, and executable model fields fail before a turn.
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
      fixture.provider(provider(22L, "openai"));
      when(fixture.sessions.find(7L)).thenReturn(Optional.of(session(102L)));
      when(fixture.entries.loadPath(7L, 102L)).thenReturn(List.of());
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot)));

      fixture.path(snapshot);
      AgentRuntimeConfig missingVariant =
          AgentRuntimeConfig.from(snapshot).withModel("11", "missing");
      assertThrows(
          IllegalArgumentException.class, () -> fixture.resolver.resolve(7L, missingVariant));

      AgentRuntimeConfig malformedTool =
          AgentRuntimeConfig.from(snapshot).withTools(List.of("read@"));
      assertThrows(
          IllegalArgumentException.class, () -> fixture.resolver.resolve(7L, malformedTool));
      AgentRuntimeConfig missingTool =
          AgentRuntimeConfig.from(snapshot).withTools(List.of("missing@1"));
      assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(7L, missingTool));

      fixture.model(model(11L, 22L, "{\"variants\":[]}"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot)));
    }

    try (Fixture fixture = new Fixture(null, List.of())) {
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot)));
      assertTrue(error.getMessage().contains("ProviderFactory"));
    }

    CapturingProviderFactory mismatchedCapability =
        new CapturingProviderFactory(ProviderType.OPENAI, PromptCacheCapability.automatic());
    try (Fixture fixture = new Fixture(mismatchedCapability, List.of())) {
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot)));
    }
  }

  /**
   * Missing database rows, invalid provider config, and ambiguous tool names fail
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
      fixture.path(snapshot);
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot)));
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot)));

      AgentProvider invalid = provider(22L, "openai");
      invalid.setConfigJson("{}");
      fixture.provider(invalid);
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot)));
      invalid.setConfigJson("{\"timeoutMillis\":45000}");
      invalid.setBaseUrl(" ");
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot)));
    }

    AgentRuntimeConfig namedTool = AgentRuntimeConfig.from(snapshot).withTools(List.of("read"));
    try (Fixture fixture = new Fixture(factory, List.of(tool("read", "1"), tool("read", "2")))) {
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      assertThrows(IllegalArgumentException.class, () -> fixture.resolver.resolve(7L, namedTool));
    }

    AgentRuntimeConfig environmentTool =
        AgentRuntimeConfig.from(snapshot).withTools(List.of("shell@1"));
    try (Fixture fixture =
        new Fixture(factory, List.of(tool("shell", "1", ToolExecutionMode.ENVIRONMENT)))) {
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      assertThrows(
          IllegalArgumentException.class, () -> fixture.resolver.resolve(7L, environmentTool));
    }
  }

  /**
   * {@code environment:<id>/<tool>@<version>} 引用必须在调度 turn 之前完成存在性、capability 严格解码、
   * exact-name-at-version 与 ENVIRONMENT 模式的校验；返回的 {@link
   * fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding} 必须携带 Environment id。
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
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      fixture.environment(environment);

      TurnResources resources =
          fixture.resolver.resolve(
              7L, AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:123/shell@1")));

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
   * Environment 引用失败路径：malformed grammar、unknown id、unknown capability、non-ENVIRONMENT、collision。
   */
  @Test
  void rejectsMalformedUnknownOrCollidingEnvironmentReferences() {
    CapturingProviderFactory factory =
        new CapturingProviderFactory(
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
    AgentSnapshot snapshot = snapshot("11", "quality", List.of());
    DaemonToolCapabilitiesCodec codec = new DaemonToolCapabilitiesCodec();
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
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      fixture.environment(environment);

      // 1) malformed grammar: 缺少 '/'
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L, AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:123"))));

      // 2) malformed grammar: 缺少 '@version'
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:123/shell"))));

      // 3) 非正数字 id
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:abc/shell@1"))));

      // 3a) plus-signed id 拒绝
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:+123/shell@1"))));

      // 3b) 负数 id 拒绝
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:-123/shell@1"))));

      // 3c) overflow id 拒绝
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:99999999999999999999/shell@1"))));

      // 4) Environment id 不存在
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot).withTools(List.of("environment:999/shell@1"))));
    }

    // 5) unknown capability name@version
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      fixture.environment(environment);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:123/missing@1"))));
    }

    // 6) non-ENVIRONMENT descriptor: environment:123/cloud-tool@1 不被允许
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      fixture.environment(environment);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("environment:123/cloud-tool@1"))));
    }

    // 7) collision: 本地 cloud-shell 与 Environment 的 shell@1 名字重复
    try (Fixture fixture =
        new Fixture(factory, List.of(toolFromDescriptor(localCloud, ToolExecutionMode.CLOUD)))) {
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, "openai"));
      fixture.environment(environment);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.resolver.resolve(
                  7L,
                  AgentRuntimeConfig.from(snapshot)
                      .withTools(List.of("shell@1", "environment:123/shell@1"))));
    }
  }

  private void assertCachePolicy(
      ProviderType type,
      String persistedType,
      PromptCacheCapability capability,
      PromptCacheMode expectedMode,
      PromptCacheRetention expectedRetention) {
    CapturingProviderFactory factory = new CapturingProviderFactory(type, capability);
    AgentSnapshot snapshot = snapshot("11", "quality", List.of());
    try (Fixture fixture = new Fixture(factory, List.of())) {
      fixture.path(snapshot);
      fixture.model(model(11L, 22L, MODEL_CONFIG));
      fixture.provider(provider(22L, persistedType));

      TurnResources resources = fixture.resolver.resolve(7L, AgentRuntimeConfig.from(snapshot));

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

  private static AgentProvider provider(long id, String type) {
    AgentProvider provider = new AgentProvider();
    provider.setId(id);
    provider.setName("provider");
    provider.setProviderType(PersistedProviderTypes.from(type));
    provider.setBaseUrl("https://provider.test/v1");
    provider.setCredential("secret");
    provider.setConfigJson("{\"timeoutMillis\":45000}");
    return provider;
  }

  private static Session session(long leafEntryId) {
    return new Session(
        7L, 1L, "session", leafEntryId, null, null, 7L, null, 0, false, 1L, NOW, NOW);
  }

  private static SessionEntry entry(long id, Long parentId, AgentSnapshot snapshot) {
    AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(snapshot);
    return new SessionEntry(id, 7L, parentId, null, SessionEntryType.AGENT_SNAPSHOT, payload, NOW);
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
    private final SessionEntryStore entries = mock(SessionEntryStore.class);
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
              entries,
              models,
              providers,
              new AgentModelRuntimeConfigParser(objectMapper),
              host,
              properties,
              environments,
              new DaemonToolCapabilitiesCodec(),
              objectMapper);
    }

    private void path(AgentSnapshot... snapshots) {
      ArrayList<SessionEntry> path = new ArrayList<>();
      Long parent = null;
      for (int index = 0; index < snapshots.length; index++) {
        long id = 101L + index;
        path.add(entry(id, parent, snapshots[index]));
        parent = id;
      }
      long leafEntryId = Objects.requireNonNull(parent, "snapshot path must not be empty");
      when(sessions.find(7L)).thenReturn(Optional.of(session(leafEntryId)));
      when(entries.loadPath(7L, leafEntryId)).thenReturn(List.copyOf(path));
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

    private void environment(long id) {
      when(environments.getById(id)).thenReturn(null);
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
