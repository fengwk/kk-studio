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
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
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
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 测试意图：锁定 Applier 的事务内落地顺序与关键语义——新增 Agent 以空 subagent 两阶段写入使循环引用可解、既有条目 CAS 更新、 Environment 新建用
 * import / 同名用 token CAS、settings 用计划期版本 CAS。
 */
class ConfigSyncApplierTest {

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
  void providersAreImportedViaExplicitUpsertCommand() {
    ConfigSyncParser.ProviderSpec created = providerSpec("new");
    ConfigSyncParser.ProviderSpec existing = providerSpec("old");

    applier.apply(
        plan(
            List.of(created, existing),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null));

    // 导入使用显式 upsert 命令恢复文件事实，不再区分 create/update，也不再读取 repository 判断存在性。
    verify(providerService).importProvider("new", created.properties());
    verify(providerService).importProvider("old", existing.properties());
    verify(providerService, never()).createProvider(any());
    verify(providerService, never()).updateProvider(any(), any());
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
    sections.getNetwork().setProxyUrl("http://127.0.0.1:9");
    sections.getNetwork().setNoProxyHosts("localhost,127.0.0.1");
    ConfigSyncPlan.SettingsUpdate update = new ConfigSyncPlan.SettingsUpdate(sections, "9");

    applier.apply(plan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), update));

    ArgumentCaptor<SystemSettingsUpdateDTO> captor =
        ArgumentCaptor.forClass(SystemSettingsUpdateDTO.class);
    verify(systemSettingsService).update(captor.capture());
    assertEquals("9", captor.getValue().getExpectedVersion());
    // 完整聚合必须携带网络设置，避免省略字段导致校验失败或丢失代理事实。
    assertEquals(sections.getNetwork(), captor.getValue().getNetwork());
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

  /** 意图：Skill 与 MCP 导入按计划直接委托，携带 exact commit manifest 与已发现的工具行。 */
  @Test
  void skillAndMcpImportsAreDelegatedWithPreparedPayloads() {
    SkillManifestEntry manifest = new SkillManifestEntry("s", "desc");
    ConfigSyncPlan.SkillImport skill =
        new ConfigSyncPlan.SkillImport(
            "pkg", "d", "https://example.com/pkg.git", "main", "a".repeat(40), List.of(manifest));
    McpTool tool = ConfigSyncFixtures.mcpTool("tool_x", "mcp");
    ConfigSyncPlan.McpImport mcp =
        new ConfigSyncPlan.McpImport(
            "mcp",
            "https://mcp.example.com/mcp",
            Map.of("Authorization", "secret"),
            Boolean.TRUE,
            30_000L,
            List.of(tool));

    applier.apply(
        plan(List.of(), List.of(), List.of(skill), List.of(mcp), List.of(), List.of(), null));

    verify(skillCatalogService)
        .importPackage(
            "pkg", "d", "https://example.com/pkg.git", "main", "a".repeat(40), List.of(manifest));
    verify(mcpServerService)
        .importServer(
            "mcp",
            "https://mcp.example.com/mcp",
            Map.of("Authorization", "secret"),
            Boolean.TRUE,
            30_000L,
            List.of(tool));
  }

  /** 意图：既有 Model 走 CAS 更新并携带既有版本，绝不重复创建。 */
  @Test
  void existingModelIsUpdatedWithExistingVersionCas() {
    AgentModel existing = new AgentModel();
    existing.setProviderName("p");
    existing.setName("m");
    existing.setVersion(4L);
    when(modelRepository.getByProviderNameAndName("p", "m")).thenReturn(existing);
    AgentModelEditablePropertiesDTO properties = new AgentModelEditablePropertiesDTO();
    properties.setName("m");
    properties.setModelId("gpt");
    properties.setConfig(ConfigSyncFixtures.modelConfig());
    ConfigSyncParser.ModelSpec spec = new ConfigSyncParser.ModelSpec("p", "m", properties);

    applier.apply(plan(List.of(), List.of(spec), List.of(), List.of(), List.of(), List.of(), null));

    ArgumentCaptor<AgentModelUpdateDTO> captor = ArgumentCaptor.forClass(AgentModelUpdateDTO.class);
    verify(modelService).updateModel(eq("p"), eq("m"), captor.capture());
    assertEquals("4", captor.getValue().getExpectedVersion());
    verify(modelService, never()).createModel(any());
  }

  /** 意图：已存在的 Agent 不再创建，直接进入第二阶段用既有版本 CAS 更新完整配置。 */
  @Test
  void existingAgentIsUpdatedWithoutCreate() {
    AgentDefinition existing = new AgentDefinition();
    existing.setName("a");
    existing.setVersion(7L);
    when(definitionRepository.getByName("a")).thenReturn(existing);
    ConfigSyncParser.AgentSpec spec =
        new ConfigSyncParser.AgentSpec(
            "a", "p", "m", agentProperties(agentConfig(List.of(), List.of(), List.of())));

    applier.apply(plan(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(spec), null));

    verify(definitionService, never()).createAgent(any());
    ArgumentCaptor<AgentDefinitionUpdateDTO> captor =
        ArgumentCaptor.forClass(AgentDefinitionUpdateDTO.class);
    verify(definitionService).updateAgent(eq("a"), captor.capture());
    assertEquals("7", captor.getValue().getExpectedVersion());
  }
}
