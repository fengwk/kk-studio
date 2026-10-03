package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncSkipped;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;

import java.util.List;
import java.util.Map;

/**
 * 导入计划：准备阶段（事务外 Git / MCP 发现与 settings 合并）完成后的可写入事实。
 *
 * <p>{@code imported} 是最终会实际写入的引用，{@code skipped} 是被明确跳过的条目；两者都已在计划阶段定性。
 */
public record ConfigSyncPlan(
    List<ConfigSyncParser.ProviderSpec> providers,
    List<ConfigSyncParser.ModelSpec> models,
    List<SkillImport> skillPackages,
    List<McpImport> mcpServers,
    List<ConfigSyncParser.EnvironmentSpec> environments,
    List<ConfigSyncParser.AgentSpec> agents,
    SettingsUpdate settings,
    List<ConfigSyncRef> imported,
    List<ConfigSyncSkipped> skipped) {

  public ConfigSyncPlan {
    providers = List.copyOf(providers);
    models = List.copyOf(models);
    skillPackages = List.copyOf(skillPackages);
    mcpServers = List.copyOf(mcpServers);
    environments = List.copyOf(environments);
    agents = List.copyOf(agents);
    imported = List.copyOf(imported);
    skipped = List.copyOf(skipped);
  }

  /** 已按 exact commit 扫描完成的 Skill Package 导入。 */
  public record SkillImport(
      String packageName,
      String description,
      String repositoryUrl,
      String branch,
      String currentCommit,
      List<SkillManifestEntry> manifest) {}

  /** 已按事务外发现准备好的 MCP Server 导入；{@code discoveredTools} 为 null 表示发现失败。 */
  public record McpImport(
      String name,
      String url,
      Map<String, String> headers,
      Boolean enabled,
      Long timeoutMillis,
      List<McpTool> discoveredTools) {}

  /** 已合并并校验的 settings 更新；版本在写事务内读取。 */
  public record SettingsUpdate(SystemSettingsSectionsDTO sections) {}
}
