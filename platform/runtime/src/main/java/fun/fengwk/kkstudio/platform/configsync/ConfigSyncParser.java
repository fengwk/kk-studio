package fun.fengwk.kkstudio.platform.configsync;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgents;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentEditablePropertiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
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
import fun.fengwk.kkstudio.platform.environment.EnvironmentInstallConfigs;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallConfigDTO;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinConfigSyncAdapter;
import fun.fengwk.kkstudio.platform.plugin.Plugin;
import fun.fengwk.kkstudio.platform.plugin.PluginRegistry;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把 YAML 文档预校验为强类型候选集合。
 *
 * <p>读取每个受支持条目的已知字段后，立即用写入路径共用的校验器校验有效值，再决定是否因未知字段整体 skip。因此「未知字段」不能绕过缺必填或非法值的硬错误， 只有真正满足当前契约的条目才会被
 * skip 上报。未知顶层类别、不支持的 Provider 协议仍是条目级 skip；结构错误、错误类型、非法有效值、缺必填与文件内重复业务名 （含被 skip
 * 的条目）作为导入错误抛出。settings 出现时按完整七节契约在解析期校验。密钥字段的值绝不进入错误文本。
 *
 * <p>插件条目在字段与未知字段检查之后才判定安装身份：未安装的 id 按条目级 skip 上报，已安装的用该插件自身的 configurationSchema 解析与校验普通值，
 * 非法值与未知（含秘密）字段是硬错误，绝不静默降级或接受秘密。
 */
@Component
public final class ConfigSyncParser {

  private static final String RESOURCE = "config_sync";
  private static final String SETTINGS_ITEM_NAME = "settings";

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
  private final ProviderService providerService;
  private final ModelService modelService;
  private final AgentService agentService;
  private final SkillCatalogService skillCatalogService;
  private final SystemSettingsCodec systemSettingsCodec;
  private final PluginRegistry pluginRegistry;

  public ConfigSyncParser(
      ConfigSyncYaml yaml,
      ProviderService providerService,
      ModelService modelService,
      AgentService agentService,
      SkillCatalogService skillCatalogService,
      SystemSettingsCodec systemSettingsCodec,
      PluginRegistry pluginRegistry) {
    this.yaml = yaml;
    this.providerService = providerService;
    this.modelService = modelService;
    this.agentService = agentService;
    this.skillCatalogService = skillCatalogService;
    this.systemSettingsCodec = systemSettingsCodec;
    this.pluginRegistry = pluginRegistry;
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

    List<String> providerNames = new ArrayList<>();
    List<ProviderSpec> providers = new ArrayList<>();
    for (Entry entry : entries(document, ConfigSyncKind.PROVIDERS)) {
      ProviderSpec spec = parseProvider(entry, skipped, providerNames);
      if (spec != null) {
        providers.add(spec);
      }
    }
    requireDistinct(providerNames, ConfigSyncKind.PROVIDERS);

    List<String> modelNames = new ArrayList<>();
    List<ModelSpec> models = new ArrayList<>();
    for (Entry entry : entries(document, ConfigSyncKind.MODELS)) {
      ModelSpec spec = parseModel(entry, skipped, modelNames);
      if (spec != null) {
        models.add(spec);
      }
    }
    requireDistinct(modelNames, ConfigSyncKind.MODELS);

    List<String> agentNames = new ArrayList<>();
    List<AgentSpec> agents = new ArrayList<>();
    for (Entry entry : entries(document, ConfigSyncKind.AGENTS)) {
      AgentSpec spec = parseAgent(entry, skipped, agentNames);
      if (spec != null) {
        agents.add(spec);
      }
    }
    requireDistinct(agentNames, ConfigSyncKind.AGENTS);

    List<String> skillNames = new ArrayList<>();
    List<SkillSpec> skillPackages = new ArrayList<>();
    for (Entry entry : entries(document, ConfigSyncKind.SKILL_PACKAGES)) {
      SkillSpec spec = parseSkillPackage(entry, skipped, skillNames);
      if (spec != null) {
        skillPackages.add(spec);
      }
    }
    requireDistinct(skillNames, ConfigSyncKind.SKILL_PACKAGES);

    List<String> environmentNames = new ArrayList<>();
    List<EnvironmentSpec> environments = new ArrayList<>();
    for (Entry entry : entries(document, ConfigSyncKind.ENVIRONMENTS)) {
      EnvironmentSpec spec = parseEnvironment(entry, skipped, environmentNames);
      if (spec != null) {
        environments.add(spec);
      }
    }
    requireDistinct(environmentNames, ConfigSyncKind.ENVIRONMENTS);

    List<String> mcpNames = new ArrayList<>();
    List<McpSpec> mcpServers = new ArrayList<>();
    for (Entry entry : entries(document, ConfigSyncKind.MCP_SERVERS)) {
      McpSpec spec = parseMcpServer(entry, skipped, mcpNames);
      if (spec != null) {
        mcpServers.add(spec);
      }
    }
    requireDistinct(mcpNames, ConfigSyncKind.MCP_SERVERS);

    List<String> pluginIds = new ArrayList<>();
    List<PluginSpec> plugins = new ArrayList<>();
    for (Entry entry : entries(document, ConfigSyncKind.PLUGINS)) {
      PluginSpec spec = parsePlugin(entry, skipped, pluginIds);
      if (spec != null) {
        plugins.add(spec);
      }
    }
    requireDistinct(pluginIds, ConfigSyncKind.PLUGINS);

    SystemSettingsSectionsDTO settings = parseSettings(document, skipped);
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

  private List<Entry> entries(Map<String, Object> document, ConfigSyncKind kind) {
    String key = kind.wireValue();
    if (!document.containsKey(key)) {
      return List.of();
    }
    Object node = document.get(key);
    if (node == null) {
      throw DomainException.validation(RESOURCE, key + " must be a list");
    }
    if (!(node instanceof List<?> list)) {
      throw DomainException.validation(RESOURCE, key + " must be a list");
    }
    List<Entry> entries = new ArrayList<>(list.size());
    for (int index = 0; index < list.size(); index++) {
      Object element = list.get(index);
      String path = key + "[" + index + "]";
      if (!(element instanceof Map<?, ?> map)) {
        throw DomainException.validation(RESOURCE, path + " must be an object");
      }
      Map<String, Object> raw = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        raw.put(String.valueOf(e.getKey()), e.getValue());
      }
      entries.add(new Entry(raw, path));
    }
    return entries;
  }

  private ProviderSpec parseProvider(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String providerType = entry.reqString("providerType");
    if (providerType.isBlank()) {
      throw DomainException.validation(RESOURCE, entry.path + ".providerType is required");
    }
    ProviderType type;
    try {
      type = ProviderType.fromWireValue(providerType);
    } catch (IllegalArgumentException error) {
      skipped.add(
          new ConfigSyncSkipped(ConfigSyncKind.PROVIDERS, name, "unsupported provider protocol"));
      return null;
    }
    ProviderCreateDTO properties = new ProviderCreateDTO();
    properties.setName(name);
    properties.setDescription(entry.optString("description"));
    properties.setProviderType(type);
    properties.setBaseUrl(entry.optString("baseUrl"));
    properties.setCredential(entry.optString("credential"));
    properties.setModelCallTimeoutMillis(entry.optLong("modelCallTimeoutMillis"));
    properties.setModelCallIdleTimeoutMillis(entry.optLong("modelCallIdleTimeoutMillis"));
    properties.setModelHttpRetryStatusCodes(entry.optIntegerList("modelHttpRetryStatusCodes"));
    // 先用写入路径校验已知字段，未知字段不能绕过缺必填与非法值。
    validateEntry(ConfigSyncKind.PROVIDERS, name, () -> providerService.validateImport(properties));
    if (skipUnknown(entry, ConfigSyncKind.PROVIDERS, name, skipped)) {
      return null;
    }
    return new ProviderSpec(name, properties);
  }

  private ModelSpec parseModel(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String kindText = entry.reqString("kind");
    ModelKind kind;
    try {
      kind = ModelKind.valueOf(kindText);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, entry.path + ".kind must be MODEL or GROUP");
    }
    String description = entry.optString("description");
    List<String> removed = new ArrayList<>();
    ModelCreateDTO properties;
    if (kind == ModelKind.MODEL) {
      Object configNode = requireObject(entry, "config");
      Object cleanedConfig =
          yaml.removeUnknownProperties(
              ModelConfigDTO.class, configNode, entry.path + ".config", removed);
      SingleModelCreateDTO single = new SingleModelCreateDTO();
      single.setName(name);
      single.setDescription(description);
      single.setProviderName(entry.reqName("providerName"));
      single.setModelId(entry.reqString("modelId"));
      single.setConfig(yaml.convert(cleanedConfig, ModelConfigDTO.class, entry.path + ".config"));
      properties = single;
    } else {
      Object groupNode = requireObject(entry, "group");
      Object cleanedGroup =
          yaml.removeUnknownProperties(
              ModelGroupConfigDTO.class, groupNode, entry.path + ".group", removed);
      ModelGroupCreateDTO group = new ModelGroupCreateDTO();
      group.setName(name);
      group.setDescription(description);
      group.setGroup(yaml.convert(cleanedGroup, ModelGroupConfigDTO.class, entry.path + ".group"));
      properties = group;
    }
    // 先用写入路径校验 known 字段（含嵌套 config/group 缺必填/非法值），未知字段不能绕过硬错误。
    validateEntry(ConfigSyncKind.MODELS, name, () -> modelService.validateImport(name, properties));
    if (skipUnknown(entry, ConfigSyncKind.MODELS, name, skipped)) {
      return null;
    }
    if (reportUnknownConfig(removed, ConfigSyncKind.MODELS, name, skipped)) {
      return null;
    }
    return new ModelSpec(name, kind, properties);
  }

  private AgentSpec parseAgent(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    if (BuiltinAgents.isReservedName(name)) {
      // 内置 Agent 身份由系统持有：文件声明同名条目是硬冲突，不能通过导入覆盖或降级内置定义。
      throw DomainException.validation(
          RESOURCE, "agent name is reserved for a built-in agent: " + name);
    }
    declared.add(name);
    String description = entry.optString("description");
    String systemPrompt = entry.optString("systemPrompt");
    Object executionNode = requireObject(entry, "execution");
    Map<?, ?> executionMap = (Map<?, ?>) executionNode;
    if (!executionMap.keySet().equals(Set.of("driver", "settings"))) {
      throw DomainException.validation(RESOURCE, entry.path + ".execution has invalid fields");
    }
    Object configNode = requireObject(entry, "config");
    List<String> removed = new ArrayList<>();
    Object cleanedConfig =
        yaml.removeUnknownProperties(
            AgentConfigDTO.class, configNode, entry.path + ".config", removed);
    AgentEditablePropertiesDTO properties = new AgentEditablePropertiesDTO();
    properties.setDescription(description);
    properties.setSystemPrompt(systemPrompt);
    properties.setExecution(
        yaml.convert(executionNode, HarnessExecutionSelectionDTO.class, entry.path + ".execution"));
    properties.setConfig(yaml.convert(cleanedConfig, AgentConfigDTO.class, entry.path + ".config"));
    // 先用写入路径校验 known 字段（含嵌套 config 缺必填/非法值），未知字段不能绕过硬错误。
    validateEntry(ConfigSyncKind.AGENTS, name, () -> agentService.validateImport(name, properties));
    if (skipUnknown(entry, ConfigSyncKind.AGENTS, name, skipped)) {
      return null;
    }
    if (reportUnknownConfig(removed, ConfigSyncKind.AGENTS, name, skipped)) {
      return null;
    }
    return new AgentSpec(
        name, BuiltinConfigSyncAdapter.model(properties.getExecution()), properties);
  }

  private SkillSpec parseSkillPackage(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String packageName = entry.reqName("packageName");
    declared.add(packageName);
    String description = entry.optString("description");
    String repositoryUrl = entry.reqString("repositoryUrl");
    String branch = entry.reqString("branch");
    String token = entry.optString("token");
    // 唯一业务校验入口：SkillCatalogService.validateImport 校验 canonical 包名、描述、URL、branch 与令牌。
    validateEntry(
        ConfigSyncKind.SKILL_PACKAGES,
        packageName,
        () ->
            skillCatalogService.validateImport(
                packageName, description, repositoryUrl, branch, token));
    if (skipUnknown(entry, ConfigSyncKind.SKILL_PACKAGES, packageName, skipped)) {
      return null;
    }
    return new SkillSpec(packageName, description, repositoryUrl, branch, token);
  }

  private EnvironmentSpec parseEnvironment(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String tokenHash = entry.reqString("registrationTokenHash");
    // 与 register/update 共用静态校验：known 非法值必须硬拒绝，未知字段不能绕过。
    EnvironmentService.validateName(name);
    EnvironmentService.validateRegistrationTokenHash(tokenHash);
    Object configNode = entry.node("installConfig");
    EnvironmentInstallConfigDTO config =
        configNode == null
            ? null
            : EnvironmentInstallConfigs.parse(EnvironmentInstallConfigs.tree(configNode));
    if (skipUnknown(entry, ConfigSyncKind.ENVIRONMENTS, name, skipped)) {
      return null;
    }
    return new EnvironmentSpec(name, tokenHash, config);
  }

  private McpSpec parseMcpServer(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String url = entry.reqString("url");
    Map<String, String> headers = entry.optStringMap("headers");
    Boolean enabled = entry.optBoolean("enabled");
    Long timeoutMillis = entry.optLong("timeoutMillis");
    // 与 CRUD 共用校验并规范化；未知字段不能绕过 name/url/header/timeout 的硬错误。
    validateEntry(ConfigSyncKind.MCP_SERVERS, name, () -> McpConfigParser.requireName(name));
    McpConfigParser.HttpConfig config =
        normalizeMcpConfig(name, url, headers, enabled, timeoutMillis);
    if (skipUnknown(entry, ConfigSyncKind.MCP_SERVERS, name, skipped)) {
      return null;
    }
    return new McpSpec(name, config);
  }

  private static McpConfigParser.HttpConfig normalizeMcpConfig(
      String name, String url, Map<String, String> headers, Boolean enabled, Long timeoutMillis) {
    try {
      return McpConfigParser.normalizeHttpConfig(url, headers, enabled, timeoutMillis);
    } catch (DomainException error) {
      if (error.code() != DomainErrorCode.VALIDATION) {
        throw error;
      }
      // 底层消息可能含 URL 或 header 值，统一替换为安全的类别与条目名，不携带 cause。
      throw DomainException.validation(
          RESOURCE, "mcp server " + name + " has an invalid url, headers or timeout");
    }
  }

  private PluginSpec parsePlugin(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String pluginId = entry.reqName("pluginId");
    declared.add(pluginId);
    String valuesJson = entry.reqString("valuesJson");
    Optional<Plugin> plugin = pluginRegistry.find(pluginId);
    String resolvedValues = null;
    if (plugin.isPresent()) {
      // known 字段非法值（类型不符、未声明字段、超限）即使伴随未知字段也必须硬拒绝：与其它类别一样先用写入路径校验，再判定未知字段 skip。
      try {
        resolvedValues = plugin.get().configurationSchema().resolveValues(valuesJson);
      } catch (IllegalArgumentException error) {
        // 底层消息可能含用户值，统一替换为安全的类别与条目名；秘密字段名不得借插件条目夹带。
        throw DomainException.validation(RESOURCE, "plugins entry is invalid: " + pluginId);
      }
    }
    if (skipUnknown(entry, ConfigSyncKind.PLUGINS, pluginId, skipped)) {
      return null;
    }
    if (plugin.isEmpty()) {
      // 安装身份由依赖决定：文件里未安装的 id 按既有条目级 skip 上报，不加载也不合成插件。
      skipped.add(
          new ConfigSyncSkipped(ConfigSyncKind.PLUGINS, pluginId, "plugin is not installed"));
      return null;
    }
    return new PluginSpec(pluginId, resolvedValues);
  }

  private SystemSettingsSectionsDTO parseSettings(
      Map<String, Object> document, List<ConfigSyncSkipped> skipped) {
    String key = ConfigSyncKind.SETTINGS.wireValue();
    if (!document.containsKey(key)) {
      return null;
    }
    Object node = document.get(key);
    if (!(node instanceof Map<?, ?> rawMap)) {
      throw DomainException.validation(RESOURCE, "settings must be an object");
    }
    Map<String, Object> raw = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      raw.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    // 未知字段移除并报告；剩余结构必须在解析期满足当前完整六节契约，避免对必然无效的文件做外部 Git/MCP 准备。
    List<String> removed = new ArrayList<>();
    Object cleaned =
        yaml.removeUnknownProperties(SystemSettingsSectionsDTO.class, raw, key, removed);
    for (String path : removed) {
      skipped.add(
          new ConfigSyncSkipped(
              ConfigSyncKind.SETTINGS, SETTINGS_ITEM_NAME, "unsupported field: " + path));
    }
    SystemSettingsSectionsDTO sections =
        yaml.convert(cleaned, SystemSettingsSectionsDTO.class, key);
    try {
      systemSettingsCodec.fromDto(sections);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, "settings are invalid");
    }
    return sections;
  }

  private Object requireObject(Entry entry, String key) {
    Object node = entry.node(key);
    if (!(node instanceof Map<?, ?>)) {
      throw DomainException.validation(RESOURCE, entry.path + "." + key + " must be an object");
    }
    return node;
  }

  /** 嵌套 config 含未声明字段时整体跳过条目；路径可读但不回显字段值。 */
  private static boolean reportUnknownConfig(
      List<String> removed, ConfigSyncKind kind, String name, List<ConfigSyncSkipped> skipped) {
    if (removed.isEmpty()) {
      return false;
    }
    skipped.add(new ConfigSyncSkipped(kind, name, "unsupported field: " + removed.get(0)));
    return true;
  }

  private boolean skipUnknown(
      Entry entry, ConfigSyncKind kind, String name, List<ConfigSyncSkipped> skipped) {
    Set<String> unknown = entry.unknownKeys();
    if (unknown.isEmpty()) {
      return false;
    }
    String first = unknown.iterator().next();
    skipped.add(
        new ConfigSyncSkipped(kind, name, "unsupported field: " + entry.path + "." + first));
    return true;
  }

  /** 用写入路径的校验器检查条目；底层消息可能含真实值，统一替换为安全的类别与条目名，不携带 cause。 */
  private static void validateEntry(ConfigSyncKind kind, String name, Runnable validation) {
    try {
      validation.run();
    } catch (DomainException error) {
      if (error.code() != DomainErrorCode.VALIDATION) {
        throw error;
      }
      throw DomainException.validation(RESOURCE, kind.wireValue() + " entry is invalid: " + name);
    }
  }

  private static void requireDistinct(List<String> names, ConfigSyncKind kind) {
    Set<String> seen = new HashSet<>();
    for (String name : names) {
      if (!seen.add(name)) {
        throw DomainException.validation(
            RESOURCE, "duplicate " + kind.wireValue() + " entry in file: " + name);
      }
    }
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
      String name, String registrationTokenHash, EnvironmentInstallConfigDTO installConfig) {}

  public record McpSpec(String name, McpConfigParser.HttpConfig config) {}

  /**
   * 已安装插件的普通配置条目：安装身份加该插件 schema 归一化后的普通值文本。
   *
   * <p>只承载 wire 上的两个字段；修订、秘密与其它身份事实都不属于 portable 条目。
   */
  public record PluginSpec(String pluginId, String valuesJson) {}

  /** 单条目字段读取器：跟踪已消费键，读取时严格校验类型且不回显值。 */
  private static final class Entry {

    private final Map<String, Object> raw;
    private final String path;
    private final Set<String> consumed = new LinkedHashSet<>();

    Entry(Map<String, Object> raw, String path) {
      this.raw = raw;
      this.path = path;
    }

    Object node(String key) {
      consumed.add(key);
      return raw.get(key);
    }

    String reqString(String key) {
      String value = optString(key);
      if (value == null) {
        throw DomainException.validation(RESOURCE, path + "." + key + " is required");
      }
      return value;
    }

    String reqName(String key) {
      String value = reqString(key);
      if (value.isBlank() || !value.equals(value.strip())) {
        throw DomainException.validation(
            RESOURCE, path + "." + key + " must be non-blank and unpadded");
      }
      return value;
    }

    String optString(String key) {
      consumed.add(key);
      Object value = raw.get(key);
      if (value == null) {
        return null;
      }
      if (!(value instanceof String text)) {
        throw DomainException.validation(RESOURCE, path + "." + key + " must be a string");
      }
      return text;
    }

    Long optLong(String key) {
      consumed.add(key);
      Object value = raw.get(key);
      if (value == null) {
        return null;
      }
      if (value instanceof BigDecimal || value instanceof Float || value instanceof Double) {
        throw DomainException.validation(RESOURCE, path + "." + key + " must be an integer");
      }
      if (value instanceof BigInteger bigInteger) {
        if (bigInteger.bitLength() > 63) {
          throw DomainException.validation(RESOURCE, path + "." + key + " must be an integer");
        }
        return bigInteger.longValue();
      }
      if (value instanceof Number number) {
        return number.longValue();
      }
      throw DomainException.validation(RESOURCE, path + "." + key + " must be an integer");
    }

    List<Integer> optIntegerList(String key) {
      consumed.add(key);
      Object value = raw.get(key);
      if (value == null) {
        return null;
      }
      if (!(value instanceof List<?> list)) {
        throw DomainException.validation(
            RESOURCE, path + "." + key + " must be a list of integers");
      }
      List<Integer> result = new ArrayList<>(list.size());
      for (Object item : list) {
        result.add(toInt(item, key));
      }
      return result;
    }

    private int toInt(Object value, String key) {
      if (value == null
          || value instanceof BigDecimal
          || value instanceof Float
          || value instanceof Double) {
        throw DomainException.validation(
            RESOURCE, path + "." + key + " must contain only integers");
      }
      long longValue;
      if (value instanceof BigInteger bigInteger) {
        if (bigInteger.bitLength() > 63) {
          throw DomainException.validation(
              RESOURCE, path + "." + key + " must contain only integers");
        }
        longValue = bigInteger.longValue();
      } else if (value instanceof Number number) {
        longValue = number.longValue();
      } else {
        throw DomainException.validation(
            RESOURCE, path + "." + key + " must contain only integers");
      }
      if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
        throw DomainException.validation(
            RESOURCE, path + "." + key + " must contain only integers");
      }
      return (int) longValue;
    }

    Boolean optBoolean(String key) {
      consumed.add(key);
      Object value = raw.get(key);
      if (value == null) {
        return null;
      }
      if (!(value instanceof Boolean bool)) {
        throw DomainException.validation(RESOURCE, path + "." + key + " must be a boolean");
      }
      return bool;
    }

    Map<String, String> optStringMap(String key) {
      consumed.add(key);
      Object value = raw.get(key);
      if (value == null) {
        return null;
      }
      if (!(value instanceof Map<?, ?> map)) {
        throw DomainException.validation(RESOURCE, path + "." + key + " must be an object");
      }
      Map<String, String> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String mapKey)) {
          throw DomainException.validation(RESOURCE, path + "." + key + " keys must be strings");
        }
        Object mapValue = entry.getValue();
        if (mapValue != null && !(mapValue instanceof String)) {
          throw DomainException.validation(RESOURCE, path + "." + key + " values must be strings");
        }
        result.put(mapKey, (String) mapValue);
      }
      return result;
    }

    Set<String> unknownKeys() {
      Set<String> unknown = new LinkedHashSet<>(raw.keySet());
      unknown.removeAll(consumed);
      return unknown;
    }
  }
}
