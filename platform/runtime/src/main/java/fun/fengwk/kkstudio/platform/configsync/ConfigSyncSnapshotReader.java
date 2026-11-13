package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.plugin.PluginRegistry;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;

import java.util.Comparator;
import java.util.List;

/** 读取一次性配置同步快照；不取业务锁，只依赖仓库的有序 listAll 与权威插件目录。 */
@AllArgsConstructor
@Component
public class ConfigSyncSnapshotReader {

  private static final String RESOURCE = "system_settings";

  private final ProviderRepository providerRepository;
  private final ModelRepository modelRepository;
  private final AgentRepository agentRepository;
  private final SkillPackageRepository skillPackageRepository;
  private final EnvironmentRepository environmentRepository;
  private final McpServerRepository mcpServerRepository;
  private final SystemSettingsRepository systemSettingsRepository;
  private final PluginConfigurationRepository pluginConfigurationRepository;
  private final PluginRegistry pluginRegistry;

  /**
   * 读取全部八类配置的当前事实。
   *
   * <p>插件配置取自已安装插件的当前配置行，只含 pluginId、修订与普通值。
   */
  public ConfigSyncSnapshot read() {
    SystemSettingsRepository.SystemSettingsRecord settings = systemSettingsRepository.get();
    if (settings == null) {
      throw DomainException.notFound(RESOURCE);
    }
    List<PluginConfigurationRow> plugins =
        pluginConfigurationRepository.findAll().stream()
            .filter(row -> pluginRegistry.find(row.pluginId()).isPresent())
            .sorted(Comparator.comparing(PluginConfigurationRow::pluginId))
            .toList();
    return new ConfigSyncSnapshot(
        providerRepository.listAll(),
        modelRepository.listAll(),
        agentRepository.listAll(),
        skillPackageRepository.listPackages(),
        environmentRepository.listAll(),
        mcpServerRepository.listAllServers(),
        mcpServerRepository.listAllTools(),
        settings,
        plugins);
  }
}
