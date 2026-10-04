package fun.fengwk.kkstudio.platform.configsync;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelRef;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncSkipped;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 YAML 文档预校验为强类型候选集合。
 *
 * <p>未知顶层类别、不支持的 Provider 协议、条目级未知字段与嵌套 config 未知字段都作为条目级 skip 上报（路径不含字段值）；结构错误、错误类型、
 * 非法有效值、缺必填与文件内重复业务名（含被 skip 的条目）作为导入错误抛出。密钥字段的值绝不进入错误文本。
 */
@Component
public final class ConfigSyncParser {

  private static final String RESOURCE = "config_sync";
  private static final Pattern COMMIT = Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64})$");

  private static final Set<String> CATEGORIES =
      Set.of(
          ConfigSyncKind.PROVIDERS.wireValue(),
          ConfigSyncKind.MODELS.wireValue(),
          ConfigSyncKind.AGENTS.wireValue(),
          ConfigSyncKind.SKILL_PACKAGES.wireValue(),
          ConfigSyncKind.ENVIRONMENTS.wireValue(),
          ConfigSyncKind.MCP_SERVERS.wireValue(),
          ConfigSyncKind.SETTINGS.wireValue());

  private final ConfigSyncYaml yaml;

  public ConfigSyncParser(ConfigSyncYaml yaml) {
    this.yaml = yaml;
  }

  /** 解析并预校验；返回的候选已剔除被 skip 的条目，重复业务名在返回前判定。 */
  public ParsedDocument parse(String yamlText) {
    Map<String, Object> document = yaml.parse(yamlText);
    List<ConfigSyncSkipped> skipped = new ArrayList<>();

    for (String key : document.keySet()) {
      if (!CATEGORIES.contains(key)) {
        skipped.add(new ConfigSyncSkipped(key, "", "unknown config category"));
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

    Map<String, Object> settings = parseSettings(document, skipped);
    return new ParsedDocument(
        providers, models, agents, skillPackages, environments, mcpServers, settings, skipped);
  }

  private List<Entry> entries(Map<String, Object> document, ConfigSyncKind kind) {
    String key = kind.wireValue();
    if (!document.containsKey(key)) {
      return List.of();
    }
    Object node = document.get(key);
    if (node == null) {
      throw new AiValidationException(RESOURCE, key + " must be a list");
    }
    if (!(node instanceof List<?> list)) {
      throw new AiValidationException(RESOURCE, key + " must be a list");
    }
    List<Entry> entries = new ArrayList<>(list.size());
    for (int index = 0; index < list.size(); index++) {
      Object element = list.get(index);
      String path = key + "[" + index + "]";
      if (!(element instanceof Map<?, ?> map)) {
        throw new AiValidationException(RESOURCE, path + " must be an object");
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
    ProviderType type;
    try {
      type = ProviderType.fromWireValue(providerType);
    } catch (IllegalArgumentException error) {
      skipped.add(
          new ConfigSyncSkipped(
              ConfigSyncKind.PROVIDERS.wireValue(), name, "unsupported provider protocol"));
      return null;
    }
    AgentProviderEditablePropertiesDTO properties = new AgentProviderEditablePropertiesDTO();
    properties.setDescription(entry.optString("description"));
    properties.setProviderType(type.wireValue());
    properties.setBaseUrl(entry.optString("baseUrl"));
    properties.setCredential(entry.optString("credential"));
    properties.setModelCallTimeoutMillis(entry.optLong("modelCallTimeoutMillis"));
    properties.setModelCallIdleTimeoutMillis(entry.optLong("modelCallIdleTimeoutMillis"));
    if (skipUnknown(entry, ConfigSyncKind.PROVIDERS, name, skipped)) {
      return null;
    }
    return new ProviderSpec(name, properties);
  }

  private ModelSpec parseModel(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String providerName = entry.reqName("providerName");
    String name = entry.reqName("name");
    String key = ConfigSyncRefs.modelName(providerName, name);
    declared.add(key);
    String modelId = entry.reqString("modelId");
    String description = entry.optString("description");
    Object configNode = requireObject(entry, "config");
    if (skipUnknown(entry, ConfigSyncKind.MODELS, key, skipped)) {
      return null;
    }
    if (skipUnknownConfig(
        configNode,
        AgentModelConfigDTO.class,
        entry.path + ".config",
        ConfigSyncKind.MODELS,
        key,
        skipped)) {
      return null;
    }
    AgentModelEditablePropertiesDTO properties = new AgentModelEditablePropertiesDTO();
    properties.setName(name);
    properties.setModelId(modelId);
    properties.setDescription(description);
    properties.setConfig(
        yaml.convert(configNode, AgentModelConfigDTO.class, entry.path + ".config"));
    return new ModelSpec(providerName, name, properties);
  }

  private AgentSpec parseAgent(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String description = entry.optString("description");
    String systemPrompt = entry.optString("systemPrompt");
    String model = entry.reqString("model");
    ModelRef modelRef;
    try {
      modelRef = ModelRef.parse(model);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, entry.path + ".model must be providerName/modelName");
    }
    String variant = entry.optString("variant");
    Object configNode = requireObject(entry, "config");
    if (skipUnknown(entry, ConfigSyncKind.AGENTS, name, skipped)) {
      return null;
    }
    if (skipUnknownConfig(
        configNode,
        AgentDefinitionConfigDTO.class,
        entry.path + ".config",
        ConfigSyncKind.AGENTS,
        name,
        skipped)) {
      return null;
    }
    AgentDefinitionEditablePropertiesDTO properties = new AgentDefinitionEditablePropertiesDTO();
    properties.setDescription(description);
    properties.setSystemPrompt(systemPrompt);
    properties.setModel(model);
    properties.setVariant(variant);
    properties.setConfig(
        yaml.convert(configNode, AgentDefinitionConfigDTO.class, entry.path + ".config"));
    return new AgentSpec(name, modelRef.providerName(), modelRef.modelName(), properties);
  }

  private SkillSpec parseSkillPackage(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String packageName = entry.reqName("packageName");
    declared.add(packageName);
    String description = entry.optString("description");
    String repositoryUrl = entry.reqString("repositoryUrl");
    String branch = entry.reqString("branch");
    String currentCommit = entry.reqString("currentCommit");
    if (!COMMIT.matcher(currentCommit).matches()) {
      throw new AiValidationException(
          RESOURCE,
          entry.path + ".currentCommit must be a 40 or 64 characters lowercase hex object id");
    }
    if (skipUnknown(entry, ConfigSyncKind.SKILL_PACKAGES, packageName, skipped)) {
      return null;
    }
    return new SkillSpec(packageName, description, repositoryUrl, branch, currentCommit);
  }

  private EnvironmentSpec parseEnvironment(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String token = entry.reqString("registrationToken");
    if (skipUnknown(entry, ConfigSyncKind.ENVIRONMENTS, name, skipped)) {
      return null;
    }
    return new EnvironmentSpec(name, token);
  }

  private McpSpec parseMcpServer(
      Entry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String url = entry.reqString("url");
    Map<String, String> headers = entry.optStringMap("headers");
    Boolean enabled = entry.optBoolean("enabled");
    Long timeoutMillis = entry.optLong("timeoutMillis");
    if (skipUnknown(entry, ConfigSyncKind.MCP_SERVERS, name, skipped)) {
      return null;
    }
    return new McpSpec(name, url, headers, enabled, timeoutMillis);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseSettings(
      Map<String, Object> document, List<ConfigSyncSkipped> skipped) {
    String key = ConfigSyncKind.SETTINGS.wireValue();
    if (!document.containsKey(key)) {
      return null;
    }
    Object node = document.get(key);
    if (node == null) {
      throw new AiValidationException(RESOURCE, "settings must be an object");
    }
    if (!(node instanceof Map<?, ?> rawMap)) {
      throw new AiValidationException(RESOURCE, "settings must be an object");
    }
    Map<String, Object> raw = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      raw.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    // 未知叶只被移除并报告，其余受支持字段继续合入当前设置。
    List<String> removed = new ArrayList<>();
    Object cleaned =
        yaml.removeUnknownProperties(SystemSettingsSectionsDTO.class, raw, key, removed);
    for (String path : removed) {
      skipped.add(new ConfigSyncSkipped(key, "settings", "unsupported field: " + path));
    }
    return (Map<String, Object>) cleaned;
  }

  private Object requireObject(Entry entry, String key) {
    Object node = entry.node(key);
    if (!(node instanceof Map<?, ?>)) {
      throw new AiValidationException(RESOURCE, entry.path + "." + key + " must be an object");
    }
    return node;
  }

  /** 嵌套 config 含未声明字段时整体跳过条目；路径可读但不回显字段值。 */
  private boolean skipUnknownConfig(
      Object node,
      Class<?> type,
      String path,
      ConfigSyncKind kind,
      String name,
      List<ConfigSyncSkipped> skipped) {
    List<String> removed = new ArrayList<>();
    yaml.removeUnknownProperties(type, node, path, removed);
    if (removed.isEmpty()) {
      return false;
    }
    skipped.add(
        new ConfigSyncSkipped(kind.wireValue(), name, "unsupported field: " + removed.get(0)));
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
        new ConfigSyncSkipped(
            kind.wireValue(), name, "unsupported field: " + entry.path + "." + first));
    return true;
  }

  private static void requireDistinct(List<String> names, ConfigSyncKind kind) {
    Set<String> seen = new HashSet<>();
    for (String name : names) {
      if (!seen.add(name)) {
        throw new AiValidationException(
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
      Map<String, Object> settings,
      List<ConfigSyncSkipped> skipped) {}

  public record ProviderSpec(String name, AgentProviderEditablePropertiesDTO properties) {}

  public record ModelSpec(
      String providerName, String name, AgentModelEditablePropertiesDTO properties) {}

  public record AgentSpec(
      String name,
      String providerName,
      String modelName,
      AgentDefinitionEditablePropertiesDTO properties) {}

  public record SkillSpec(
      String packageName,
      String description,
      String repositoryUrl,
      String branch,
      String currentCommit) {}

  public record EnvironmentSpec(String name, String registrationToken) {}

  public record McpSpec(
      String name, String url, Map<String, String> headers, Boolean enabled, Long timeoutMillis) {}

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
        throw new AiValidationException(RESOURCE, path + "." + key + " is required");
      }
      return value;
    }

    String reqName(String key) {
      String value = reqString(key);
      if (value.isBlank() || !value.equals(value.strip())) {
        throw new AiValidationException(
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
        throw new AiValidationException(RESOURCE, path + "." + key + " must be a string");
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
        throw new AiValidationException(RESOURCE, path + "." + key + " must be an integer");
      }
      if (value instanceof BigInteger bigInteger) {
        if (bigInteger.bitLength() > 63) {
          throw new AiValidationException(RESOURCE, path + "." + key + " must be an integer");
        }
        return bigInteger.longValue();
      }
      if (value instanceof Number number) {
        return number.longValue();
      }
      throw new AiValidationException(RESOURCE, path + "." + key + " must be an integer");
    }

    Boolean optBoolean(String key) {
      consumed.add(key);
      Object value = raw.get(key);
      if (value == null) {
        return null;
      }
      if (!(value instanceof Boolean bool)) {
        throw new AiValidationException(RESOURCE, path + "." + key + " must be a boolean");
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
        throw new AiValidationException(RESOURCE, path + "." + key + " must be an object");
      }
      Map<String, String> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String mapKey)) {
          throw new AiValidationException(RESOURCE, path + "." + key + " keys must be strings");
        }
        Object mapValue = entry.getValue();
        if (mapValue != null && !(mapValue instanceof String)) {
          throw new AiValidationException(RESOURCE, path + "." + key + " values must be strings");
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
