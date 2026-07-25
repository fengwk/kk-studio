package fun.fengwk.kkstudio.core.harness.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.extension.ToolFactory;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderType;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Verifies that command-time resolution freezes descriptor data without provider I/O. */
class RuntimeConfigSnapshotResolverTest {

  private AgentDefinitionMapper definitions;
  private AgentDefinitionConfigCodec configs;
  private AgentModelRepository models;
  private AgentProviderRepository providers;
  private AgentModelRuntimeConfigParser parser;
  private HarnessExtensionHost extensions;
  private LiveEnvironmentRegistry environments;
  private ProviderFactory providerFactory;
  private RuntimeConfigSnapshotResolver resolver;

  @BeforeEach
  void setUp() {
    definitions = mock(AgentDefinitionMapper.class);
    configs = mock(AgentDefinitionConfigCodec.class);
    models = mock(AgentModelRepository.class);
    providers = mock(AgentProviderRepository.class);
    parser = mock(AgentModelRuntimeConfigParser.class);
    extensions = mock(HarnessExtensionHost.class);
    environments = mock(LiveEnvironmentRegistry.class);
    providerFactory = mock(ProviderFactory.class);
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setEnvironmentRoot(Path.of("/workspace"));
    properties.setWorkdir(Path.of("project"));
    resolver =
        new RuntimeConfigSnapshotResolver(
            definitions, configs, models, providers, parser, extensions, environments, properties);
    when(extensions.providerFactory(ProviderType.OPENAI)).thenReturn(Optional.of(providerFactory));
    when(providerFactory.promptCacheCapability())
        .thenReturn(PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT)));
  }

  @Test
  void freezesAgentModelPlatformAndEnvironmentResourcesWithoutProviderCreation() {
    AgentDefinitionDO definition = definition(1, 2, "quality");
    AgentDefinitionConfigDTO config = config("dev", List.of("read", "shell"), List.of("git"));
    when(definitions.getById(1)).thenReturn(definition);
    when(configs.decode("definition-config")).thenReturn(config);
    model(2, true, "quality");
    ToolDescriptor read = tool("read", ToolExecutionLocation.PLATFORM);
    ToolDescriptor shell = tool("shell", ToolExecutionLocation.ENVIRONMENT);
    ToolDescriptor createGoal = tool("create_goal", ToolExecutionLocation.PLATFORM);
    ToolDescriptor loadSkill = tool("load_skill", ToolExecutionLocation.PLATFORM);
    ToolFactory readFactory = factory(read);
    ToolFactory createGoalFactory = factory(createGoal);
    ToolFactory loadSkillFactory = factory(loadSkill);
    when(extensions.toolFactories())
        .thenReturn(List.of(readFactory, createGoalFactory, loadSkillFactory));
    LiveEnvironment environment = mock(LiveEnvironment.class);
    when(environment.isReady()).thenReturn(true);
    when(environment.environmentName()).thenReturn("dev");
    when(environment.tools()).thenReturn(List.of(shell));
    when(environment.skills()).thenReturn(List.of(new DaemonSkillDescriptor("git", "Git rules")));
    when(environments.find("dev")).thenReturn(Optional.of(environment));

    RuntimeConfigSnapshot snapshot = resolver.resolveAgent(1, true);

    assertEquals("agent", snapshot.agent().name());
    assertEquals("quality", snapshot.model().variant().id());
    assertEquals(
        Set.of("read", "shell", "create_goal", "load_skill"),
        Set.copyOf(snapshot.tools().stream().map(b -> b.descriptor().name()).toList()));
    assertEquals("dev", snapshot.skills().getFirst().sourceEnvironment());
    assertEquals("/workspace/project", snapshot.environment().workspaceReference());
    assertEquals(true, snapshot.policy().yoloEnabled());
    verify(providerFactory, never()).create(anyString(), anyString());
  }

  @Test
  void replacesOnlyRequestedModelOrYoloAndRejectsToolIncapableModel() {
    AgentDefinitionDO definition = definition(1, 2, "quality");
    when(definitions.getById(1)).thenReturn(definition);
    when(configs.decode("definition-config")).thenReturn(config(null, List.of("read"), List.of()));
    ToolDescriptor read = tool("read", ToolExecutionLocation.PLATFORM);
    ToolFactory readFactory = factory(read);
    when(extensions.toolFactories()).thenReturn(List.of(readFactory));
    model(2, true, "quality");
    RuntimeConfigSnapshot current = resolver.resolveAgent(1, false);
    model(3, true, "fast");

    RuntimeConfigSnapshot replaced = resolver.replaceModel(current, 3, "fast");
    RuntimeConfigSnapshot yolo = replaced.withYoloEnabled(true);
    assertEquals(current.agent(), replaced.agent());
    assertEquals(current.tools(), replaced.tools());
    assertEquals("fast", replaced.model().variant().id());
    assertEquals(replaced.model(), yolo.model());
    assertEquals(true, yolo.policy().yoloEnabled());

    model(4, false, "plain");
    assertThrows(IllegalArgumentException.class, () -> resolver.replaceModel(current, 4, "plain"));
  }

  @Test
  void rejectsOfflineUnknownAndAmbiguousResourcesAndInvalidModels() {
    when(definitions.getById(1)).thenReturn(definition(1, 2, "quality"));
    when(configs.decode("definition-config")).thenReturn(config("offline", List.of(), List.of()));
    when(environments.find("offline")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> resolver.resolveAgent(1, false));

    when(configs.decode("definition-config"))
        .thenReturn(config(null, List.of("missing"), List.of()));
    when(extensions.toolFactories()).thenReturn(List.of());
    assertThrows(IllegalArgumentException.class, () -> resolver.resolveAgent(1, false));

    when(configs.decode("definition-config"))
        .thenReturn(config(null, List.of(), List.of("missing")));
    assertThrows(IllegalArgumentException.class, () -> resolver.resolveAgent(1, false));

    RuntimeConfigSnapshot current = emptySnapshot();
    when(models.getById(2)).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class, () -> resolver.replaceModel(current, 2, "quality"));
  }

  @Test
  void rejectsMissingDefinitionsModelsProvidersAndVariants() {
    assertThrows(NullPointerException.class, () -> resolver.replaceModel(null, 1, "default"));
    assertThrows(IllegalArgumentException.class, () -> resolver.resolveAgent(0, false));
    assertThrows(IllegalArgumentException.class, () -> resolver.resolveAgent(1, false));

    AgentDefinitionDO noModel = definition(1, 2, "quality");
    noModel.setModelId(null);
    when(definitions.getById(1)).thenReturn(noModel);
    assertThrows(IllegalArgumentException.class, () -> resolver.resolveAgent(1, false));

    when(definitions.getById(1)).thenReturn(definition(1, 2, "missing"));
    when(configs.decode("definition-config")).thenReturn(config(null, List.of(), List.of()));
    when(extensions.toolFactories()).thenReturn(List.of());
    model(2, true, "quality");
    assertThrows(IllegalArgumentException.class, () -> resolver.resolveAgent(1, false));

    RuntimeConfigSnapshot current = emptySnapshot();
    assertThrows(
        IllegalArgumentException.class, () -> resolver.replaceModel(current, 0, "quality"));
    AgentModel noProvider = new AgentModel();
    noProvider.setId(8L);
    noProvider.setProviderId(null);
    when(models.getById(8L)).thenReturn(noProvider);
    assertThrows(IllegalStateException.class, () -> resolver.replaceModel(current, 8, "quality"));

    model(9, true, "quality");
    when(providers.getById(7L)).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class, () -> resolver.replaceModel(current, 9, "quality"));

    model(10, true, "quality");
    when(extensions.providerFactory(ProviderType.OPENAI)).thenReturn(Optional.empty());
    assertThrows(
        IllegalArgumentException.class, () -> resolver.replaceModel(current, 10, "quality"));

    when(extensions.providerFactory(ProviderType.OPENAI)).thenReturn(Optional.of(providerFactory));
    model(10, true, "quality");
    AgentProvider invalidProvider = new AgentProvider();
    invalidProvider.setId(7L);
    when(providers.getById(7L)).thenReturn(invalidProvider);
    assertThrows(
        IllegalArgumentException.class, () -> resolver.replaceModel(current, 10, "quality"));
    verify(providerFactory, never()).create(anyString(), anyString());
  }

  private void model(long id, boolean tools, String variant) {
    AgentModel model = new AgentModel();
    model.setId(id);
    model.setProviderId(7L);
    model.setName("model-" + id);
    model.setDescription("display-" + id);
    model.setConfigJson("model-" + id);
    AgentProvider provider = new AgentProvider();
    provider.setId(7L);
    provider.setProviderType(AgentProviderType.openai);
    when(models.getById(id)).thenReturn(model);
    when(providers.getById(7L)).thenReturn(provider);
    ModelVariant value =
        new ModelVariant(variant, null, null, null, null, null, null, List.of(), null);
    when(parser.parse("model-" + id))
        .thenReturn(
            new ParsedAgentModelConfig(
                4096,
                1024,
                EnumSet.of(ModelInputModality.TEXT),
                tools,
                false,
                List.of(value),
                variant,
                new ModelPricing(
                    "USD",
                    "in",
                    "out",
                    BigDecimal.ONE,
                    "v1",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO)));
  }

  private static AgentDefinitionDO definition(long id, long modelId, String variant) {
    AgentDefinitionDO definition = new AgentDefinitionDO();
    definition.setId(id);
    definition.setName("agent");
    definition.setSystemPrompt("prompt");
    definition.setModelId(modelId);
    definition.setVariant(variant);
    definition.setConfigJson("definition-config");
    return definition;
  }

  private static AgentDefinitionConfigDTO config(
      String environment, List<String> tools, List<String> skills) {
    AgentExecutionPolicyDTO policy = new AgentExecutionPolicyDTO();
    policy.setMaxTurns(3);
    policy.setMaxDepth(2);
    policy.setMaxDirectSubagents(1);
    policy.setMaxTotalSubagents(2);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setEnvironmentName(environment);
    config.setTools(tools);
    config.setSkills(skills);
    config.setAllowedSubagents(List.of("helper"));
    config.setExecutionPolicy(policy);
    return config;
  }

  private static ToolFactory factory(ToolDescriptor descriptor) {
    ToolFactory factory = mock(ToolFactory.class);
    when(factory.descriptor()).thenReturn(descriptor);
    return factory;
  }

  private static ToolDescriptor tool(String name, ToolExecutionLocation location) {
    ToolDescriptor descriptor = mock(ToolDescriptor.class);
    when(descriptor.name()).thenReturn(name);
    when(descriptor.version()).thenReturn("v1");
    return descriptor;
  }

  private RuntimeConfigSnapshot emptySnapshot() {
    AgentDefinitionDO definition = definition(1, 2, "quality");
    when(definitions.getById(1)).thenReturn(definition);
    when(configs.decode("definition-config")).thenReturn(config(null, List.of(), List.of()));
    model(2, true, "quality");
    when(extensions.toolFactories()).thenReturn(List.of());
    return resolver.resolveAgent(1, false);
  }
}
