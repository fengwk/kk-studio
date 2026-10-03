package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;

import java.util.List;

/** 读取一次性配置同步快照；不取业务锁，只依赖仓库的有序 listAll。 */
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

  /** 读取全部七类配置的当前事实。 */
  public ConfigSyncSnapshot read() {
    SystemSettingsRepository.SystemSettingsRecord settings = systemSettingsRepository.get();
    if (settings == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    List<SkillPackage> skillPackages = skillCatalogQueryService.listPackages();
    return new ConfigSyncSnapshot(
        providerRepository.listAll(),
        modelRepository.listAll(),
        definitionRepository.listAll(),
        skillPackages,
        environmentRepository.listAll(),
        mcpServerRepository.listAllServers(),
        mcpServerRepository.listAllTools(),
        settings);
  }
}
