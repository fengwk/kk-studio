package fun.fengwk.kkstudio.platform.configsync;

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

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;

import java.util.List;

/** 测试意图：锁定一次性快照读取装配八类事实，settings 行缺失时给出资源不存在错误，插件配置只取已安装 Plugin 的当前行。 */
class ConfigSyncSnapshotReaderTest {

  private static final String PLUGIN_VALUES = "{\"promptAgentName\":\"h3-agent\"}";

  private final ProviderRepository providerRepository = mock(ProviderRepository.class);
  private final ModelRepository modelRepository = mock(ModelRepository.class);
  private final AgentRepository definitionRepository = mock(AgentRepository.class);
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

  @Test
  void missingSettingsRowIsAResourceError() {
    when(systemSettingsRepository.get()).thenReturn(null);
    assertThrows(DomainException.class, () -> reader.read());
  }
}
