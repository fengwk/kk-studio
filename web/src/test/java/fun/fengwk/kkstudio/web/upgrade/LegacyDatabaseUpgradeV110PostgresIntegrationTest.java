package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillTokenCipher;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 已发行 Java 1.1.0 源的离线转换：真实专题源库在 dry-run 与 apply 里走同一条写入/校验路径，原样保留 catalog
 * epoch/revision/manifest/配置历史、当前 Harness 历史与认证代际，只把 format 2 凭据重封装到目标主密钥并删除已退役的 {@code javaHome}。
 *
 * <p>负例覆盖源身份、Flyway 校验和、凭据密文/AAD、排空守卫、重复转换与静态材料拒绝；失败只断言固定阶段，不回显任何秘密。
 */
class LegacyDatabaseUpgradeV110PostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x4d);

  private static final SecretKey TARGET_KEY = key(0x5e);

  private static final PluginCredentialCodec CODEC = new PluginCredentialCodec();

  @Test
  void currentSourceConvertsOnceAndPreservesCatalogHistoryAndCredentials() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase(LegacyV110SourceFixture.PROFILE);
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyV110SourceFixture.load(source, SOURCE_KEY);
    UpgradePostgresTestSupport.assertPristine(target);
    Map<String, Long> sourceCounts = counts(source);
    Map<String, String> sourceHistoryDigests = historyDigests(source);

    LegacyDatabaseUpgradeResult dry = convert(source, target, true);
    assertFalse(dry.applied());
    assertEquals(LegacyV110SourceFixture.PROFILE, dry.sourceProfile());
    assertEquals(0L, dry.retiredClusterNodeRows());
    assertEquals(0L, dry.installedStaticCredentials());
    assertEquals(sourceCounts.get("plugin_credential"), dry.rewrittenCredentials());
    UpgradePostgresTestSupport.assertPristine(target);

    LegacyDatabaseUpgradeResult applied = convert(source, target, false);
    assertTrue(applied.applied());
    assertEquals(dry.rowCounts(), applied.rowCounts());
    assertEquals(new TreeSet<>(LegacySchemaProfile.V1_1_0_TABLES), applied.rowCounts().keySet());
    for (String table : LegacySchemaProfile.V1_1_0_TABLES) {
      assertEquals(sourceCounts.get(table), applied.rowCounts().get(table), table);
      assertEquals(sourceCounts.get(table), UpgradePostgresTestSupport.count(target, table), table);
    }

    assertCatalogPreserved(source, target);
    assertMultiEpochCatalogHistory(source, target);
    assertCredentialsReferenceCurrentEpoch(target);
    assertTargetCatalogRestores(target);
    assertCredentialsRewrapped(source, target);
    assertEnvironmentInstallConfigConverted(source, target);
    assertSettingsPreserved(source, target);
    assertSkillTokenRewrapped(source, target);
    assertHistoryPreserved(source, target, sourceHistoryDigests);
    assertEquals(0L, UpgradePostgresTestSupport.count(target, LegacySchemaProfile.CLUSTER_NODE));

    String sourceFingerprint;
    try (Connection connection = source.getConnection()) {
      sourceFingerprint = LegacySchemaProfile.fingerprint(connection);
    }
    assertEquals(LegacySchemaProfile.V1_1_0, sourceFingerprint);

    LegacyDatabaseUpgradeException second =
        assertThrows(LegacyDatabaseUpgradeException.class, () -> convert(source, target, false));
    assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE, second.stage());
  }

  @Test
  void tamperedSchemaIdentityAndHistoryAreRejected() throws SQLException {
    DataSource schemaSource =
        UpgradePostgresTestSupport.sourceDatabase(LegacyV110SourceFixture.PROFILE);
    LegacyV110SourceFixture.load(schemaSource, SOURCE_KEY);
    execute(schemaSource, "create index idx_v110_tampered on agent_provider (base_url)");
    assertStage(
        LegacyDatabaseUpgradeException.Stage.SOURCE_PROFILE,
        schemaSource,
        UpgradePostgresTestSupport.pristineTargetDatabase());

    DataSource historySource =
        UpgradePostgresTestSupport.sourceDatabase(LegacyV110SourceFixture.PROFILE);
    LegacyV110SourceFixture.load(historySource, SOURCE_KEY);
    execute(
        historySource,
        "update flyway_schema_history set checksum = checksum + 1 where version = '1'");
    assertStage(
        LegacyDatabaseUpgradeException.Stage.SOURCE_HISTORY,
        historySource,
        UpgradePostgresTestSupport.pristineTargetDatabase());
  }

  @Test
  void tamperedCiphertextAndWrongSourceKeyAreRejected() throws SQLException {
    DataSource tampered =
        UpgradePostgresTestSupport.sourceDatabase(LegacyV110SourceFixture.PROFILE);
    LegacyV110SourceFixture.load(tampered, SOURCE_KEY);
    execute(
        tampered,
        "update plugin_credential set encrypted_payload ="
            + " overlay(encrypted_payload placing '\\x00'::bytea from 20 for 1)");
    assertStage(
        LegacyDatabaseUpgradeException.Stage.CREDENTIAL,
        tampered,
        UpgradePostgresTestSupport.pristineTargetDatabase());

    DataSource wrongKey =
        UpgradePostgresTestSupport.sourceDatabase(LegacyV110SourceFixture.PROFILE);
    LegacyV110SourceFixture.load(wrongKey, SOURCE_KEY);
    LegacyDatabaseUpgradeException failure =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () ->
                LegacyDatabaseUpgrade.convert(
                    wrongKey,
                    UpgradePostgresTestSupport.pristineTargetDatabase(),
                    key(0x7f),
                    TARGET_KEY,
                    Map.of(),
                    false,
                    LegacyUpgradeSourceFixture.CLOCK));
    assertEquals(LegacyDatabaseUpgradeException.Stage.CREDENTIAL, failure.stage());
  }

  @Test
  void pendingAuthAndCredentialLeaseBlockTheDrainGuard() throws SQLException {
    DataSource pendingAuth =
        UpgradePostgresTestSupport.sourceDatabase(LegacyV110SourceFixture.PROFILE);
    LegacyV110SourceFixture.load(pendingAuth, SOURCE_KEY);
    execute(
        pendingAuth,
        "insert into plugin_auth_attempt (plugin_id, nonce_hash, auth_generation,"
            + " context_fingerprint, config_revision, region, expires_at) values"
            + " ('minimax-mavis', repeat('a', 64),"
            + " (select auth_generation from plugin_credential where plugin_id = 'minimax-mavis'),"
            + " (select context_fingerprint from plugin_credential where plugin_id = 'minimax-mavis'),"
            + " 1, 'cn', now() + interval '1 hour')");
    assertStage(
        LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN,
        pendingAuth,
        UpgradePostgresTestSupport.pristineTargetDatabase());

    DataSource lease = UpgradePostgresTestSupport.sourceDatabase(LegacyV110SourceFixture.PROFILE);
    LegacyV110SourceFixture.load(lease, SOURCE_KEY);
    execute(
        lease,
        "update plugin_credential set refresh_lease_token = 'leased',"
            + " refresh_lease_until = now() + interval '5 minutes'"
            + " where plugin_id = 'comfyui'");
    assertStage(
        LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN,
        lease,
        UpgradePostgresTestSupport.pristineTargetDatabase());
  }

  @Test
  void staticMaterialIsRejectedForACurrentSource() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase(LegacyV110SourceFixture.PROFILE);
    LegacyV110SourceFixture.load(source, SOURCE_KEY);
    LegacyDatabaseUpgradeException failure =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () ->
                LegacyDatabaseUpgrade.convert(
                    source,
                    UpgradePostgresTestSupport.pristineTargetDatabase(),
                    SOURCE_KEY,
                    TARGET_KEY,
                    LegacyUpgradeSourceFixture.staticSecrets(),
                    false,
                    LegacyUpgradeSourceFixture.CLOCK));
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONVERT, failure.stage());
  }

  private static void assertCatalogPreserved(DataSource source, DataSource target)
      throws SQLException {
    assertEquals(
        UpgradePostgresTestSupport.text(
            source, "select epoch from plugin_catalog_state where id = 1"),
        UpgradePostgresTestSupport.text(
            target, "select epoch from plugin_catalog_state where id = 1"));
    assertEquals(
        UpgradePostgresTestSupport.text(
            source,
            "select string_agg(epoch::text || ':' || manifest_json::text, '|' order by epoch)"
                + " from plugin_catalog_revision"),
        UpgradePostgresTestSupport.text(
            target,
            "select string_agg(epoch::text || ':' || manifest_json::text, '|' order by epoch)"
                + " from plugin_catalog_revision"));
    assertEquals(
        UpgradePostgresTestSupport.longValue(
            source, "select count(*) from plugin_configuration_revision"),
        UpgradePostgresTestSupport.longValue(
            target, "select count(*) from plugin_configuration_revision"));
    assertEquals(
        UpgradePostgresTestSupport.text(
            source,
            "select string_agg(values_json, '|' order by plugin_id, revision)"
                + " from plugin_configuration_revision"),
        UpgradePostgresTestSupport.text(
            target,
            "select string_agg(values_json, '|' order by plugin_id, revision)"
                + " from plugin_configuration_revision"));
    assertEquals(
        UpgradePostgresTestSupport.text(
            source,
            "select string_agg(auth_generation::text || ':' || context_fingerprint, '|'"
                + " order by plugin_id, revision) from plugin_configuration_revision"),
        UpgradePostgresTestSupport.text(
            target,
            "select string_agg(auth_generation::text || ':' || context_fingerprint, '|'"
                + " order by plugin_id, revision) from plugin_configuration_revision"));
  }

  /** 源端必须带 epoch 0、1、2 三段 manifest 与每 Plugin revision 1、2 两段配置历史，并逐字段复制到目标。 */
  private static void assertMultiEpochCatalogHistory(DataSource source, DataSource target)
      throws SQLException {
    assertEquals(
        "2",
        UpgradePostgresTestSupport.text(
            source, "select epoch::text from plugin_catalog_state where id = 1"));
    assertEquals(
        "2",
        UpgradePostgresTestSupport.text(
            target, "select epoch::text from plugin_catalog_state where id = 1"));
    assertEquals(
        3L,
        UpgradePostgresTestSupport.longValue(
            source, "select count(*) from plugin_catalog_revision"));
    assertEquals(
        3L,
        UpgradePostgresTestSupport.longValue(
            target, "select count(*) from plugin_catalog_revision"));
    String perPluginRevisions =
        "select string_agg(plugin_id || ':' || revision_count, '|' order by plugin_id) from ("
            + " select plugin_id, count(*)::text as revision_count"
            + " from plugin_configuration_revision group by plugin_id) r";
    assertEquals(
        UpgradePostgresTestSupport.text(source, perPluginRevisions),
        UpgradePostgresTestSupport.text(target, perPluginRevisions));
    assertEquals(
        "1",
        UpgradePostgresTestSupport.text(
            source, "select min(revision)::text from plugin_configuration_revision"));
    assertEquals(
        "2",
        UpgradePostgresTestSupport.text(
            source, "select max(revision)::text from plugin_configuration_revision"));
  }

  /** 凭据必须引用最后 epoch（epoch 2）manifest 里每个 Plugin 的真实认证代际与上下文指纹。 */
  private static void assertCredentialsReferenceCurrentEpoch(DataSource target)
      throws SQLException {
    String credentialIdentity =
        "select string_agg(plugin_id || ':' || auth_generation::text || ':' || context_fingerprint,"
            + " '|' order by plugin_id) from plugin_credential";
    String currentEpochIdentity =
        "select string_agg(c.plugin_id || ':' || (e.value->>'authGeneration') || ':'"
            + " || (e.value->>'contextFingerprint'), '|' order by c.plugin_id)"
            + " from plugin_credential c"
            + " join plugin_catalog_revision r on r.epoch = 2"
            + " join lateral jsonb_each(r.manifest_json->'plugins') e on e.key = c.plugin_id";
    assertEquals(
        UpgradePostgresTestSupport.text(target, currentEpochIdentity),
        UpgradePostgresTestSupport.text(target, credentialIdentity));
  }

  /** 已提交目标必须能被一次独立的真实 OfflinePluginCatalog.restore 按当前代码重新读取全部历史。 */
  private static void assertTargetCatalogRestores(DataSource target) throws SQLException {
    SystemSettings settings =
        new SystemSettingsCodec()
            .decode(
                UpgradePostgresTestSupport.text(target, "select config::text from system_setting"));
    PluginCatalogRecord record =
        new PostgresqlPluginConfigurationRepository(
                new PluginConfigurationJdbcStore(JdbcClient.create(target)),
                new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(target)))
            .readCatalog();
    try (OfflinePluginCatalog catalog = OfflinePluginCatalog.open(target, settings);
        PluginCatalogSnapshot ignored = catalog.restore(record)) {
      // restore 已重读 revision 2 历史并核对 manifest、上下文与能力指纹。
    }
  }

  private static void assertCredentialsRewrapped(DataSource source, DataSource target)
      throws SQLException {
    assertEquals(
        UpgradePostgresTestSupport.text(
            source,
            "select string_agg(plugin_id || ':' || auth_generation::text || ':'"
                + " || context_fingerprint || ':' || coalesce(region, '-') || ':' || status"
                + " || ':' || version, '|' order by plugin_id) from plugin_credential"),
        UpgradePostgresTestSupport.text(
            target,
            "select string_agg(plugin_id || ':' || auth_generation::text || ':'"
                + " || context_fingerprint || ':' || coalesce(region, '-') || ':' || status"
                + " || ':' || version, '|' order by plugin_id) from plugin_credential"));
    assertEquals(
        0L,
        UpgradePostgresTestSupport.count(
            target, "plugin_credential where refresh_lease_token is not null"));
    assertDecrypts(
        target,
        LegacyV110SourceFixture.INTERACTIVE_PLUGIN_ID,
        LegacyV110SourceFixture.INTERACTIVE_REGION,
        LegacyV110SourceFixture.INTERACTIVE_PLAINTEXT);
    assertDecrypts(
        target,
        LegacyV110SourceFixture.STATIC_PLUGIN_ID,
        null,
        LegacyV110SourceFixture.STATIC_PLAINTEXT);
    assertNotEquals(
        UpgradePostgresTestSupport.text(
            source,
            "select encode(encrypted_payload, 'hex') from plugin_credential where plugin_id = '"
                + LegacyV110SourceFixture.INTERACTIVE_PLUGIN_ID
                + "'"),
        UpgradePostgresTestSupport.text(
            target,
            "select encode(encrypted_payload, 'hex') from plugin_credential where plugin_id = '"
                + LegacyV110SourceFixture.INTERACTIVE_PLUGIN_ID
                + "'"));
  }

  private static void assertDecrypts(
      DataSource target, String pluginId, String region, String expected) throws SQLException {
    String authGeneration =
        UpgradePostgresTestSupport.text(
            target,
            "select auth_generation from plugin_credential where plugin_id = '" + pluginId + "'");
    String fingerprint =
        UpgradePostgresTestSupport.text(
            target,
            "select context_fingerprint from plugin_credential where plugin_id = '"
                + pluginId
                + "'");
    assertEquals(
        expected,
        CODEC.decrypt(
            TARGET_KEY,
            pluginId,
            UUID.fromString(authGeneration),
            fingerprint,
            region,
            UpgradePostgresTestSupport.bytes(
                target,
                "select encrypted_payload from plugin_credential where plugin_id = '"
                    + pluginId
                    + "'")));
  }

  private static void assertEnvironmentInstallConfigConverted(DataSource source, DataSource target)
      throws SQLException {
    assertEquals(
        "true",
        UpgradePostgresTestSupport.text(
            source, "select (install_config ? 'javaHome')::text from environment"));
    assertEquals(
        "false",
        UpgradePostgresTestSupport.text(
            target, "select (install_config ? 'javaHome')::text from environment"));
    assertEquals(
        "linux",
        UpgradePostgresTestSupport.text(
            target, "select install_config ->> 'operatingSystem' from environment"));
    assertEquals(
        "https://legacy-studio.example.invalid",
        UpgradePostgresTestSupport.text(
            target, "select install_config -> 'daemon' ->> 'studioUrl' from environment"));
  }

  private static void assertSettingsPreserved(DataSource source, DataSource target)
      throws SQLException {
    assertEquals(
        UpgradePostgresTestSupport.text(source, "select config::text from system_setting"),
        UpgradePostgresTestSupport.text(target, "select config::text from system_setting"));
    assertEquals(
        UpgradePostgresTestSupport.text(source, "select version::text from system_setting"),
        UpgradePostgresTestSupport.text(target, "select version::text from system_setting"));
  }

  private static void assertSkillTokenRewrapped(DataSource source, DataSource target)
      throws SQLException {
    String packageName = "legacy-skill";
    assertEquals(
        SkillTokenCipher.decrypt(
            SOURCE_KEY,
            packageName,
            UpgradePostgresTestSupport.bytes(
                source,
                "select encrypted_token from skill_package where package_name = '"
                    + packageName
                    + "'")),
        SkillTokenCipher.decrypt(
            TARGET_KEY,
            packageName,
            UpgradePostgresTestSupport.bytes(
                target,
                "select encrypted_token from skill_package where package_name = '"
                    + packageName
                    + "'")));
  }

  private static void assertHistoryPreserved(
      DataSource source, DataSource target, Map<String, String> sourceHistoryDigests)
      throws SQLException {
    for (Map.Entry<String, String> entry : sourceHistoryDigests.entrySet()) {
      assertEquals(
          entry.getValue(),
          UpgradePostgresTestSupport.text(target, digestSql(entry.getKey())),
          entry.getKey());
    }
  }

  private static Map<String, String> historyDigests(DataSource source) throws SQLException {
    Map<String, String> digests = new LinkedHashMap<>();
    for (String table : LegacySchemaProfile.V1_1_0_TABLES) {
      if (table.equals("environment")
          || table.equals("system_setting")
          || table.equals("plugin_credential")
          || table.equals("skill_package")) {
        continue;
      }
      digests.put(table, UpgradePostgresTestSupport.text(source, digestSql(table)));
    }
    return digests;
  }

  private static String digestSql(String table) {
    return "select coalesce(md5(string_agg(to_jsonb(t)::text, E'\\n' order by to_jsonb(t)::text)),"
        + " '(empty)') from "
        + table
        + " t";
  }

  private static Map<String, Long> counts(DataSource dataSource) throws SQLException {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : LegacySchemaProfile.V1_1_0_TABLES) {
      counts.put(table, UpgradePostgresTestSupport.count(dataSource, table));
    }
    return counts;
  }

  private static void assertStage(
      LegacyDatabaseUpgradeException.Stage stage, DataSource source, DataSource target)
      throws SQLException {
    LegacyDatabaseUpgradeException failure =
        assertThrows(LegacyDatabaseUpgradeException.class, () -> convert(source, target, false));
    assertEquals(stage, failure.stage());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  private static LegacyDatabaseUpgradeResult convert(
      DataSource source, DataSource target, boolean dryRun) {
    return LegacyDatabaseUpgrade.convert(
        source, target, SOURCE_KEY, TARGET_KEY, Map.of(), dryRun, LegacyUpgradeSourceFixture.CLOCK);
  }

  private static void execute(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }
}
