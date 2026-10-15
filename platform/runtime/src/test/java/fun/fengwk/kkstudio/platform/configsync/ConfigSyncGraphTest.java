package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agent;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.agentConfig;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.environment;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpServer;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.mcpTool;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.model;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.provider;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.skillPackage;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.skillRef;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.snapshot;
import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncFixtures.snapshotWithPlugins;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;

import java.time.Instant;
import java.util.List;

/** 测试意图：锁定依赖图的一次性构图、传递闭包去重与循环终止，以及导出的严格校验（空选择/未知引用/缺失依赖都必须拒绝，不产出不完整包）。 */
class ConfigSyncGraphTest {

  private static final String PLUGIN_ID = "acme.plugin";

  private final ConfigSyncGraph graph =
      new ConfigSyncGraph(ConfigSyncFixtures.AGENT_CONFIG_CODEC, ConfigSyncFixtures.registry());

  private ConfigSyncGraph pluginGraph() {
    return new ConfigSyncGraph(
        ConfigSyncFixtures.AGENT_CONFIG_CODEC,
        ConfigSyncFixtures.registry(new ConfigSyncFixtures.AgentDependencyPlugin(PLUGIN_ID)));
  }

  private static ConfigSyncSnapshot pluginSnapshot(String requiredAgent, boolean agentPresent) {
    List<AgentDefinition> agents =
        agentPresent
            ? List.of(agent(requiredAgent, "p", "m", agentConfig(List.of(), List.of(), List.of())))
            : List.of();
    return snapshotWithPlugins(
        List.of(provider("p")),
        List.of(model("p", "m")),
        agents,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        5L,
        List.of(
            ConfigSyncFixtures.pluginRow(
                PLUGIN_ID, 3L, "{\"promptAgentName\":\"" + requiredAgent + "\"}")));
  }

  /** 旧 settings 集成字段仍完整保留在聚合里，但依赖图不再由它反向扩展 Agent。 */
  private static SystemSettingsRepository.SystemSettingsRecord legacyPromptAgentSettings() {
    SystemSettingsSectionsDTO sections =
        new SystemSettingsCodec().toSections(SystemSettings.DEFAULT);
    sections.getIntegrations().getMinimaxH3().setPromptAgentName("legacy-h3");
    return new SystemSettingsRepository.SystemSettingsRecord(
        new SystemSettingsCodec().fromDto(sections), 1L, Instant.EPOCH, Instant.EPOCH);
  }

  private ConfigSyncSnapshot richSnapshot() {
    return snapshot(
        List.of(provider("p")),
        List.of(model("p", "m")),
        List.of(
            agent(
                "a",
                "p",
                "m",
                agentConfig(List.of("tool_x"), List.of(skillRef("pkg", "s")), List.of("b"))),
            agent("b", "p", "m", agentConfig(List.of(), List.of(), List.of("a")))),
        List.of(skillPackage("pkg", "s")),
        List.of(environment("env")),
        List.of(mcpServer("mcp", true)),
        List.of(mcpTool("tool_x", "mcp")));
  }

  @Test
  void allRefsCoversAllCategories() {
    List<ConfigSyncRef> refs = graph.allRefs(richSnapshot());
    assertTrue(refs.contains(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    assertTrue(refs.contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    assertTrue(refs.contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    assertTrue(refs.contains(new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg")));
    assertTrue(refs.contains(new ConfigSyncRef(ConfigSyncKind.ENVIRONMENTS, "env")));
    assertTrue(refs.contains(new ConfigSyncRef(ConfigSyncKind.MCP_SERVERS, "mcp")));
    assertTrue(refs.contains(new ConfigSyncRef(ConfigSyncKind.SETTINGS, "settings")));
  }

  @Test
  void closureTraversesModelSkillSubagentAndMcp() {
    ConfigSyncSnapshot snapshot = richSnapshot();
    ConfigSyncGraph.Graph prepared = graph.graph(snapshot);
    List<ConfigSyncRef> closure =
        graph.closure(prepared, new ConfigSyncRef(ConfigSyncKind.AGENTS, "a"));

    assertTrue(closure.contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    assertTrue(closure.contains(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    assertTrue(closure.contains(new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg")));
    assertTrue(closure.contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "b")));
    assertTrue(closure.contains(new ConfigSyncRef(ConfigSyncKind.MCP_SERVERS, "mcp")));
    // 闭包不含自身，也不反向扩展 Environment。
    assertFalse(closure.contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    assertFalse(closure.contains(new ConfigSyncRef(ConfigSyncKind.ENVIRONMENTS, "env")));
  }

  @Test
  void closureTerminatesOnMutuallyReferencingAgents() {
    ConfigSyncSnapshot snapshot = richSnapshot();
    ConfigSyncGraph.Graph prepared = graph.graph(snapshot);
    List<ConfigSyncRef> closure =
        graph.closure(prepared, new ConfigSyncRef(ConfigSyncKind.AGENTS, "a"));

    assertEquals(1, closure.stream().filter(ref -> ref.getName().equals("b")).count());
    assertFalse(closure.contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
  }

  @Test
  void expandRejectsEmptySelection() {
    ConfigSyncGraph.Graph prepared = graph.graph(richSnapshot());
    assertThrows(AiValidationException.class, () -> graph.expand(prepared, List.of()));
    assertThrows(AiValidationException.class, () -> graph.expand(prepared, null));
  }

  @Test
  void expandRejectsUnknownReference() {
    ConfigSyncGraph.Graph prepared = graph.graph(richSnapshot());
    assertThrows(
        AiValidationException.class,
        () -> graph.expand(prepared, List.of(new ConfigSyncRef(ConfigSyncKind.AGENTS, "missing"))));
  }

  @Test
  void expandRejectsMissingDependency() {
    // Agent 引用不存在的 subagent：导出必须拒绝而不是产出不完整包。
    ConfigSyncSnapshot snapshot =
        snapshot(
            List.of(provider("p")),
            List.of(model("p", "m")),
            List.of(agent("a", "p", "m", agentConfig(List.of(), List.of(), List.of("ghost")))),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    ConfigSyncGraph.Graph prepared = graph.graph(snapshot);
    assertThrows(
        AiValidationException.class,
        () -> graph.expand(prepared, List.of(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a"))));
  }

  @Test
  void expandExpandsClosureAndDeduplicates() {
    ConfigSyncGraph.Graph prepared = graph.graph(richSnapshot());
    List<ConfigSyncRef> expanded =
        graph.expand(
            prepared,
            List.of(
                new ConfigSyncRef(ConfigSyncKind.AGENTS, "a"),
                new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg")));

    assertTrue(expanded.contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "a")));
    assertTrue(expanded.contains(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    assertTrue(expanded.contains(new ConfigSyncRef(ConfigSyncKind.SKILL_PACKAGES, "pkg")));
    assertEquals(expanded.size(), expanded.stream().distinct().count());
  }

  /** 合成插件声明 Agent 依赖：导出选中插件自动带出该 Agent 及其递归依赖（Model/Provider）。 */
  @Test
  void pluginClosureFollowsDeclaredRequiredAgents() {
    ConfigSyncGraph pluginGraph = pluginGraph();
    ConfigSyncGraph.Graph prepared = pluginGraph.graph(pluginSnapshot("h3", true));

    List<ConfigSyncRef> expanded =
        pluginGraph.expand(prepared, List.of(new ConfigSyncRef(ConfigSyncKind.PLUGINS, PLUGIN_ID)));

    assertTrue(expanded.contains(new ConfigSyncRef(ConfigSyncKind.PLUGINS, PLUGIN_ID)));
    assertTrue(expanded.contains(new ConfigSyncRef(ConfigSyncKind.AGENTS, "h3")));
    assertTrue(expanded.contains(new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m")));
    assertTrue(expanded.contains(new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")));
    assertEquals(
        List.of(
            new ConfigSyncRef(ConfigSyncKind.AGENTS, "h3"),
            new ConfigSyncRef(ConfigSyncKind.MODELS, "p/m"),
            new ConfigSyncRef(ConfigSyncKind.PROVIDERS, "p")),
        pluginGraph.closure(prepared, new ConfigSyncRef(ConfigSyncKind.PLUGINS, PLUGIN_ID)));
  }

  /** 插件声明的 Agent 不在快照中时导出必须拒绝，绝不产出缺依赖的包。 */
  @Test
  void pluginExportRejectsMissingRequiredAgent() {
    ConfigSyncGraph pluginGraph = pluginGraph();
    ConfigSyncGraph.Graph prepared = pluginGraph.graph(pluginSnapshot("ghost", false));

    assertThrows(
        AiValidationException.class,
        () ->
            pluginGraph.expand(
                prepared, List.of(new ConfigSyncRef(ConfigSyncKind.PLUGINS, PLUGIN_ID))));
  }

  /** 旧 settings 的 H3 提示词 Agent 引用不再产生依赖边；该字段仍完整存在于 settings 聚合。 */
  @Test
  void settingsNoLongerExpandsLegacyPromptAgent() {
    ConfigSyncSnapshot snapshot =
        new ConfigSyncSnapshot(
            List.of(),
            List.of(),
            List.of(agent("legacy-h3", "p", "m", agentConfig(List.of(), List.of(), List.of()))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            legacyPromptAgentSettings(),
            1L,
            List.of());
    ConfigSyncGraph.Graph prepared = graph.graph(snapshot);

    assertEquals(
        List.of(), graph.closure(prepared, new ConfigSyncRef(ConfigSyncKind.SETTINGS, "settings")));
    // settings 仍被视为一个可同步条目。
    assertTrue(prepared.present().contains(new ConfigSyncRef(ConfigSyncKind.SETTINGS, "settings")));
  }
}
