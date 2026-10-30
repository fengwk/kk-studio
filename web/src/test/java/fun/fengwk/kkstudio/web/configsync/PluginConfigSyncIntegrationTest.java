package fun.fengwk.kkstudio.web.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncApplier;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncPlan;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncPlanner;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncSnapshot;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncSnapshotReader;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginManagementService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通插件配置作为 ConfigSync 第八类的真实 PostgreSQL 集成契约。
 *
 * <p>校验只在这里对真实数据库、真实 Spring 写事务与真实 HTTP 边界成立：portable 导出条目只含安装身份与普通值文本（不含秘密与修订），插件与其它类别在
 * 同一次导入事务内写入，任一条目失败（第二个插件的陈旧修订、并发保存）都完整回滚本次导入的全部事实，且普通导入绝不会为已安装插件创建凭据行。
 *
 * <p>每个用例都推进真实插件配置修订，因此必须 {@link DirtiesContext} 逐方法重建 Context：共享支持基座按 Context 当前快照写回插件配置，本类的写入会
 * 故意让两者不一致。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PluginConfigSyncIntegrationTest extends ConfigSyncTestSupport {

  /** 本用例独享的一次性 provider 名；每个用例前都重置数据库，因此固定名不会互相干扰。 */
  private static final String CAS_PROVIDER = "plugin-sync-cas-provider";

  private static final String ROLLBACK_PROVIDER = "plugin-sync-rollback-provider";

  @Autowired private ConfigSyncParser parser;
  @Autowired private ConfigSyncPlanner planner;
  @Autowired private ConfigSyncApplier applier;
  @Autowired private ConfigSyncSnapshotReader snapshotReader;
  @Autowired private PluginManagementService pluginManagementService;
  @Autowired private DataSource dataSource;
  @Autowired private AgentDefinitionRepository definitionRepository;

  /** inventory 与导出把已安装插件当第八类，且 portable 条目只含 pluginId 与 valuesJson。 */
  @SuppressWarnings("unchecked")
  @Test
  void exportTreatsInstalledPluginsAsPortableEighthKind() throws Exception {
    Map<String, String> plugins = installedPluginValues();
    assertFalse(plugins.isEmpty(), "the web context must install at least one StudioPlugin");
    String pluginId = plugins.keySet().iterator().next();
    String valuesJson = plugins.get(pluginId);

    JsonNode inventory = getData("/api/settings/sync");
    JsonNode pluginItem = findItem(inventory.path("items"), "plugins", pluginId);
    assertNotNull(pluginItem, "inventory must list every installed plugin as its own entry");
    assertEquals(
        3,
        pluginItem.size(),
        "the inventory entry must only carry the reference and its dependency closure");

    JsonNode exported =
        postData(
            "/api/settings/sync/export",
            Map.of("items", List.of(Map.of("kind", "plugins", "name", pluginId))));
    String yamlText = exported.path("yaml").asText();
    Map<String, Object> document = parseYaml(yamlText);
    assertEquals(
        List.of("plugins"), List.copyOf(document.keySet()), "only the selected kind is exported");
    List<Map<String, Object>> entries = (List<Map<String, Object>>) document.get("plugins");
    assertEquals(1, entries.size());
    assertEquals(Set.of("pluginId", "valuesJson"), entries.get(0).keySet());
    assertEquals(pluginId, entries.get(0).get("pluginId"));
    assertEquals(
        valuesJson,
        entries.get(0).get("valuesJson"),
        "the portable entry must carry the authoritative ordinary values verbatim");
    assertFalse(yamlText.contains("credential"), "credentials never leave the host");
    assertFalse(yamlText.contains("secrets"), "secret projections never leave the host");
  }

  /** 一次导入把插件与 settings 放进同一事务：导入后插件修订恰好前进一次且不产生任何凭据。 */
  @Test
  void importAppliesPluginsTogetherWithOtherKinds() throws Exception {
    Map<String, String> plugins = installedPluginValues();
    String pluginId = plugins.keySet().iterator().next();
    long revisionBefore = pluginRow(snapshotReader.read(), pluginId).revision();

    String yaml =
        mutateYaml(
            exportedSettingsYaml(),
            document ->
                document.put(
                    "plugins",
                    List.of(Map.of("pluginId", pluginId, "valuesJson", plugins.get(pluginId)))));

    JsonNode result =
        postData("/api/settings/sync/import", Map.of("yaml", yaml, "allowPartial", true));
    assertTrue(references(result.path("imported")).contains("plugins/" + pluginId));
    assertTrue(references(result.path("imported")).contains("settings/settings"));

    ConfigSyncSnapshot after = snapshotReader.read();
    PluginConfigurationRow row = pluginRow(after, pluginId);
    assertEquals(revisionBefore + 1, row.revision(), "exactly one plugin write");
    assertEquals(plugins.get(pluginId), row.valuesJson());
    assertNotEquals(
        "CONNECTED",
        getData("/api/plugins/" + pluginId).path("status").asText(),
        "an ordinary import must never fabricate a credential for an installed plugin");
  }

  /** 同一次导入里第二个插件的修订冲突必须让第一个插件与 providers 的写入一起完整回滚。 */
  @Test
  void secondPluginConflictRollsBackEveryKind() throws Exception {
    List<String> pluginIds = new ArrayList<>(installedPluginValues().keySet());
    assertTrue(pluginIds.size() >= 2, "this acceptance requires two installed plugins");
    String first = pluginIds.get(0);
    String second = pluginIds.get(1);

    ConfigSyncPlan tampered =
        withStalePlugin(planFor(ROLLBACK_PROVIDER, List.of(first, second)), second);
    ConfigSyncSnapshot before = snapshotReader.read();

    assertThrows(AiVersionConflictException.class, () -> applier.apply(tampered));

    ConfigSyncSnapshot after = snapshotReader.read();
    assertEquals(
        pluginRow(before, first),
        pluginRow(after, first),
        "the first plugin already written in this transaction must be rolled back with the failing one");
    assertFalse(
        providerNames(after).contains(ROLLBACK_PROVIDER),
        "an earlier provider write must be rolled back with the failing plugin");
  }

  /** 导入事务外并发保存插件配置时，插件的修订 CAS 失败必须回滚本次导入已写入的 providers。 */
  @Test
  void concurrentPluginSaveRollsBackEveryKind() throws Exception {
    String pluginId = installedPluginValues().keySet().iterator().next();
    ConfigSyncPlan plan = planFor(CAS_PROVIDER, List.of(pluginId));
    ConfigSyncSnapshot before = snapshotReader.read();
    PluginConfigurationRow row = pluginRow(before, pluginId);

    // 并发写入：按计划期的修订保存一次普通配置。
    pluginManagementService.updateConfiguration(
        pluginId, row.revision(), row.valuesJson(), Map.of());

    assertThrows(AiVersionConflictException.class, () -> applier.apply(plan));

    ConfigSyncSnapshot after = snapshotReader.read();
    assertEquals(row.revision() + 1, pluginRow(after, pluginId).revision());
    assertFalse(
        providerNames(after).contains(CAS_PROVIDER),
        "the earlier provider write must be rolled back when the plugin revision CAS fails");
  }

  /**
   * 意图：系统内置 Agent 绝不导出、绝不出现在 inventory、也不允许被覆盖，但它的存在必须满足引用依赖。
   *
   * <p>subagent 指向真实内置 Agent 的自定义 Agent 能正常 inventory、导出与重新导入；portable 文件里只有这一个自定义 Agent 条目。
   */
  @Test
  void builtinAgentSatisfiesReferencesWithoutBecomingPortable() throws Exception {
    // 系统内置 Agent 由系统持有身份：测试按同类身份落一行真实 BUILTIN 行，绝不把它当作可同步条目。
    seedBuiltinAgent(BuiltinAgentDefinitions.COMPACTION_NAME);
    String agent = "builtin-dep-agent";
    createBuiltinDependentAgent(agent);

    JsonNode items = getData("/api/settings/sync").path("items");
    assertNotNull(findItem(items, "agents", agent));
    assertNull(
        findItem(items, "agents", BuiltinAgentDefinitions.COMPACTION_NAME),
        "a builtin agent must never be a selectable inventory item");

    JsonNode exported =
        postData(
            "/api/settings/sync/export",
            Map.of("items", List.of(Map.of("kind", "agents", "name", agent))));
    String yaml = exported.path("yaml").asText();
    assertEquals(
        List.of(agent),
        agentNames(parseYaml(yaml)),
        "only the selected custom agent may be exported; the builtin subagent must not become an item");

    JsonNode result =
        postData("/api/settings/sync/import", Map.of("yaml", yaml, "allowPartial", true));
    assertTrue(references(result.path("imported")).contains("agents/" + agent));
    assertTrue(references(result.path("skipped")).isEmpty());

    // 保留名仍不可覆盖：导入内置身份本身必须整份拒绝，库内内置行逐字段不变（version/config 都不被改写）。
    AgentDefinition before =
        definitionRepository.getByName(BuiltinAgentDefinitions.COMPACTION_NAME);
    String reserved =
        "agents:\n  - name: "
            + BuiltinAgentDefinitions.COMPACTION_NAME
            + "\n    description: overridden\n    model: acceptance-stub\n"
            + "    systemPrompt: overridden\n";
    mockMvc
        .perform(
            post("/api/settings/sync/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("yaml", reserved))))
        .andExpect(status().isBadRequest());
    AgentDefinition after = definitionRepository.getByName(BuiltinAgentDefinitions.COMPACTION_NAME);
    assertEquals(before.version(), after.version());
    assertEquals(before.configJson(), after.configJson());
    assertEquals(before.systemPrompt(), after.systemPrompt());
  }

  /** 以系统持有身份落一行 BUILTIN Agent：只用于证明内置身份满足引用却绝不进入 portable 范围。 */
  private void seedBuiltinAgent(String name) {
    AgentDefinition builtin =
        AgentDefinition.builder()
            .name(name)
            .type(AgentDefinitionType.BUILTIN)
            .systemPrompt("builtin prompt")
            .configJson(
                "{\"tools\":[],\"skills\":[],\"subagents\":[],\"inheritParentEnvironment\":true}")
            .build();
    definitionRepository.create(builtin);
  }

  /** 建一个 subagent 指向系统内置 Agent 的自定义 Agent：内置身份必须满足该引用。 */
  private void createBuiltinDependentAgent(String name) throws Exception {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of(BuiltinAgentDefinitions.COMPACTION_NAME));
    config.setInheritParentEnvironment(Boolean.TRUE);

    AgentDefinitionCreateDTO dto = new AgentDefinitionCreateDTO();
    dto.setName(name);
    dto.setDescription("agent " + name);
    dto.setSystemPrompt("prompt " + name);
    dto.setModel("acceptance-stub");
    dto.setVariant("default");
    dto.setConfig(config);
    postData("/api/agents", dto);
  }

  /** 导出文档里的 Agent 条目名，用于断言内置身份没有变成独立条目。 */
  @SuppressWarnings("unchecked")
  private static List<String> agentNames(Map<String, Object> document) {
    List<Map<String, Object>> agents =
        (List<Map<String, Object>>) document.getOrDefault("agents", List.of());
    List<String> names = new ArrayList<>();
    for (Map<String, Object> entry : agents) {
      names.add((String) entry.get("name"));
    }
    return names;
  }

  /** 已安装插件的 pluginId 到权威普通值文本；只取真实存在配置投影的插件。 */
  private Map<String, String> installedPluginValues() throws Exception {
    Map<String, String> values = new LinkedHashMap<>();
    for (JsonNode node : getData("/api/plugins")) {
      String valuesJson = node.path("configuration").path("valuesJson").asText();
      if (!valuesJson.isEmpty()) {
        values.put(node.path("pluginId").asText(), valuesJson);
      }
    }
    return values;
  }

  /** 由真实 parser/planner 构造计划：一个新建 provider、给定插件与当前 settings，全部取自同一只读快照。 */
  private ConfigSyncPlan planFor(String providerName, List<String> pluginIds) throws Exception {
    Map<String, String> plugins = installedPluginValues();
    String yaml =
        mutateYaml(
            exportedSettingsYaml(),
            document -> {
              document.put(
                  "providers", List.of(Map.of("name", providerName, "providerType", "openai")));
              document.put(
                  "plugins",
                  pluginIds.stream()
                      .map(id -> Map.of("pluginId", id, "valuesJson", plugins.get(id)))
                      .toList());
            });
    return planner.plan(parser.parse(yaml));
  }

  /** 把指定插件的计划期修订改为陈旧值，模拟计划与提交之间的并发推进。 */
  private static ConfigSyncPlan withStalePlugin(ConfigSyncPlan plan, String pluginId) {
    List<ConfigSyncPlan.PluginImport> plugins =
        plan.plugins().stream()
            .map(
                plugin ->
                    plugin.pluginId().equals(pluginId)
                        ? new ConfigSyncPlan.PluginImport(
                            plugin.pluginId(), plugin.expectedRevision() + 1, plugin.valuesJson())
                        : plugin)
            .toList();
    return new ConfigSyncPlan(
        plan.providers(),
        plan.models(),
        plan.skillPackages(),
        plan.mcpServers(),
        plan.environments(),
        plan.agents(),
        plugins,
        plan.settings(),
        plan.imported(),
        plan.skipped());
  }

  private static PluginConfigurationRow pluginRow(ConfigSyncSnapshot snapshot, String pluginId) {
    return snapshot.plugins().stream()
        .filter(row -> row.pluginId().equals(pluginId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("plugin is not in the snapshot: " + pluginId));
  }

  private static List<String> providerNames(ConfigSyncSnapshot snapshot) {
    List<String> names = new ArrayList<>();
    for (AgentProvider provider : snapshot.providers()) {
      names.add(provider.name());
    }
    return names;
  }

  private static List<String> references(JsonNode refs) {
    List<String> values = new ArrayList<>();
    refs.forEach(ref -> values.add(ref.path("kind").asText() + "/" + ref.path("name").asText()));
    return values;
  }

  private static JsonNode findItem(JsonNode items, String kind, String name) {
    for (JsonNode item : items) {
      if (kind.equals(item.path("kind").asText()) && name.equals(item.path("name").asText())) {
        return item;
      }
    }
    return null;
  }
}
