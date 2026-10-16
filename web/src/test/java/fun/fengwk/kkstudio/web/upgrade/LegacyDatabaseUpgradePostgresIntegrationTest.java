package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 真实 PostgreSQL 17 上的完整转换：同一份已排空的旧源分别做 dry run 与真实提交，逐条校验目标行数、文本 COPY 的标量改写、settings 升级、凭据重封装、目录
 * epoch CAS 与二次转换的 fail-closed。
 */
class LegacyDatabaseUpgradePostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x11);

  private static final SecretKey TARGET_KEY = key(0x22);

  private static final PluginCredentialCodec CODEC = new PluginCredentialCodec();

  /** dev 源先 dry run 再真实提交：dry run 之后目标仍 pristine，只有真实提交才推进 epoch。 */
  @Test
  void devSourceConvertsIntoAPristineTargetOnlyOnce() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    UpgradePostgresTestSupport.assertPristine(target);

    LegacyDatabaseUpgradeResult dry = convert(source, target, true);
    assertFalse(dry.applied());
    assertEquals("dev", dry.sourceProfile());
    assertEquals(1L, dry.rewrittenCredentials());
    assertEquals(1L, dry.installedStaticCredentials());
    UpgradePostgresTestSupport.assertPristine(target);

    LegacyDatabaseUpgradeResult applied = convert(source, target, false);
    assertTrue(applied.applied());
    assertEquals("dev", applied.sourceProfile());
    assertEquals(dry.rowCounts(), applied.rowCounts());
    assertEquals(0L, applied.retiredClusterNodeRows());
    assertEquals(1L, applied.rowCounts().get("agent_provider"));
    assertEquals(1L, applied.rowCounts().get("agent_model"));
    assertEquals(1L, applied.rowCounts().get("agent_definition"));
    assertEquals(1L, applied.rowCounts().get("environment"));
    assertEquals(1L, applied.rowCounts().get("storage_blob"));
    assertEquals(1L, applied.rowCounts().get("chat"));
    assertEquals(1L, applied.rowCounts().get("mcp_server"));
    assertEquals(1L, applied.rowCounts().get("mcp_tool"));
    assertEquals(1L, applied.rowCounts().get("system_setting"));
    assertEquals(2L, applied.rowCounts().get("plugin_credential"));
    assertEquals(1L, applied.rowCounts().get("harness_session"));
    assertEquals(1L, applied.rowCounts().get("harness_thread"));
    assertEquals(6L, applied.rowCounts().get("harness_entry"));
    assertEquals(2L, applied.rowCounts().get("harness_thread_command"));
    assertEquals(1L, applied.rowCounts().get("harness_thread_stop_receipt"));
    assertEquals(new TreeSet<>(LegacySchemaProfile.SHARED_TABLES), applied.rowCounts().keySet());

    assertConvertedScalars(target);
    assertConvertedHarness(target);
    assertConvertedSettings(target);
    assertConvertedCredentials(target);
    assertCatalogInitialized(target);

    LegacyDatabaseUpgradeException second =
        assertThrows(LegacyDatabaseUpgradeException.class, () -> convert(source, target, false));
    assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE, second.stage());
    assertFalse(second.outcomeUncertain());
  }

  /** MAIN 源同样被识别为真实部署并在 dry run 里走通同一条写入与校验路径。 */
  @Test
  void mainSourceIsRecognizedAndConvertsInDryRun() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("main");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyUpgradeSourceFixture.retireClusterNodes(source, 2);

    LegacyDatabaseUpgradeResult dry = convert(source, target, true);
    assertFalse(dry.applied());
    assertEquals("main", dry.sourceProfile());
    assertEquals(2L, dry.retiredClusterNodeRows());
    assertEquals(1L, dry.rowCounts().get("agent_provider"));
    UpgradePostgresTestSupport.assertPristine(target);
  }

  private static LegacyDatabaseUpgradeResult convert(
      DataSource source, DataSource target, boolean dryRun) {
    return LegacyDatabaseUpgrade.convert(
        source,
        target,
        SOURCE_KEY,
        TARGET_KEY,
        LegacyUpgradeSourceFixture.staticSecrets(),
        dryRun,
        LegacyUpgradeSourceFixture.CLOCK);
  }

  /** 文本 COPY 只改 provider_type 的旧 wire 值，其余列与行数原样。 */
  private static void assertConvertedScalars(DataSource target) throws SQLException {
    String provider = "'" + LegacyUpgradeSourceFixture.PROVIDER + "'";
    assertEquals(
        "openai_responses",
        UpgradePostgresTestSupport.text(
            target, "select provider_type from agent_provider where name = " + provider));
    assertEquals(
        "{\"temperature\": 0.5}",
        UpgradePostgresTestSupport.text(
            target, "select config::text from agent_provider where name = " + provider));
    assertEquals(
        7L,
        UpgradePostgresTestSupport.longValue(
            target, "select version from agent_provider where name = " + provider));
    assertEquals(
        "legacy-inline-credential",
        UpgradePostgresTestSupport.text(
            target, "select credential from agent_provider where name = " + provider));
    assertEquals(
        "2025-02-03 04:05:06.789",
        UpgradePostgresTestSupport.text(
            target,
            "select to_char(updated_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS')"
                + " from agent_provider where name = "
                + provider));
    assertEquals(
        "2026-02-01 00:00:00.000",
        UpgradePostgresTestSupport.text(
            target,
            "select to_char(create_time at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS')"
                + " from plugin_credential where plugin_id = 'comfyui'"));
  }

  /** 六节 settings 保留源行的 id / version / 时间，且不再携带旧 integrations 节。 */
  private static void assertConvertedSettings(DataSource target) throws SQLException {
    assertEquals(1L, UpgradePostgresTestSupport.count(target, "system_setting"));
    assertEquals(
        12L, UpgradePostgresTestSupport.longValue(target, "select version from system_setting"));
    assertEquals(
        0L,
        UpgradePostgresTestSupport.longValue(
            target, "select count(*) from system_setting where config ? 'integrations'"));
    assertEquals(
        6L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select (select count(*) from jsonb_object_keys(config))::bigint from system_setting"));
    assertEquals(
        "2025-06-07 08:09:10.111",
        UpgradePostgresTestSupport.text(
            target,
            "select to_char(created_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS')"
                + " from system_setting"));
    assertEquals(
        "false",
        UpgradePostgresTestSupport.text(
            target,
            "select values_json::jsonb ->> 'enabled' from plugin_configuration_revision"
                + " where plugin_id = 'comfyui' and revision = 1"));
    // 显式提供了 comfyui 静态密钥，因此启用的普通配置声明 BEARER，而不是任何默认兜底。
    assertEquals(
        "BEARER",
        UpgradePostgresTestSupport.text(
            target,
            "select values_json::jsonb ->> 'authMode' from plugin_configuration_revision"
                + " where plugin_id = 'comfyui' and revision = 1"));
  }

  /** 旧 format 1 行按 format 2 以目标主密钥与真实代际重封装，仍解出同一份不透明明文。 */
  private static void assertConvertedCredentials(DataSource target) throws SQLException {
    String pluginId = "'" + LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID + "'";
    String authGeneration =
        UpgradePostgresTestSupport.text(
            target, "select auth_generation from plugin_credential where plugin_id = " + pluginId);
    String fingerprint =
        UpgradePostgresTestSupport.text(
            target,
            "select context_fingerprint from plugin_credential where plugin_id = " + pluginId);
    assertEquals(
        LegacyUpgradeSourceFixture.LEGACY_CREDENTIAL_PLAINTEXT,
        CODEC.decrypt(
            TARGET_KEY,
            LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID,
            UUID.fromString(authGeneration),
            fingerprint,
            LegacyUpgradeSourceFixture.LEGACY_REGION,
            UpgradePostgresTestSupport.bytes(
                target,
                "select encrypted_payload from plugin_credential where plugin_id = " + pluginId)));
    assertEquals(
        "CONNECTED",
        UpgradePostgresTestSupport.text(
            target, "select status from plugin_credential where plugin_id = " + pluginId));
    assertEquals(
        4L,
        UpgradePostgresTestSupport.longValue(
            target, "select version from plugin_credential where plugin_id = " + pluginId));
    assertEquals(
        0L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from plugin_credential where refresh_lease_token is not null"
                + " or refresh_lease_until is not null"));
    assertFalse(
        Arrays.equals(
            LegacyUpgradeSourceFixture.legacyEnvelope(SOURCE_KEY),
            UpgradePostgresTestSupport.bytes(
                target,
                "select encrypted_payload from plugin_credential where plugin_id = " + pluginId)));

    assertEquals(
        0L,
        UpgradePostgresTestSupport.longValue(
            target, "select version from plugin_credential where plugin_id = 'comfyui'"));
    assertEquals(
        0L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from plugin_credential where plugin_id = 'comfyui'"
                + " and (region is not null or expires_at is not null)"));
    String staticPlaintext =
        CODEC.decrypt(
            TARGET_KEY,
            "comfyui",
            UUID.fromString(
                UpgradePostgresTestSupport.text(
                    target,
                    "select auth_generation from plugin_credential where plugin_id = 'comfyui'")),
            UpgradePostgresTestSupport.text(
                target,
                "select context_fingerprint from plugin_credential where plugin_id = 'comfyui'"),
            null,
            UpgradePostgresTestSupport.bytes(
                target,
                "select encrypted_payload from plugin_credential where plugin_id = 'comfyui'"));
    assertTrue(staticPlaintext.contains(LegacyUpgradeSourceFixture.COMFYUI_API_KEY));
  }

  /** 文本 COPY 的历史转换：replay state 的旧 provider 别名被改名，tool result metadata 被补上显式 null binding。 */
  private static void assertConvertedHarness(DataSource target) throws SQLException {
    String assistantEntry = "'" + LegacyHarnessHistoryFixture.ASSISTANT_ENTRY_ID + "'";
    assertEquals(
        1L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from harness_entry where id = "
                + assistantEntry
                + " and provider_replay_state = cast('"
                + LegacyHarnessHistoryFixture.upgradedReplayState()
                + "' as jsonb)"));
    String metadata = "payload -> 'toolResultMetadata'";
    String toolResult = "'" + LegacyHarnessHistoryFixture.TOOL_RESULT_ENTRY_ID + "'";
    assertEquals(
        9L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select (select count(*) from jsonb_object_keys("
                + metadata
                + "))::bigint from harness_entry where id = "
                + toolResult));
    assertEquals(
        "null",
        UpgradePostgresTestSupport.text(
            target,
            "select "
                + metadata
                + " -> 'binding' from harness_entry where id = "
                + toolResult
                + " and "
                + metadata
                + " ? 'binding'"));
    assertEquals(
        "SUCCEEDED",
        UpgradePostgresTestSupport.text(
            target,
            "select " + metadata + " ->> 'status' from harness_entry where id = " + toolResult));
    assertEquals(
        LegacyHarnessHistoryFixture.ASSISTANT_ENTRY_ID.toString(),
        UpgradePostgresTestSupport.text(
            target,
            "select "
                + metadata
                + " ->> 'assistantEntryId' from harness_entry where id = "
                + toolResult));
    assertEquals(
        LegacyHarnessHistoryFixture.CANCELLED_COMMAND_IDEMPOTENCY.toString(),
        UpgradePostgresTestSupport.text(
            target,
            "select cancelled_inputs -> 0 ->> 'idempotencyKey' from harness_thread_stop_receipt"
                + " where thread_id = '"
                + LegacyHarnessHistoryFixture.THREAD_ID
                + "'"));
    assertEquals(
        LegacyHarnessHistoryFixture.TURN_END_ENTRY_ID.toString(),
        UpgradePostgresTestSupport.text(
            target,
            "select head_entry_id from harness_thread where id = '"
                + LegacyHarnessHistoryFixture.THREAD_ID
                + "'"));
    assertEquals(
        1L,
        UpgradePostgresTestSupport.count(
            target,
            "harness_thread_command where cancelled_at is not null and applied_entry_id is null"));
  }

  /** 目录只有一个已初始化 epoch：指针推进到 1，四个已安装 Plugin 各有一行 revision 1 历史。 */
  private static void assertCatalogInitialized(DataSource target) throws SQLException {
    assertEquals(
        1L,
        UpgradePostgresTestSupport.longValue(
            target, "select epoch from plugin_catalog_state where id = 1"));
    assertEquals(
        1L, UpgradePostgresTestSupport.count(target, "plugin_catalog_revision where epoch = 1"));
    assertEquals(
        4L,
        UpgradePostgresTestSupport.count(
            target, "plugin_configuration_revision where revision = 1"));
    assertEquals(
        0L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from plugin_catalog_revision where manifest_json -> 'plugins'"
                + " is null"));
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }
}
