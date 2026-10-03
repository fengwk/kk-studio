package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncSkipped;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入计划：在事务外完成校验、依赖判定与外部准备（Git exact commit、MCP 发现、settings 合并）。
 *
 * <p>只做确定性决策，不写数据库；未知/不支持项与依赖缺失在这里变为明确的 skip，真正写入交给事务内
 * {@link ConfigSyncApplier}。
 */
@AllArgsConstructor
@Component
public final class ConfigSyncPlanner {

  private static final String RESOURCE = "config_sync";
  private static final String SETTINGS_NAME = "settings";

  private final ConfigSyncSnapshotReader snapshotReader;
  private final ConfigSyncYaml yaml;
  private final ConfigSyncMcpDiscovery mcpDiscovery;
  private final SkillGitCache skillGitCache;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final McpServerRepository mcpServerRepository;
  private final SystemSettingsCodec systemSettingsCodec;
  private final RuntimeToolCatalog toolCatalog;

  /** 依 ParsedDocument 构建可写入计划。 */
  public ConfigSyncPlan plan(ConfigSyncParser.ParsedDocument document) {
    ConfigSyncSnapshot snapshot = snapshotReader.read();
    List<ConfigSyncSkipped> skipped = new ArrayList<>(document.skipped());
    List<ConfigSyncRef> imported = new ArrayList<>();

    Set<String> providerNames = new LinkedHashSet<>();
    snapshot.providers().forEach(provider -> providerNames.add(provider.getName()));
    for (ConfigSyncParser.ProviderSpec spec : document.providers()) {
      providerNames.add(spec.name());
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.PROVIDERS, spec.name()));
    }

    Set<String> existingModelKeys = new LinkedHashSet<>();
    snapshot.models()
        .forEach(model -> existingModelKeys.add(ConfigSyncRefs.modelName(model.getProviderName(), model.getName())));
    Set<String> availableModelKeys = new LinkedHashSet<>(existingModelKeys);
    List<ConfigSyncParser.ModelSpec> models = new ArrayList<>();
    for (ConfigSyncParser.ModelSpec spec : document.models()) {
      if (!providerNames.contains(spec.providerName())) {
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.MODELS.wireValue(),
                ConfigSyncRefs.modelName(spec.providerName(), spec.name()),
                "missing provider: " + spec.providerName()));
        continue;
      }
      String key = ConfigSyncRefs.modelName(spec.providerName(), spec.name());
      availableModelKeys.add(key);
      models.add(spec);
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.MODELS, key));
    }

    Map<String, SkillPackage> existingPackages = new LinkedHashMap<>();
    Map<String, Set<String>> availableSkills = new LinkedHashMap<>();
    snapshot.skillPackages()
        .forEach(
            pkg -> {
              existingPackages.put(pkg.getPackageName(), pkg);
              availableSkills.put(pkg.getPackageName(), skillNames(pkg.getSkills()));
            });
    List<ConfigSyncPlan.SkillImport> skillImports = new ArrayList<>();
    for (ConfigSyncParser.SkillSpec spec : document.skillPackages()) {
      SkillPackage existing = existingPackages.get(spec.packageName());
      if (existing != null && !existing.getRepositoryUrl().equals(spec.repositoryUrl())) {
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.SKILL_PACKAGES.wireValue(),
                spec.packageName(),
                "repositoryUrl is immutable for an existing package"));
        continue;
      }
      List<SkillManifestEntry> manifest;
      try {
        skillGitCache.ensureCommit(spec.packageName(), spec.repositoryUrl(), spec.currentCommit());
        manifest = skillGitCache.scanManifest(spec.packageName(), spec.currentCommit());
      } catch (SkillGitException error) {
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.SKILL_PACKAGES.wireValue(),
                spec.packageName(),
                "cannot restore exact commit"));
        continue;
      }
      if (existing != null) {
        Set<String> published = new LinkedHashSet<>(skillNames(manifest));
        List<String> referenced =
            skillNames(existing.getSkills()).stream()
                .filter(skill -> !published.contains(skill))
                .filter(skill -> agentDefinitionRepository.existsReferencingSkill(spec.packageName(), skill))
                .sorted()
                .toList();
        if (!referenced.isEmpty()) {
          skipped.add(
              new ConfigSyncSkipped(
                  ConfigSyncKind.SKILL_PACKAGES.wireValue(),
                  spec.packageName(),
                  "skills are still referenced by agents: " + String.join(", ", referenced)));
          continue;
        }
      }
      skillImports.add(
          new ConfigSyncPlan.SkillImport(
              spec.packageName(),
              spec.description(),
              spec.repositoryUrl(),
              spec.branch(),
              spec.currentCommit(),
              manifest));
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.SKILL_PACKAGES, spec.packageName()));
      availableSkills.put(spec.packageName(), skillNames(manifest));
    }

    Map<String, Set<String>> availableMcpTools = new LinkedHashMap<>();
    Set<String> knownMcpToolNames = new LinkedHashSet<>();
    snapshot.mcpServers()
        .forEach(server -> availableMcpTools.put(server.getName(), new LinkedHashSet<>()));
    for (McpTool tool : snapshot.mcpTools()) {
      availableMcpTools.computeIfAbsent(tool.getServerName(), ignored -> new LinkedHashSet<>()).add(tool.getName());
      knownMcpToolNames.add(tool.getName());
    }
    Set<String> referencedMcpToolNames = new LinkedHashSet<>(mcpServerRepository.selectReferencedToolNames());
    List<ConfigSyncPlan.McpImport> mcpImports = new ArrayList<>();
    for (ConfigSyncParser.McpSpec spec : document.mcpServers()) {
      McpServerMutationValidator.HttpConfig config;
      try {
        config =
            McpServerMutationValidator.normalizeHttpConfig(
                spec.url(), spec.headers(), spec.enabled(), spec.timeoutMillis());
      } catch (AiValidationException error) {
        throw new AiValidationException(
            RESOURCE, "mcp server " + spec.name() + " has an invalid url, headers or timeout");
      }
      McpServer probe = new McpServer();
      probe.setName(spec.name());
      probe.setUrl(config.url());
      probe.setHeaders(config.headers());
      probe.setEnabled(config.enabled());
      probe.setTimeoutMillis(config.timeoutMillis());
      List<McpTool> discovered = mcpDiscovery.discoverOrNull(probe);
      Set<String> currentNames = availableMcpTools.getOrDefault(spec.name(), Set.of());
      if (discovered != null) {
        Set<String> nextNames = new LinkedHashSet<>();
        discovered.forEach(tool -> nextNames.add(tool.getName()));
        List<String> blocked =
            currentNames.stream()
                .filter(name -> !nextNames.contains(name))
                .filter(referencedMcpToolNames::contains)
                .sorted()
                .toList();
        if (!blocked.isEmpty()) {
          skipped.add(
              new ConfigSyncSkipped(
                  ConfigSyncKind.MCP_SERVERS.wireValue(),
                  spec.name(),
                  "referenced mcp tool would be removed: " + String.join(", ", blocked)));
          continue;
        }
      }
      mcpImports.add(
          new ConfigSyncPlan.McpImport(
              spec.name(),
              config.url(),
              config.headers(),
              config.enabled(),
              config.timeoutMillis(),
              discovered));
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.MCP_SERVERS, spec.name()));
      if (discovered != null) {
        Set<String> names = new LinkedHashSet<>();
        discovered.forEach(tool -> names.add(tool.getName()));
        availableMcpTools.put(spec.name(), names);
        knownMcpToolNames.addAll(names);
      }
    }

    List<ConfigSyncParser.EnvironmentSpec> environments = new ArrayList<>(document.environments());
    for (ConfigSyncParser.EnvironmentSpec spec : environments) {
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.ENVIRONMENTS, spec.name()));
    }

    Set<String> agentPool = new LinkedHashSet<>();
    snapshot.agents().forEach(agent -> agentPool.add(agent.getName()));
    document.agents().forEach(spec -> agentPool.add(spec.name()));
    boolean changed = true;
    while (changed) {
      changed = false;
      for (ConfigSyncParser.AgentSpec spec : document.agents()) {
        if (!agentPool.contains(spec.name())) {
          continue;
        }
        if (!agentSatisfiable(
            spec, agentPool, availableModelKeys, availableSkills, availableMcpTools, knownMcpToolNames)) {
          agentPool.remove(spec.name());
          changed = true;
        }
      }
    }
    List<ConfigSyncParser.AgentSpec> agents = new ArrayList<>();
    for (ConfigSyncParser.AgentSpec spec : document.agents()) {
      if (agentPool.contains(spec.name())) {
        agents.add(spec);
        imported.add(ConfigSyncRefs.ref(ConfigSyncKind.AGENTS, spec.name()));
      } else {
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.AGENTS.wireValue(),
                spec.name(),
                agentUnsatisfiedReason(
                    spec, agentPool, availableModelKeys, availableSkills, availableMcpTools, knownMcpToolNames)));
      }
    }

    ConfigSyncPlan.SettingsUpdate settingsUpdate = null;
    if (document.settings() != null) {
      SystemSettingsSectionsDTO current = systemSettingsCodec.toSections(snapshot.settings().settings());
      Map<String, Object> base = yaml.toMap(current, SETTINGS_NAME);
      deepMerge(base, document.settings());
      SystemSettingsSectionsDTO merged = yaml.convert(base, SystemSettingsSectionsDTO.class, SETTINGS_NAME);
      String reason = settingsUnsatisfiedReason(merged, availableModelKeys, agentPool);
      if (reason != null) {
        skipped.add(new ConfigSyncSkipped(ConfigSyncKind.SETTINGS.wireValue(), SETTINGS_NAME, reason));
      } else {
        try {
          systemSettingsCodec.fromDto(merged);
        } catch (IllegalArgumentException error) {
          throw new AiValidationException(RESOURCE, "settings are invalid");
        }
        settingsUpdate = new ConfigSyncPlan.SettingsUpdate(merged);
        imported.add(ConfigSyncRefs.ref(ConfigSyncKind.SETTINGS, SETTINGS_NAME));
      }
    }

    return new ConfigSyncPlan(
        document.providers(),
        models,
        skillImports,
        mcpImports,
        environments,
        agents,
        settingsUpdate,
        imported,
        skipped);
  }

  private boolean agentSatisfiable(
      ConfigSyncParser.AgentSpec spec,
      Set<String> agentPool,
      Set<String> availableModelKeys,
      Map<String, Set<String>> availableSkills,
      Map<String, Set<String>> availableMcpTools,
      Set<String> knownMcpToolNames) {
    if (!availableModelKeys.contains(ConfigSyncRefs.modelName(spec.providerName(), spec.modelName()))) {
      return false;
    }
    AgentDefinitionConfigDTO config = spec.properties().getConfig();
    if (config == null) {
      return false;
    }
    if (config.getSkills() != null) {
      for (SkillRefDTO ref : config.getSkills()) {
        if (ref == null
            || !availableSkills.getOrDefault(ref.getPackageName(), Set.of()).contains(ref.getName())) {
          return false;
        }
      }
    }
    if (config.getSubagents() != null) {
      for (String subagent : config.getSubagents()) {
        if (!agentPool.contains(subagent)) {
          return false;
        }
      }
    }
    if (config.getTools() != null) {
      for (String tool : config.getTools()) {
        if (!toolAvailable(tool, availableMcpTools, knownMcpToolNames)) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean toolAvailable(
      String toolName, Map<String, Set<String>> availableMcpTools, Set<String> knownMcpToolNames) {
    if (knownMcpToolNames.contains(toolName)) {
      return availableMcpTools.values().stream().anyMatch(names -> names.contains(toolName));
    }
    return toolCatalog
        .findTool(toolName)
        .map(contribution -> contribution.definition().visibility() == ToolVisibility.SELECTABLE)
        .orElse(false);
  }

  private String agentUnsatisfiedReason(
      ConfigSyncParser.AgentSpec spec,
      Set<String> agentPool,
      Set<String> availableModelKeys,
      Map<String, Set<String>> availableSkills,
      Map<String, Set<String>> availableMcpTools,
      Set<String> knownMcpToolNames) {
    String modelKey = ConfigSyncRefs.modelName(spec.providerName(), spec.modelName());
    if (!availableModelKeys.contains(modelKey)) {
      return "missing model: " + modelKey;
    }
    AgentDefinitionConfigDTO config = spec.properties().getConfig();
    if (config == null) {
      return "missing config";
    }
    if (config.getSkills() != null) {
      for (SkillRefDTO ref : config.getSkills()) {
        if (ref == null) {
          return "invalid skill reference";
        }
        if (!availableSkills.containsKey(ref.getPackageName())) {
          return "missing skill package: " + ref.getPackageName();
        }
        if (!availableSkills.get(ref.getPackageName()).contains(ref.getName())) {
          return "missing skill: " + ref.getPackageName() + "/" + ref.getName();
        }
      }
    }
    if (config.getSubagents() != null) {
      for (String subagent : config.getSubagents()) {
        if (!agentPool.contains(subagent)) {
          return "missing subagent: " + subagent;
        }
      }
    }
    if (config.getTools() != null) {
      for (String tool : config.getTools()) {
        if (!toolAvailable(tool, availableMcpTools, knownMcpToolNames)) {
          return "unsupported or unknown agent tool: " + tool;
        }
      }
    }
    return "unsatisfied dependency";
  }

  private String settingsUnsatisfiedReason(
      SystemSettingsSectionsDTO settings, Set<String> availableModelKeys, Set<String> agentPool) {
    if (settings.getAiRuntime() != null
        && settings.getAiRuntime().getCompactionFallbackModel() != null) {
      var fallback = settings.getAiRuntime().getCompactionFallbackModel();
      String key = ConfigSyncRefs.modelName(fallback.getProviderName(), fallback.getModelName());
      if (!availableModelKeys.contains(key)) {
        return "missing fallback model: " + key;
      }
    }
    if (settings.getIntegrations() != null
        && settings.getIntegrations().getMinimaxH3() != null
        && settings.getIntegrations().getMinimaxH3().getPromptAgentName() != null) {
      String agent = settings.getIntegrations().getMinimaxH3().getPromptAgentName();
      if (!agentPool.contains(agent)) {
        return "missing prompt agent: " + agent;
      }
    }
    return null;
  }

  private static Set<String> skillNames(List<SkillManifestEntry> skills) {
    Set<String> names = new LinkedHashSet<>();
    if (skills != null) {
      skills.forEach(entry -> names.add(entry.name()));
    }
    return names;
  }

  @SuppressWarnings("unchecked")
  private static void deepMerge(Map<String, Object> base, Map<String, Object> override) {
    for (Map.Entry<String, Object> entry : override.entrySet()) {
      Object current = base.get(entry.getKey());
      Object replacement = entry.getValue();
      if (current instanceof Map<?, ?> currentMap && replacement instanceof Map<?, ?> replacementMap) {
        deepMerge((Map<String, Object>) currentMap, (Map<String, Object>) replacementMap);
      } else {
        base.put(entry.getKey(), replacement);
      }
    }
  }
}
