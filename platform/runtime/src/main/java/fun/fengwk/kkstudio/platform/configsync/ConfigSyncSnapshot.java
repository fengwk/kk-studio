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
import java.util.Set;

/**
 * 单次配置同步读取的一致快照。
 *
 * <p>导出与 inventory 都基于同一个快照构建，避免跨集合的读放大不一致；条目已按各自稳定顺序排列。
 *
 * <p>插件条目是已安装插件的当前配置行，只含 pluginId、修订与普通值；修订只用于导入事务内的 CAS，绝不进入 portable 导出。
 *
 * <p>{@code builtinAgentNames} 是同一批读取里按系统持有类型识别的内置 Agent 名称：它们由系统初始化并持有身份，绝不导出、绝不出现在
 * inventory、也不允许被导入覆盖，但作为「已存在」满足 Agent 引用依赖（插件声明的 Agent 与 Agent 的 subagent）。
 */
public record ConfigSyncSnapshot(
    List<AgentProvider> providers,
    List<AgentModel> models,
    List<AgentDefinition> agents,
    Set<String> builtinAgentNames,
    List<SkillPackage> skillPackages,
    List<Environment> environments,
    List<McpServer> mcpServers,
    List<McpTool> mcpTools,
    SystemSettingsRecord settings,
    List<PluginConfigurationRow> plugins) {

  public ConfigSyncSnapshot {
    providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    models = List.copyOf(Objects.requireNonNull(models, "models"));
    agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
    builtinAgentNames = Set.copyOf(Objects.requireNonNull(builtinAgentNames, "builtinAgentNames"));
    skillPackages = List.copyOf(Objects.requireNonNull(skillPackages, "skillPackages"));
    environments = List.copyOf(Objects.requireNonNull(environments, "environments"));
    mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
    mcpTools = List.copyOf(Objects.requireNonNull(mcpTools, "mcpTools"));
    Objects.requireNonNull(settings, "settings");
    plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
  }
}
