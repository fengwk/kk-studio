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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;

import java.util.List;
import java.util.Map;

/** 测试意图：锁定导出只包含可编辑业务字段且保留原始凭据/registrationToken，不泄露运行态（UUID/时间戳/版本/发现状态），settings 以对象导出。 */
class ConfigSyncExporterTest {

  private final ConfigSyncYaml yaml = new ConfigSyncYaml();
  private final ConfigSyncExporter exporter =
      new ConfigSyncExporter(
          ConfigSyncFixtures.PROVIDER_CONFIG_CODEC,
          ConfigSyncFixtures.MODEL_CONFIG_PARSER,
          ConfigSyncFixtures.AGENT_CONFIG_CODEC,
          new SystemSettingsCodec(),
          yaml);

  private ConfigSyncSnapshot buildSnapshot() {
    return snapshot(
        List.of(provider("p")),
        List.of(model("p", "m")),
        List.of(agent("a", "p", "m", agentConfig(List.of(), List.of(), List.of()))),
        List.of(skillPackage("pkg", "s")),
        List.of(environment("env")),
        List.of(mcpServer("mcp", true)),
        List.of(mcpTool("tool_x", "mcp")));
  }

  @Test
  void exportIncludesEditableFieldsAndSecretsButNoRuntimeState() {
    ConfigSyncSnapshot snapshot = buildSnapshot();
    List<ConfigSyncRef> refs =
        List.of(
            new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p"),
            new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m"),
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
    assertEquals("token-env", environments.get(0).get("registrationToken"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> mcpServers = (List<Map<String, Object>>) document.get("mcpServers");
    assertEquals(Boolean.TRUE, mcpServers.get(0).get("enabled"));
    assertFalse(mcpServers.get(0).containsKey("discoveryStatus"));

    assertTrue(document.get("settings") instanceof Map<?, ?>);
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
}
