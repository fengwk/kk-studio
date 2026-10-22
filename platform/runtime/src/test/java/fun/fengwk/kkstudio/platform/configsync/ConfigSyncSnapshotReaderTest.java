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

import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.util.List;
import java.util.Set;

/** 测试意图：锁定一次性快照读取装配八类事实，settings 行缺失时给出资源不存在错误，插件配置只取已安装 Plugin 的当前行。 */
class ConfigSyncSnapshotReaderTest {

  private static final String PLUGIN_VALUES = "{\"promptAgentName\":\"h3-agent\"}";

  private final AgentProviderRepository providerRepository = mock(AgentProviderRepository.class);
  private final AgentModelRepository modelRepository = mock(AgentModelRepository.class);
  private final AgentDefinitionRepository definitionRepository =
      mock(AgentDefinitionRepository.class);
  private final SkillPackageRepository skillPackageRepository = mock(SkillPackageRepository.class);
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
          skillPackageRepository,
          environmentRepository,
          mcpServerRepository,
          systemSettingsRepository,
          pluginConfigurationRepository,
          ConfigSyncFixtures.registry(
              new ConfigSyncFixtures.AgentDependencyPlugin("acme.plugin"),
              new ConfigSyncFixtures.AgentDependencyPlugin("beta.plugin")));

  private void stubBaseFacts() {
    when(skillPackageRepository.listPackages()).thenReturn(List.of());
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
    when(skillPackageRepository.listPackages()).thenReturn(List.of(skillPackage));
    when(providerRepository.listAll()).thenReturn(List.of(provider));
    when(modelRepository.listAll()).thenReturn(List.of(model));
    when(definitionRepository.listAll()).thenReturn(List.of());
    when(environmentRepository.listAll()).thenReturn(List.of(environment));
    when(mcpServerRepository.listAllServers()).thenReturn(List.of(server));
    when(mcpServerRepository.listAllTools()).thenReturn(List.of(tool));
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
  }

  /** 插件配置只取已安装 Plugin 的当前行并按 pluginId 排序；已卸载 Plugin 的残留行被忽略。 */
  @Test
  void readsCurrentConfigurationOfInstalledPluginsOnly() {
    stubBaseFacts();
    when(systemSettingsRepository.get()).thenReturn(settings(1L));
    PluginConfigurationRow beta = ConfigSyncFixtures.pluginRow("beta.plugin", 2L, PLUGIN_VALUES);
    PluginConfigurationRow acme = ConfigSyncFixtures.pluginRow("acme.plugin", 7L, PLUGIN_VALUES);
    when(pluginConfigurationRepository.findAll())
        .thenReturn(List.of(beta, ConfigSyncFixtures.pluginRow("removed.plugin", 1L, "{}"), acme));

    ConfigSyncSnapshot snapshot = reader.read();

    assertEquals(List.of(acme, beta), snapshot.plugins());
  }

  /** 测试意图：系统内置 Agent 由系统持有身份，不参与配置同步，读取快照时被排除；其名称作为「已由系统满足」的依赖单独带出。 */
  @Test
  void excludesBuiltinAgentsFromTheSnapshot() {
    when(systemSettingsRepository.get()).thenReturn(settings(1L));
    when(skillPackageRepository.listPackages()).thenReturn(List.of());
    AgentDefinition user =
        agent("user-agent", "m", agentConfig(List.of(), List.of(), List.of())).toBuilder()
            .type(AgentDefinitionType.USER)
            .build();
    AgentDefinition builtin =
        agent(
                BuiltinAgentDefinitions.COMPACTION_NAME,
                null,
                agentConfig(List.of(), List.of(), List.of()))
            .toBuilder()
            .type(AgentDefinitionType.BUILTIN)
            .build();
    when(definitionRepository.listAll()).thenReturn(List.of(user, builtin));

    ConfigSyncSnapshot snapshot = reader.read();

    assertEquals(
        List.of("user-agent"), snapshot.agents().stream().map(AgentDefinition::name).toList());
    assertEquals(Set.of(BuiltinAgentDefinitions.COMPACTION_NAME), snapshot.builtinAgentNames());
  }

  @Test
  void missingSettingsRowIsAResourceError() {
    when(systemSettingsRepository.get()).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> reader.read());
  }
}
