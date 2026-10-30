package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
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
 *
 * <p>插件条目与其它类别同一规则：安装身份与普通值已在解析期校验，这里只判定其声明的 Agent 依赖是否由导入后将生效的 Agent 池满足，并记录该插件当时的配置修订作为导入事务内的 CAS
 * 坐标。
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
  private final RuntimeToolCatalogSource toolCatalogSource;
  private final AgentModelRuntimeConfigParser configParser;
  private final AgentModelGroupConfigCodec groupConfigCodec;
  private final PluginAgentDependencies pluginAgentDependencies;

  /** 读取快照后按 ParsedDocument 构建可写入计划。 */
  public ConfigSyncPlan plan(ConfigSyncParser.ParsedDocument document) {
    return plan(document, snapshotReader.read());
  }

  /** 在给定快照上校验并构建可写入计划；预检查与执行复用同一套规则。 */
  public ConfigSyncPlan plan(
      ConfigSyncParser.ParsedDocument document, ConfigSyncSnapshot snapshot) {
    // 一次租用包住完整规划：工具 schema 与可选择性来自同一份目录快照。
    try (RuntimeToolCatalogSource.Lease lease = toolCatalogSource.acquire()) {
      return plan(document, snapshot, lease.catalog());
    }
  }

  /**
   * 在给定快照与同一次租用的运行时目录上校验并构建可写入计划。
   *
   * <p>步骤按配置类别顺序执行，每步只读取前序步骤产出的可用池；跳过条目与导入引用按同一次序累积到计划中。
   */
  private ConfigSyncPlan plan(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      RuntimeToolCatalog toolCatalog) {
    rejectEnvironmentTokenConflicts(document.environments(), snapshot);
    List<ConfigSyncSkipped> skipped = new ArrayList<>(document.skipped());
    List<ConfigSyncRef> imported = new ArrayList<>();

    Set<String> availableProviders = planProviders(document, snapshot, skipped, imported);
    ModelPlan models = planModels(document, snapshot, availableProviders, skipped, imported);
    // 模型池就绪后、外部准备前：Agent 显式 variant 必须由导入后将生效的 Model config 声明，GROUP 不得带 variant。
    rejectUnknownAgentModelVariants(document.agents(), models.available());
    SkillPlan skills = planSkillPackages(document, snapshot, skipped, imported);
    McpPlan mcp = planMcpServers(document, snapshot, skipped, imported);
    List<ConfigSyncParser.EnvironmentSpec> environments = planEnvironments(document, imported);
    AgentPlan agents =
        planAgents(
            document,
            snapshot,
            models.available().keySet(),
            skills.available(),
            mcp.availableTools(),
            mcp.knownToolNames(),
            toolCatalog,
            skipped,
            imported);
    List<ConfigSyncPlan.PluginImport> pluginImports =
        planPlugins(document, snapshot, agents.pool(), skipped, imported);
    ConfigSyncPlan.SettingsUpdate settingsUpdate = planSettings(document, snapshot, imported);

    return new ConfigSyncPlan(
        document.providers(),
        models.ordered(),
        skills.imports(),
        mcp.imports(),
        environments,
        agents.ordered(),
        pluginImports,
        settingsUpdate,
        imported,
        skipped);
  }

  /** Provider 没有依赖：文件条目全部进入导入池，快照中已被跳过的同名 Provider 不可用。 */
  private static Set<String> planProviders(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedProviders = skippedNames(skipped, ConfigSyncKind.PROVIDERS);
    Set<String> availableProviders = new LinkedHashSet<>();
    for (var provider : snapshot.providers()) {
      if (!skippedProviders.contains(provider.name())) {
        availableProviders.add(provider.name());
      }
    }
    for (ConfigSyncParser.ProviderSpec spec : document.providers()) {
      availableProviders.add(spec.name());
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.PROVIDERS, spec.name()));
    }
    return availableProviders;
  }

  /** 快照 Model 先进入可用池；文件条目覆盖同名并在此判定 Provider 依赖，最后消解组依赖并按 MODEL → GROUP 排序。 */
  private ModelPlan planModels(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      Set<String> availableProviders,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedModels = skippedNames(skipped, ConfigSyncKind.MODELS);
    Map<String, ModelAvailability> availableModels = new LinkedHashMap<>();
    for (var model : snapshot.models()) {
      String key = model.name();
      if (skippedModels.contains(key)) {
        continue;
      }
      if (model.kind() == AgentModelKind.MODEL) {
        if (!availableProviders.contains(model.providerName())) {
          continue;
        }
        // 快照中未被文件覆盖的 Model 以 DB config 为准；解码失败与写入路径一致地硬拒绝。
        availableModels.put(
            key,
            new ModelAvailability(
                key,
                AgentModelKind.MODEL,
                configParser.decode(model.configJson()),
                null,
                model.providerName()));
      } else {
        availableModels.put(
            key,
            new ModelAvailability(
                key,
                AgentModelKind.GROUP,
                null,
                groupConfigCodec.decode(model.groupConfigJson()),
                null));
      }
    }
    Map<String, ConfigSyncParser.ModelSpec> candidateModels = new LinkedHashMap<>();
    for (ConfigSyncParser.ModelSpec spec : document.models()) {
      String key = spec.name();
      AgentModelKind kind = spec.properties().getKind();
      if (kind == AgentModelKind.MODEL
          && !availableProviders.contains(spec.properties().getProviderName())) {
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.MODELS.wireValue(),
                key,
                "missing provider: " + spec.properties().getProviderName()));
        skippedModels.add(key);
        availableModels.remove(key);
        continue;
      }
      // 文件内同名 Model 覆盖快照：预检查按导入后将生效的配置判定。
      availableModels.put(
          key,
          new ModelAvailability(
              key,
              kind,
              spec.properties().getConfig(),
              spec.properties().getGroup(),
              spec.properties().getProviderName()));
      candidateModels.put(key, spec);
    }
    removeUnsatisfiedGroups(availableModels, skippedModels, skipped);
    return new ModelPlan(availableModels, orderModels(candidateModels, availableModels, imported));
  }

  /** 组只能引用可用的 MODEL；任一成员缺失或 Variant 未声明时移除该组，依赖它的 Agent 随池判定被 skip。 */
  private static void removeUnsatisfiedGroups(
      Map<String, ModelAvailability> availableModels,
      Set<String> skippedModels,
      List<ConfigSyncSkipped> skipped) {
    boolean modelPoolChanged = true;
    while (modelPoolChanged) {
      modelPoolChanged = false;
      for (Map.Entry<String, ModelAvailability> entry : availableModels.entrySet()) {
        String reason = groupUnsatisfiedReason(entry.getValue(), availableModels);
        if (reason == null) {
          continue;
        }
        skipped.add(
            new ConfigSyncSkipped(ConfigSyncKind.MODELS.wireValue(), entry.getKey(), reason));
        skippedModels.add(entry.getKey());
        availableModels.remove(entry.getKey());
        modelPoolChanged = true;
        break;
      }
    }
  }

  /** 写入顺序先 MODEL 后 GROUP：同一次导入中的组必须能看到已插入的成员行。 */
  private static List<ConfigSyncParser.ModelSpec> orderModels(
      Map<String, ConfigSyncParser.ModelSpec> candidateModels,
      Map<String, ModelAvailability> availableModels,
      List<ConfigSyncRef> imported) {
    List<ConfigSyncParser.ModelSpec> models = new ArrayList<>();
    for (AgentModelKind kind : List.of(AgentModelKind.MODEL, AgentModelKind.GROUP)) {
      for (ConfigSyncParser.ModelSpec spec : candidateModels.values()) {
        if (!availableModels.containsKey(spec.name()) || spec.properties().getKind() != kind) {
          continue;
        }
        models.add(spec);
        imported.add(ConfigSyncRefs.model(spec.name()));
      }
    }
    return models;
  }

  /** Skill Package 需要外部 Git 精确 commit；Git 失败只跳过条目，repositoryUrl 变化或丢失被引用 Skill 是硬冲突。 */
  private SkillPlan planSkillPackages(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedSkills = skippedNames(skipped, ConfigSyncKind.SKILL_PACKAGES);
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
    for (ConfigSyncParser.SkillSpec spec : document.skillPackages()) {
      SkillPackage existing = existingPackages.get(spec.packageName());
      if (existing != null && !existing.repositoryUrl().equals(spec.repositoryUrl())) {
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
      if (existing != null
          && !referencedRemovedSkills(spec.packageName(), existing, manifest).isEmpty()) {
        // 移除仍被 Agent 引用的 Skill 是硬冲突，不能通过部分导入规避。
        throw new AiValidationException(
            RESOURCE, "skill package would remove referenced skills: " + spec.packageName());
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
    return new SkillPlan(skillImports, availableSkills);
  }

  /** 新 manifest 不再提供、但仍被 Agent 引用的 Skill（按名称排序，用于硬拒绝判定）。 */
  private List<String> referencedRemovedSkills(
      String packageName, SkillPackage existing, List<SkillManifestEntry> manifest) {
    Set<String> published = new LinkedHashSet<>(skillNames(manifest));
    return skillNames(existing.skills()).stream()
        .filter(skill -> !published.contains(skill))
        .filter(skill -> agentDefinitionRepository.existsReferencingSkill(packageName, skill))
        .sorted()
        .toList();
  }

  /** MCP Server 先建立可用池与已知工具名池，再逐个 Server 做发现；文件条目的工具变化在此判定。 */
  private McpPlan planMcpServers(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedMcp = skippedNames(skipped, ConfigSyncKind.MCP_SERVERS);
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
    for (ConfigSyncParser.McpSpec spec : document.mcpServers()) {
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
      ConfigSyncParser.McpSpec spec,
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
      discovered = null;
    } else {
      discovered = mcpDiscovery.discoverOrNull(probe);
      if (discovered == null) {
        // 契约：发现失败不伪造 UNVERIFIED 成功，skip 当前 MCP 并让依赖它的 Agent 一并 skip。
        skipped.add(
            new ConfigSyncSkipped(
                ConfigSyncKind.MCP_SERVERS.wireValue(), spec.name(), "cannot discover mcp tools"));
        skippedMcp.add(spec.name());
        availableMcpTools.remove(spec.name());
        return null;
      }
    }
    if (discovered == null) {
      return new ConfigSyncPlan.McpImport(
          spec.name(),
          config.url(),
          config.headers(),
          config.enabled(),
          config.timeoutMillis(),
          null);
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
      throw new AiValidationException(
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

  /** Environment 无导入期依赖：条目全部进入计划并登记导入引用。 */
  private static List<ConfigSyncParser.EnvironmentSpec> planEnvironments(
      ConfigSyncParser.ParsedDocument document, List<ConfigSyncRef> imported) {
    List<ConfigSyncParser.EnvironmentSpec> environments = new ArrayList<>(document.environments());
    for (ConfigSyncParser.EnvironmentSpec spec : environments) {
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.ENVIRONMENTS, spec.name()));
    }
    return environments;
  }

  /** Agent 依赖模型池、Skill、MCP 工具与工具目录；池内不动点移除不满足者，保留文件顺序作为写入顺序。 */
  private AgentPlan planAgents(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      Set<String> availableModelKeys,
      Map<String, Set<String>> availableSkills,
      Map<String, Set<String>> availableMcpTools,
      Set<String> knownMcpToolNames,
      RuntimeToolCatalog toolCatalog,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    Set<String> skippedAgents = skippedNames(skipped, ConfigSyncKind.AGENTS);
    Set<String> agentPool = new LinkedHashSet<>();
    for (var agent : snapshot.agents()) {
      if (!skippedAgents.contains(agent.name())) {
        agentPool.add(agent.name());
      }
    }
    // 系统内置 Agent 不在可导入条目里，但确实存在：作为已满足的依赖进入池，插件与 Agent 的引用都可解析。
    agentPool.addAll(snapshot.builtinAgentNames());
    document.agents().forEach(spec -> agentPool.add(spec.name()));
    boolean agentPoolChanged = true;
    while (agentPoolChanged) {
      agentPoolChanged = false;
      for (ConfigSyncParser.AgentSpec spec : document.agents()) {
        if (!agentPool.contains(spec.name())) {
          continue;
        }
        if (agentUnsatisfiedReason(
                spec,
                agentPool,
                availableModelKeys,
                availableSkills,
                availableMcpTools,
                knownMcpToolNames,
                toolCatalog)
            != null) {
          agentPool.remove(spec.name());
          agentPoolChanged = true;
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
                    availableModelKeys,
                    availableSkills,
                    availableMcpTools,
                    knownMcpToolNames,
                    toolCatalog)));
      }
    }
    return new AgentPlan(agentPool, agents);
  }

  /** 插件只判定其普通配置声明的 Agent 依赖，并携带导入事务内的配置修订 CAS 坐标。 */
  private List<ConfigSyncPlan.PluginImport> planPlugins(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      Set<String> agentPool,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    List<ConfigSyncPlan.PluginImport> pluginImports = new ArrayList<>();
    Map<String, Long> pluginRevisions = new LinkedHashMap<>();
    for (PluginConfigurationRow row : snapshot.plugins()) {
      pluginRevisions.put(row.pluginId(), row.revision());
    }
    for (ConfigSyncParser.PluginSpec spec : document.plugins()) {
      Long revision = pluginRevisions.get(spec.pluginId());
      if (revision == null) {
        // 权威目录与安装集合必须一致：解析期已确认安装，却没有任何目录坐标意味着目录被篡改，绝不静默导入。
        throw new AiValidationException(
            RESOURCE, "plugin catalog is inconsistent: " + spec.pluginId());
      }
      String reason = pluginUnsatisfiedReason(spec, revision, agentPool);
      if (reason != null) {
        skipped.add(
            new ConfigSyncSkipped(ConfigSyncKind.PLUGINS.wireValue(), spec.pluginId(), reason));
        continue;
      }
      pluginImports.add(
          new ConfigSyncPlan.PluginImport(spec.pluginId(), revision, spec.valuesJson()));
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.PLUGINS, spec.pluginId()));
    }
    return pluginImports;
  }

  /** 设置按当前完整六节直接生效；携带计划期读到的版本，apply 时以 CAS 写入。 */
  private static ConfigSyncPlan.SettingsUpdate planSettings(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      List<ConfigSyncRef> imported) {
    SystemSettingsSectionsDTO sections = document.settings();
    if (sections == null) {
      return null;
    }
    // 完整六节契约已在解析期校验；设置自身不再持有任何 Agent 引用，因此没有额外依赖判定。
    // 携带计划期读到的版本：apply 时以 CAS 写入，中间若有并发修改会正常冲突并整体回滚。
    ConfigSyncPlan.SettingsUpdate settingsUpdate =
        new ConfigSyncPlan.SettingsUpdate(
            sections, SystemSettingsVersions.format(snapshot.settings().version()));
    imported.add(ConfigSyncRefs.ref(ConfigSyncKind.SETTINGS, SETTINGS_NAME));
    return settingsUpdate;
  }

  /**
   * 插件普通配置声明的 Agent 必须由导入后将生效的 Agent 池满足。
   *
   * <p>文件里声明但被跳过的 Agent 已从池中移除，因此插件绝不会靠旧快照同名 Agent 蒙混过关；错误消息只带安全条目名，不回显配置或 cause。
   */
  private String pluginUnsatisfiedReason(
      ConfigSyncParser.PluginSpec spec, long revision, Set<String> agentPool) {
    for (String agent :
        pluginAgentDependencies.declaredAgentNames(spec.pluginId(), revision, spec.valuesJson())) {
      if (!agentPool.contains(agent)) {
        return "missing agent: " + agent;
      }
    }
    return null;
  }

  /**
   * Agent 引用的显式 variant 必须由导入后将生效的 Model config 声明。仅校验引用可用 Model 且 variant 非空白的 Agent：缺 Model
   * 仍按既有依赖 skip 政策处理；即使该 Agent 会因其它依赖被 skip，已知非法 variant 仍必须先硬拒绝。
   *
   * <p>错误消息只带安全条目名，不回显 raw variant、Model 配置或 cause。
   */
  private static void rejectUnknownAgentModelVariants(
      List<ConfigSyncParser.AgentSpec> agents, Map<String, ModelAvailability> availableModels) {
    for (ConfigSyncParser.AgentSpec spec : agents) {
      String variant = spec.properties().getVariant();
      ModelAvailability model = availableModels.get(spec.modelName());
      if (model == null) {
        continue;
      }
      if (variant == null || variant.isBlank()) {
        continue;
      }
      if (model.kind() == AgentModelKind.GROUP) {
        throw new AiValidationException(
            RESOURCE, "agent references a model group with variant: " + spec.name());
      }
      try {
        AgentModelDefaultVariantResolver.resolveConfiguredVariant(
            spec.modelName(), variant, model.config());
      } catch (IllegalArgumentException error) {
        throw new AiValidationException(
            RESOURCE, "agent references unknown model variant: " + spec.name());
      }
    }
  }

  /** 非组的可用性恒为满足；组要求每个成员都是当前池中的 MODEL 且 Variant 已声明。 */
  private static String groupUnsatisfiedReason(
      ModelAvailability model, Map<String, ModelAvailability> availableModels) {
    if (model.kind() == AgentModelKind.MODEL) {
      return null;
    }
    for (var member : model.group().getMembers()) {
      ModelAvailability target = availableModels.get(member.getModelName());
      if (target == null) {
        return "missing group member: " + member.getModelName();
      }
      if (target.kind() != AgentModelKind.MODEL) {
        return "group member is not a model: " + member.getModelName();
      }
      if (member.getVariant() != null && !member.getVariant().isBlank()) {
        try {
          AgentModelDefaultVariantResolver.resolveConfiguredVariant(
              member.getModelName(), member.getVariant(), target.config());
        } catch (IllegalArgumentException error) {
          return "group member references unknown variant: " + member.getModelName();
        }
      }
    }
    return null;
  }

  private record ModelPlan(
      Map<String, ModelAvailability> available, List<ConfigSyncParser.ModelSpec> ordered) {}

  private record SkillPlan(
      List<ConfigSyncPlan.SkillImport> imports, Map<String, Set<String>> available) {}

  private record McpPlan(
      List<ConfigSyncPlan.McpImport> imports,
      Map<String, Set<String>> availableTools,
      Set<String> knownToolNames) {}

  private record AgentPlan(Set<String> pool, List<ConfigSyncParser.AgentSpec> ordered) {}

  private record ModelAvailability(
      String name,
      AgentModelKind kind,
      AgentModelConfigDTO config,
      AgentModelGroupDTO group,
      String providerName) {}

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
      RuntimeToolCatalog toolCatalog,
      String toolName,
      Map<String, Set<String>> availableMcpTools,
      Set<String> knownMcpToolNames) {
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
      Set<String> knownMcpToolNames,
      RuntimeToolCatalog toolCatalog) {
    String modelKey = spec.modelName();
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
        if (!toolAvailable(toolCatalog, tool, availableMcpTools, knownMcpToolNames)) {
          return "unsupported or unknown agent tool: " + tool;
        }
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

  /** Environment 只判定与快照身份相关的凭证摘要冲突；摘要格式已由解析期校验。 */
  private static void rejectEnvironmentTokenConflicts(
      List<ConfigSyncParser.EnvironmentSpec> specs, ConfigSyncSnapshot snapshot) {
    Map<String, String> ownerByToken = new LinkedHashMap<>();
    for (ConfigSyncParser.EnvironmentSpec spec : specs) {
      String owner = ownerByToken.putIfAbsent(spec.registrationTokenHash(), spec.name());
      if (owner != null && !owner.equals(spec.name())) {
        throw new AiValidationException(
            RESOURCE, "environment registrationToken is used by multiple entries");
      }
      for (Environment existing : snapshot.environments()) {
        if (spec.registrationTokenHash().equals(existing.getRegistrationTokenHash())
            && !spec.name().equals(existing.getName())) {
          throw new AiValidationException(
              RESOURCE, "environment registrationToken is already in use");
        }
      }
    }
  }
}
