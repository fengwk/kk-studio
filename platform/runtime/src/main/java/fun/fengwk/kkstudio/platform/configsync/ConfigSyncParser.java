package fun.fengwk.kkstudio.platform.configsync;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentEditablePropertiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;
import fun.fengwk.kkstudio.platform.plugin.PluginRegistry;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 YAML 文档预校验为强类型候选集合。
 *
 * <p>读取每个受支持条目的已知字段后，立即用写入路径共用的校验器校验有效值，再决定是否因未知字段整体 skip。因此「未知字段」不能绕过缺必填或非法值的硬错误， 只有真正满足当前契约的条目才会被
 * skip 上报。未知顶层类别、不支持的 Provider 协议仍是条目级 skip；结构错误、错误类型、非法有效值、缺必填与文件内重复业务名 （含被 skip
 * 的条目）作为导入错误抛出。settings 出现时按完整六节契约在解析期校验。密钥字段的值绝不进入错误文本。
 *
 * <p>各分类的字段读取与预校验下沉到同包 {@code *Entries} 协作者；本类只负责类别顺序、文件名唯一性判定与结果装配。
 */
@Component
public final class ConfigSyncParser {

  private static final Set<String> CATEGORIES =
      Set.of(
          ConfigSyncKind.PROVIDERS.wireValue(),
          ConfigSyncKind.MODELS.wireValue(),
          ConfigSyncKind.AGENTS.wireValue(),
          ConfigSyncKind.SKILL_PACKAGES.wireValue(),
          ConfigSyncKind.ENVIRONMENTS.wireValue(),
          ConfigSyncKind.MCP_SERVERS.wireValue(),
          ConfigSyncKind.SETTINGS.wireValue(),
          ConfigSyncKind.PLUGINS.wireValue());

  private final ConfigSyncYaml yaml;
  private final ConfigSyncCatalogEntries catalogEntries;
  private final ConfigSyncPlatformEntries platformEntries;
  private final ConfigSyncPluginEntries pluginEntries;
  private final ConfigSyncSettingsEntries settingsEntries;

  public ConfigSyncParser(
      ConfigSyncYaml yaml,
      ProviderService providerService,
      ModelService modelService,
      AgentService agentService,
      SkillCatalogService skillCatalogService,
      SystemSettingsCodec systemSettingsCodec,
      PluginRegistry pluginRegistry) {
    this.yaml = yaml;
    this.catalogEntries =
        new ConfigSyncCatalogEntries(yaml, providerService, modelService, agentService);
    this.platformEntries = new ConfigSyncPlatformEntries(yaml, skillCatalogService);
    this.pluginEntries = new ConfigSyncPluginEntries(pluginRegistry);
    this.settingsEntries = new ConfigSyncSettingsEntries(yaml, systemSettingsCodec);
  }

  /** 解析并预校验；返回的候选已剔除被 skip 的条目，重复业务名在返回前判定。 */
  public ParsedDocument parse(String yamlText) {
    Map<String, Object> document = yaml.parse(yamlText);
    List<ConfigSyncSkipped> skipped = new ArrayList<>();
    for (String key : document.keySet()) {
      if (!CATEGORIES.contains(key)) {
        skipped.add(new ConfigSyncSkipped(null, key, "unknown config category"));
      }
    }

    List<ProviderSpec> providers =
        parseCategory(document, ConfigSyncKind.PROVIDERS, catalogEntries::parseProvider, skipped);
    List<ModelSpec> models =
        parseCategory(document, ConfigSyncKind.MODELS, catalogEntries::parseModel, skipped);
    List<AgentSpec> agents =
        parseCategory(document, ConfigSyncKind.AGENTS, catalogEntries::parseAgent, skipped);
    List<SkillSpec> skillPackages =
        parseCategory(
            document, ConfigSyncKind.SKILL_PACKAGES, platformEntries::parseSkillPackage, skipped);
    List<EnvironmentSpec> environments =
        parseCategory(
            document, ConfigSyncKind.ENVIRONMENTS, platformEntries::parseEnvironment, skipped);
    List<McpSpec> mcpServers =
        parseCategory(
            document, ConfigSyncKind.MCP_SERVERS, platformEntries::parseMcpServer, skipped);
    List<PluginSpec> plugins =
        parseCategory(document, ConfigSyncKind.PLUGINS, pluginEntries::parsePlugin, skipped);
    SystemSettingsSectionsDTO settings = settingsEntries.parseSettings(document, skipped);

    return new ParsedDocument(
        providers,
        models,
        agents,
        skillPackages,
        environments,
        mcpServers,
        plugins,
        settings,
        skipped);
  }

  /** 按文档顺序读一类条目：读取器返回 {@code null} 表示按契约 skip 该条目，其余进入候选集合；返回前判定文件内业务名唯一。 */
  private static <S> List<S> parseCategory(
      Map<String, Object> document,
      ConfigSyncKind kind,
      CategoryEntries<S> reader,
      List<ConfigSyncSkipped> skipped) {
    List<String> declared = new ArrayList<>();
    List<S> specs = new ArrayList<>();
    for (ConfigSyncEntry entry : ConfigSyncEntrySupport.entries(document, kind)) {
      S spec = reader.read(entry, skipped, declared);
      if (spec != null) {
        specs.add(spec);
      }
    }
    ConfigSyncEntrySupport.requireDistinct(declared, kind);
    return specs;
  }

  /** 一类条目的读取器：消费已知字段、追加业务名并返回强类型候选，skip 时返回 {@code null}。 */
  @FunctionalInterface
  private interface CategoryEntries<S> {

    S read(ConfigSyncEntry entry, List<ConfigSyncSkipped> skipped, List<String> declared);
  }

  /** 解析得到的强类型候选项与条目级 skip。 */
  public record ParsedDocument(
      List<ProviderSpec> providers,
      List<ModelSpec> models,
      List<AgentSpec> agents,
      List<SkillSpec> skillPackages,
      List<EnvironmentSpec> environments,
      List<McpSpec> mcpServers,
      List<PluginSpec> plugins,
      SystemSettingsSectionsDTO settings,
      List<ConfigSyncSkipped> skipped) {}

  public record ProviderSpec(String name, ProviderCreateDTO properties) {

    ProviderType providerType() {
      return properties.getProviderType();
    }
  }

  public record ModelSpec(String name, ModelKind kind, ModelCreateDTO properties) {

    String providerName() {
      return properties instanceof SingleModelCreateDTO single ? single.getProviderName() : null;
    }

    ModelConfigDTO config() {
      return properties instanceof SingleModelCreateDTO single ? single.getConfig() : null;
    }

    ModelGroupConfigDTO group() {
      return properties instanceof ModelGroupCreateDTO group ? group.getGroup() : null;
    }
  }

  public record AgentSpec(
      String name, ModelSelection builtinModel, AgentEditablePropertiesDTO properties) {}

  public record SkillSpec(
      String packageName, String description, String repositoryUrl, String branch, String token) {}

  public record EnvironmentSpec(
      String name, String note, String registrationTokenHash, DaemonConfigDTO daemonConfig) {}

  public record McpSpec(String name, McpConfigParser.HttpConfig config) {}

  /**
   * 已安装插件的普通配置条目：安装身份加该插件 schema 归一化后的普通值文本。
   *
   * <p>只承载 wire 上的两个字段；修订、秘密与其它身份事实都不属于 portable 条目。
   */
  public record PluginSpec(String pluginId, String valuesJson) {}
}
