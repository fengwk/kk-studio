package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.RESOURCE;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.reportUnknownConfig;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.requireObject;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.skipUnknown;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.validateEntry;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentEditablePropertiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.AgentSpec;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.ModelSpec;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser.ProviderSpec;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinConfigSyncAdapter;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider / Model / Agent 条目的读取与预校验。
 *
 * <p>三者都可能携带嵌套的强类型配置块（Model 的 {@code config}/{@code group}、Agent 的 {@code execution}/{@code
 * config}）：嵌套未声明字段与条目级未声明字段一样按条目 skip，但嵌套缺必填或非法值必须先被写入路径的校验器拒绝，不能被 skip 掩盖。
 */
final class ConfigSyncCatalogEntries {

  private final ConfigSyncYaml yaml;
  private final ProviderService providerService;
  private final ModelService modelService;
  private final AgentService agentService;

  ConfigSyncCatalogEntries(
      ConfigSyncYaml yaml,
      ProviderService providerService,
      ModelService modelService,
      AgentService agentService) {
    this.yaml = yaml;
    this.providerService = providerService;
    this.modelService = modelService;
    this.agentService = agentService;
  }

  ProviderSpec parseProvider(
      ConfigSyncEntry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String providerType = entry.reqString("providerType");
    if (providerType.isBlank()) {
      throw DomainException.validation(RESOURCE, entry.path() + ".providerType is required");
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
    properties.setCredential(providerCredential(entry.optString("credential")));
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

  /** 文件中的凭据是普通文本：空白表示无凭据，非空值按 SET 事实校验，非法值不回显。 */
  private static SecretUpdate providerCredential(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return SecretUpdate.set(value);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, "provider credential is invalid");
    }
  }

  ModelSpec parseModel(
      ConfigSyncEntry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String kindText = entry.reqString("kind");
    ModelKind kind;
    try {
      kind = ModelKind.valueOf(kindText);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, entry.path() + ".kind must be MODEL or GROUP");
    }
    String description = entry.optString("description");
    List<String> removed = new ArrayList<>();
    ModelCreateDTO properties;
    if (kind == ModelKind.MODEL) {
      Object configNode = requireObject(entry, "config");
      Object cleanedConfig =
          yaml.removeUnknownProperties(
              ModelConfigDTO.class, configNode, entry.path() + ".config", removed);
      SingleModelCreateDTO single = new SingleModelCreateDTO();
      single.setName(name);
      single.setDescription(description);
      single.setProviderName(entry.reqName("providerName"));
      single.setModelId(entry.reqString("modelId"));
      single.setConfig(yaml.convert(cleanedConfig, ModelConfigDTO.class, entry.path() + ".config"));
      properties = single;
    } else {
      Object groupNode = requireObject(entry, "group");
      Object cleanedGroup =
          yaml.removeUnknownProperties(
              ModelGroupConfigDTO.class, groupNode, entry.path() + ".group", removed);
      ModelGroupCreateDTO group = new ModelGroupCreateDTO();
      group.setName(name);
      group.setDescription(description);
      group.setGroup(
          yaml.convert(cleanedGroup, ModelGroupConfigDTO.class, entry.path() + ".group"));
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

  AgentSpec parseAgent(
      ConfigSyncEntry entry, List<ConfigSyncSkipped> skipped, List<String> declared) {
    String name = entry.reqName("name");
    declared.add(name);
    String description = entry.optString("description");
    String systemPrompt = entry.optString("systemPrompt");
    Object executionNode = requireObject(entry, "execution");
    Map<?, ?> executionMap = (Map<?, ?>) executionNode;
    if (!executionMap.keySet().equals(Set.of("driver", "settings"))) {
      throw DomainException.validation(RESOURCE, entry.path() + ".execution has invalid fields");
    }
    Object configNode = requireObject(entry, "config");
    List<String> removed = new ArrayList<>();
    Object cleanedConfig =
        yaml.removeUnknownProperties(
            AgentConfigDTO.class, configNode, entry.path() + ".config", removed);
    AgentEditablePropertiesDTO properties = new AgentEditablePropertiesDTO();
    properties.setDescription(description);
    properties.setSystemPrompt(systemPrompt);
    properties.setExecution(
        yaml.convert(
            executionNode, HarnessExecutionSelectionDTO.class, entry.path() + ".execution"));
    properties.setConfig(
        yaml.convert(cleanedConfig, AgentConfigDTO.class, entry.path() + ".config"));
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
}
