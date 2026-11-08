package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServer;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageGitSource;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncRef;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;

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

  private final ProviderConfigurationCodec providerConfigurationCodec;
  private final ModelRuntimeConfigParser modelConfigParser;
  private final ModelGroupConfigCodec modelGroupConfigCodec;
  private final AgentConfigCodec agentConfigCodec;
  private final SystemSettingsCodec systemSettingsCodec;
  private final ConfigSyncYaml yaml;

  /** 按已补齐闭包的引用集合构建 YAML 文本。 */
  public String export(ConfigSyncSnapshot snapshot, List<ConfigSyncRef> refs) {
    Set<ConfigSyncRef> selected = new LinkedHashSet<>(refs);
    Map<String, Object> document = new LinkedHashMap<>();

    List<Object> providers = new ArrayList<>();
    for (Provider provider : snapshot.providers()) {
      if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.PROVIDERS, provider.name()))) {
        providers.add(exportProvider(provider));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.PROVIDERS, providers);

    List<Object> models = new ArrayList<>();
    for (Model model : snapshot.models()) {
      if (selected.contains(ConfigSyncRefs.model(model.name()))) {
        models.add(exportModel(model));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.MODELS, models);

    List<Object> agents = new ArrayList<>();
    for (Agent agent : snapshot.agents()) {
      if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.AGENTS, agent.name()))) {
        agents.add(exportAgent(agent));
      }
    }
    putIfNotEmpty(document, ConfigSyncKind.AGENTS, agents);

    List<Object> skills = new ArrayList<>();
    for (SkillPackage pkg : snapshot.skillPackages()) {
      if (pkg.gitSource() != null
          && selected.contains(
              ConfigSyncRefs.ref(ConfigSyncKind.SKILL_PACKAGES, pkg.packageName()))) {
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
      if (selected.contains(ConfigSyncRefs.ref(ConfigSyncKind.MCP_SERVERS, server.name()))) {
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

  private Map<String, Object> exportProvider(Provider provider) {
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

  private Map<String, Object> exportModel(Model model) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("kind", model.kind().name());
    map.put("name", model.name());
    putIfNotNull(map, "description", model.description());
    if (model.kind() == ModelKind.MODEL) {
      map.put("providerName", model.providerName());
      map.put("modelId", model.modelId());
      map.put("config", yaml.toMap(modelConfigParser.decode(model.configJson()), "model.config"));
    } else {
      map.put(
          "group",
          yaml.toMap(modelGroupConfigCodec.decode(model.groupConfigJson()), "model.group"));
    }
    return map;
  }

  private Map<String, Object> exportAgent(Agent agent) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", agent.name());
    putIfNotNull(map, "description", agent.description());
    putIfNotNull(map, "systemPrompt", agent.systemPrompt());
    ExecutionSelection execution = agent.execution();
    if (execution == null) {
      throw new IllegalStateException("agent execution is missing: " + agent.name());
    }
    Map<String, Object> executionMap = new LinkedHashMap<>();
    executionMap.put("driver", execution.driver());
    executionMap.put(
        "settings",
        StrictJson.standard().read(execution.settingsJson(), Map.class, "execution.settings"));
    map.put("execution", executionMap);
    map.put("config", yaml.toMap(agentConfigCodec.decode(agent.configJson()), "agent.config"));
    return map;
  }

  private Map<String, Object> exportSkillPackage(SkillPackage pkg) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("packageName", pkg.packageName());
    putIfNotNull(map, "description", pkg.description());
    SkillPackageGitSource gitSource = pkg.gitSource();
    map.put("repositoryUrl", gitSource.repositoryUrl());
    map.put("branch", gitSource.branch());
    // 与 provider credential 一致：按既有“凭据原值导出”策略，令牌不隐式遗失。
    if (gitSource.token() != null) {
      map.put("token", gitSource.token());
    }
    return map;
  }

  private Map<String, Object> exportEnvironment(Environment env) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", env.getName());
    putIfNotNull(map, "note", env.getNote());
    map.put("daemonConfig", yaml.toMap(env.getDaemonConfig(), "daemonConfig"));
    // 只导出凭证摘要：导入后已安装的 Daemon 继续用原明文握手，导出文件本身不能冒充 Daemon。
    map.put("registrationTokenHash", env.getRegistrationTokenHash());
    return map;
  }

  private Map<String, Object> exportMcpServer(McpServer server) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", server.name());
    map.put("url", server.url());
    if (server.headers() != null && !server.headers().isEmpty()) {
      map.put("headers", new LinkedHashMap<>(server.headers()));
    }
    map.put("enabled", server.enabled());
    map.put("timeoutMillis", server.timeoutMillis());
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
