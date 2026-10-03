package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agentConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

import java.util.List;
import java.util.UUID;

/**
 * 测试意图：锁定 Applier 的事务内落地顺序与关键语义——新增 Agent 以空 subagent 两阶段写入使循环引用可解、既有条目 CAS 更新、 Environment 新建用
 * import / 同名用 token CAS、settings 用计划期版本 CAS。
 */
class ConfigSyncApplierTest {

  private final AgentProviderRepository providerRepository = mock(AgentProviderRepository.class);
  private final AgentProviderService providerService = mock(AgentProviderService.class);
  private final AgentModelRepository modelRepository = mock(AgentModelRepository.class);
  private final AgentModelService modelService = mock(AgentModelService.class);
  private final SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
  private final McpServerService mcpServerService = mock(McpServerService.class);
  private final AgentDefinitionRepository definitionRepository =
      mock(AgentDefinitionRepository.class);
  private final AgentDefinitionService definitionService = mock(AgentDefinitionService.class);
  private final EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
  private final EnvironmentService environmentService = mock(EnvironmentService.class);
  private final SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);

  private final ConfigSyncApplier applier =
      new ConfigSyncApplier(
          providerRepository,
          providerService,
          modelRepository,
          modelService,
          skillCatalogService,
          mcpServerService,
          definitionRepository,
          definitionService,
          environmentRepository,
          environmentService,
          systemSettingsService);

  private static ConfigSyncPlan plan(
      List<ConfigSyncParser.ProviderSpec> providers,
      List<ConfigSyncParser.ModelSpec> models,
      List<ConfigSyncPlan.SkillImport> skills,
      List<ConfigSyncPlan.McpImport> mcps,
      List<ConfigSyncParser.EnvironmentSpec> environments,
      List<ConfigSyncParser.AgentSpec> agents,
      ConfigSyncPlan.SettingsUpdate settings) {
    return new ConfigSyncPlan(
        providers, models, skills, mcps, environments, agents, settings, List.of(), List.of());
  }

  private static ConfigSyncParser.ProviderSpec providerSpec(String name) {
    AgentProviderEditablePropertiesDTO properties = new AgentProviderEditablePropertiesDTO();
    properties.setProviderType("openai");
    properties.setBaseUrl("https://api.example.com");
    properties.setCredential("secret");
    return new ConfigSyncParser.ProviderSpec(name, properties);
  }

  @Test
  void agentTwoStageWriteResolvesCyclicSubagents() {
    AgentDefinition existingA = new AgentDefinition();
    existingA.setName("a");
    existingA.setVersion(0L);
    AgentDefinition existingB = new AgentDefinition();
    existingB.setName("b");
    existingB.setVersion(0L);
    // 阶段一每个名字返回 null（尚不存在），阶段二返回已创建的行。
    when(definitionRepository.getByName("a")).thenReturn(null, existingA);
    when(definitionRepository.getByName("b")).thenReturn(null, existingB);

    ConfigSyncParser.AgentSpec specA =
        new ConfigSyncParser.AgentSpec(
            "a", "p", "m", agentProperties(agentConfig(List.of(), List.of(), List.of("b"))));
    ConfigSyncParser.AgentSpec specB =
        new ConfigSyncParser.AgentSpec(
            "b", "p", "m", agentProperties(agentConfig(List.of(), List.of(), List.of("a"))));

    applier.apply(
        plan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(specA, specB), null));

    ArgumentCaptor<AgentDefinitionCreateDTO> createCaptor =
        ArgumentCaptor.forClass(AgentDefinitionCreateDTO.class);
    verify(definitionService, times(2)).createAgent(createCaptor.capture());
    // 阶段一创建时 subagents 必须为空，否则循环引用无目标行。
    for (AgentDefinitionCreateDTO created : createCaptor.getAllValues()) {
      assertTrue(created.getConfig().getSubagents().isEmpty());
    }

    ArgumentCaptor<AgentDefinitionUpdateDTO> updateCaptor =
        ArgumentCaptor.forClass(AgentDefinitionUpdateDTO.class);
    verify(definitionService, times(2)).updateAgent(any(), updateCaptor.capture());
    List<List<String>> subagents =
        updateCaptor.getAllValues().stream().map(dto -> dto.getConfig().getSubagents()).toList();
    assertTrue(subagents.contains(List.of("b")));
    assertTrue(subagents.contains(List.of("a")));
  }

  @Test
  void newProviderIsCreatedAndExistingProviderIsUpdated() {
    when(providerRepository.getByName("new")).thenReturn(null);
    AgentProvider existing = new AgentProvider();
    existing.setName("old");
    existing.setVersion(3L);
    when(providerRepository.getByName("old")).thenReturn(existing);

    applier.apply(
        plan(
            List.of(providerSpec("new"), providerSpec("old")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null));

    ArgumentCaptor<AgentProviderCreateDTO> create =
        ArgumentCaptor.forClass(AgentProviderCreateDTO.class);
    verify(providerService).createProvider(create.capture());
    assertEquals("new", create.getValue().getName());

    ArgumentCaptor<AgentProviderUpdateDTO> update =
        ArgumentCaptor.forClass(AgentProviderUpdateDTO.class);
    verify(providerService).updateProvider(eq("old"), update.capture());
    assertEquals("3", update.getValue().getExpectedVersion());
  }

  @Test
  void environmentCreateUsesImportAndExistingUsesTokenCas() {
    when(environmentRepository.getByName("new")).thenReturn(null);
    Environment existing = new Environment();
    existing.setId(UUID.randomUUID());
    existing.setName("old");
    existing.setVersion(2L);
    when(environmentRepository.getByName("old")).thenReturn(existing);

    applier.apply(
        plan(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new ConfigSyncParser.EnvironmentSpec("new", "token-new"),
                new ConfigSyncParser.EnvironmentSpec("old", "token-old")),
            List.of(),
            null));

    verify(environmentService).importEnvironment("new", "token-new");
    verify(environmentService)
        .updateRegistrationToken(EnvironmentId.of(existing.getId()), "token-old", "2");
  }

  @Test
  void settingsUpdateUsesPlanVersionInsteadOfReadingNewVersion() {
    SystemSettingsSectionsDTO sections =
        new SystemSettingsCodec().toSections(SystemSettings.DEFAULT);
    ConfigSyncPlan.SettingsUpdate update = new ConfigSyncPlan.SettingsUpdate(sections, "9");

    applier.apply(plan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), update));

    ArgumentCaptor<SystemSettingsUpdateDTO> captor =
        ArgumentCaptor.forClass(SystemSettingsUpdateDTO.class);
    verify(systemSettingsService).update(captor.capture());
    assertEquals("9", captor.getValue().getExpectedVersion());
    // 不应再读当前版本。
    verify(systemSettingsService, never()).get();
  }

  @Test
  void modelImportCreatesNewModel() {
    when(modelRepository.getByProviderNameAndName("p", "m")).thenReturn(null);
    AgentModelEditablePropertiesDTO properties = new AgentModelEditablePropertiesDTO();
    properties.setName("m");
    properties.setModelId("gpt");
    properties.setConfig(ConfigSyncFixtures.modelConfig());
    ConfigSyncParser.ModelSpec spec = new ConfigSyncParser.ModelSpec("p", "m", properties);

    applier.apply(plan(List.of(), List.of(spec), List.of(), List.of(), List.of(), List.of(), null));

    ArgumentCaptor<AgentModelCreateDTO> captor = ArgumentCaptor.forClass(AgentModelCreateDTO.class);
    verify(modelService).createModel(captor.capture());
    assertEquals("p", captor.getValue().getProviderName());
    assertEquals("m", captor.getValue().getName());
  }

  private static AgentDefinitionEditablePropertiesDTO agentProperties(
      AgentDefinitionConfigDTO config) {
    AgentDefinitionEditablePropertiesDTO properties = new AgentDefinitionEditablePropertiesDTO();
    properties.setModel("p/m");
    properties.setConfig(config);
    return properties;
  }
}
