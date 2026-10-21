package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 读取一次性配置同步快照；不取业务锁，只依赖仓库的有序 listAll 与权威插件目录。 */
@AllArgsConstructor
@Component
public class ConfigSyncSnapshotReader {

  private static final String RESOURCE = "system_settings";

  private final AgentProviderRepository providerRepository;
  private final AgentModelRepository modelRepository;
  private final AgentDefinitionRepository definitionRepository;
  private final SkillCatalogQueryService skillCatalogQueryService;
  private final EnvironmentRepository environmentRepository;
  private final McpServerRepository mcpServerRepository;
  private final SystemSettingsRepository systemSettingsRepository;
  private final PluginConfigurationRepository pluginConfigurationRepository;
  private final StudioPluginRegistry pluginRegistry;

  /**
   * 读取全部八类配置的当前事实。
   *
   * <p>系统内置 Agent 由系统初始化并持有身份，不参与配置同步：从快照条目中排除，既不导出也不参与 inventory 选择；其名称在同一批读取里
   * 按系统持有类型单独带出，只作为「依赖已由系统满足」的判断依据；导入侧对保留名硬拒绝。
   *
   * <p>插件配置取自已安装插件的当前配置行，只含 pluginId、修订与普通值。
   */
  public ConfigSyncSnapshot read() {
    SystemSettingsRepository.SystemSettingsRecord settings = systemSettingsRepository.get();
    if (settings == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    List<PluginConfigurationRow> plugins =
        pluginConfigurationRepository.findAll().stream()
            .filter(row -> pluginRegistry.find(row.pluginId()).isPresent())
            .sorted(Comparator.comparing(PluginConfigurationRow::pluginId))
            .toList();
    List<SkillPackage> skillPackages = skillCatalogQueryService.listPackages();
    // 同一次 listAll 里按持有者类型分流：内置 Agent 只贡献名称，绝不进入可导出条目。
    List<AgentDefinition> agents = new ArrayList<>();
    Set<String> builtinAgentNames = new LinkedHashSet<>();
    for (AgentDefinition agent : definitionRepository.listAll()) {
      if (agent.getType() == AgentDefinitionType.BUILTIN) {
        builtinAgentNames.add(agent.getName());
      } else {
        agents.add(agent);
      }
    }
    return new ConfigSyncSnapshot(
        providerRepository.listAll(),
        modelRepository.listAll(),
        agents,
        builtinAgentNames,
        skillPackages,
        environmentRepository.listAll(),
        mcpServerRepository.listAllServers(),
        mcpServerRepository.listAllTools(),
        settings,
        plugins);
  }
}
