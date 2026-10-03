package fun.fengwk.kkstudio.platform.configsync;

import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpDiscoveryStatus;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.configuration.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

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

  private ConfigSyncFixtures() {}

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
    AgentModel model = new AgentModel();
    model.setProviderName(providerName);
    model.setName(name);
    model.setModelId("gpt-4");
    model.setConfigJson(MODEL_CONFIG_PARSER.encode(modelConfig()));
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

  static Map<String, Object> mapOf(Object... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      map.put((String) keyValues[index], keyValues[index + 1]);
    }
    return map;
  }
}
