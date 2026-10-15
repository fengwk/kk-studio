package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository.SystemSettingsRecord;

import java.util.List;
import java.util.Objects;

/**
 * 单次配置同步读取的一致快照。
 *
 * <p>导出与 inventory 都基于同一个快照构建，避免跨集合的读放大不一致；条目已按各自稳定顺序排列。
 *
 * <p>插件条目是权威 desired manifest 与对应配置历史严格核对后的历史行，只含 pluginId、修订与普通值；{@code pluginCatalogEpoch} 是该
 * manifest 的全局 epoch，仅用于导入计划的内部 CAS，绝不进入 portable 导出。
 */
public record ConfigSyncSnapshot(
    List<AgentProvider> providers,
    List<AgentModel> models,
    List<AgentDefinition> agents,
    List<SkillPackage> skillPackages,
    List<Environment> environments,
    List<McpServer> mcpServers,
    List<McpTool> mcpTools,
    SystemSettingsRecord settings,
    long pluginCatalogEpoch,
    List<PluginConfigurationRow> plugins) {

  public ConfigSyncSnapshot {
    providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    models = List.copyOf(Objects.requireNonNull(models, "models"));
    agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
    skillPackages = List.copyOf(Objects.requireNonNull(skillPackages, "skillPackages"));
    environments = List.copyOf(Objects.requireNonNull(environments, "environments"));
    mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
    mcpTools = List.copyOf(Objects.requireNonNull(mcpTools, "mcpTools"));
    Objects.requireNonNull(settings, "settings");
    if (pluginCatalogEpoch < 0) {
      throw new IllegalArgumentException("pluginCatalogEpoch must not be negative");
    }
    plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
  }
}
