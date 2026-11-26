package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.McpSpec;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.SkillSpec;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncRef;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill Package 与 MCP Server 的导入期判定。
 *
 * <p>Skill 只校验不可变 repositoryUrl 与渠道，不访问 Git（归档在写事务之前准备、发布时在事务内再次校验）；MCP 先建立可用池与已知工具名池， 再逐个 Server
 * 做发现，移除仍被引用的工具是硬冲突。
 */
final class ConfigSyncSkillAndMcpPlanning {

  private static final String RESOURCE = "config_sync";

  private final ConfigSyncMcpDiscovery mcpDiscovery;
  private final McpServerRepository mcpServerRepository;

  ConfigSyncSkillAndMcpPlanning(
      ConfigSyncMcpDiscovery mcpDiscovery, McpServerRepository mcpServerRepository) {
    this.mcpDiscovery = mcpDiscovery;
    this.mcpServerRepository = mcpServerRepository;
  }

  SkillPlan planSkillPackages(
      List<SkillSpec> candidates,
      ConfigSyncSnapshot snapshot,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedSkills = ConfigSyncSkippedNames.of(skipped, ConfigSyncKind.SKILL_PACKAGES);
    Map<String, SkillPackage> existingPackages = new LinkedHashMap<>();
    Map<String, Set<String>> availableSkills = new LinkedHashMap<>();
    for (SkillPackage pkg : snapshot.skillPackages()) {
      if (skippedSkills.contains(pkg.packageName())) {
        continue;
      }
      existingPackages.put(pkg.packageName(), pkg);
      availableSkills.put(pkg.packageName(), skillNames(pkg.skills()));
    }
    List<ConfigSyncPlan.SkillImport> skillImports = new ArrayList<>();
    for (SkillSpec spec : candidates) {
      SkillPackage existing = existingPackages.get(spec.packageName());
      if (existing != null) {
        if (!existing.isGit()) {
          throw DomainException.validation(
              RESOURCE,
              "uploaded skill package cannot be replaced by a Git import: " + spec.packageName());
        }
        if (!spec.repositoryUrl().equals(existing.gitSource().repositoryUrl())) {
          throw DomainException.validation(
              RESOURCE, "skill package repositoryUrl is immutable: " + spec.packageName());
        }
      }
      skillImports.add(
          new ConfigSyncPlan.SkillImport(
              spec.packageName(),
              spec.description(),
              spec.repositoryUrl(),
              spec.branch(),
              spec.token()));
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.SKILL_PACKAGES, spec.packageName()));
      availableSkills.put(spec.packageName(), null);
    }
    skippedSkills.forEach(availableSkills::remove);
    return new SkillPlan(skillImports, availableSkills);
  }

  /** MCP Server 先建立可用池与已知工具名池，再逐个 Server 做发现；文件条目的工具变化在此判定。 */
  McpPlan planMcpServers(
      List<McpSpec> candidates,
      ConfigSyncSnapshot snapshot,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedMcp = ConfigSyncSkippedNames.of(skipped, ConfigSyncKind.MCP_SERVERS);
    Map<String, Set<String>> availableMcpTools = new LinkedHashMap<>();
    Set<String> knownMcpToolNames = new LinkedHashSet<>();
    for (McpServer server : snapshot.mcpServers()) {
      if (skippedMcp.contains(server.name())) {
        continue;
      }
      availableMcpTools.put(server.name(), new LinkedHashSet<>());
    }
    for (McpTool tool : snapshot.mcpTools()) {
      knownMcpToolNames.add(tool.name());
      if (skippedMcp.contains(tool.serverName())) {
        continue;
      }
      availableMcpTools
          .computeIfAbsent(tool.serverName(), ignored -> new LinkedHashSet<>())
          .add(tool.name());
    }
    Set<String> referencedMcpToolNames =
        new LinkedHashSet<>(mcpServerRepository.selectReferencedToolNames());
    List<ConfigSyncPlan.McpImport> mcpImports = new ArrayList<>();
    for (McpSpec spec : candidates) {
      ConfigSyncPlan.McpImport mcpImport =
          planMcpServer(
              spec,
              referencedMcpToolNames,
              availableMcpTools,
              knownMcpToolNames,
              skipped,
              skippedMcp);
      if (mcpImport == null) {
        continue;
      }
      mcpImports.add(mcpImport);
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.MCP_SERVERS, spec.name()));
    }
    return new McpPlan(mcpImports, availableMcpTools, knownMcpToolNames);
  }

  /**
   * 单个 MCP Server：禁用时只保存配置；启用时发现失败即跳过该 Server 及其依赖，移除仍被引用的工具是硬冲突。返回 {@code null} 表示该 Server 被跳过。
   */
  private ConfigSyncPlan.McpImport planMcpServer(
      McpSpec spec,
      Set<String> referencedMcpToolNames,
      Map<String, Set<String>> availableMcpTools,
      Set<String> knownMcpToolNames,
      List<ConfigSyncSkipped> skipped,
      Set<String> skippedMcp) {
    McpConfigParser.HttpConfig config = spec.config();
    McpServer probe =
        McpServer.builder()
            .name(spec.name())
            .url(config.url())
            .headers(config.headers())
            .enabled(config.enabled())
            .timeoutMillis(config.timeoutMillis())
            .build();
    List<McpTool> discovered;
    if (!config.enabled()) {
      // 禁用 server：不发起发现，仅保存配置（enabled=false 可独立恢复）；既有工具行保持，引用这些工具的 Agent 仍按现
      // findTool semantics 可解析，不因 disabled 被误丢。
      return new ConfigSyncPlan.McpImport(
          spec.name(),
          config.url(),
          config.headers(),
          config.enabled(),
          config.timeoutMillis(),
          null);
    }
    discovered = mcpDiscovery.discoverOrNull(probe);
    if (discovered == null) {
      // 契约：发现失败不伪造 UNVERIFIED 成功，skip 当前 MCP 并让依赖它的 Agent 一并 skip。
      skipped.add(
          new ConfigSyncSkipped(
              ConfigSyncKind.MCP_SERVERS, spec.name(), "cannot discover mcp tools"));
      skippedMcp.add(spec.name());
      availableMcpTools.remove(spec.name());
      return null;
    }
    Set<String> currentNames = availableMcpTools.getOrDefault(spec.name(), Set.of());
    Set<String> nextNames = new LinkedHashSet<>();
    discovered.forEach(tool -> nextNames.add(tool.name()));
    List<String> blocked =
        currentNames.stream()
            .filter(name -> !nextNames.contains(name))
            .filter(referencedMcpToolNames::contains)
            .sorted()
            .toList();
    if (!blocked.isEmpty()) {
      // 移除仍被引用的 MCP 工具是硬冲突，不能通过部分导入规避。
      throw DomainException.validation(
          RESOURCE, "mcp server would remove referenced tools: " + spec.name());
    }
    ConfigSyncPlan.McpImport mcpImport =
        new ConfigSyncPlan.McpImport(
            spec.name(),
            config.url(),
            config.headers(),
            config.enabled(),
            config.timeoutMillis(),
            discovered);
    availableMcpTools.put(spec.name(), nextNames);
    knownMcpToolNames.addAll(nextNames);
    return mcpImport;
  }

  private static Set<String> skillNames(List<SkillManifestEntry> skills) {
    Set<String> names = new LinkedHashSet<>();
    if (skills != null) {
      skills.forEach(entry -> names.add(entry.name()));
    }
    return names;
  }

  /** Skill 导入与导入后将生效的 Skill 池（值为 {@code null} 表示文件条目，成员集合未知）。 */
  record SkillPlan(List<ConfigSyncPlan.SkillImport> imports, Map<String, Set<String>> available) {}

  /** MCP 导入、导入后将生效的工具池与已知工具名池。 */
  record McpPlan(
      List<ConfigSyncPlan.McpImport> imports,
      Map<String, Set<String>> availableTools,
      Set<String> knownToolNames) {}
}
