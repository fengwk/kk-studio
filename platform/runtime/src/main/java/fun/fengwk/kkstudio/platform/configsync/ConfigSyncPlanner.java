package fun.fengwk.kkstudio.platform.configsync;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncRef;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入计划：在事务外完成依赖判定与 MCP 发现；Git HEAD 和归档在计划之后、写事务之前准备。
 *
 * <p>文件条目的静态输入校验（缺必填、类型、有效值、未知字段与 settings 完整六节）已在 {@link ConfigSyncParser} 解析期完成；Planner 只负责与快照
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
 *
 * <p>Model、Agent、Skill/MCP 的池判定各自下沉到同包协作者，本类只负责步骤次序与计划装配。
 */
@Component
public final class ConfigSyncPlanner {

  private static final String RESOURCE = "config_sync";
  private static final String SETTINGS_NAME = "settings";

  private final ConfigSyncSnapshotReader snapshotReader;
  private final RuntimeToolCatalogSource toolCatalogSource;
  private final ConfigSyncModelPlanning modelPlanning;
  private final ConfigSyncSkillAndMcpPlanning skillAndMcpPlanning;
  private final ConfigSyncAgentPlanning agentPlanning = new ConfigSyncAgentPlanning();
  private final PluginAgentDependencies pluginAgentDependencies;

  public ConfigSyncPlanner(
      ConfigSyncSnapshotReader snapshotReader,
      ConfigSyncMcpDiscovery mcpDiscovery,
      McpServerRepository mcpServerRepository,
      RuntimeToolCatalogSource toolCatalogSource,
      ModelRuntimeConfigParser configParser,
      ModelGroupConfigCodec groupConfigCodec,
      PluginAgentDependencies pluginAgentDependencies) {
    this.snapshotReader = snapshotReader;
    this.toolCatalogSource = toolCatalogSource;
    this.modelPlanning = new ConfigSyncModelPlanning(configParser, groupConfigCodec);
    this.skillAndMcpPlanning = new ConfigSyncSkillAndMcpPlanning(mcpDiscovery, mcpServerRepository);
    this.pluginAgentDependencies = pluginAgentDependencies;
  }

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
    ConfigSyncModelPlanning.ModelPlan models =
        modelPlanning.plan(document, snapshot, availableProviders, skipped, imported);
    // 模型池就绪后、外部准备前：Agent 显式 variant 必须由导入后将生效的 Model config 声明，GROUP 不得带 variant。
    ConfigSyncAgentPlanning.rejectUnknownModelVariants(document.agents(), models.available());
    ConfigSyncSkillAndMcpPlanning.SkillPlan skills =
        skillAndMcpPlanning.planSkillPackages(
            document.skillPackages(), snapshot, skipped, imported);
    ConfigSyncSkillAndMcpPlanning.McpPlan mcp =
        skillAndMcpPlanning.planMcpServers(document.mcpServers(), snapshot, skipped, imported);
    List<ConfigSyncParser.EnvironmentSpec> environments = planEnvironments(document, imported);
    ConfigSyncAgentPlanning.AgentPlan agents =
        agentPlanning.plan(
            document.agents(),
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
    Set<String> skippedProviders = ConfigSyncSkippedNames.of(skipped, ConfigSyncKind.PROVIDERS);
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

  /** Environment 无导入期依赖：条目全部进入计划并登记导入引用。 */
  private static List<ConfigSyncParser.EnvironmentSpec> planEnvironments(
      ConfigSyncParser.ParsedDocument document, List<ConfigSyncRef> imported) {
    List<ConfigSyncParser.EnvironmentSpec> environments = new ArrayList<>(document.environments());
    for (ConfigSyncParser.EnvironmentSpec spec : environments) {
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.ENVIRONMENTS, spec.name()));
    }
    return environments;
  }

  /** 插件只判定其普通配置声明的 Agent 依赖，并携带导入事务内的配置版本 CAS 坐标。 */
  private List<ConfigSyncPlan.PluginImport> planPlugins(
      ConfigSyncParser.ParsedDocument document,
      ConfigSyncSnapshot snapshot,
      Set<String> agentPool,
      List<ConfigSyncSkipped> skipped,
      List<ConfigSyncRef> imported) {
    List<ConfigSyncPlan.PluginImport> pluginImports = new ArrayList<>();
    Map<String, Long> pluginVersions = new LinkedHashMap<>();
    for (PluginConfigurationRow row : snapshot.plugins()) {
      pluginVersions.put(row.pluginId(), row.version());
    }
    for (ConfigSyncParser.PluginSpec spec : document.plugins()) {
      Long version = pluginVersions.get(spec.pluginId());
      if (version == null) {
        // 权威目录与安装集合必须一致：解析期已确认安装，却没有任何目录坐标意味着目录被篡改，绝不静默导入。
        throw DomainException.validation(
            RESOURCE, "plugin catalog is inconsistent: " + spec.pluginId());
      }
      String reason = pluginUnsatisfiedReason(spec, version, agentPool);
      if (reason != null) {
        skipped.add(new ConfigSyncSkipped(ConfigSyncKind.PLUGINS, spec.pluginId(), reason));
        continue;
      }
      pluginImports.add(
          new ConfigSyncPlan.PluginImport(spec.pluginId(), version, spec.valuesJson()));
      imported.add(ConfigSyncRefs.ref(ConfigSyncKind.PLUGINS, spec.pluginId()));
    }
    return pluginImports;
  }

  /**
   * 插件普通配置声明的 Agent 必须由导入后将生效的 Agent 池满足。
   *
   * <p>文件里声明但被跳过的 Agent 已从池中移除，因此插件绝不会靠旧快照同名 Agent 蒙混过关；错误消息只带安全条目名，不回显配置或 cause。
   */
  private String pluginUnsatisfiedReason(
      ConfigSyncParser.PluginSpec spec, long version, Set<String> agentPool) {
    for (String agent :
        pluginAgentDependencies.declaredAgentNames(spec.pluginId(), version, spec.valuesJson())) {
      if (!agentPool.contains(agent)) {
        return "missing agent: " + agent;
      }
    }
    return null;
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
    // 完整契约已在解析期校验；设置自身不持有任何 Agent 引用，因此没有额外依赖判定。
    // 携带计划期读到的版本：apply 时以 CAS 写入，中间若有并发修改会正常冲突并整体回滚。
    ConfigSyncPlan.SettingsUpdate settingsUpdate =
        new ConfigSyncPlan.SettingsUpdate(sections, snapshot.settings().version());
    imported.add(ConfigSyncRefs.ref(ConfigSyncKind.SETTINGS, SETTINGS_NAME));
    return settingsUpdate;
  }

  /** Environment 只判定与快照身份相关的凭证摘要冲突；摘要格式已由解析期校验。 */
  private static void rejectEnvironmentTokenConflicts(
      List<ConfigSyncParser.EnvironmentSpec> specs, ConfigSyncSnapshot snapshot) {
    Map<String, String> ownerByToken = new LinkedHashMap<>();
    for (ConfigSyncParser.EnvironmentSpec spec : specs) {
      String owner = ownerByToken.putIfAbsent(spec.registrationTokenHash(), spec.name());
      if (owner != null && !owner.equals(spec.name())) {
        throw DomainException.validation(
            RESOURCE, "environment registrationToken is used by multiple entries");
      }
      for (Environment existing : snapshot.environments()) {
        if (spec.registrationTokenHash().equals(existing.getRegistrationTokenHash())
            && !spec.name().equals(existing.getName())) {
          throw DomainException.validation(
              RESOURCE, "environment registrationToken is already in use");
        }
      }
    }
  }
}
