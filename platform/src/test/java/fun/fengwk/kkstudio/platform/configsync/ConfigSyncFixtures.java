package fun.fengwk.kkstudio.platform.configsync;

import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.impl.AgentDefinitionMutationFactory;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.impl.AgentModelMutationFactory;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.service.impl.AgentProviderMutationFactory;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillTokenCipher;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.impl.SkillCatalogTestFixtures;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 配置同步单测共用 fixture：用真实 codec 生成可被生产 parser 解码的模型/Agent 配置。 */
final class ConfigSyncFixtures {

  static final ObjectMapper MAPPER = new ObjectMapper();
  static final AgentDefinitionConfigCodec AGENT_CONFIG_CODEC =
      new AgentDefinitionConfigCodec(MAPPER);
  static final AgentModelRuntimeConfigParser MODEL_CONFIG_PARSER =
      new AgentModelRuntimeConfigParser(MAPPER);
  static final AgentProviderConfigurationCodec PROVIDER_CONFIG_CODEC =
      new AgentProviderConfigurationCodec(MAPPER);
  private static final AgentEditableSupport EDITABLE_SUPPORT = new AgentEditableSupport(MAPPER);

  static final AgentProviderMutationFactory PROVIDER_MUTATION_FACTORY =
      new AgentProviderMutationFactory(EDITABLE_SUPPORT, PROVIDER_CONFIG_CODEC);
  static final AgentModelMutationFactory MODEL_MUTATION_FACTORY =
      new AgentModelMutationFactory(EDITABLE_SUPPORT, MODEL_CONFIG_PARSER);
  static final AgentDefinitionMutationFactory AGENT_MUTATION_FACTORY =
      new AgentDefinitionMutationFactory(EDITABLE_SUPPORT, AGENT_CONFIG_CODEC);

  /**
   * 真实 {@code SkillCatalogServiceImpl.validateImport}：只做纯静态校验，避免单测 mock 掉能力而要求另一份生产校验。其余仓储依赖由
   * {@link SkillCatalogTestFixtures} 以 mock 占位。
   */
  static final SkillCatalogService SKILL_CATALOG_SERVICE = SkillCatalogTestFixtures.realValidator();

  private ConfigSyncFixtures() {}

  /** 构造注入了真实校验器的 Parser，供解析期硬校验测试使用。 */
  static ConfigSyncParser parser(ConfigSyncYaml yaml) {
    return new ConfigSyncParser(
        yaml,
        PROVIDER_MUTATION_FACTORY,
        MODEL_MUTATION_FACTORY,
        AGENT_MUTATION_FACTORY,
        SKILL_CATALOG_SERVICE,
        new SystemSettingsCodec());
  }

  static AgentModelConfigDTO modelConfig() {
    AgentModelLimitDTO limit = new AgentModelLimitDTO();
    limit.setContext(1000);
    limit.setOutput(100);
    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    abilities.setTools(true);
    abilities.setReasoning(false);
    abilities.setInputModalities(List.of(AgentModelInputModality.TEXT));
    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    pricing.setCurrency("USD");
    pricing.setPricingTier("t");
    pricing.setServiceTier("s");
    pricing.setServiceTierMultiplier(BigDecimal.ONE);
    pricing.setVersion("v");
    pricing.setInputPerMillionTokens(BigDecimal.ZERO);
    pricing.setOutputPerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheReadPerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWritePerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWriteLongPerMillionTokens(BigDecimal.ZERO);
    pricing.setReasoningPerMillionTokens(BigDecimal.ZERO);
    AgentModelVariantDTO variant = new AgentModelVariantDTO();
    variant.setId("default");
    AgentModelConfigDTO config = new AgentModelConfigDTO();
    config.setLimit(limit);
    config.setAbilities(abilities);
    config.setPricing(pricing);
    config.setDefaultVariant("default");
    config.setVariants(List.of(variant));
    return config;
  }

  static AgentModelConfigDTO modelConfigWithVariants(
      List<String> variantIds, String defaultVariant) {
    AgentModelConfigDTO config = modelConfig();
    List<AgentModelVariantDTO> variants = new ArrayList<>();
    for (String id : variantIds) {
      AgentModelVariantDTO variant = new AgentModelVariantDTO();
      variant.setId(id);
      variants.add(variant);
    }
    config.setVariants(variants);
    config.setDefaultVariant(defaultVariant);
    return config;
  }

  static AgentDefinitionConfigDTO agentConfig(
      List<String> tools, List<SkillRefDTO> skills, List<String> subagents) {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(tools);
    config.setSkills(skills);
    config.setSubagents(subagents);
    config.setInheritParentEnvironment(Boolean.TRUE);
    return config;
  }

  static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }

  static AgentProvider provider(String name) {
    AgentProvider provider = new AgentProvider();
    provider.setName(name);
    provider.setProviderType(ProviderType.OPENAI);
    provider.setBaseUrl("https://api.example.com");
    provider.setCredential("secret-key");
    provider.setVersion(0L);
    return provider;
  }

  static AgentModel model(String providerName, String name) {
    return model(providerName, name, modelConfig());
  }

  static AgentModel model(String providerName, String name, AgentModelConfigDTO config) {
    AgentModel model = new AgentModel();
    model.setProviderName(providerName);
    model.setName(name);
    model.setModelId("gpt-4");
    model.setConfigJson(MODEL_CONFIG_PARSER.encode(config));
    model.setVersion(0L);
    return model;
  }

  static AgentDefinition agent(
      String name, String providerName, String modelName, AgentDefinitionConfigDTO config) {
    AgentDefinition agent = new AgentDefinition();
    agent.setName(name);
    agent.setModelProviderName(providerName);
    agent.setModelName(modelName);
    agent.setConfigJson(AGENT_CONFIG_CODEC.encode(config));
    agent.setVersion(0L);
    return agent;
  }

  static SkillPackage skillPackage(String packageName, String... skillNames) {
    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName(packageName);
    pkg.setRepositoryUrl("https://example.com/" + packageName + ".git");
    pkg.setBranch("main");
    pkg.setCurrentCommit("0".repeat(40));
    List<SkillManifestEntry> skills = new ArrayList<>();
    for (String skillName : skillNames) {
      skills.add(new SkillManifestEntry(skillName, "desc " + skillName));
    }
    pkg.setSkills(skills);
    return pkg;
  }

  /** 以一次性 owner-only 主密钥构建真实 {@link SkillTokenCipher}，用于凭据导出等真实加解密断言。 */
  static SkillTokenCipher tokenCipher() {
    try {
      Path keyFile = Files.createTempFile("kkstudio-configsync-token", ".key");
      Files.write(keyFile, "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
      Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
      return new SkillTokenCipher(
          new PluginCredentialCodec(),
          new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString()));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  static Environment environment(String name) {
    Environment environment = new Environment();
    environment.setId(UUID.randomUUID());
    environment.setName(name);
    environment.setRegistrationToken("token-" + name);
    return environment;
  }

  static McpServer mcpServer(String name, boolean enabled) {
    McpServer server = new McpServer();
    server.setName(name);
    server.setUrl("https://mcp.example.com/" + name);
    server.setEnabled(enabled);
    server.setTimeoutMillis(30_000L);
    server.setDiscoveryStatus(McpDiscoveryStatus.AVAILABLE);
    return server;
  }

  static McpTool mcpTool(String name, String serverName) {
    McpTool tool = new McpTool();
    tool.setName(name);
    tool.setServerName(serverName);
    tool.setSourceName(name);
    return tool;
  }

  static SystemSettingsRepository.SystemSettingsRecord settings(long version) {
    return new SystemSettingsRepository.SystemSettingsRecord(
        SystemSettings.DEFAULT, version, Instant.EPOCH, Instant.EPOCH);
  }

  static ConfigSyncSnapshot snapshot(
      List<AgentProvider> providers,
      List<AgentModel> models,
      List<AgentDefinition> agents,
      List<SkillPackage> skillPackages,
      List<Environment> environments,
      List<McpServer> mcpServers,
      List<McpTool> mcpTools) {
    return new ConfigSyncSnapshot(
        providers, models, agents, skillPackages, environments, mcpServers, mcpTools, settings(1L));
  }

  static List<String> listOf(String... values) {
    return new ArrayList<>(Arrays.asList(values));
  }

  /** 读取与本 fixture 同包的测试 YAML 资源；长 YAML 不再以字符串拼接内联。 */
  static String resourceYaml(String name) {
    try (InputStream in = ConfigSyncFixtures.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing test yaml resource: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  /** 通用「Provider p + Model p/m」文档基座；具体测试再追加 mcpServers/agents 等段落。 */
  static String providerAndModelYaml() {
    return "providers:\n  - name: p\n    providerType: openai\n" + resourceYaml("model-entry.yaml");
  }

  /** 当前七节 settings 的完整 DTO，便于测试按需改值后重新序列化。 */
  static SystemSettingsSectionsDTO defaultSettings() {
    return new SystemSettingsCodec().toSections(SystemSettings.DEFAULT);
  }

  /** 把完整 sections 序列化为 config sync YAML 文本；settings 出现时必须完整。 */
  static String settingsYaml(SystemSettingsSectionsDTO sections) {
    ConfigSyncYaml yaml = new ConfigSyncYaml();
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("settings", yaml.toMap(sections, "settings"));
    return yaml.dump(document);
  }

  static Map<String, Object> mapOf(Object... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      map.put((String) keyValues[index], keyValues[index + 1]);
    }
    return map;
  }

  /** 把 typed Agent Model config 序列化为 config sync 纯结构，便于构造文件内 Model 条目。 */
  static Map<String, Object> modelConfigMap(AgentModelConfigDTO config) {
    return new ConfigSyncYaml().toMap(config, "config");
  }

  /** 把 typed Agent config 序列化为 config sync 纯结构。 */
  static Map<String, Object> agentConfigMap(AgentDefinitionConfigDTO config) {
    return new ConfigSyncYaml().toMap(config, "config");
  }

  /** 按纯结构 dump 完整 config sync 文档，避免内联长篇 YAML。 */
  static String documentYaml(Map<String, Object> document) {
    return new ConfigSyncYaml().dump(document);
  }
}
