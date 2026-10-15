package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agentConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

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
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationCommitService;
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

  private static final String DEPENDENT_PLUGIN_ID = "dep.plugin";

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
  private final PluginConfigurationCommitService pluginCommitService =
      mock(PluginConfigurationCommitService.class);

  /** 计划里出现的插件都必须是安装目录中的插件：事务内重检直接复用插件自身的依赖声明。 */
  private final PluginAgentDependencies pluginAgentDependencies =
      ConfigSyncFixtures.agentDependencies(
          definitionRepository,
          new ConfigSyncFixtures.AgentDependencyPlugin("a.plugin"),
          new ConfigSyncFixtures.AgentDependencyPlugin("b.plugin"),
          new ConfigSyncFixtures.AgentDependencyPlugin(DEPENDENT_PLUGIN_ID),
          new ConfigSyncFixtures.FailingAgentDeclarationPlugin("broken.plugin"));

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
          systemSettingsService,
          pluginCommitService,
          pluginAgentDependencies);

  private static ConfigSyncPlan plan(
      List<ConfigSyncParser.ProviderSpec> providers,
      List<ConfigSyncParser.ModelSpec> models,
      List<ConfigSyncPlan.SkillImport> skills,
      List<ConfigSyncPlan.McpImport> mcps,
      List<ConfigSyncParser.EnvironmentSpec> environments,
      List<ConfigSyncParser.AgentSpec> agents,
      ConfigSyncPlan.SettingsUpdate settings) {
    return planWithPlugins(
        providers, models, skills, mcps, environments, agents, List.of(), 0L, settings);
  }

  private static ConfigSyncPlan planWithPlugins(
      List<ConfigSyncParser.ProviderSpec> providers,
      List<ConfigSyncParser.ModelSpec> models,
      List<ConfigSyncPlan.SkillImport> skills,
      List<ConfigSyncPlan.McpImport> mcps,
      List<ConfigSyncParser.EnvironmentSpec> environments,
      List<ConfigSyncParser.AgentSpec> agents,
      List<ConfigSyncPlan.PluginImport> plugins,
      long pluginCatalogEpoch,
      ConfigSyncPlan.SettingsUpdate settings) {
    return new ConfigSyncPlan(
        providers,
        models,
        skills,
        mcps,
        environments,
        agents,
        plugins,
        pluginCatalogEpoch,
        settings,
        List.of(),
        List.of());
  }

  /** epoch 与 plugins 直接取形状合法的记录：Applier 只读返回的 epoch，不关心 manifest 内容。 */
  private static PluginCatalogRecord catalogAt(long epoch) {
    return new PluginCatalogRecord(
        epoch, new PluginCatalogManifest(Map.of(), ConfigSyncFixtures.FINGERPRINT));
  }

  /** 合成依赖插件：普通值里的 Agent 名称字段由测试显式给出。 */
  private static AgentDefinition existingAgent(String name) {
    AgentDefinition agent = new AgentDefinition();
    agent.setName(name);
    agent.setVersion(0L);
    return agent;
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
                new ConfigSyncParser.EnvironmentSpec("new", "token-new", null),
                new ConfigSyncParser.EnvironmentSpec("old", "token-old", null)),
            List.of(),
            null));

    verify(environmentService).importEnvironment("new", "token-new", null);
    verify(environmentService)
        .updateImportedEnvironment(EnvironmentId.of(existing.getId()), "token-old", null, "2");
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

  /** 意图：插件按计划顺序经唯一提交入口写入，第一项比较计划 epoch，后续用前一成功的新 epoch，各自仍携带计划期修订。 */
  @Test
  void pluginsAreAppliedInOrderWithChainedEpochs() {
    String values = "{\"promptAgentName\":\"h3\"}";
    when(definitionRepository.getByNameForUpdate("h3")).thenReturn(existingAgent("h3"));
    when(pluginCommitService.configure("a.plugin", 10L, 1L, values, Map.of()))
        .thenReturn(catalogAt(11L));
    when(pluginCommitService.configure("b.plugin", 11L, 2L, values, Map.of()))
        .thenReturn(catalogAt(12L));

    applier.apply(
        planWithPlugins(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new ConfigSyncPlan.PluginImport("a.plugin", 1L, values),
                new ConfigSyncPlan.PluginImport("b.plugin", 2L, values)),
            10L,
            null));

    InOrder order = Mockito.inOrder(pluginCommitService);
    order.verify(pluginCommitService).configure("a.plugin", 10L, 1L, values, Map.of());
    order.verify(pluginCommitService).configure("b.plugin", 11L, 2L, values, Map.of());
  }

  /** 意图：插件提交冲突必须原样上抛（不吞异常），由外层事务整体回滚；不携带任何秘密补丁。 */
  @Test
  void pluginCommitFailurePropagatesWithoutSecretPatch() {
    when(pluginCommitService.configure(anyString(), anyLong(), anyLong(), anyString(), any()))
        .thenThrow(new AiVersionConflictException("plugin", "epoch=10", "epoch=13"));

    assertThrows(
        AiVersionConflictException.class,
        () ->
            applier.apply(
                planWithPlugins(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(new ConfigSyncPlan.PluginImport("a.plugin", 1L, "{}")),
                    10L,
                    null)));

    verify(pluginCommitService).configure("a.plugin", 10L, 1L, "{}", Map.of());
  }

  /**
   * 意图：计划到写入之间被删除的 Agent 不会改变插件目录 epoch，必须靠事务内的依赖重检发现：缺失即固定安全校验失败， 且在任何 plugin configure
   * 之前抛出（外层事务因此整体回滚）。
   */
  @Test
  void pluginAgentDependenciesAreRecheckedBeforeAnyPluginWrite() {
    String values = "{\"promptAgentName\":\"dep-agent\"}";
    when(definitionRepository.getByNameForUpdate("dep-agent")).thenReturn(null);

    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () ->
                applier.apply(
                    planWithPlugins(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new ConfigSyncPlan.PluginImport(DEPENDENT_PLUGIN_ID, 1L, values)),
                        10L,
                        null)));

    assertTrue(error.getMessage().contains("missing agent"));
    verify(pluginCommitService, never()).configure(anyString(), anyLong(), anyLong(), any(), any());
  }

  /** 意图：依赖池里的既有（含系统内置）Agent 满足插件依赖；重检按稳定顺序去重，每个名称只锁一次。 */
  @Test
  void pluginAgentDependenciesAcceptExistingAgentsInStableOrder() {
    when(definitionRepository.getByNameForUpdate(anyString()))
        .thenAnswer(invocation -> existingAgent(invocation.getArgument(0)));
    when(pluginCommitService.configure(anyString(), anyLong(), anyLong(), anyString(), any()))
        .thenReturn(catalogAt(11L));
    String values = "{\"promptAgentName\":\"%s\"}";

    applier.apply(
        planWithPlugins(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new ConfigSyncPlan.PluginImport(
                    DEPENDENT_PLUGIN_ID, 1L, String.format(values, "zeta")),
                new ConfigSyncPlan.PluginImport("a.plugin", 1L, String.format(values, "alpha")),
                new ConfigSyncPlan.PluginImport("b.plugin", 2L, String.format(values, "alpha"))),
            10L,
            null));

    InOrder order = Mockito.inOrder(definitionRepository);
    order.verify(definitionRepository).getByNameForUpdate("alpha");
    order.verify(definitionRepository).getByNameForUpdate("zeta");
    verify(definitionRepository, times(2)).getByNameForUpdate(anyString());
  }

  /** 意图：重检发生在 Agent 两阶段写入之后、任何 plugin configure 之前。 */
  @Test
  void pluginAgentDependenciesAreRecheckedAfterAgentsAndBeforePlugins() {
    AgentDefinition existing = existingAgent("dep-agent");
    when(definitionRepository.getByName("dep-agent")).thenReturn(existing);
    when(definitionRepository.getByNameForUpdate("dep-agent")).thenReturn(existing);
    when(pluginCommitService.configure(
            DEPENDENT_PLUGIN_ID, 10L, 1L, "{\"promptAgentName\":\"dep-agent\"}", Map.of()))
        .thenReturn(catalogAt(11L));

    applier.apply(
        planWithPlugins(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new ConfigSyncParser.AgentSpec(
                    "dep-agent",
                    "p",
                    "m",
                    agentProperties(agentConfig(List.of(), List.of(), List.of())))),
            List.of(
                new ConfigSyncPlan.PluginImport(
                    DEPENDENT_PLUGIN_ID, 1L, "{\"promptAgentName\":\"dep-agent\"}")),
            10L,
            null));

    InOrder order = Mockito.inOrder(definitionService, definitionRepository, pluginCommitService);
    order.verify(definitionService).updateAgent(eq("dep-agent"), any());
    order.verify(definitionRepository).getByNameForUpdate("dep-agent");
    order
        .verify(pluginCommitService)
        .configure(DEPENDENT_PLUGIN_ID, 10L, 1L, "{\"promptAgentName\":\"dep-agent\"}", Map.of());
  }

  /** 意图：插件拒绝自身普通配置（已知输入边界）收敛为固定去敏校验失败，不回显配置值、不串联 cause。 */
  @Test
  void pluginDeclarationInputErrorBecomesSafeValidationFailure() {
    String values = "{\"promptAgentName\":\"!secret-marker\"}";

    AiValidationException error =
        assertThrows(
            AiValidationException.class,
            () ->
                applier.apply(
                    planWithPlugins(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new ConfigSyncPlan.PluginImport(DEPENDENT_PLUGIN_ID, 1L, values)),
                        10L,
                        null)));

    assertEquals("plugin configuration is invalid: " + DEPENDENT_PLUGIN_ID, error.getMessage());
    assertTrue(error.getCause() == null, "the input boundary must not chain the plugin cause");
    verify(pluginCommitService, never()).configure(anyString(), anyLong(), anyLong(), any(), any());
  }

  /** 意图：插件声明解析抛出的未知/致命错误绝不伪装成校验失败或 skip，必须原样上抛。 */
  @Test
  void unexpectedPluginDeclarationErrorPropagates() {
    assertThrows(
        IllegalStateException.class,
        () ->
            applier.apply(
                planWithPlugins(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(new ConfigSyncPlan.PluginImport("broken.plugin", 1L, "{}")),
                    10L,
                    null)));
    verify(pluginCommitService, never()).configure(anyString(), anyLong(), anyLong(), any(), any());
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
            "pkg",
            "d",
            "https://example.com/pkg.git",
            "main",
            "a".repeat(40),
            List.of(manifest),
            null);
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
            "pkg",
            "d",
            "https://example.com/pkg.git",
            "main",
            "a".repeat(40),
            List.of(manifest),
            null);
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
