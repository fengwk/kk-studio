package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agent;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agentConfig;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.environment;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpServer;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpTool;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.model;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.provider;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.settings;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.skillPackage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 测试意图：锁定一次性快照读取装配八类事实，settings 行缺失时给出资源不存在错误，插件配置取自权威目录并与其配置历史逐字段核对。 */
class ConfigSyncSnapshotReaderTest {

  private static final String PLUGIN_VALUES = "{\"promptAgentName\":\"h3-agent\"}";

  private final AgentProviderRepository providerRepository = mock(AgentProviderRepository.class);
  private final AgentModelRepository modelRepository = mock(AgentModelRepository.class);
  private final AgentDefinitionRepository definitionRepository =
      mock(AgentDefinitionRepository.class);
  private final SkillCatalogQueryService skillCatalogQueryService =
      mock(SkillCatalogQueryService.class);
  private final EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
  private final McpServerRepository mcpServerRepository = mock(McpServerRepository.class);
  private final SystemSettingsRepository systemSettingsRepository =
      mock(SystemSettingsRepository.class);
  private final PluginConfigurationRepository pluginConfigurationRepository =
      mock(PluginConfigurationRepository.class);

  private final ConfigSyncSnapshotReader reader =
      new ConfigSyncSnapshotReader(
          providerRepository,
          modelRepository,
          definitionRepository,
          skillCatalogQueryService,
          environmentRepository,
          mcpServerRepository,
          systemSettingsRepository,
          pluginConfigurationRepository);

  /** 空目录：epoch 与 manifest 指纹形状合法，但没有插件条目。 */
  private static PluginCatalogRecord emptyCatalog() {
    return new PluginCatalogRecord(
        1L, new PluginCatalogManifest(Map.of(), ConfigSyncFixtures.FINGERPRINT));
  }

  private static PluginCatalogManifest.Entry entryOf(PluginConfigurationRow row) {
    return new PluginCatalogManifest.Entry(
        row.revision(),
        row.codeVersion(),
        row.schemaFingerprint(),
        row.capabilityFingerprint(),
        row.authGeneration(),
        row.contextFingerprint());
  }

  private static PluginCatalogRecord catalogOf(long epoch, PluginConfigurationRow row) {
    Map<String, PluginCatalogManifest.Entry> plugins = new LinkedHashMap<>();
    plugins.put(row.pluginId(), entryOf(row));
    return new PluginCatalogRecord(
        epoch, new PluginCatalogManifest(plugins, ConfigSyncFixtures.FINGERPRINT));
  }

  private void stubBaseFacts() {
    when(skillCatalogQueryService.listPackages()).thenReturn(List.of());
    when(definitionRepository.listAll()).thenReturn(List.of());
    when(environmentRepository.listAll()).thenReturn(List.of());
    when(mcpServerRepository.listAllServers()).thenReturn(List.of());
    when(mcpServerRepository.listAllTools()).thenReturn(List.of());
    when(providerRepository.listAll()).thenReturn(List.of());
    when(modelRepository.listAll()).thenReturn(List.of());
  }

  @Test
  void readsAllSevenCategories() {
    var provider = provider("p");
    var model = model("p", "m");
    var skillPackage = skillPackage("pkg", "s");
    var environment = environment("env");
    var server = mcpServer("mcp", true);
    var tool = mcpTool("tool_x", "mcp");
    var settings = settings(3L);
    when(systemSettingsRepository.get()).thenReturn(settings);
    when(skillCatalogQueryService.listPackages()).thenReturn(List.of(skillPackage));
    when(providerRepository.listAll()).thenReturn(List.of(provider));
    when(modelRepository.listAll()).thenReturn(List.of(model));
    when(definitionRepository.listAll()).thenReturn(List.of());
    when(environmentRepository.listAll()).thenReturn(List.of(environment));
    when(mcpServerRepository.listAllServers()).thenReturn(List.of(server));
    when(mcpServerRepository.listAllTools()).thenReturn(List.of(tool));
    when(pluginConfigurationRepository.readCatalog()).thenReturn(emptyCatalog());

    ConfigSyncSnapshot snapshot = reader.read();

    assertSame(settings, snapshot.settings());
    assertEquals(List.of(provider), snapshot.providers());
    assertEquals(List.of(model), snapshot.models());
    assertEquals(List.of(skillPackage), snapshot.skillPackages());
    assertEquals(List.of(environment), snapshot.environments());
    assertEquals(List.of(server), snapshot.mcpServers());
    assertEquals(List.of(tool), snapshot.mcpTools());
    assertTrue(snapshot.agents().isEmpty());
    assertTrue(snapshot.plugins().isEmpty());
    assertEquals(1L, snapshot.pluginCatalogEpoch());
  }

  /** 插件配置只能来自权威目录坐标 + 逐字段一致的配置历史；这里同时锁定 epoch 随目录一起进入快照。 */
  @Test
  void readsPluginConfigurationFromAuthoritativeCatalogAndHistory() {
    stubBaseFacts();
    when(systemSettingsRepository.get()).thenReturn(settings(1L));
    PluginConfigurationRow row = ConfigSyncFixtures.pluginRow("acme.plugin", 7L, PLUGIN_VALUES);
    when(pluginConfigurationRepository.readCatalog()).thenReturn(catalogOf(9L, row));
    when(pluginConfigurationRepository.readConfiguration("acme.plugin", 7L))
        .thenReturn(Optional.of(row));

    ConfigSyncSnapshot snapshot = reader.read();

    assertEquals(List.of(row), snapshot.plugins());
    assertEquals(9L, snapshot.pluginCatalogEpoch());
  }

  /** 目录 entry 与历史行身份漂移时必须 fail closed，绝不用任意旧修订的普通值冒充当前配置。 */
  @Test
  void driftingPluginHistoryFailsClosed() {
    stubBaseFacts();
    when(systemSettingsRepository.get()).thenReturn(settings(1L));
    PluginConfigurationRow row = ConfigSyncFixtures.pluginRow("acme.plugin", 7L, PLUGIN_VALUES);
    when(pluginConfigurationRepository.readCatalog()).thenReturn(catalogOf(9L, row));
    PluginConfigurationRow drifted = ConfigSyncFixtures.pluginRow("acme.plugin", 8L, PLUGIN_VALUES);
    when(pluginConfigurationRepository.readConfiguration("acme.plugin", 7L))
        .thenReturn(Optional.of(drifted));

    assertThrows(IllegalStateException.class, () -> reader.read());
  }

  @Test
  void missingPluginCatalogIsAResourceError() {
    when(systemSettingsRepository.get()).thenReturn(settings(1L));
    when(pluginConfigurationRepository.readCatalog()).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> reader.read());
  }

  /** 测试意图：系统内置 Agent 由系统持有身份，不参与配置同步，读取快照时被排除。 */
  @Test
  void excludesBuiltinAgentsFromTheSnapshot() {
    when(systemSettingsRepository.get()).thenReturn(settings(1L));
    when(skillCatalogQueryService.listPackages()).thenReturn(List.of());
    when(pluginConfigurationRepository.readCatalog()).thenReturn(emptyCatalog());
    AgentDefinition user =
        agent("user-agent", "p", "m", agentConfig(List.of(), List.of(), List.of()));
    user.setType(AgentDefinitionType.USER);
    AgentDefinition builtin =
        agent(
            BuiltinAgentDefinitions.COMPACTION_NAME,
            null,
            null,
            agentConfig(List.of(), List.of(), List.of()));
    builtin.setType(AgentDefinitionType.BUILTIN);
    when(definitionRepository.listAll()).thenReturn(List.of(user, builtin));

    ConfigSyncSnapshot snapshot = reader.read();

    assertEquals(
        List.of("user-agent"), snapshot.agents().stream().map(AgentDefinition::getName).toList());
  }

  @Test
  void missingSettingsRowIsAResourceError() {
    when(systemSettingsRepository.get()).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> reader.read());
  }
}
