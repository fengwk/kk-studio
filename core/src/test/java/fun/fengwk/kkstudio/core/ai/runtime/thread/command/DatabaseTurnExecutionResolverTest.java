package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailure;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailureKind;
import fun.fengwk.kkstudio.harness.runtime.model.plan.TurnExecutionResolver.Resolution;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Covers the nullable Environment boundary without weakening exact Agent capability resolution. */
class DatabaseTurnExecutionResolverTest {

  @Test
  void requiresEnvironmentForConfiguredEnvironmentTools() {
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());

    Resolution.Failed failed = assertInstanceOf(Resolution.Failed.class, fixture.resolve(null));

    // A missing selection must be distinguishable from a selected Environment going offline.
    assertEquals(PlanningFailureKind.ENVIRONMENT_REQUIRED, failed.failure().kind());
    assertEquals(
        "selected Agent requires a READY Environment for tools [read]", failed.failure().message());
  }

  @Test
  void requiresEnvironmentForConfiguredSkills() {
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of());

    Resolution.Failed failed = assertInstanceOf(Resolution.Failed.class, fixture.resolve(null));

    // Skills only exist behind a live Environment, so null cannot be silently treated as no skill.
    assertEquals(PlanningFailureKind.ENVIRONMENT_REQUIRED, failed.failure().kind());
    assertEquals(
        "selected Agent requires a READY Environment for skills [dev]", failed.failure().message());
  }

  @Test
  void reportsAllRequiredEnvironmentCapabilitiesTogether() {
    Fixture fixture = new Fixture(List.of("read"), List.of("dev"), List.of());

    PlanningFailure failure = failure(fixture.resolve(null));

    // One failure explains the complete configuration instead of forcing one retry per capability.
    assertEquals(PlanningFailureKind.ENVIRONMENT_REQUIRED, failure.kind());
    assertEquals(
        "selected Agent requires a READY Environment for tools [read] and skills [dev]",
        failure.message());
  }

  @Test
  void preservesSelectedUnavailableEnvironmentName() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    Resolution.Failed failed =
        assertInstanceOf(Resolution.Failed.class, fixture.resolve("offline"));

    // An explicit stale selection remains a separate failure and must retain its concrete name.
    assertEquals(PlanningFailureKind.ENVIRONMENT_NOT_FOUND, failed.failure().kind());
    assertEquals("environment not found or not READY/open: offline", failed.failure().message());
  }

  @Test
  void rejectsSelectedEnvironmentThatIsConnectedButNotReady() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.connectingEnvironment("starting");

    PlanningFailure failure = failure(fixture.resolve("starting"));

    // Registry presence alone is insufficient; planning requires READY and an open connection.
    assertEquals(PlanningFailureKind.ENVIRONMENT_NOT_FOUND, failure.kind());
    assertEquals("environment not found or not READY/open: starting", failure.message());
  }

  @Test
  void resolvesLocalToolsWithoutEnvironment() {
    ToolDescriptor localTool = descriptor("create_goal");
    Fixture fixture = new Fixture(List.of(localTool.name()), List.of(), List.of(localTool));

    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));

    // This proves nullable Environment still supports model-only and application-local tools.
    assertEquals(1, resolved.execution().toolBindings().size());
    assertEquals(localTool, resolved.execution().toolBindings().getFirst().descriptor());
    assertNull(resolved.execution().toolBindings().getFirst().environmentName());
  }

  @Test
  void resolvesModelOnlyAgentWithoutEnvironment() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());

    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));

    // Nullable Environment is a valid first-class setting when no remote capability is selected.
    assertEquals(List.of(), resolved.execution().toolBindings());
    assertEquals(List.of(), resolved.execution().skillBindings());
  }

  @Test
  void resolvesEnvironmentToolsAgainstReadySelection() {
    Fixture fixture = new Fixture(List.of("read"), List.of(), List.of());
    fixture.readyEnvironment("local");

    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve("local"));

    // A READY selection freezes the Environment target into the resolved ToolBinding.
    assertEquals(1, resolved.execution().toolBindings().size());
    assertEquals("read", resolved.execution().toolBindings().getFirst().descriptor().name());
    assertEquals("local", resolved.execution().toolBindings().getFirst().environmentName());
  }

  @Test
  void reportsMissingTurnSettingsAndCatalogResources() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    PlanningFailure missingSettings = failure(fixture.resolver.resolve(null));
    assertEquals(PlanningFailureKind.MISSING_TURN_SETTINGS, missingSettings.kind());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingAgent();
    PlanningFailure missingAgent = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.AGENT_NOT_FOUND, missingAgent.kind());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingProvider();
    PlanningFailure missingProvider = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.PROVIDER_NOT_FOUND, missingProvider.kind());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.missingModel();
    PlanningFailure missingModel = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.MODEL_NOT_FOUND, missingModel.kind());
  }

  @Test
  void reportsInvalidReferencesAndProviderType() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.agent.setModelProviderName(null);
    PlanningFailure missingProviderReference = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, missingProviderReference.kind());
    assertEquals(
        "cannot resolve turn settings: agent model provider reference is blank",
        missingProviderReference.message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.agent.setModelName(" ");
    PlanningFailure missingModelReference = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, missingModelReference.kind());
    assertEquals(
        "cannot resolve turn settings: agent model reference is blank",
        missingModelReference.message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.provider.setProviderType(null);
    PlanningFailure missingProviderType = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, missingProviderType.kind());
    assertEquals("provider type must not be null", missingProviderType.message());
  }

  @Test
  void reportsStoredConfigurationFailuresWithoutLeakingNullMessages() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failModelConfig(new IllegalStateException("broken model config"));
    PlanningFailure invalidConfig = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, invalidConfig.kind());
    assertEquals(
        "stored Agent or Model configuration is invalid: broken model config",
        invalidConfig.message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failAgentLookup(new IllegalStateException());
    PlanningFailure noDetail = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, noDetail.kind());
    assertEquals("cannot resolve turn settings", noDetail.message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.failAgentLookup(new IllegalStateException(" "));
    PlanningFailure blankDetail = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, blankDetail.kind());
    assertEquals("cannot resolve turn settings", blankDetail.message());
  }

  @Test
  void resolvesDefaultVariantAndReportsMissingExplicitVariant() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.agent.setVariant(null);
    Resolution.Resolved defaulted =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));
    assertEquals("default", defaulted.execution().variant().id());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.agent.setVariant(" ");
    Resolution.Resolved blankDefaulted =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));
    assertEquals("default", blankDefaulted.execution().variant().id());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.agent.setVariant("missing");
    PlanningFailure missingVariant = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.VARIANT_NOT_FOUND, missingVariant.kind());
    assertEquals(
        "model variant not found: provider/model variant=missing", missingVariant.message());
  }

  @Test
  void reportsUnknownToolAndMissingSkillByName() {
    ToolDescriptor localTool = descriptor("create_goal");
    Fixture fixture =
        new Fixture(List.of(localTool.name(), "missing"), List.of(), List.of(localTool));
    PlanningFailure missingTool = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.TOOL_NOT_FOUND, missingTool.kind());
    assertEquals("tool not found: missing", missingTool.message());

    fixture = new Fixture(List.of(), List.of("available", "missing"), List.of());
    fixture.readyEnvironment("local", List.of("available"));
    PlanningFailure missingSkill = failure(fixture.resolve("local"));
    assertEquals(PlanningFailureKind.SKILL_NOT_FOUND, missingSkill.kind());
    assertEquals("skill not found in environment local: missing", missingSkill.message());
  }

  @Test
  void reportsMissingProviderFactoryAndUnsupportedModelTools() {
    Fixture fixture =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            false);
    PlanningFailure missingFactory = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.PROVIDER_NOT_FOUND, missingFactory.kind());
    assertEquals("provider factory not found for provider (OPENAI)", missingFactory.message());

    ToolDescriptor localTool = descriptor("create_goal");
    fixture = new Fixture(List.of(localTool.name()), List.of(), List.of(localTool));
    fixture.modelSupportsTools(false);
    PlanningFailure unsupportedTools = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, unsupportedTools.kind());
    assertEquals("model does not support tools: provider/model", unsupportedTools.message());
  }

  @Test
  void requiresAndInjectsRuntimeManagedLoadSkillTool() {
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of());
    fixture.readyEnvironment("local", List.of("dev"));
    PlanningFailure missingLoadSkill = failure(fixture.resolve("local"));
    assertEquals(PlanningFailureKind.TOOL_NOT_FOUND, missingLoadSkill.kind());
    assertEquals("runtime-managed tool not found: load_skill", missingLoadSkill.message());

    ToolDescriptor loadSkill = descriptor("load_skill");
    fixture =
        new Fixture(
            List.of(),
            List.of("dev"),
            List.of(loadSkill),
            Set.of(loadSkill.name()),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true);
    fixture.readyEnvironment("local", List.of("dev"));
    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve("local"));
    assertEquals(
        List.of(loadSkill),
        resolved.execution().toolBindings().stream().map(binding -> binding.descriptor()).toList());
    assertEquals("local", resolved.execution().skillBindings().getFirst().sourceEnvironment());
  }

  @Test
  void mapsEveryProviderTypeAndSelectsSupportedPromptCacheRetention() {
    Map<AgentProviderType, ProviderType> mappings =
        Map.of(
            AgentProviderType.openai,
            ProviderType.OPENAI,
            AgentProviderType.openai_response,
            ProviderType.OPENAI_RESPONSES,
            AgentProviderType.anthropic,
            ProviderType.ANTHROPIC,
            AgentProviderType.google,
            ProviderType.GOOGLE);
    for (Map.Entry<AgentProviderType, ProviderType> mapping : mappings.entrySet()) {
      Fixture fixture =
          new Fixture(
              List.of(),
              List.of(),
              List.of(),
              Set.of(),
              mapping.getKey(),
              mapping.getValue(),
              PromptCacheCapability.unsupported(),
              true);
      Resolution.Resolved resolved =
          assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));
      assertEquals(mapping.getValue(), resolved.execution().model().providerType());
      assertEquals(
          PromptCacheRetention.NONE, resolved.execution().model().promptCachePolicy().retention());
    }

    Fixture cached =
        new Fixture(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)),
            true);
    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, cached.resolve(null));
    assertEquals(
        PromptCacheRetention.SHORT, resolved.execution().model().promptCachePolicy().retention());
  }

  @Test
  void rejectsMissingAndNegativeProviderVersions() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.provider.setVersion(null);
    PlanningFailure missingVersion = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, missingVersion.kind());
    assertEquals(
        "cannot resolve turn settings: provider version must be non-negative",
        missingVersion.message());

    fixture = new Fixture(List.of(), List.of(), List.of());
    fixture.provider.setVersion(-1L);
    PlanningFailure negativeVersion = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, negativeVersion.kind());
    assertEquals(
        "cannot resolve turn settings: provider version must be non-negative",
        negativeVersion.message());
  }

  private static PlanningFailure failure(Resolution resolution) {
    return assertInstanceOf(Resolution.Failed.class, resolution).failure();
  }

  private static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "1",
        name + " description",
        name,
        new ToolParamsSchema(null, Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
        "USD",
        "standard",
        "default",
        BigDecimal.ONE,
        "v1",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static final class Fixture {

    private final AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    private final AgentModelRepository models = mock(AgentModelRepository.class);
    private final AgentProviderRepository providers = mock(AgentProviderRepository.class);
    private final AgentDefinitionConfigCodec agentConfigCodec =
        mock(AgentDefinitionConfigCodec.class);
    private final AgentModelRuntimeConfigParser modelConfigParser =
        mock(AgentModelRuntimeConfigParser.class);
    private final LiveEnvironmentRegistry environmentRegistry = new LiveEnvironmentRegistry();
    private final AgentDefinition agent = new AgentDefinition();
    private final AgentProvider provider = new AgentProvider();
    private final DatabaseTurnExecutionResolver resolver;

    private Fixture(
        List<String> tools, List<String> skills, List<ToolDescriptor> localDescriptors) {
      this(
          tools,
          skills,
          localDescriptors,
          Set.of(),
          AgentProviderType.openai,
          ProviderType.OPENAI,
          PromptCacheCapability.unsupported(),
          true);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> localDescriptors,
        Set<String> runtimeManagedToolNames,
        AgentProviderType persistedProviderType,
        ProviderType factoryType,
        PromptCacheCapability cacheCapability,
        boolean includeProviderFactory) {
      agent.setName("assistant");
      agent.setModelProviderName("provider");
      agent.setModelName("model");
      agent.setVariant("default");
      agent.setConfigJson("agent-config");
      when(agents.getByName("assistant")).thenReturn(agent);

      provider.setName("provider");
      provider.setProviderType(persistedProviderType);
      provider.setVersion(0L);
      when(providers.getByName("provider")).thenReturn(provider);

      AgentModel model = new AgentModel();
      model.setProviderName("provider");
      model.setName("model");
      model.setConfigJson("model-config");
      when(models.getByProviderNameAndName("provider", "model")).thenReturn(model);

      AgentDefinitionConfigDTO agentConfig = new AgentDefinitionConfigDTO();
      agentConfig.setTools(tools);
      agentConfig.setSkills(skills);
      when(agentConfigCodec.decode("agent-config")).thenReturn(agentConfig);

      modelSupportsTools(true);
      ProviderFactory providerFactory = mock(ProviderFactory.class);
      when(providerFactory.providerType()).thenReturn(factoryType);
      when(providerFactory.promptCacheCapability()).thenReturn(cacheCapability);
      List<ProviderFactory> providerFactories =
          includeProviderFactory ? List.of(providerFactory) : List.of();
      resolver =
          new DatabaseTurnExecutionResolver(
              agents,
              models,
              providers,
              agentConfigCodec,
              modelConfigParser,
              new ProviderFactories(providerFactories),
              new ToolCatalog(localDescriptors, runtimeManagedToolNames),
              environmentRegistry);
    }

    private void missingAgent() {
      when(agents.getByName("assistant")).thenReturn(null);
    }

    private void missingProvider() {
      when(providers.getByName("provider")).thenReturn(null);
    }

    private void missingModel() {
      when(models.getByProviderNameAndName("provider", "model")).thenReturn(null);
    }

    private void failAgentLookup(RuntimeException error) {
      when(agents.getByName("assistant")).thenThrow(error);
    }

    private void failModelConfig(RuntimeException error) {
      when(modelConfigParser.parse("model-config")).thenThrow(error);
    }

    private void modelSupportsTools(boolean tools) {
      ModelVariant variant =
          new ModelVariant("default", 1024, null, null, null, null, null, List.of(), null);
      when(modelConfigParser.parse("model-config"))
          .thenReturn(
              new ParsedAgentModelConfig(
                  4096,
                  1024,
                  Set.of(ModelInputModality.TEXT),
                  tools,
                  false,
                  List.of(variant),
                  variant.id(),
                  pricing()));
    }

    private Resolution resolve(String environmentName) {
      return resolver.resolve(new TurnSettings("assistant", environmentName, false));
    }

    private void connectingEnvironment(String environmentName) {
      EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
      when(connection.connectionId()).thenReturn("connection");
      when(connection.isOpen()).thenReturn(true);
      Instant now = Instant.parse("2026-08-02T00:00:00Z");
      environmentRegistry.tryBind(environmentName, connection, now);
    }

    private void readyEnvironment(String environmentName) {
      readyEnvironment(environmentName, List.of());
    }

    private void readyEnvironment(String environmentName, List<String> skills) {
      EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
      when(connection.connectionId()).thenReturn("connection");
      when(connection.isOpen()).thenReturn(true);
      Instant now = Instant.parse("2026-08-02T00:00:00Z");
      environmentRegistry.tryBind(environmentName, connection, now);
      environmentRegistry.updateSkills(
          environmentName,
          connection,
          skills.stream()
              .map(name -> new DaemonSkillDescriptor(name, name + " description"))
              .toList(),
          now);
      environmentRegistry.markReady(environmentName, connection, now);
    }
  }
}
