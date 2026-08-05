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
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
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

/** Covers the latest per-invocation Platform/Environment capability view. */
class DatabaseTurnExecutionResolverTest {

  /** The resolver interprets its Environment String as the canonical id; names are display-only. */
  private static final EnvironmentId LOCAL_ENVIRONMENT_ID =
      new EnvironmentId("7f8fad5b-d9cb-469f-a165-70867728950e");

  private static final EnvironmentId STARTING_ENVIRONMENT_ID =
      new EnvironmentId("8f8fad5b-d9cb-469f-a165-70867728950e");
  private static final EnvironmentId OFFLINE_ENVIRONMENT_ID =
      new EnvironmentId("9f8fad5b-d9cb-469f-a165-70867728950e");

  @Test
  void noEnvironmentKeepsPlatformToolsAndOmitsEnvironmentTools() {
    ToolDescriptor platformTool = descriptor("create_goal");
    Fixture fixture =
        new Fixture(List.of(platformTool.name(), "read"), List.of(), List.of(platformTool));

    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));

    assertEquals(
        List.of(platformTool.name()),
        resolved.execution().toolBindings().stream()
            .map(binding -> binding.descriptor().name())
            .toList());
    assertEquals(ToolType.PLATFORM, resolved.execution().toolBindings().getFirst().type());
  }

  @Test
  void omitsSkillsWhenNoEnvironmentIsSelected() {
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of());

    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));

    assertEquals(List.of(), resolved.execution().skillBindings());
  }

  @Test
  void omitsAllUnavailableEnvironmentCapabilitiesTogether() {
    Fixture fixture = new Fixture(List.of("read"), List.of("dev"), List.of());

    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));

    assertEquals(List.of(), resolved.execution().toolBindings());
    assertEquals(List.of(), resolved.execution().skillBindings());
  }

  @Test
  void staleEnvironmentKeepsPlatformToolsAndOmitsEnvironmentTools() {
    ToolDescriptor platformTool = descriptor("create_goal");
    Fixture fixture =
        new Fixture(List.of(platformTool.name(), "read"), List.of(), List.of(platformTool));

    Resolution.Resolved resolved =
        assertInstanceOf(
            Resolution.Resolved.class, fixture.resolve(OFFLINE_ENVIRONMENT_ID.value()));

    assertEquals(
        List.of(platformTool.name()),
        resolved.execution().toolBindings().stream()
            .map(binding -> binding.descriptor().name())
            .toList());
  }

  @Test
  void nonReadyEnvironmentKeepsPlatformToolsAndOmitsEnvironmentTools() {
    ToolDescriptor platformTool = descriptor("create_goal");
    Fixture fixture =
        new Fixture(List.of(platformTool.name(), "read"), List.of(), List.of(platformTool));
    fixture.connectingEnvironment(STARTING_ENVIRONMENT_ID, "starting");

    Resolution.Resolved resolved =
        assertInstanceOf(
            Resolution.Resolved.class, fixture.resolve(STARTING_ENVIRONMENT_ID.value()));

    assertEquals(
        List.of(platformTool.name()),
        resolved.execution().toolBindings().stream()
            .map(binding -> binding.descriptor().name())
            .toList());
  }

  @Test
  void resolvesPlatformToolsWithoutEnvironment() {
    ToolDescriptor platformTool = descriptor("create_goal");
    Fixture fixture = new Fixture(List.of(platformTool.name()), List.of(), List.of(platformTool));

    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(null));

    // This proves nullable Environment still supports model-only and Platform tools.
    assertEquals(1, resolved.execution().toolBindings().size());
    assertEquals(platformTool, resolved.execution().toolBindings().getFirst().descriptor());
    assertEquals(ToolType.PLATFORM, resolved.execution().toolBindings().getFirst().type());
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
  void readyEnvironmentAddsSelectedEnvironmentToolsToPlatformTools() {
    ToolDescriptor platformTool = descriptor("create_goal");
    Fixture fixture =
        new Fixture(List.of(platformTool.name(), "read"), List.of(), List.of(platformTool));
    fixture.readyEnvironment(LOCAL_ENVIRONMENT_ID, "local");

    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(LOCAL_ENVIRONMENT_ID.value()));

    assertEquals(
        List.of(platformTool.name(), "read"),
        resolved.execution().toolBindings().stream()
            .map(binding -> binding.descriptor().name())
            .toList());
    assertEquals(ToolType.PLATFORM, resolved.execution().toolBindings().get(0).type());
    assertNull(resolved.execution().toolBindings().get(0).environmentName());
    assertEquals(ToolType.ENVIRONMENT, resolved.execution().toolBindings().get(1).type());
    assertEquals(
        LOCAL_ENVIRONMENT_ID.value(), resolved.execution().toolBindings().get(1).environmentName());
  }

  @Test
  void reportsMissingTurnSettingsAndCatalogResources() {
    Fixture fixture = new Fixture(List.of(), List.of(), List.of());
    PlanningFailure missingSettings = failure(fixture.resolver.resolve(null, null));
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
    ToolDescriptor platformTool = descriptor("create_goal");
    Fixture fixture =
        new Fixture(List.of(platformTool.name(), "missing"), List.of(), List.of(platformTool));
    PlanningFailure missingTool = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.TOOL_NOT_FOUND, missingTool.kind());
    assertEquals("tool not found: missing", missingTool.message());

    ToolDescriptor loadSkill = descriptor("load_skill");
    fixture =
        new Fixture(
            List.of(),
            List.of("available", "missing"),
            List.of(loadSkill),
            Set.of(loadSkill.name()),
            AgentProviderType.openai,
            ProviderType.OPENAI,
            PromptCacheCapability.unsupported(),
            true);
    fixture.readyEnvironment(LOCAL_ENVIRONMENT_ID, "local", List.of("available"));
    Resolution.Resolved missingSkill =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(LOCAL_ENVIRONMENT_ID.value()));
    assertEquals(
        List.of("available"),
        missingSkill.execution().skillBindings().stream().map(skill -> skill.name()).toList());
    assertEquals(
        List.of("load_skill"),
        missingSkill.execution().toolBindings().stream()
            .map(binding -> binding.descriptor().name())
            .toList());
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

    ToolDescriptor platformTool = descriptor("create_goal");
    fixture = new Fixture(List.of(platformTool.name()), List.of(), List.of(platformTool));
    fixture.modelSupportsTools(false);
    PlanningFailure unsupportedTools = failure(fixture.resolve(null));
    assertEquals(PlanningFailureKind.INVALID_TURN_SETTINGS, unsupportedTools.kind());
    assertEquals("model does not support tools: provider/model", unsupportedTools.message());
  }

  @Test
  void requiresAndInjectsInternalPlatformLoadSkillTool() {
    Fixture fixture = new Fixture(List.of(), List.of("dev"), List.of());
    fixture.readyEnvironment(LOCAL_ENVIRONMENT_ID, "local", List.of("dev"));
    PlanningFailure missingLoadSkill = failure(fixture.resolve(LOCAL_ENVIRONMENT_ID.value()));
    assertEquals(PlanningFailureKind.TOOL_NOT_FOUND, missingLoadSkill.kind());
    assertEquals("internal platform tool not found: load_skill", missingLoadSkill.message());

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
    fixture.readyEnvironment(LOCAL_ENVIRONMENT_ID, "local", List.of("dev"));
    Resolution.Resolved resolved =
        assertInstanceOf(Resolution.Resolved.class, fixture.resolve(LOCAL_ENVIRONMENT_ID.value()));
    assertEquals(
        List.of(loadSkill),
        resolved.execution().toolBindings().stream().map(binding -> binding.descriptor()).toList());
    assertEquals(
        LOCAL_ENVIRONMENT_ID.value(),
        resolved.execution().skillBindings().getFirst().sourceEnvironment());
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
        ToolType.PLATFORM,
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
        List<String> tools, List<String> skills, List<ToolDescriptor> platformDescriptors) {
      this(
          tools,
          skills,
          platformDescriptors,
          Set.of(),
          AgentProviderType.openai,
          ProviderType.OPENAI,
          PromptCacheCapability.unsupported(),
          true);
    }

    private Fixture(
        List<String> tools,
        List<String> skills,
        List<ToolDescriptor> platformDescriptors,
        Set<String> internalPlatformToolNames,
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
              new ToolCatalog(platformDescriptors, internalPlatformToolNames),
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

    private Resolution resolve(String environmentId) {
      return resolver.resolve(new TurnSettings("assistant", false), environmentId);
    }

    private void connectingEnvironment(EnvironmentId environmentId, String environmentName) {
      EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
      when(connection.connectionId()).thenReturn("connection");
      when(connection.isOpen()).thenReturn(true);
      Instant now = Instant.parse("2026-08-02T00:00:00Z");
      environmentRegistry.tryBind(environmentId, environmentName, connection, now);
    }

    private void readyEnvironment(EnvironmentId environmentId, String environmentName) {
      readyEnvironment(environmentId, environmentName, List.of());
    }

    private void readyEnvironment(
        EnvironmentId environmentId, String environmentName, List<String> skills) {
      EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
      when(connection.connectionId()).thenReturn("connection");
      when(connection.isOpen()).thenReturn(true);
      Instant now = Instant.parse("2026-08-02T00:00:00Z");
      environmentRegistry.tryBind(environmentId, environmentName, connection, now);
      environmentRegistry.updateSkills(
          environmentId,
          connection,
          skills.stream()
              .map(name -> new DaemonSkillDescriptor(name, name + " description"))
              .toList(),
          now);
      environmentRegistry.markReady(environmentId, connection, now);
    }
  }
}
