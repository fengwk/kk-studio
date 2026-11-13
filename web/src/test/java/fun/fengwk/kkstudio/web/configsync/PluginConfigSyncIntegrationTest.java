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

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncApplier;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncPlan;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncPlanner;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncSnapshot;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncSnapshotReader;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginManagementService;

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

  /** inventory 与导出把已安装插件当第八类，且 portable 条目只含 pluginId 与 valuesJson。 */
  @SuppressWarnings("unchecked")
  @Test
  void exportTreatsInstalledPluginsAsPortableEighthKind() throws Exception {
    Map<String, String> plugins = installedPluginValues();
    assertFalse(plugins.isEmpty(), "the web context must install at least one Plugin");
    String pluginId = plugins.keySet().iterator().next();
    String valuesJson = plugins.get(pluginId);

    JsonNode inventory = getData("/api/platform/config-sync");
    JsonNode pluginItem = findItem(inventory.path("items"), "plugins", pluginId);
    assertNotNull(pluginItem, "inventory must list every installed plugin as its own entry");
    assertEquals(
        3,
        pluginItem.size(),
        "the inventory entry must only carry the reference and its dependency closure");

    JsonNode exported =
        postData(
            "/api/platform/config-sync/export",
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

  /** 一次导入把插件与 settings 放进同一事务：导入后插件版本恰好前进一次且不产生任何凭据。 */
  @Test
  void importAppliesPluginsTogetherWithOtherKinds() throws Exception {
    Map<String, String> plugins = installedPluginValues();
    String pluginId = plugins.keySet().iterator().next();
    long versionBefore = pluginRow(snapshotReader.read(), pluginId).version();

    String yaml =
        mutateYaml(
            exportedSettingsYaml(),
            document ->
                document.put(
                    "plugins",
                    List.of(Map.of("pluginId", pluginId, "valuesJson", plugins.get(pluginId)))));

    JsonNode result =
        postData("/api/platform/config-sync/import", Map.of("yaml", yaml, "allowPartial", true));
    assertTrue(references(result.path("imported")).contains("plugins/" + pluginId));
    assertTrue(references(result.path("imported")).contains("settings/settings"));

    ConfigSyncSnapshot after = snapshotReader.read();
    PluginConfigurationRow row = pluginRow(after, pluginId);
    assertEquals(versionBefore + 1, row.version(), "exactly one plugin write");
    assertEquals(plugins.get(pluginId), row.valuesJson());
    assertNotEquals(
        "CONNECTED",
        getData("/api/platform/plugins/" + pluginId).path("status").asText(),
        "an ordinary import must never fabricate a credential for an installed plugin");
  }

  /** 同一次导入里第二个插件的版本冲突必须让第一个插件与 providers 的写入一起完整回滚。 */
  @Test
  void secondPluginConflictRollsBackEveryKind() throws Exception {
    List<String> pluginIds = new ArrayList<>(installedPluginValues().keySet());
    assertTrue(pluginIds.size() >= 2, "this acceptance requires two installed plugins");
    String first = pluginIds.get(0);
    String second = pluginIds.get(1);

    ConfigSyncPlan tampered =
        withStalePlugin(planFor(ROLLBACK_PROVIDER, List.of(first, second)), second);
    ConfigSyncSnapshot before = snapshotReader.read();

    assertThrows(DomainException.class, () -> applier.apply(tampered, List.of()));

    ConfigSyncSnapshot after = snapshotReader.read();
    assertEquals(
        pluginRow(before, first),
        pluginRow(after, first),
        "the first plugin already written in this transaction must be rolled back with the failing one");
    assertFalse(
        providerNames(after).contains(ROLLBACK_PROVIDER),
        "an earlier provider write must be rolled back with the failing plugin");
  }

  /** 导入事务外并发保存插件配置时，插件的版本 CAS 失败必须回滚本次导入已写入的 providers。 */
  @Test
  void concurrentPluginSaveRollsBackEveryKind() throws Exception {
    String pluginId = installedPluginValues().keySet().iterator().next();
    ConfigSyncPlan plan = planFor(CAS_PROVIDER, List.of(pluginId));
    ConfigSyncSnapshot before = snapshotReader.read();
    PluginConfigurationRow row = pluginRow(before, pluginId);

    // 并发写入：按计划期的版本保存一次普通配置。
    pluginManagementService.updateConfiguration(
        pluginId, row.version(), row.valuesJson(), Map.of());

    assertThrows(DomainException.class, () -> applier.apply(plan, List.of()));

    ConfigSyncSnapshot after = snapshotReader.read();
    assertEquals(row.version() + 1, pluginRow(after, pluginId).version());
    assertFalse(
        providerNames(after).contains(CAS_PROVIDER),
        "the earlier provider write must be rolled back when the plugin version CAS fails");
  }

  /** 已安装插件的 pluginId 到权威普通值原文；只取真实存在配置投影的插件。 */
  private Map<String, String> installedPluginValues() throws Exception {
    Map<String, String> values = new LinkedHashMap<>();
    for (JsonNode node : getData("/api/platform/plugins")) {
      JsonNode valuesJson = node.path("configuration").path("valuesJson");
      if (valuesJson.isTextual()) {
        values.put(node.path("pluginId").asText(), valuesJson.asText());
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

  /** 把指定插件的计划期版本改为陈旧值，模拟计划与提交之间的并发推进。 */
  private static ConfigSyncPlan withStalePlugin(ConfigSyncPlan plan, String pluginId) {
    List<ConfigSyncPlan.PluginImport> plugins =
        plan.plugins().stream()
            .map(
                plugin ->
                    plugin.pluginId().equals(pluginId)
                        ? new ConfigSyncPlan.PluginImport(
                            plugin.pluginId(), plugin.expectedVersion() + 1, plugin.valuesJson())
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
    for (Provider provider : snapshot.providers()) {
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
