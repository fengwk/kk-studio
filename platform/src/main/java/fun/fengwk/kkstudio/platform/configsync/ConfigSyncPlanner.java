package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerMutationValidator;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
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
 * 导入计划：在事务外完成依赖判定与外部准备（Git exact commit、MCP 发现）。
 *
 * <p>文件条目的静态输入校验（缺必填、类型、有效值、未知字段与 settings 完整七节）已在 {@link ConfigSyncParser} 解析期完成；Planner 只负责与快照
 * 相关的判定：Environment token 冲突、修改 Skill 不可变 repositoryUrl、移除仍被引用的 Skill/MCP 工具都是硬错误；依赖缺失与外部准备失败才是条目级
 * skip。
 *
 * <p>只做确定性决策，不写数据库；真正写入交给事务内 {@link ConfigSyncApplier}。
 *
 * <p>外部准备失败绝不降级为成功：MCP 发现失败时跳过该 Server 及其依赖 Agent，Git 失败时不使用最新 HEAD 兜底也不重试。文件内声明但
 * 被跳过（失败/不支持）的同名条目会被从可用依赖池中剔除，避免依赖借此绕过 skip；文件未声明的既有依赖仍可满足。
 */
@AllArgsConstructor
@Component
public final class ConfigSyncPlanner {

  private static final String RESOURCE = "config_sync";
  private static final String SETTINGS_NAME = "settings";

  private final ConfigSyncSnapshotReader snapshotReader;
  private final ConfigSyncMcpDiscovery mcpDiscovery;
  private final SkillGitCache skillGitCache;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final McpServerRepository mcpServerRepository;
  private final RuntimeToolCatalog toolCatalog;
  private final AgentModelRuntimeConfigParser configParser;

  /** 读取快照后按 ParsedDocument 构建可写入计划。 */
  public ConfigSyncPlan plan(ConfigSyncParser.ParsedDocument document) {
    return plan(document, snapshotReader.read());
  }

  /** 在给定快照上校验并构建可写入计划；预检查与执行复用同一套规则。 */
  public ConfigSyncPlan plan(
      ConfigSyncParser.ParsedDocument document, ConfigSyncSnapshot snapshot) {
    rejectEnvironmentTokenConflicts(document.environments(), snapshot);
    List<ConfigSyncSkipped> skipped = new ArrayList<>(document.skipped());
    List<ConfigSyncRef> imported = new ArrayList<>();

    Set<String> skippedProviders = skippedNames(skipped, ConfigSyncKind.PROVIDERS);
    Set<String> skippedModels = skippedNames(skipped, ConfigSyncKind.MODELS);
    Set<String> skippedSkills = skippedNames(skipped, ConfigSyncKind.SKILL_PACKAGES);
    Set<String> skippedMcp = skippedNames(skipped, ConfigSyncKind.MCP_SERVERS);
    Set<String> skippedAgents = skippedNames(skipped, ConfigSyncKind.AGENTS);

    Set<String> availableProviders = new LinkedHashSet<>();
    for (var provider : snapshot.providers()) {
      if (!skippedProviders.contains(provider.getName())) {
        availableProviders.add(provider.getName());
      }
    }
    for (ConfigSyncParser.ProviderSpec spec : document.providers()) {
      availableProviders.add(spec.name());
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.PROVIDERS, spec.name()));
    }

    Map<String, AgentModelConfigDTO> availableModels = new LinkedHashMap<>();
    for (var model : snapshot.models()) {
      String key = ConfigSyncRefs.modelName(model.getProviderName(), model.getName());
      if (!skippedModels.contains(key) && availableProviders.contains(model.getProviderName())) {
        // 快照中未被文件覆盖的 Model 以 DB config 为准；解码失败与写入路径一致地硬拒绝。
        availableModels.put(key, configParser.decode(model.getConfigJson()));
      }
    }
    List<ConfigSyncParser.ModelSpec> models = new ArrayList<>();
    for (ConfigSyncParser.ModelSpec spec : document.models()) {
      String key = ConfigSyncRefs.modelName(spec.providerName(), spec.name());
      if (!availableProviders.contains(spec.providerName())) {
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.MODELS.wireValue(),
                key,
                "missing provider: " + spec.providerName()));
        skippedModels.add(key);
        availableModels.remove(key);
        continue;
      }
      // 文件内同名 Model 覆盖快照 config：预检查按导入后将生效的配置判定。
      availableModels.put(key, spec.properties().getConfig());
      models.add(spec);
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.MODELS, key));
    }

    // 模型池就绪后、外部准备前：Agent 显式 variant 必须由导入后将生效的 Model config 声明。
    rejectUnknownAgentModelVariants(document.agents(), availableModels);

    Map<String, SkillPackage> existingPackages = new LinkedHashMap<>();
    Map<String, Set<String>> availableSkills = new LinkedHashMap<>();
    for (SkillPackage pkg : snapshot.skillPackages()) {
      if (skippedSkills.contains(pkg.getPackageName())) {
        continue;
      }
      existingPackages.put(pkg.getPackageName(), pkg);
      availableSkills.put(pkg.getPackageName(), skillNames(pkg.getSkills()));
    }
    List<ConfigSyncPlan.SkillImport> skillImports = new ArrayList<>();
    for (ConfigSyncParser.SkillSpec spec : document.skillPackages()) {
      SkillPackage existing = existingPackages.get(spec.packageName());
      if (existing != null && !existing.getRepositoryUrl().equals(spec.repositoryUrl())) {
        // repositoryUrl 是不可变身份：同名换仓库是硬冲突，不能通过部分导入规避，也不回显 URL。
        throw new AiValidationException(
            RESOURCE, "skill package repositoryUrl is immutable: " + spec.packageName());
      }
      List<SkillManifestEntry> manifest;
      try {
        skillGitCache.ensureCommit(
            spec.packageName(), spec.repositoryUrl(), spec.currentCommit(), spec.token());
        manifest = skillGitCache.scanManifest(spec.packageName(), spec.currentCommit());
      } catch (SkillGitException error) {
        // 不回显 Git 错误文本：其中可能包含 repositoryUrl 或凭据。
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.SKILL_PACKAGES.wireValue(),
                spec.packageName(),
                "cannot restore exact commit"));
        skippedSkills.add(spec.packageName());
        continue;
      }
      if (existing != null) {
        Set<String> published = new LinkedHashSet<>(skillNames(manifest));
        List<String> referenced =
            skillNames(existing.getSkills()).stream()
                .filter(skill -> !published.contains(skill))
                .filter(
                    skill ->
                        agentDefinitionRepository.existsReferencingSkill(spec.packageName(), skill))
                .sorted()
                .toList();
        if (!referenced.isEmpty()) {
          // 移除仍被 Agent 引用的 Skill 是硬冲突，不能通过部分导入规避。
          throw new AiValidationException(
              RESOURCE, "skill package would remove referenced skills: " + spec.packageName());
        }
      }
      skillImports.add(
          new ConfigSyncPlan.SkillImport(
              spec.packageName(),
              spec.description(),
              spec.repositoryUrl(),
              spec.branch(),
              spec.currentCommit(),
              manifest,
              spec.token()));
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.SKILL_PACKAGES, spec.packageName()));
      availableSkills.put(spec.packageName(), skillNames(manifest));
    }
    skippedSkills.forEach(availableSkills::remove);

    Map<String, Set<String>> availableMcpTools = new LinkedHashMap<>();
    Set<String> knownMcpToolNames = new LinkedHashSet<>();
    for (McpServer server : snapshot.mcpServers()) {
      if (skippedMcp.contains(server.getName())) {
        continue;
      }
      availableMcpTools.put(server.getName(), new LinkedHashSet<>());
    }
    for (McpTool tool : snapshot.mcpTools()) {
      knownMcpToolNames.add(tool.getName());
      if (skippedMcp.contains(tool.getServerName())) {
        continue;
      }
      availableMcpTools
          .computeIfAbsent(tool.getServerName(), ignored -> new LinkedHashSet<>())
          .add(tool.getName());
    }
    Set<String> referencedMcpToolNames =
        new LinkedHashSet<>(mcpServerRepository.selectReferencedToolNames());
    List<ConfigSyncPlan.McpImport> mcpImports = new ArrayList<>();
    for (ConfigSyncParser.McpSpec spec : document.mcpServers()) {
      McpServerMutationValidator.HttpConfig config = spec.config();
      McpServer probe = new McpServer();
      probe.setName(spec.name());
      probe.setUrl(config.url());
      probe.setHeaders(config.headers());
      probe.setEnabled(config.enabled());
      probe.setTimeoutMillis(config.timeoutMillis());
      List<McpTool> discovered;
      if (!config.enabled()) {
        // 禁用 server：不发起发现，仅保存配置（enabled=false 可独立恢复）；既有工具行保持，引用这些工具的 Agent 仍按现
        // findTool semantics 可解析，不因 disabled 被误丢。
        discovered = null;
      } else {
        discovered = mcpDiscovery.discoverOrNull(probe);
        if (discovered == null) {
          // 契约：发现失败不伪造 UNVERIFIED 成功，skip 当前 MCP 并让依赖它的 Agent 一并 skip。
          skipped.add(
              new ConfigSyncSkipped(
                  ConfigSyncKind.MCP_SERVERS.wireValue(),
                  spec.name(),
                  "cannot discover mcp tools"));
          skippedMcp.add(spec.name());
          availableMcpTools.remove(spec.name());
          continue;
        }
      }
      if (discovered == null) {
        mcpImports.add(
            new ConfigSyncPlan.McpImport(
                spec.name(),
                config.url(),
                config.headers(),
                config.enabled(),
                config.timeoutMillis(),
                null));
        imported.add(ConfigSyncRefs.ref(ConfigSyncKind.MCP_SERVERS, spec.name()));
        continue;
      }
      Set<String> currentNames = availableMcpTools.getOrDefault(spec.name(), Set.of());
      Set<String> nextNames = new LinkedHashSet<>();
      discovered.forEach(tool -> nextNames.add(tool.getName()));
      List<String> blocked =
          currentNames.stream()
              .filter(name -> !nextNames.contains(name))
              .filter(referencedMcpToolNames::contains)
              .sorted()
              .toList();
      if (!blocked.isEmpty()) {
        // 移除仍被引用的 MCP 工具是硬冲突，不能通过部分导入规避。
        throw new AiValidationException(
            RESOURCE, "mcp server would remove referenced tools: " + spec.name());
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
      availableMcpTools.put(spec.name(), nextNames);
      knownMcpToolNames.addAll(nextNames);
    }

    List<ConfigSyncParser.EnvironmentSpec> environments = new ArrayList<>(document.environments());
    for (ConfigSyncParser.EnvironmentSpec spec : environments) {
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.ENVIRONMENTS, spec.name()));
    }

    Set<String> agentPool = new LinkedHashSet<>();
    for (var agent : snapshot.agents()) {
      if (!skippedAgents.contains(agent.getName())) {
        agentPool.add(agent.getName());
      }
    }
    document.agents().forEach(spec -> agentPool.add(spec.name()));
    boolean changed = true;
    while (changed) {
      changed = false;
      for (ConfigSyncParser.AgentSpec spec : document.agents()) {
        if (!agentPool.contains(spec.name())) {
          continue;
        }
        if (agentUnsatisfiedReason(
                spec,
                agentPool,
                availableModels.keySet(),
                availableSkills,
                availableMcpTools,
                knownMcpToolNames)
            != null) {
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
                    spec,
                    agentPool,
                    availableModels.keySet(),
                    availableSkills,
                    availableMcpTools,
                    knownMcpToolNames)));
      }
    }

    ConfigSyncPlan.SettingsUpdate settingsUpdate = null;
    SystemSettingsSectionsDTO sections = document.settings();
    if (sections != null) {
      // 完整七节契约已在解析期校验，这里只判定与当前可用依赖的引用关系。
      String reason = settingsUnsatisfiedReason(sections, availableModels.keySet(), agentPool);
      if (reason != null) {
        skipped.add(
            new ConfigSyncSkipped(ConfigSyncKind.SETTINGS.wireValue(), SETTINGS_NAME, reason));
      } else {
        // 携带计划期读到的版本：apply 时以 CAS 写入，中间若有并发修改会正常冲突并整体回滚。
        settingsUpdate =
            new ConfigSyncPlan.SettingsUpdate(
                sections, SystemSettingsVersions.format(snapshot.settings().version()));
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

  /**
   * Agent 引用的显式 variant 必须由导入后将生效的 Model config 声明。仅校验引用可用 Model 且 variant 非空白的 Agent：缺 Model
   * 仍按既有依赖 skip 政策处理；即使该 Agent 会因其它依赖被 skip，已知非法 variant 仍必须先硬拒绝。
   *
   * <p>错误消息只带安全条目名，不回显 raw variant、Model 配置或 cause。
   */
  private static void rejectUnknownAgentModelVariants(
      List<ConfigSyncParser.AgentSpec> agents, Map<String, AgentModelConfigDTO> availableModels) {
    for (ConfigSyncParser.AgentSpec spec : agents) {
      String variant = spec.properties().getVariant();
      if (variant == null || variant.isBlank()) {
        continue;
      }
      AgentModelConfigDTO config =
          availableModels.get(ConfigSyncRefs.modelName(spec.providerName(), spec.modelName()));
      if (config == null) {
        continue;
      }
      try {
        AgentModelDefaultVariantResolver.resolveConfiguredVariant(
            spec.providerName(), spec.modelName(), variant, config);
      } catch (IllegalArgumentException error) {
        throw new AiValidationException(
            RESOURCE, "agent references unknown model variant: " + spec.name());
      }
    }
  }

  private static Set<String> skippedNames(List<ConfigSyncSkipped> skipped, ConfigSyncKind kind) {
    Set<String> names = new LinkedHashSet<>();
    for (ConfigSyncSkipped entry : skipped) {
      if (kind.wireValue().equals(entry.getKind())
          && entry.getName() != null
          && !entry.getName().isEmpty()) {
        names.add(entry.getName());
      }
    }
    return names;
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
    return null;
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

  /** Environment 只判定与快照身份相关的 registrationToken 冲突；token 格式已由解析期校验，不回显 token。 */
  private static void rejectEnvironmentTokenConflicts(
      List<ConfigSyncParser.EnvironmentSpec> specs, ConfigSyncSnapshot snapshot) {
    Map<String, String> ownerByToken = new LinkedHashMap<>();
    for (ConfigSyncParser.EnvironmentSpec spec : specs) {
      String owner = ownerByToken.putIfAbsent(spec.registrationToken(), spec.name());
      if (owner != null && !owner.equals(spec.name())) {
        throw new AiValidationException(
            RESOURCE, "environment registrationToken is used by multiple entries");
      }
      for (Environment existing : snapshot.environments()) {
        if (spec.registrationToken().equals(existing.getRegistrationToken())
            && !spec.name().equals(existing.getName())) {
          throw new AiValidationException(
              RESOURCE, "environment registrationToken is already in use");
        }
      }
    }
  }
}
