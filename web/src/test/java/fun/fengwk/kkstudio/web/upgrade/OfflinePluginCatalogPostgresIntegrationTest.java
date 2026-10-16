package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.upgrade.OfflineHostProbes.LocalHttpProbe;

import javax.sql.DataSource;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 离线候选与真实 PostgreSQL 的边界回归：纯构造不碰 epoch 0、历史与凭据；候选在一个外层事务里提交后，同版本普通 bootstrap 严格恢复出逐字段一致的 epoch 1。
 *
 * <p>每例使用独立数据库，断言 V1 的 epoch 0、空配置历史、单行空 manifest 和空凭据表；不启动 Web 或 worker。
 */
class OfflinePluginCatalogPostgresIntegrationTest {

  private static final Set<String> INSTALLED_PLUGIN_IDS =
      Set.of("comfyui", "media", "minimax-mavis", "opencli");

  private DataSource dataSource;

  /** 每个测试一个全新独立库：目录身份与共享 web 库完全隔离。 */
  @BeforeEach
  void createIsolatedDatabase() {
    dataSource = WebPostgresTestSupport.createIsolatedDatabase();
  }

  /** 真实 defaults：候选提交后由普通 bootstrap 严格恢复。 */
  @Test
  void preparesWithoutTouchingTheTargetAndRestoresTheCommittedEpochThroughTheNormalPath()
      throws IOException {
    assertRoundTrips(false);
  }

  /** 启用态（ComfyUI 默认 H3 加追加 DIRECT、OpenCLI GPT Image 2 与 Seedance）同样只构造不执行，并且恢复后能力集合不变。 */
  @Test
  void restoresEnabledCapabilitiesThroughTheNormalPath() throws IOException {
    assertRoundTrips(true);
  }

  private void assertRoundTrips(boolean enabledCapabilities) throws IOException {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertUninitialized(jdbc);

    try (LocalHttpProbe probe = LocalHttpProbe.start();
        OfflinePluginCatalog catalog =
            OfflinePluginCatalog.open(
                dataSource, OfflineHostProbes.settingsWithLocalProxy(probe.proxyUrl()))) {
      StudioPluginRegistry registry = catalog.registry();
      assertEquals(INSTALLED_PLUGIN_IDS, Set.copyOf(registry.pluginIds()));

      PluginCatalogBootstrap.PreparedInitial prepared =
          catalog.prepareInitial(
              enabledCapabilities
                  ? OfflineInitialCatalogValues.valuesJsonWithEnabledCapabilities(registry)
                  : OfflineInitialCatalogValues.valuesJsonByPluginId(registry),
              OfflineInitialCatalogValues.noConfiguredSecretFields(registry));
      try {
        PluginCatalogSnapshot snapshot = prepared.snapshot();
        assertEquals(1L, snapshot.epoch());
        assertEquals(4, prepared.configurationRows().size());
        assertNotNull(snapshot.catalogFingerprint());
        assertEquals(INSTALLED_PLUGIN_IDS, snapshot.capabilityFingerprints().keySet());
        assertCapabilities(snapshot, enabledCapabilities);

        // prepare 是纯构造：目标库仍停在唯一未初始化形态，凭据表也没有任何行。
        assertUninitialized(jdbc);

        PluginConfigurationRepository repository = repository();
        TransactionTemplate transactions =
            new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertTrue(
            Boolean.TRUE.equals(
                transactions.execute(
                    status ->
                        repository.commit(0L, prepared.configurationRows(), prepared.manifest()))),
            "the single outer transaction must win the epoch 0 CAS");
        assertEquals(1L, epoch(jdbc));
        assertEquals(4, historyRows(jdbc));
        assertEquals(2, manifestRows(jdbc));
        assertEquals(0, credentialRows(jdbc));

        // 落库历史必须与候选逐字段相同（含空的 configured_secret_fields）。
        for (PluginConfigurationRow row : prepared.configurationRows()) {
          assertEquals(row, repository.readConfiguration(row.pluginId(), 1L).orElseThrow());
        }

        // 同版本普通 bootstrap 路径严格恢复：epoch、两域指纹、能力指纹与逐插件配置身份全部一致。
        try (PluginCatalogSnapshot restored =
            new PluginCatalogBootstrap(
                    registry, repository, new DataSourceTransactionManager(dataSource))
                .bootstrap(catalog.contexts(), catalog.builtins(), List.of())) {
          assertEquals(1L, restored.epoch());
          assertEquals(snapshot.catalogFingerprint(), restored.catalogFingerprint());
          assertEquals(snapshot.capabilityFingerprints(), restored.capabilityFingerprints());
          assertConfigurationIdentity(snapshot, restored);
          assertCapabilities(restored, enabledCapabilities);
          assertEquals(0, credentialRows(jdbc), "restore must not touch credentials");
        }
      } finally {
        prepared.snapshot().close();
      }

      assertEquals(0, probe.connections(), "prepare and restore must not issue any HTTP request");
    }
    assertEquals(1L, epoch(jdbc), "closing the assembly must not change the committed epoch");
  }

  /** V1 的真实未初始化形态：epoch 0、epoch-0 空 manifest 一行、无配置历史、无凭据行。 */
  private static void assertUninitialized(JdbcClient jdbc) {
    assertEquals(0L, epoch(jdbc));
    assertEquals(0, historyRows(jdbc));
    assertEquals(1, manifestRows(jdbc));
    assertEquals(0, credentialRows(jdbc));
  }

  /** 默认值下 ComfyUI/OpenCLI 的声明能力保持停用；启用态下这些 Canvas Function 必须真的处于 enabled。 */
  private static void assertCapabilities(
      PluginCatalogSnapshot snapshot, boolean enabledCapabilities) {
    Set<String> enabledFunctions = OfflineInitialCatalogValues.enabledCanvasFunctions(snapshot);
    assertTrue(enabledFunctions.contains("image.crop"), enabledFunctions.toString());
    if (enabledCapabilities) {
      assertEquals(OfflineInitialCatalogValues.EXPECTED_ENABLED_CANVAS_FUNCTIONS, enabledFunctions);
    } else {
      assertFalse(enabledFunctions.contains(OfflineInitialCatalogValues.H3_WORKFLOW_ID));
      assertFalse(enabledFunctions.contains(OfflineInitialCatalogValues.DIRECT_WORKFLOW_ID));
      assertFalse(enabledFunctions.contains("gpt-image-2"));
    }
  }

  /** 逐插件比较 descriptor、普通值、revision、认证代际与认证上下文指纹；恢复必须与真实持久值一致。 */
  private static void assertConfigurationIdentity(
      PluginCatalogSnapshot prepared, PluginCatalogSnapshot restored) {
    assertEquals(prepared.configuredPlugins().size(), restored.configuredPlugins().size());
    for (int index = 0; index < prepared.configuredPlugins().size(); index++) {
      PluginCatalogSnapshot.ConfiguredPlugin expected = prepared.configuredPlugins().get(index);
      PluginCatalogSnapshot.ConfiguredPlugin actual = restored.configuredPlugins().get(index);
      assertEquals(expected.descriptor(), actual.descriptor());
      assertEquals(expected.config().revision(), actual.config().revision());
      assertEquals(expected.config().valuesJson(), actual.config().valuesJson());
      assertEquals(expected.credentialReference(), actual.credentialReference());
    }
  }

  private PluginConfigurationRepository repository() {
    return new PostgresqlPluginConfigurationRepository(
        new PluginConfigurationJdbcStore(JdbcClient.create(dataSource)),
        new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
  }

  private static long epoch(JdbcClient jdbc) {
    return jdbc.sql("select epoch from plugin_catalog_state where id = 1")
        .query(Long.class)
        .single();
  }

  private static int historyRows(JdbcClient jdbc) {
    return jdbc.sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
        .single();
  }

  private static int manifestRows(JdbcClient jdbc) {
    return jdbc.sql("select count(*) from plugin_catalog_revision").query(Integer.class).single();
  }

  private static int credentialRows(JdbcClient jdbc) {
    return jdbc.sql("select count(*) from plugin_credential").query(Integer.class).single();
  }
}
