package fun.fengwk.kkstudio.platform.configsync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderModelDiscovery;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentConfigValidator;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentReferenceResolver;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.definition.BuiltinAgentExecutionValidator;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelAbilitiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelLimitDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelVariantDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogTestFixtures;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageGitSource;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.plugin.Plugin;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.PluginRegistry;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.pluginhost.testing.TestPluginFixtures;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;

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
  static final AgentConfigCodec AGENT_CONFIG_CODEC = new AgentConfigCodec(MAPPER);
  static final ModelRuntimeConfigParser MODEL_CONFIG_PARSER = new ModelRuntimeConfigParser(MAPPER);
  static final ModelGroupConfigCodec GROUP_CONFIG_CODEC = new ModelGroupConfigCodec(MAPPER);
  static final ProviderConfigurationCodec PROVIDER_CONFIG_CODEC =
      new ProviderConfigurationCodec(MAPPER);
  private static final AgentEditableSupport EDITABLE_SUPPORT = new AgentEditableSupport(MAPPER);

  /** 真实 {@code ProviderService.validateImport}：纯静态校验，仓储与上游发现以 mock 占位。 */
  static final ProviderService PROVIDER_SERVICE =
      new ProviderService(
          mock(ProviderRepository.class),
          PROVIDER_CONFIG_CODEC,
          EDITABLE_SUPPORT,
          mock(ProviderModelDiscovery.class));

  /** 真实 {@code ModelService.validateImport}：纯静态校验，仓储以 mock 占位。 */
  static final ModelService MODEL_SERVICE =
      new ModelService(
          mock(ModelRepository.class),
          mock(ProviderRepository.class),
          EDITABLE_SUPPORT,
          MODEL_CONFIG_PARSER,
          GROUP_CONFIG_CODEC);

  /** 真实 {@code AgentService.validateImport}：纯静态校验，仓储与引用解析以 mock 占位。 */
  static final AgentService AGENT_SERVICE =
      new AgentService(
          mock(AgentRepository.class),
          mock(AgentReferenceResolver.class),
          builtinDrivers(),
          new BuiltinAgentExecutionValidator(
              mock(ModelRepository.class), mock(ModelDefaultVariantResolver.class)),
          mock(AgentConfigValidator.class),
          AGENT_CONFIG_CODEC,
          EDITABLE_SUPPORT,
          mock(PluginAgentDependencies.class),
          List.of());

  private static DriverRegistry builtinDrivers() {
    AgentDriver driver = mock(AgentDriver.class);
    when(driver.id()).thenReturn(BuiltinExecution.DRIVER);
    doAnswer(
            invocation -> {
              BuiltinExecution.parseSettings(invocation.getArgument(0));
              return null;
            })
        .when(driver)
        .validateSettings(any());
    return new DriverRegistry(driver);
  }

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
  static ConfigSyncParser parser(ConfigSyncYaml yaml, PluginRegistry pluginRegistry) {
    return new ConfigSyncParser(
        yaml,
        PROVIDER_SERVICE,
        MODEL_SERVICE,
        AGENT_SERVICE,
        SKILL_CATALOG_SERVICE,
        new SystemSettingsCodec(),
        pluginRegistry);
  }

  static ModelConfigDTO modelConfig() {
    ModelLimitDTO limit = new ModelLimitDTO();
    limit.setContext(1000);
    limit.setOutput(100);
    ModelAbilitiesDTO abilities = new ModelAbilitiesDTO();
    abilities.setTools(true);
    abilities.setReasoning(false);
    abilities.setInputModalities(List.of(ModelInputModality.TEXT));
    ModelPricingDTO pricing = new ModelPricingDTO();
    ModelRateSetDTO base = new ModelRateSetDTO();
    base.setInput(BigDecimal.ZERO);
    base.setOutput(BigDecimal.ZERO);
    base.setCacheRead(BigDecimal.ZERO);
    base.setCacheWrite(BigDecimal.ZERO);
    base.setCacheWriteByTtl(Map.of());
    base.setReasoning(null);
    pricing.setBase(base);
    pricing.setOverrides(List.of());
    pricing.setMultipliers(List.of());
    ModelVariantDTO variant = new ModelVariantDTO();
    variant.setId("default");
    variant.setPromptCacheRetention(PromptCacheRetention.NONE);
    ModelConfigDTO config = new ModelConfigDTO();
    config.setLimit(limit);
    config.setAbilities(abilities);
    config.setPricing(pricing);
    config.setDefaultVariant("default");
    config.setVariants(List.of(variant));
    return config;
  }

  static ModelConfigDTO modelConfigWithVariants(List<String> variantIds, String defaultVariant) {
    ModelConfigDTO config = modelConfig();
    List<ModelVariantDTO> variants = new ArrayList<>();
    for (String id : variantIds) {
      ModelVariantDTO variant = new ModelVariantDTO();
      variant.setId(id);
      variant.setPromptCacheRetention(PromptCacheRetention.NONE);
      variants.add(variant);
    }
    config.setVariants(variants);
    config.setDefaultVariant(defaultVariant);
    return config;
  }

  static AgentConfigDTO agentConfig(
      List<String> tools, List<SkillRefDTO> skills, List<String> subagents) {
    AgentConfigDTO config = new AgentConfigDTO();
    config.setTools(tools);
    config.setSkills(skills);
    config.setSubagents(subagents);
    return config;
  }

  static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }

  static Provider provider(String name) {
    return Provider.builder()
        .name(name)
        .providerType(ProviderType.OPENAI)
        .baseUrl("https://api.example.com")
        .credential("secret-key")
        .version(0L)
        .build();
  }

  static Model model(String providerName, String name) {
    return model(providerName, name, modelConfig());
  }

  static Model model(String providerName, String name, ModelConfigDTO config) {
    return Model.builder()
        .kind(ModelKind.MODEL)
        .providerName(providerName)
        .name(name)
        .modelId("gpt-4")
        .configJson(MODEL_CONFIG_PARSER.encode(config))
        .version(0L)
        .build();
  }

  static Agent agent(String name, String modelName, AgentConfigDTO config) {
    // 未配置的内置 Agent 没有执行选择；只有绑定了模型的 Agent 才构造 ModelSelection。
    Agent agent =
        Agent.builder()
            .name(name)
            .execution(
                modelName == null ? null : BuiltinExecution.of(new ModelSelection(modelName, null)))
            .configJson(AGENT_CONFIG_CODEC.encode(config))
            .version(0L)
            .build();
    return agent;
  }

  static SkillPackage skillPackage(String packageName, String... skillNames) {
    return gitSkillPackage(
        packageName, "https://example.com/" + packageName + ".git", "main", null, skillNames);
  }

  static SkillPackage gitSkillPackage(
      String packageName, String repositoryUrl, String branch, String token, String... skillNames) {
    List<SkillManifestEntry> skills = new ArrayList<>();
    for (String skillName : skillNames) {
      skills.add(new SkillManifestEntry(skillName, "desc " + skillName));
    }
    return new SkillPackage(
        packageName,
        "desc " + packageName,
        "1.0.0",
        UUID.randomUUID(),
        skills,
        Instant.EPOCH,
        0L,
        Instant.EPOCH,
        Instant.EPOCH,
        new SkillPackageGitSource(
            repositoryUrl, branch, token, "0".repeat(40), Instant.EPOCH, null));
  }

  static SkillPackage uploadSkillPackage(String packageName, String... skillNames) {
    List<SkillManifestEntry> skills = new ArrayList<>();
    for (String skillName : skillNames) {
      skills.add(new SkillManifestEntry(skillName, "desc " + skillName));
    }
    return new SkillPackage(
        packageName,
        "desc " + packageName,
        "1.0.0",
        UUID.randomUUID(),
        skills,
        Instant.EPOCH,
        0L,
        Instant.EPOCH,
        Instant.EPOCH,
        null);
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
        SystemSettingsTestFixture.DEFAULT, version, Instant.EPOCH, Instant.EPOCH);
  }

  static ConfigSyncSnapshot snapshot(
      List<Provider> providers,
      List<Model> models,
      List<Agent> agents,
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
      List<Provider> providers,
      List<Model> models,
      List<Agent> agents,
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

  static PluginRegistry registry(Plugin... plugins) {
    return new PluginRegistry(List.of(plugins));
  }

  /** 宿主依赖服务替身：Graph/Planner 只做纯声明解析，仓库替身不会被调用。 */
  static PluginAgentDependencies agentDependencies(Plugin... plugins) {
    return new PluginAgentDependencies(
        registry(plugins), mock(PluginConfigurationRepository.class), mock(AgentRepository.class));
  }

  static PluginAgentDependencies agentDependencies(
      AgentRepository definitionRepository, Plugin... plugins) {
    return new PluginAgentDependencies(
        registry(plugins), mock(PluginConfigurationRepository.class), definitionRepository);
  }

  /**
   * 合成插件：普通配置声明一个 Agent 名称字段，{@code requiredAgentNames} 只解析该字段。
   *
   * <p>用于证明「插件声明 Agent 依赖」的通用机制，不引用也不猜测任何具体插件的字段或实现。名称以 {@code "!"} 开头表示插件拒绝该普通配置
   * （真实的插件会纯解析内嵌结构化配置并抛输入错误），用于证明输入边界异常被收敛为固定去敏校验失败。
   */
  static final class AgentDependencyPlugin implements Plugin {

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
  static final class FailingAgentDeclarationPlugin implements Plugin {

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

  /** 读取与本 fixture 同包的测试 YAML 资源。 */
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

  /** 当前六节 settings 的完整 DTO，便于测试按需改值后重新序列化。 */
  static SystemSettingsSectionsDTO defaultSettings() {
    return new SystemSettingsCodec().toSections(SystemSettingsTestFixture.DEFAULT);
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
  static Map<String, Object> modelConfigMap(ModelConfigDTO config) {
    return new ConfigSyncYaml().toMap(config, "config");
  }

  /** 把 typed Agent config 序列化为 config sync 纯结构。 */
  static Map<String, Object> agentConfigMap(AgentConfigDTO config) {
    return new ConfigSyncYaml().toMap(config, "config");
  }

  /** 按纯结构 dump 完整 config sync 文档，避免内联长篇 YAML。 */
  static String documentYaml(Map<String, Object> document) {
    return new ConfigSyncYaml().dump(document);
  }
}
