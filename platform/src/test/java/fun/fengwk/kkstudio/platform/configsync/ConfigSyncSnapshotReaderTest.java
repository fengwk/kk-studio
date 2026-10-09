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

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.util.List;

/** 测试意图：锁定一次性快照读取装配七类事实，settings 行缺失时给出资源不存在错误。 */
class ConfigSyncSnapshotReaderTest {

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

  private final ConfigSyncSnapshotReader reader =
      new ConfigSyncSnapshotReader(
          providerRepository,
          modelRepository,
          definitionRepository,
          skillCatalogQueryService,
          environmentRepository,
          mcpServerRepository,
          systemSettingsRepository);

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

    ConfigSyncSnapshot snapshot = reader.read();

    assertSame(settings, snapshot.settings());
    assertEquals(List.of(provider), snapshot.providers());
    assertEquals(List.of(model), snapshot.models());
    assertEquals(List.of(skillPackage), snapshot.skillPackages());
    assertEquals(List.of(environment), snapshot.environments());
    assertEquals(List.of(server), snapshot.mcpServers());
    assertEquals(List.of(tool), snapshot.mcpTools());
    assertTrue(snapshot.agents().isEmpty());
  }

  /** 测试意图：系统内置 Agent 由系统持有身份，不参与配置同步，读取快照时被排除。 */
  @Test
  void excludesBuiltinAgentsFromTheSnapshot() {
    when(systemSettingsRepository.get()).thenReturn(settings(1L));
    when(skillCatalogQueryService.listPackages()).thenReturn(List.of());
    AgentDefinition user =
        agent("user-agent", "p", "m", agentConfig(List.of(), List.of(), List.of()));
    user.setType(AgentDefinitionType.USER);
    AgentDefinition builtin =
        agent("compaction", null, null, agentConfig(List.of(), List.of(), List.of()));
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
