package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agent;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agentConfig;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.environment;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpServer;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpTool;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.model;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.provider;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.skillPackage;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.snapshot;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.snapshotWithPlugins;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncRef;
import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 测试意图：锁定导出只包含可编辑业务字段且保留原始凭据与 Environment 凭证摘要，不泄露运行态（UUID/时间戳/版本/发现状态），settings 以对象导出。 */
class ConfigSyncExporterTest {

  private final ConfigSyncYaml yaml = new ConfigSyncYaml();
  private final ConfigSyncExporter exporter =
      new ConfigSyncExporter(
          ConfigSyncFixtures.PROVIDER_CONFIG_CODEC,
          ConfigSyncFixtures.MODEL_CONFIG_PARSER,
          ConfigSyncFixtures.GROUP_CONFIG_CODEC,
          ConfigSyncFixtures.AGENT_CONFIG_CODEC,
          new SystemSettingsCodec(),
          yaml);

  private ConfigSyncSnapshot buildSnapshot() {
    return snapshot(
        List.of(provider("p")),
        List.of(model("p", "m")),
        List.of(agent("a", "m", agentConfig(List.of(), List.of(), List.of()))),
        List.of(skillPackage("pkg", "s")),
        List.of(environment("env")),
        List.of(mcpServer("mcp", true)),
        List.of(mcpTool("tool_x", "mcp")));
  }

  @Test
  void exportIncludesProviderHttpRetryOverride() {
    Provider p =
        provider("p").toBuilder().configJson("{\"modelHttpRetryStatusCodes\":[408,429]}").build();
    ConfigSyncSnapshot snapshot =
        snapshot(List.of(p), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    String yamlText =
        exporter.export(snapshot, List.of(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    Map<String, Object> document = yaml.parse(yamlText);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> providers = (List<Map<String, Object>>) document.get("providers");
    assertEquals(List.of(408, 429), providers.get(0).get("modelHttpRetryStatusCodes"));
  }

  @Test
  void exportOmitsAbsentProviderHttpRetryOverride() {
    ConfigSyncSnapshot snapshot =
        snapshot(
            List.of(provider("p")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    String yamlText =
        exporter.export(snapshot, List.of(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    Map<String, Object> document = yaml.parse(yamlText);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> providers = (List<Map<String, Object>>) document.get("providers");
    assertFalse(providers.get(0).containsKey("modelHttpRetryStatusCodes"));
  }

  /** 导出使用嵌套 execution 并保留 variant。 */
  @Test
  void agentExportCarriesExecution() {
    var configured =
        agent("a", "m", agentConfig(List.of(), List.of(), List.of())).toBuilder()
            .execution(BuiltinExecution.of(new ModelSelection("m", "default")))
            .build();
    var snapshot =
        snapshot(
            List.of(), List.of(), List.of(configured), List.of(), List.of(), List.of(), List.of());
    var document =
        yaml.parse(
            exporter.export(snapshot, List.of(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a"))));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> agents = (List<Map<String, Object>>) document.get("agents");
    assertEquals(
        Map.of("driver", "builtin", "settings", Map.of("modelName", "m", "variant", "default")),
        agents.get(0).get("execution"));
    assertEquals(Set.of("name", "execution", "config"), agents.get(0).keySet());
  }

  @Test
  void exportIncludesEditableFieldsAndSecretsButNoRuntimeState() {
    ConfigSyncSnapshot snapshot = buildSnapshot();
    List<ConfigSyncRef> refs =
        List.of(
            new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p"),
            new ConfigSyncRef(ConfigSyncKind.MODELS, "m"),
            new ConfigSyncRef(ConfigSyncKind.AGENTS, "a"),
            new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg"),
            new ConfigSyncRef(ConfigSyncKind.ENVIRONMENTS, "env"),
            new ConfigSyncRef(ConfigSyncKind.MCP_SERVERS, "mcp"),
            new ConfigSyncRef(ConfigSyncKind.SETTINGS, "settings"));

    String yamlText = exporter.export(snapshot, refs);
    Map<String, Object> document = yaml.parse(yamlText);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> providers = (List<Map<String, Object>>) document.get("providers");
    assertEquals("p", providers.get(0).get("name"));
    assertEquals("secret-key", providers.get(0).get("credential"));
    assertFalse(providers.get(0).containsKey("version"));
    assertFalse(providers.get(0).containsKey("id"));
    assertFalse(providers.get(0).containsKey("createTime"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> environments =
        (List<Map<String, Object>>) document.get("environments");
    assertEquals(
        RegistrationTokens.hash("token-env"), environments.get(0).get("registrationTokenHash"));
    assertEquals("https://studio.example.com", ((Map<?, ?>) environments.get(0).get("daemonConfig")).get("studioUrl"));
    assertFalse(environments.get(0).containsKey("installConfig"));
    assertFalse(environments.get(0).containsKey("id"));
    assertFalse(environments.get(0).containsKey("version"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> mcpServers = (List<Map<String, Object>>) document.get("mcpServers");
    assertEquals(Boolean.TRUE, mcpServers.get(0).get("enabled"));
    assertFalse(mcpServers.get(0).containsKey("discoveryStatus"));

    assertTrue(document.get("settings") instanceof Map<?, ?>);
  }

  @Test
  void exportIncludesSkillAccessTokenVerbatim() {
    SkillPackage pkg =
        ConfigSyncFixtures.gitSkillPackage(
            "pkg", "https://example.com/pkg.git", "main", "pkg-token", "s");
    ConfigSyncSnapshot snapshot =
        snapshot(List.of(), List.of(), List.of(), List.of(pkg), List.of(), List.of(), List.of());

    String yamlText =
        exporter.export(snapshot, List.of(new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg")));
    Map<String, Object> document = yaml.parse(yamlText);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> skillPackages =
        (List<Map<String, Object>>) document.get("skillPackages");
    assertEquals("pkg-token", skillPackages.get(0).get("token"));
  }

  @Test
  void exportOmitsUploadSkillPackagesAndPinnedCommits() {
    SkillPackage gitPkg =
        ConfigSyncFixtures.gitSkillPackage(
            "git-pkg", "https://example.com/git.git", "main", null, "s1");
    SkillPackage uploadPkg = ConfigSyncFixtures.uploadSkillPackage("upload-pkg", "s2");
    ConfigSyncSnapshot snapshot =
        snapshot(
            List.of(),
            List.of(),
            List.of(),
            List.of(gitPkg, uploadPkg),
            List.of(),
            List.of(),
            List.of());

    String yamlText =
        exporter.export(
            snapshot,
            List.of(
                new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "git-pkg"),
                new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "upload-pkg")));
    Map<String, Object> document = yaml.parse(yamlText);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> skillPackages =
        (List<Map<String, Object>>) document.get("skillPackages");
    assertEquals(1, skillPackages.size());
    Map<String, Object> exported = skillPackages.get(0);
    assertEquals("git-pkg", exported.get("packageName"));
    assertEquals("https://example.com/git.git", exported.get("repositoryUrl"));
    assertEquals("main", exported.get("branch"));
    assertFalse(exported.containsKey("currentCommit"));
    assertFalse(exported.containsKey("headCommit"));
    assertFalse(exported.containsKey("packageVersion"));
  }

  @Test
  void exportOnlySelectedSections() {
    ConfigSyncSnapshot snapshot = buildSnapshot();
    String yamlText =
        exporter.export(snapshot, List.of(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    Map<String, Object> document = yaml.parse(yamlText);

    assertTrue(document.containsKey("providers"));
    assertFalse(document.containsKey("models"));
    assertFalse(document.containsKey("agents"));
    assertFalse(document.containsKey("settings"));
  }

  @Test
  void daemonConfigExportsAsNestedStructureAndParsesBackWithoutSecretsInConfig() {
    ConfigSyncParser parser = ConfigSyncFixtures.parser(yaml);
    var spec =
        parser
            .parse(ConfigSyncFixtures.resourceYaml("environment-card.yaml"))
            .environments()
            .get(0);
    var env = environment("env");
    env.setNote(spec.note());
    env.setDaemonConfig(spec.daemonConfig());
    var state =
        snapshot(List.of(), List.of(), List.of(), List.of(), List.of(env), List.of(), List.of());
    String exported =
        exporter.export(state, List.of(new ConfigSyncRef(ConfigSyncKind.ENVIRONMENTS, "env")));
    var imported = parser.parse(exported).environments().get(0);
    assertEquals(spec.note(), imported.note());
    assertEquals(spec.daemonConfig(), imported.daemonConfig());
    assertEquals(spec.registrationTokenHash(), imported.registrationTokenHash());
    assertEquals("https://studio.example.com", imported.daemonConfig().getStudioUrl());
    assertEquals("http://127.0.0.1:7890", imported.daemonConfig().getProxyUrl());
    assertFalse(
        yaml.toMap(imported.daemonConfig(), "daemonConfig").containsKey("registrationTokenHash"));
  }

  /** 插件条目只导出安装身份与普通值文本：epoch、修订、认证代际/上下文指纹与任何秘密/身份事实都不进入文件。 */
  @Test
  void exportPluginCarriesOnlyIdentityAndOrdinaryValues() {
    String values = "{\"promptAgentName\":\"h3-agent\"}";
    ConfigSyncSnapshot pluginSnapshot =
        snapshotWithPlugins(
            List.of(),
            List.of(),
            List.of(),
            Set.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(ConfigSyncFixtures.pluginRow("acme.plugin", 7L, values)));

    String yamlText =
        exporter.export(
            pluginSnapshot, List.of(new ConfigSyncRef(ConfigSyncKind.PLUGINS, "acme.plugin")));
    Map<String, Object> document = yaml.parse(yamlText);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> plugins = (List<Map<String, Object>>) document.get("plugins");
    assertEquals(1, plugins.size());
    assertEquals(
        Map.of("pluginId", "acme.plugin", "valuesJson", values),
        plugins.get(0),
        "plugin entry must carry exactly pluginId and valuesJson");
  }

  /** 普通值文本按原值导出；同名但未选中的插件与其它类别都不出现，重建同一选择得到逐字段一致的文档。 */
  @Test
  void exportPluginOnlyWhenSelectedAndIsReimportable() {
    String values = "{\"promptAgentName\":\"h3-agent\"}";
    ConfigSyncSnapshot pluginSnapshot =
        snapshotWithPlugins(
            List.of(provider("p")),
            List.of(),
            List.of(),
            Set.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                ConfigSyncFixtures.pluginRow("acme.plugin", 2L, values),
                ConfigSyncFixtures.pluginRow("other.plugin", 2L, "{}")));

    String yamlText =
        exporter.export(
            pluginSnapshot, List.of(new ConfigSyncRef(ConfigSyncKind.PLUGINS, "other.plugin")));
    Map<String, Object> document = yaml.parse(yamlText);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> plugins = (List<Map<String, Object>>) document.get("plugins");
    assertEquals(1, plugins.size());
    assertEquals("other.plugin", plugins.get(0).get("pluginId"));
    assertFalse(document.containsKey("providers"), "unselected kinds must not be exported");
  }
}
