package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一次快照按引用选择导出为纯结构 YAML 文档。
 *
 * <p>只导出可编辑业务字段：不含 UUID、时间戳、乐观锁版本、Provider generation、连接/发现状态等运行态事实；凭据、registrationToken 与 MCP
 * headers 按原值导出，{@code ${VAR}} 不解析。插件只导出安装身份与普通值文本，其秘密一律不进入文件。
 */
@AllArgsConstructor
@Component
public final class ConfigSyncExporter {

  private final AgentProviderConfigurationCodec providerConfigurationCodec;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final SystemSettingsCodec systemSettingsCodec;
  private final ConfigSyncYaml yaml;

  /** 按已补齐闭包的引用集合构建 YAML 文本。 */
  public String export(ConfigSyncSnapshot snapshot, List<ConfigSyncRef> refs) {
    Set<ConfigSyncRef> selected = new LinkedHashSet<>(refs);
    Map<String, Object> document = new LinkedHashMap<>();

    List<Object> providers = new ArrayList<>();
    for (AgentProvider provider : snapshot.providers()) {
      if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.PROVIDERS, provider.name()))) {
        providers.add(exportProvider(provider));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.PROVIDERS, providers);

    List<Object> models = new ArrayList<>();
    for (AgentModel model : snapshot.models()) {
      if (selected.contains(ConfigSyncRefs.model(model.getProviderName(), model.getName()))) {
        models.add(exportModel(model));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.MODELS, models);

    List<Object> agents = new ArrayList<>();
    for (AgentDefinition agent : snapshot.agents()) {
      if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.AGENTS, agent.getName()))) {
        agents.add(exportAgent(agent));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.AGENTS, agents);

    List<Object> skills = new ArrayList<>();
    for (SkillPackage pkg : snapshot.skillPackages()) {
      if (selected.contains(
          ConfigSyncRefs.ref(ConfigSyncKind.SKILL_PACKAGES, pkg.getPackageName()))) {
        skills.add(exportSkillPackage(pkg));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.SKILL_PACKAGES, skills);

    List<Object> environments = new ArrayList<>();
    for (Environment env : snapshot.environments()) {
      if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.ENVIRONMENTS, env.getName()))) {
        environments.add(exportEnvironment(env));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.ENVIRONMENTS, environments);

    List<Object> mcpServers = new ArrayList<>();
    for (McpServer server : snapshot.mcpServers()) {
      if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.MCP_SERVERS, server.getName()))) {
        mcpServers.add(exportMcpServer(server));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.MCP_SERVERS, mcpServers);

    if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.SETTINGS, "settings"))) {
      SystemSettingsSectionsDTO sections =
          systemSettingsCodec.toSections(snapshot.settings().settings());
      document.put(ConfigSyncKind.SETTINGS.wireValue(), yaml.toMap(sections, "settings"));
    }

    List<Object> plugins = new ArrayList<>();
    for (PluginConfigurationRow plugin : snapshot.plugins()) {
      if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.PLUGINS, plugin.pluginId()))) {
        plugins.add(exportPlugin(plugin));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.PLUGINS, plugins);

    return yaml.dump(document);
  }

  /**
   * 插件条目只带安装身份与普通值文本。
   *
   * <p>秘密、受保护 instanceId 与凭据状态都不属于普通值，绝不进入文件。
   */
  private Map<String, Object> exportPlugin(PluginConfigurationRow plugin) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("pluginId", plugin.pluginId());
    map.put("valuesJson", plugin.valuesJson());
    return map;
  }

  private Map<String, Object> exportProvider(AgentProvider provider) {
    ModelCallTimeoutPolicy timeout =
        providerConfigurationCodec.readTimeoutPolicy(provider.configJson());
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", provider.name());
    putIfNotNull(map, "description", provider.description());
    map.put("providerType", provider.providerType().wireValue());
    putIfNotNull(map, "baseUrl", provider.baseUrl());
    putIfNotNull(map, "credential", provider.credential());
    map.put("modelCallTimeoutMillis", timeout.modelCallTimeout().toMillis());
    map.put("modelCallIdleTimeoutMillis", timeout.modelCallIdleTimeout().toMillis());
    putIfNotNull(
        map,
        "modelHttpRetryStatusCodes",
        providerConfigurationCodec.readHttpRetryStatusCodes(provider.configJson()));
    return map;
  }

  private Map<String, Object> exportModel(AgentModel model) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("providerName", model.getProviderName());
    map.put("name", model.getName());
    map.put("modelId", model.getModelId());
    putIfNotNull(map, "description", model.getDescription());
    map.put("config", yaml.toMap(modelConfigParser.decode(model.getConfigJson()), "model.config"));
    return map;
  }

  private Map<String, Object> exportAgent(AgentDefinition agent) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", agent.getName());
    putIfNotNull(map, "description", agent.getDescription());
    putIfNotNull(map, "systemPrompt", agent.getSystemPrompt());
    map.put("model", ConfigSyncRefs.modelName(agent.getModelProviderName(), agent.getModelName()));
    putIfNotNull(map, "variant", agent.getVariant());
    map.put("config", yaml.toMap(agentConfigCodec.decode(agent.getConfigJson()), "agent.config"));
    return map;
  }

  private Map<String, Object> exportSkillPackage(SkillPackage pkg) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("packageName", pkg.getPackageName());
    putIfNotNull(map, "description", pkg.getDescription());
    map.put("repositoryUrl", pkg.getRepositoryUrl());
    map.put("branch", pkg.getBranch());
    map.put("currentCommit", pkg.getCurrentCommit());
    // 与 provider credential 一致：按既有“凭据原值导出”策略，令牌不隐式遗失。
    if (pkg.getToken() != null) {
      map.put("token", pkg.getToken());
    }
    return map;
  }

  private Map<String, Object> exportEnvironment(Environment env) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", env.getName());
    // 只导出凭证摘要：导入后已安装的 Daemon 继续用原明文握手，导出文件本身不能冒充 Daemon。
    map.put("registrationTokenHash", env.getRegistrationTokenHash());
    if (env.getInstallConfig() != null) {
      map.put("installConfig", yaml.toMap(env.getInstallConfig(), "installConfig"));
    }
    return map;
  }

  private Map<String, Object> exportMcpServer(McpServer server) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", server.getName());
    map.put("url", server.getUrl());
    if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
      map.put("headers", new LinkedHashMap<>(server.getHeaders()));
    }
    map.put("enabled", server.isEnabled());
    map.put("timeoutMillis", server.getTimeoutMillis());
    return map;
  }

  private static void putIfNotEmpty(
      Map<String, Object> document, ConfigSyncKind kind, List<Object> entries) {
    if (!entries.isEmpty()) {
      document.put(kind.wireValue(), entries);
    }
  }

  private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
