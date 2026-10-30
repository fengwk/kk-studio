package fun.fengwk.kkstudio.platform.configsync;

import static org.mockito.Mockito.mock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.provider.ProviderModelDiscovery;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionConfigValidator;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionReferenceResolver;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogTestFixtures;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.pluginhost.testing.TestPluginFixtures;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 配置同步单测共用 fixture：用真实 codec 生成可被生产 parser 解码的模型/Agent 配置。 */
final class ConfigSyncFixtures {

  static final ObjectMapper MAPPER = JsonMapper.builder().build();
  static final AgentDefinitionConfigCodec AGENT_CONFIG_CODEC =
      new AgentDefinitionConfigCodec(MAPPER);
  static final AgentModelRuntimeConfigParser MODEL_CONFIG_PARSER =
      new AgentModelRuntimeConfigParser(MAPPER);
  static final AgentModelGroupConfigCodec GROUP_CONFIG_CODEC =
      new AgentModelGroupConfigCodec(MAPPER);
  static final AgentProviderConfigurationCodec PROVIDER_CONFIG_CODEC =
      new AgentProviderConfigurationCodec(MAPPER);
  private static final AgentEditableSupport EDITABLE_SUPPORT = new AgentEditableSupport(MAPPER);

  /** 真实 {@code AgentProviderService.validateImport}：纯静态校验，仓储与上游发现以 mock 占位。 */
  static final AgentProviderService PROVIDER_SERVICE =
      new AgentProviderService(
          mock(AgentProviderRepository.class),
          PROVIDER_CONFIG_CODEC,
          EDITABLE_SUPPORT,
          mock(ProviderModelDiscovery.class));

  /** 真实 {@code AgentModelService.validateImport}：纯静态校验，仓储以 mock 占位。 */
  static final AgentModelService MODEL_SERVICE =
      new AgentModelService(
          mock(AgentModelRepository.class),
          mock(AgentProviderRepository.class),
          EDITABLE_SUPPORT,
          MODEL_CONFIG_PARSER,
          GROUP_CONFIG_CODEC);

  /** 真实 {@code AgentDefinitionService.validateImport}：纯静态校验，仓储与引用解析以 mock 占位。 */
  static final AgentDefinitionService AGENT_SERVICE =
      new AgentDefinitionService(
          mock(AgentDefinitionRepository.class),
          mock(AgentDefinitionReferenceResolver.class),
          mock(AgentModelDefaultVariantResolver.class),
          mock(AgentDefinitionConfigValidator.class),
          AGENT_CONFIG_CODEC,
          EDITABLE_SUPPORT,
          mock(PluginAgentDependencies.class));

  /**
   * 真实 {@code SkillCatalogService.validateImport}：只做纯静态校验，避免单测 mock 掉能力而要求另一份生产校验。其余仓储依赖由 {@link
   * SkillCatalogTestFixtures} 以 mock 占位。
   */
  static final SkillCatalogService SKILL_CATALOG_SERVICE = SkillCatalogTestFixtures.realValidator();

  private ConfigSyncFixtures() {}

  /** 构造注入了真实校验器的 Parser，供解析期硬校验测试使用；无已安装插件。 */
  static ConfigSyncParser parser(ConfigSyncYaml yaml) {
    return parser(yaml, registry());
  }

  /** 同上，但带指定安装目录，用于插件条目的解析期校验。 */
  static ConfigSyncParser parser(ConfigSyncYaml yaml, StudioPluginRegistry pluginRegistry) {
    return new ConfigSyncParser(
        yaml,
        PROVIDER_SERVICE,
        MODEL_SERVICE,
        AGENT_SERVICE,
        SKILL_CATALOG_SERVICE,
        new SystemSettingsCodec(),
        pluginRegistry);
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
    AgentModelRateSetDTO base = new AgentModelRateSetDTO();
    base.setInput(BigDecimal.ZERO);
    base.setOutput(BigDecimal.ZERO);
    base.setCacheRead(BigDecimal.ZERO);
    base.setCacheWrite(BigDecimal.ZERO);
    base.setCacheWriteByTtl(Map.of());
    base.setReasoning(null);
    pricing.setBase(base);
    pricing.setOverrides(List.of());
    pricing.setMultipliers(List.of());
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
    return AgentProvider.builder()
        .name(name)
        .providerType(ProviderType.OPENAI)
        .baseUrl("https://api.example.com")
        .credential("secret-key")
        .version(0L)
        .build();
  }

  static AgentModel model(String providerName, String name) {
    return model(providerName, name, modelConfig());
  }

  static AgentModel model(String providerName, String name, AgentModelConfigDTO config) {
    return AgentModel.builder()
        .kind(AgentModelKind.MODEL)
        .providerName(providerName)
        .name(name)
        .modelId("gpt-4")
        .configJson(MODEL_CONFIG_PARSER.encode(config))
        .version(0L)
        .build();
  }

  static AgentDefinition agent(String name, String modelName, AgentDefinitionConfigDTO config) {
    AgentDefinition agent =
        AgentDefinition.builder()
            .name(name)
            .modelName(modelName)
            .configJson(AGENT_CONFIG_CODEC.encode(config))
            .version(0L)
            .build();
    return agent;
  }

  static SkillPackage skillPackage(String packageName, String... skillNames) {
    List<SkillManifestEntry> skills = new ArrayList<>();
    for (String skillName : skillNames) {
      skills.add(new SkillManifestEntry(skillName, "desc " + skillName));
    }
    return SkillPackage.builder()
        .packageName(packageName)
        .repositoryUrl("https://example.com/" + packageName + ".git")
        .branch("main")
        .currentCommit("0".repeat(40))
        .skills(skills)
        .build();
  }

  static Environment environment(String name) {
    Environment environment = new Environment();
    environment.setId(UUID.randomUUID());
    environment.setName(name);
    environment.setRegistrationTokenHash(RegistrationTokens.hash("token-" + name));
    return environment;
  }

  static McpServer mcpServer(String name, boolean enabled) {
    McpServer server =
        McpServer.builder()
            .name(name)
            .url("https://mcp.example.com/" + name)
            .enabled(enabled)
            .timeoutMillis(30_000L)
            .discoveryStatus(McpDiscoveryStatus.AVAILABLE)
            .build();
    return server;
  }

  static McpTool mcpTool(String name, String serverName) {
    McpTool tool = McpTool.builder().name(name).serverName(serverName).sourceName(name).build();
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
    return snapshotWithPlugins(
        providers,
        models,
        agents,
        Set.of(),
        skillPackages,
        environments,
        mcpServers,
        mcpTools,
        List.of());
  }

  static ConfigSyncSnapshot snapshotWithPlugins(
      List<AgentProvider> providers,
      List<AgentModel> models,
      List<AgentDefinition> agents,
      Set<String> builtinAgentNames,
      List<SkillPackage> skillPackages,
      List<Environment> environments,
      List<McpServer> mcpServers,
      List<McpTool> mcpTools,
      List<PluginConfigurationRow> plugins) {
    return new ConfigSyncSnapshot(
        providers,
        models,
        agents,
        builtinAgentNames,
        skillPackages,
        environments,
        mcpServers,
        mcpTools,
        settings(1L),
        plugins);
  }

  static PluginConfigurationRow pluginRow(String pluginId, long revision, String valuesJson) {
    return new PluginConfigurationRow(pluginId, revision, valuesJson);
  }

  static StudioPluginRegistry registry(StudioPlugin... plugins) {
    return new StudioPluginRegistry(List.of(plugins));
  }

  /** 宿主依赖服务替身：Graph/Planner 只做纯声明解析，仓库替身不会被调用。 */
  static PluginAgentDependencies agentDependencies(StudioPlugin... plugins) {
    return new PluginAgentDependencies(
        registry(plugins),
        mock(PluginConfigurationRepository.class),
        mock(AgentDefinitionRepository.class));
  }

  static PluginAgentDependencies agentDependencies(
      AgentDefinitionRepository definitionRepository, StudioPlugin... plugins) {
    return new PluginAgentDependencies(
        registry(plugins), mock(PluginConfigurationRepository.class), definitionRepository);
  }

  /**
   * 合成插件：普通配置声明一个 Agent 名称字段，{@code requiredAgentNames} 只解析该字段。
   *
   * <p>用于证明「插件声明 Agent 依赖」的通用机制，不引用也不猜测任何具体插件的字段或实现。名称以 {@code "!"} 开头表示插件拒绝该普通配置
   * （真实的插件会纯解析内嵌结构化配置并抛输入错误），用于证明输入边界异常被收敛为固定去敏校验失败。
   */
  static final class AgentDependencyPlugin implements StudioPlugin {

    static final String AGENT_FIELD = "promptAgentName";

    private static final PluginConfigSchema SCHEMA =
        new PluginConfigSchema(
            new InputSchema(
                "config",
                Map.of(AGENT_FIELD, new StringSchema("prompt agent name")),
                Set.of(),
                false),
            "{}",
            Map.of());

    private final String pluginId;

    AgentDependencyPlugin(String pluginId) {
      this.pluginId = pluginId;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(pluginId, "Synthetic " + pluginId, "1.0.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }

    @Override
    public Set<String> requiredAgentNames(PluginConfig config) {
      JsonNode node = JsonValues.readTree(config.valuesJson());
      JsonNode agent = node.get(AGENT_FIELD);
      if (agent == null || !agent.isString() || agent.stringValue().isBlank()) {
        return Set.of();
      }
      String name = agent.stringValue();
      if (name.startsWith("!")) {
        throw new IllegalArgumentException("invalid agent name: " + name);
      }
      return Set.of(name);
    }
  }

  /**
   * 合成插件：声明解析抛非输入边界异常。
   *
   * <p>用于证明未知运行时错误绝不被伪装成校验失败或 skip，而是照常上抛。
   */
  static final class FailingAgentDeclarationPlugin implements StudioPlugin {

    private final String pluginId;

    FailingAgentDeclarationPlugin(String pluginId) {
      this.pluginId = pluginId;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(pluginId, "Failing " + pluginId, "1.0.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return AgentDependencyPlugin.SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }

    @Override
    public Set<String> requiredAgentNames(PluginConfig config) {
      throw new IllegalStateException("declaration failure for " + pluginId);
    }
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

  /** 通用「Provider p + Model m」文档基座；具体测试再追加 mcpServers/agents 等段落。 */
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
