package fun.fengwk.kkstudio.web.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncApplier;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncPlan;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncPlanner;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncSnapshot;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncSnapshotReader;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通插件配置作为 ConfigSync 第八类的真实 PostgreSQL 集成契约。
 *
 * <p>校验只在这里对真实数据库、真实 Spring 写事务与真实 HTTP 边界成立：portable 导出条目只含安装身份与普通值文本（不含秘密、代际、epoch 与
 * revision），插件与其它类别在同一次导入事务内写入，任一条目失败（第二个插件的陈旧修订、并发推进的目录 epoch）都完整回滚本次导入的全部事实，
 * 且普通导入绝不会为已安装插件创建凭据行。插件权威表的 FK 必须保持立即约束：坏行立刻失败，而不是被推迟到提交。
 *
 * <p>每个用例都推进真实插件目录 epoch，因此必须 {@link DirtiesContext} 逐方法重建 Context：共享支持基座按「Context 冻结快照」重建权威
 * catalog，只有在 Context 与库内目录同身份时成立，本类的写入会故意打破该身份。
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
    assertFalse(
        yamlText.contains("authGeneration"), "authentication generations never leave the host");
    assertFalse(yamlText.contains("credential"), "credentials never leave the host");
    assertFalse(yamlText.contains("secrets"), "secret projections never leave the host");
  }

  /** 一次导入把插件与 settings 放进同一事务：导入后目录 epoch 恰好前进一次且不产生任何凭据。 */
  @Test
  void importAppliesPluginsTogetherWithOtherKinds() throws Exception {
    Map<String, String> plugins = installedPluginValues();
    String pluginId = plugins.keySet().iterator().next();
    long epochBefore = snapshotReader.read().pluginCatalogEpoch();

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
    assertEquals(
        epochBefore + 1,
        after.pluginCatalogEpoch(),
        "exactly one plugin write must advance the global catalog epoch once");
    PluginConfigurationRow row = pluginRow(after, pluginId);
    assertEquals(after.pluginCatalogEpoch(), row.revision());
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
        before.pluginCatalogEpoch(),
        after.pluginCatalogEpoch(),
        "the first plugin already committed in this transaction must be rolled back with the failing one");
    assertEquals(pluginRow(before, first), pluginRow(after, first));
    assertFalse(
        providerNames(after).contains(ROLLBACK_PROVIDER),
        "an earlier provider write must be rolled back with the failing plugin");
  }

  /** 导入事务外并发推进目录 epoch 时，插件的 epoch CAS 失败必须回滚本次导入已写入的 providers。 */
  @Test
  void concurrentCatalogAdvanceRollsBackEveryKind() throws Exception {
    String pluginId = installedPluginValues().keySet().iterator().next();
    ConfigSyncPlan plan = planFor(CAS_PROVIDER, List.of(pluginId));
    ConfigSyncSnapshot before = snapshotReader.read();
    long epoch = before.pluginCatalogEpoch();
    PluginConfigurationRow row = pluginRow(before, pluginId);

    // 并发写入：按计划期的 epoch/revision 保存一次普通配置，真实推进权威目录。
    pluginManagementService.updateConfiguration(
        pluginId, epoch, row.revision(), row.valuesJson(), Map.of());

    assertThrows(AiVersionConflictException.class, () -> applier.apply(plan));

    ConfigSyncSnapshot after = snapshotReader.read();
    assertEquals(epoch + 1, after.pluginCatalogEpoch());
    assertFalse(
        providerNames(after).contains(CAS_PROVIDER),
        "the earlier provider write must be rolled back when the plugin epoch CAS fails");
  }

  /** 插件权威表的 FK 必须保持立即约束：任何坏行立刻失败，而不是被推迟到事务提交。 */
  @Test
  void pluginForeignKeysStayImmediate() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "select c.conname, c.condeferrable, c.condeferred"
                    + " from pg_constraint c join pg_class t on t.oid = c.conrelid"
                    + " where c.contype = 'f' and t.relname like 'plugin%'")) {
      boolean found = false;
      while (rows.next()) {
        found = true;
        assertFalse(
            rows.getBoolean("condeferrable"), rows.getString("conname") + " must stay immediate");
        assertFalse(
            rows.getBoolean("condeferred"), rows.getString("conname") + " must stay immediate");
      }
      assertTrue(found, "the plugin tables must declare at least one foreign key");
    }
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
        plan.pluginCatalogEpoch(),
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
      names.add(provider.getName());
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
