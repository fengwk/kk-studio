package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.impl.PostgresqlEnvironmentChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * J4 密集跨表引用的真实 PostgreSQL 17 保真验收：在冻结 DEV / MAIN 源上补齐所有可非空持久表的真实代表行后，逐表核对原生 binary COPY
 * 的完整行、逐字段核对四类转换、验证 self-FK 的 child-first 单语句语义，并用真实终态 V4 Canvas 历史确认 引用没有被重写或丢失。
 *
 * <p>它同时证明源端 drain 守卫对真实 Canvas 坏形态（字符串 {@code "4"}、未知 version、READY / RUNNING / UNKNOWN、 stage 与
 * status 不一致）fail closed，且拒绝后目标保持 pristine。
 */
class LegacyDatabaseUpgradeFidelityPostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x55);

  private static final SecretKey TARGET_KEY = key(0x66);

  private static final PluginCredentialCodec CODEC = new PluginCredentialCodec();

  /** LegacyUpgradeSourceFixture 写入的全局 storage blob（只在本测试的断言中引用）。 */
  private static final String BLOB_ID = "6c1f5a1e-58b2-4b1c-9d7e-2f3a4b5c6d7e";

  /** LegacyUpgradeSourceFixture 写入的源 Environment；导入后必须能被当前 EnvironmentRegistry 读回。 */
  private static final UUID LEGACY_ENVIRONMENT_ID =
      UUID.fromString("0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f");

  /** 只用于构造读侧 registry 的节点身份，不参与任何写入。 */
  private static final UUID REGISTRY_NODE_ID =
      UUID.fromString("9f9f9f9f-0000-4000-8000-0000000000ff");

  /** 只被文本转换改写的四张表；其余 37 张共有表必须是原生逐字节保真。 */
  private static final Set<String> CONVERTED_TABLES =
      Set.of("agent_provider", "harness_entry", "plugin_credential", "system_setting");

  private static final String PROVIDER_PROJECTION =
      "select name, description, provider_type, base_url, credential, config::text,"
          + " connection_generation_id::text, created_at::text, updated_at::text, version"
          + " from agent_provider order by name";

  private static final String ENTRY_SELECTION =
      "select id, session_id, parent_entry_id, entry_type, payload::text, created_at::text,"
          + " provider_replay_state::text from harness_entry order by id";

  private static final String ENTRY_CHECK =
      "select count(*) from harness_entry where id = cast(? as uuid)"
          + " and session_id = cast(? as uuid) and parent_entry_id is not distinct from cast(? as uuid)"
          + " and entry_type = ? and payload = cast(? as jsonb)"
          + " and created_at = cast(? as timestamptz)"
          + " and provider_replay_state is not distinct from cast(? as jsonb)";

  /** DEV 密集源：干跑不改目标、真实提交逐表对账，且源库在被读取前后逐表不变。 */
  @Test
  void denseDevSourceConvertsWithPerTableFidelity() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    loadDenseSource(source, "dev");

    Map<String, String> sourceBefore = digests(source);
    assertChildFirstPhysicalOrder(source);

    LegacyDatabaseUpgradeResult dry = convert(source, target, true);
    assertFalse(dry.applied());
    assertEquals("dev", dry.sourceProfile());
    assertEquals(1L, dry.rewrittenCredentials());
    assertEquals(1L, dry.installedStaticCredentials());
    UpgradePostgresTestSupport.assertPristine(target);
    assertEquals(sourceBefore, digests(source));

    LegacyDatabaseUpgradeResult applied = convert(source, target, false);
    assertTrue(applied.applied());
    assertEquals("dev", applied.sourceProfile());
    assertEquals(dry.rowCounts(), applied.rowCounts());
    assertEquals(0L, applied.retiredClusterNodeRows());
    assertEquals(new TreeMap<>(expectedRowCounts(source, 1L)), applied.rowCounts());
    assertEquals(LegacySchemaProfile.SHARED_TABLES.size(), applied.rowCounts().size());

    // 源库在真实提交后逐表不变，包括被拒绝/重写的表：源历史字节没有被就地改写。
    assertEquals(sourceBefore, digests(source));
    assertDigestFidelity(sourceBefore, digests(target));
    assertRawTablesAreByteIdentical(source, target);
    assertProviderFidelity(source, target);
    assertHarnessEntryFidelity(source, target);
    assertConvertedSettings(source, target);
    assertConvertedCredentials(source, target);
    assertCanvasHistoryPreserved(source, target);
    assertDenseReferencesPreserved(target);
    // 目标正常读取方必须能消费导入的 READY 连接：DEV canonical metadata 与当前 codec 同代，必须成功。
    assertImportedEnvironmentConnectionReadable(target);

    LegacyDatabaseUpgradeException second =
        assertThrows(LegacyDatabaseUpgradeException.class, () -> convert(source, target, false));
    assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE, second.stage());
    assertFalse(second.outcomeUncertain());
  }

  /** MAIN 密集源同样通过 profile 识别、drain 守卫与四类转换，且 cluster_node 按已到期 lease 退休。 */
  @Test
  void denseMainSourceConvertsInDryRun() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("main");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    loadDenseSource(source, "main");
    LegacyUpgradeSourceFixture.retireClusterNodes(source, 3);

    Map<String, String> sourceBefore = digests(source);
    LegacyDatabaseUpgradeResult dry = convert(source, target, true);
    assertFalse(dry.applied());
    assertEquals("main", dry.sourceProfile());
    assertEquals(3L, dry.retiredClusterNodeRows());
    assertEquals(new TreeMap<>(expectedRowCounts(source, 1L)), dry.rowCounts());
    UpgradePostgresTestSupport.assertPristine(target);
    assertEquals(sourceBefore, digests(source));
  }

  /**
   * MAIN 源真实留下的是 capabilities 版本 5 的 READY metadata（environment 含 architecture）。导入后 target 必须能用当前
   * EnvironmentRegistry 正常读回该连接；这里若失败即「MAIN V5 metadata 无法被当前读取方消费」的真实缺陷，不得靠把 runtime_info 改 null
   * 或降级成版本 4 来凑绿。
   */
  @Test
  void importedMainEnvironmentConnectionIsReadableByTheCurrentRegistry() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("main");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    loadDenseSource(source, "main");
    LegacyUpgradeSourceFixture.retireClusterNodes(source, 3);

    LegacyDatabaseUpgradeResult applied = convert(source, target, false);
    assertTrue(applied.applied());
    assertEquals("main", applied.sourceProfile());
    assertImportedEnvironmentConnectionReadable(target);
  }

  /** 真实坏 Canvas 形态必须在 drain 守卫处 fail closed，且目标保持 pristine（不做任何部分写入）。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCanvasRuns")
  void invalidCanvasRunIsRejectedAndTargetStaysPristine(
      String name, String status, String stateJson, String error) throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    loadDenseSource(source, "dev");
    LegacyReferenceDataFixture.rewriteCanvasRun(
        source, LegacyReferenceDataFixture.CANVAS_SUCCEEDED_NODE, status, stateJson, error);

    LegacyDatabaseUpgradeException errorThrown =
        assertThrows(LegacyDatabaseUpgradeException.class, () -> convert(source, target, false));
    assertEquals(LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN, errorThrown.stage());
    assertFalse(errorThrown.outcomeUncertain());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  static Stream<Arguments> invalidCanvasRuns() {
    return Stream.of(
        Arguments.of(
            "stringVersion", "SUCCEEDED", "{\"version\":\"4\",\"stage\":\"SUCCEEDED\"}", null),
        Arguments.of(
            "unknownVersion", "SUCCEEDED", "{\"version\":7,\"stage\":\"SUCCEEDED\"}", null),
        Arguments.of("ready", "READY", "{\"version\":4,\"stage\":\"READY\"}", null),
        Arguments.of("running", "RUNNING", "{\"version\":4,\"stage\":\"RUNNING\"}", null),
        Arguments.of(
            "unknown",
            "UNKNOWN",
            "{\"version\":4,\"stage\":\"UNKNOWN\"}",
            "legacy unknown outcome"),
        Arguments.of("stageMismatch", "SUCCEEDED", "{\"version\":4,\"stage\":\"FAILED\"}", null));
  }

  private static void loadDenseSource(DataSource source, String profile) throws SQLException {
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    LegacyReferenceDataFixture.load(source, profile);
  }

  /**
   * 用当前正常读取方（{@link EnvironmentRegistry}）读回目标里导入的连接行：这会真实走 {@code runtime_info} / {@code
   * skill_state} / {@code recent_events} 的无条件严格 decode，是 source→target metadata 语义可消费性的直接证据，绝不复刻
   * codec。
   */
  private static void assertImportedEnvironmentConnectionReadable(DataSource target)
      throws SQLException {
    EnvironmentRegistry registry =
        new EnvironmentRegistry(
            new JdbcTemplate(target),
            REGISTRY_NODE_ID,
            Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC),
            new DataSourceTransactionManager(target),
            new PostgresqlEnvironmentChangeNotifier(new JdbcTemplate(target)));
    List<EnvironmentConnection> connections = registry.list();
    assertEquals(1, connections.size());
    EnvironmentConnection connection = connections.get(0);
    assertEquals(LEGACY_ENVIRONMENT_ID, connection.environmentId().value());
    assertEquals(LiveEnvironmentStatus.READY, connection.status());
    assertNotNull(connection.daemonCapabilities(), "READY 连接的 runtime_info 必须能被当前 codec 解码");
    assertEquals(1, connection.skillState().size());
    assertEquals("legacy-skill", connection.skillState().get(0).packageName());
    assertEquals(1, connection.recentEvents().size());
    assertEquals("READY", connection.recentEvents().get(0).type());
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select count(*) from environment_connection where environment_id = '"
                    + LEGACY_ENVIRONMENT_ID
                    + "'"));
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

  /**
   * 每个含 self-FK 的表真实以 child-first 堆顺序被 COPY：先断言源库物理顺序里每个子行的父行都更靠后，因此任何逐行 INSERT 的 COPY
   * 都会被真实即时外键拒绝，而单语句 COPY 在语句末校验时成功。
   */
  private static void assertChildFirstPhysicalOrder(DataSource source) throws SQLException {
    assertParentFollowsChild(
        source,
        "select id::text, parent_entry_id::text from harness_entry where session_id = '"
            + LegacyReferenceDataFixture.SESSION
            + "' order by ctid");
    assertParentFollowsChild(
        source,
        "select id::text, parent_thread_id::text from harness_thread where session_id = '"
            + LegacyReferenceDataFixture.SESSION
            + "' order by ctid");
    assertParentFollowsChild(
        source,
        "select invocation_id::text, superseded_by_invocation_id::text from harness_thread_join"
            + " order by ctid");
  }

  private static void assertParentFollowsChild(DataSource source, String sql) throws SQLException {
    List<String[]> rows = new ArrayList<>();
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {
        rows.add(new String[] {result.getString(1), result.getString(2)});
      }
    }
    assertTrue(rows.size() >= 2, "a self-referencing table needs at least one parent/child pair");
    boolean hasChild = false;
    for (String[] row : rows) {
      if (row[1] == null) {
        continue;
      }
      hasChild = true;
      int parentIndex = indexOf(rows, row[1]);
      int childIndex = indexOf(rows, row[0]);
      assertTrue(parentIndex > childIndex, "the parent row must follow its child: " + row[0]);
    }
    assertTrue(hasChild, "the self-referencing probe must contain a real child row");
  }

  private static int indexOf(List<String[]> rows, String id) {
    for (int index = 0; index < rows.size(); index++) {
      if (rows.get(index)[0].equals(id)) {
        return index;
      }
    }
    throw new IllegalStateException("the referenced row is missing: " + id);
  }

  /** 41 张共有表的按 PK 稳定排序原生摘要：只有四类转换表允许不同，其余 37 张必须逐字节一致。 */
  private static void assertDigestFidelity(
      Map<String, String> sourceDigests, Map<String, String> targetDigests) {
    assertEquals(LegacySchemaProfile.SHARED_TABLES.size(), sourceDigests.size());
    for (String table : LegacySchemaProfile.SHARED_TABLES) {
      if (CONVERTED_TABLES.contains(table)) {
        assertNotEquals(sourceDigests.get(table), targetDigests.get(table), table);
      } else {
        assertEquals(sourceDigests.get(table), targetDigests.get(table), table);
      }
    }
  }

  /** 37 张原生表的完整行必须与源逐字段相同。 */
  private static void assertRawTablesAreByteIdentical(DataSource source, DataSource target)
      throws SQLException {
    for (String table : LegacySchemaProfile.SHARED_TABLES) {
      if (CONVERTED_TABLES.contains(table)) {
        continue;
      }
      String columns = primaryKeyColumns(source, table);
      assertEquals(
          readTextRows(source, "select * from " + table + " order by " + columns),
          readTextRows(target, "select * from " + table + " order by " + columns),
          table);
    }
  }

  /** agent_provider 只把 provider_type 归一化为当前 wire 值，其余列与 config 中的同名字面量原样保留。 */
  private static void assertProviderFidelity(DataSource source, DataSource target)
      throws SQLException {
    List<List<String>> expected = readTextRows(source, PROVIDER_PROJECTION);
    for (List<String> row : expected) {
      row.set(2, LegacyHarnessHistoryJson.providerType(row.get(2)));
    }
    assertEquals(expected, readTextRows(target, PROVIDER_PROJECTION));
    // 两条旧 singular 行都被归一化为当前 plural，无旧 wire 值残留；已知当前值原样通过。
    assertEquals(
        2L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select count(*) from agent_provider where provider_type = 'openai_responses'"));
    assertEquals(
        0L,
        UpgradePostgresTestSupport.longValue(
            target, "select count(*) from agent_provider where provider_type = 'openai_response'"));
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target, "select count(*) from agent_provider where provider_type = 'anthropic'"));
    // config 中同名字面量绝不被全局字符串替换。
    assertEquals(
        1L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from agent_provider where name = 'legacy-openai-secondary'"
                + " and config ->> 'note' = 'openai_response literal must survive'"));
  }

  /**
   * harness_entry 的完整字段比较用 jsonb 值相等完成：只有 {@code payload} 的旧 8 键 tool metadata 补显式 null binding、
   * {@code provider_replay_state} 的旧 provider 别名改名，其余列（含精确 BigDecimal 数值）逐字段保持。
   */
  private static void assertHarnessEntryFidelity(DataSource source, DataSource target)
      throws SQLException {
    List<List<String>> rows = readTextRows(source, ENTRY_SELECTION);
    assertTrue(rows.size() >= 8, "the dense fixture must exercise several history entries");
    try (Connection connection = target.getConnection();
        PreparedStatement statement = connection.prepareStatement(ENTRY_CHECK)) {
      for (List<String> row : rows) {
        statement.setString(1, row.get(0));
        statement.setString(2, row.get(1));
        setNullable(statement, 3, row.get(2));
        statement.setString(4, row.get(3));
        statement.setString(
            5, LegacyHarnessHistoryJson.entryPayload(EntryType.valueOf(row.get(3)), row.get(4)));
        statement.setString(6, row.get(5));
        setNullable(statement, 7, LegacyHarnessHistoryJson.providerReplayState(row.get(6)));
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          assertEquals(1L, result.getLong(1), "converted history entry " + row.get(0));
        }
      }
    }
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select count(*) from harness_entry where id = '"
                    + LegacyReferenceDataFixture.TOOL_RESULT_ENTRY
                    + "' and jsonb_typeof(payload -> 'toolResultMetadata' -> 'binding') = 'null'"));
    assertEquals(
        "openai_responses",
        UpgradePostgresTestSupport.text(
            target,
            "select provider_replay_state -> 'affinity' ->> 'providerType' from harness_entry"
                + " where id = '"
                + LegacyReferenceDataFixture.ASSISTANT_ENTRY
                + "'"));
  }

  /** 六节 settings 保留源行的 id / version / 两个应用侧时间，且不再携带旧 integrations 节。 */
  private static void assertConvertedSettings(DataSource source, DataSource target)
      throws SQLException {
    List<List<String>> sourceRows =
        readTextRows(
            source,
            "select id::text, version::text, created_at::text, updated_at::text from system_setting");
    List<List<String>> targetRows =
        readTextRows(
            target,
            "select id::text, version::text, created_at::text, updated_at::text from system_setting");
    assertEquals(sourceRows, targetRows);
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select count(*) from system_setting where"
                    + " (select count(*) from jsonb_object_keys(config)) = 6"
                    + " and not config ? 'integrations'"));
    assertNotEquals(
        UpgradePostgresTestSupport.text(source, "select config::text from system_setting"),
        UpgradePostgresTestSupport.text(target, "select config::text from system_setting"));
  }

  /** 旧 format 1 行按 format 2 真实重封装并逐字节解出同一份不透明明文；源密文保持旧格式。 */
  private static void assertConvertedCredentials(DataSource source, DataSource target)
      throws SQLException {
    String pluginId = "'" + LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID + "'";
    assertEquals(
        1L,
        UpgradePostgresTestSupport.count(
            source, "plugin_credential where plugin_id = " + pluginId));
    // 源行仍是旧 format 1：GCM nonce 随机，因此不做字节相等，直接用生产同款 legacy reader 与源密钥解出同一份明文。
    byte[] sourceEnvelope =
        UpgradePostgresTestSupport.bytes(
            source,
            "select encrypted_payload from plugin_credential where plugin_id = " + pluginId);
    assertEquals(1, sourceEnvelope[0], "the legacy source envelope keeps format 1");
    assertEquals(
        LegacyUpgradeSourceFixture.LEGACY_CREDENTIAL_PLAINTEXT,
        LegacyPluginCredentialCodec.decrypt(
            SOURCE_KEY,
            LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID,
            LegacyUpgradeSourceFixture.LEGACY_REGION,
            sourceEnvelope));
    assertEquals(2L, UpgradePostgresTestSupport.count(target, "plugin_credential"));
    String authGeneration =
        UpgradePostgresTestSupport.text(
            target, "select auth_generation from plugin_credential where plugin_id = " + pluginId);
    String fingerprint =
        UpgradePostgresTestSupport.text(
            target,
            "select context_fingerprint from plugin_credential where plugin_id = " + pluginId);
    byte[] targetEnvelope =
        UpgradePostgresTestSupport.bytes(
            target,
            "select encrypted_payload from plugin_credential where plugin_id = " + pluginId);
    // 目标密文必须用当前 format 2 + targetKey 在同一 scope 下解出与源逐字节相同的明文。
    assertEquals(
        LegacyUpgradeSourceFixture.LEGACY_CREDENTIAL_PLAINTEXT,
        CODEC.decrypt(
            TARGET_KEY,
            LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID,
            UUID.fromString(authGeneration),
            fingerprint,
            LegacyUpgradeSourceFixture.LEGACY_REGION,
            targetEnvelope));
    // 两条实际密文（源实查 / 目标实查）必须不同，且目标行不再能被旧 format 1 reader 读取。
    assertFalse(
        Arrays.equals(sourceEnvelope, targetEnvelope),
        "the rewritten credential must not reuse the source ciphertext bytes");
    assertThrows(
        LegacyPluginCredentialCodec.LegacyCredentialUnreadableException.class,
        () ->
            LegacyPluginCredentialCodec.decrypt(
                SOURCE_KEY,
                LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID,
                LegacyUpgradeSourceFixture.LEGACY_REGION,
                targetEnvelope));
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

  /**
   * Canvas V4 终态历史必须原样保留：三条终态 Run 的 state_json、resource/blob 引用与 pins 在目标逐值存在，没有被重写成 V5、 没有丢 blob 或
   * pins，节点/资源/幂等行也一个不少。
   */
  private static void assertCanvasHistoryPreserved(DataSource source, DataSource target)
      throws SQLException {
    assertEquals(3L, UpgradePostgresTestSupport.count(target, "canvas_function_run"));
    assertEquals(
        0L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from canvas_function_run where state_json -> 'version' <> '4'::jsonb"));
    List<List<String>> runs =
        readTextRows(
            source,
            "select node_id::text, request_id::text, status, attempt::text, state_json::text,"
                + " error from canvas_function_run order by node_id, request_id");
    try (Connection connection = target.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from canvas_function_run where node_id = cast(? as uuid)"
                    + " and request_id = cast(? as uuid) and status = ? and attempt = cast(? as integer)"
                    + " and state_json = cast(? as jsonb) and error is not distinct from cast(? as text)")) {
      for (List<String> run : runs) {
        statement.setString(1, run.get(0));
        statement.setString(2, run.get(1));
        statement.setString(3, run.get(2));
        statement.setString(4, run.get(3));
        statement.setString(5, run.get(4));
        setNullable(statement, 6, run.get(5));
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          assertEquals(1L, result.getLong(1), "preserved canvas run " + run.get(0));
        }
      }
    }
    assertEquals(4L, UpgradePostgresTestSupport.count(target, "canvas_node"));
    assertEquals(3L, UpgradePostgresTestSupport.count(target, "canvas_resource"));
    assertEquals(2L, UpgradePostgresTestSupport.count(target, "canvas_function_resource_pin"));
    assertEquals(1L, UpgradePostgresTestSupport.count(target, "canvas_command_dedup"));
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select count(*) from canvas_resource where blob_id = '"
                    + BLOB_ID
                    + "' and owner_node_id = '"
                    + LegacyReferenceDataFixture.CANVAS_SUCCEEDED_NODE
                    + "'"));
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select count(*) from canvas_function_resource_pin where role = 'OUTPUT'"
                    + " and resource_id = '1e620000-0000-4000-8000-0000000000e0'"));
  }

  /** Project / Issue / Join / Stop 回执的密集引用链在目标完整存在。 */
  private static void assertDenseReferencesPreserved(DataSource target) throws SQLException {
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select count(*) from project_issue_run where status = 'COMPLETED'"
                    + " and next_state = 'REVIEW' and remaining_execution_ms = 0"));
    assertEquals(6L, UpgradePostgresTestSupport.count(target, "project_issue_activity"));
    assertEquals(1L, UpgradePostgresTestSupport.count(target, "project_issue_evidence"));
    assertEquals(1L, UpgradePostgresTestSupport.count(target, "project_issue_agent_thread"));
    assertEquals(2L, UpgradePostgresTestSupport.count(target, "harness_thread_join"));
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select count(*) from harness_thread_join where superseded_by_invocation_id = '"
                    + LegacyReferenceDataFixture.SUPERSEDING_JOIN
                    + "' and terminal_entry_id is null"));
    // 密集 fixture 自己为 root Thread 留下两条回执（child + root）；J3a 历史 fixture 的另一条不计入本断言。
    assertEquals(
        2L,
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from harness_thread_stop_receipt where root_thread_id = '"
                + LegacyReferenceDataFixture.ROOT_THREAD
                + "'"));
    assertEquals(
        1L,
        (long)
            UpgradePostgresTestSupport.longValue(
                target,
                "select cancelled_command_count from harness_thread_stop_receipt where thread_id = '"
                    + LegacyReferenceDataFixture.CHILD_THREAD
                    + "'"));
  }

  /** 逐表目标预期行数：除 plugin_credential 加上显式静态密钥行外，全部与源行数一致。 */
  private static Map<String, Long> expectedRowCounts(DataSource source, long installedStatic)
      throws SQLException {
    Map<String, Long> expected = new TreeMap<>();
    long credentials = 0;
    for (String table : LegacySchemaProfile.SHARED_TABLES) {
      long rows = UpgradePostgresTestSupport.count(source, table);
      if ("plugin_credential".equals(table)) {
        credentials = rows;
        continue;
      }
      expected.put(table, rows);
    }
    expected.put("plugin_credential", credentials + installedStatic);
    return expected;
  }

  /** 41 张共有表的按 PK 稳定排序原生摘要：jsonb / bytea / 数组 / 高精度数值都参与比较。 */
  private static Map<String, String> digests(DataSource dataSource) throws SQLException {
    Map<String, String> digests = new TreeMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("set time zone 'UTC'");
      statement.execute("set datestyle = 'ISO, MDY'");
      for (String table : LegacySchemaProfile.SHARED_TABLES) {
        String order = primaryKeyColumns(statement.getConnection(), table);
        try (ResultSet rows =
            statement.executeQuery(
                "select coalesce(md5(string_agg(to_jsonb(t)::text, E'\\n' order by "
                    + order
                    + ")), 'EMPTY') from "
                    + table
                    + " t")) {
          rows.next();
          digests.put(table, rows.getString(1));
        }
      }
    }
    return digests;
  }

  private static String primaryKeyColumns(DataSource dataSource, String table) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      return primaryKeyColumns(connection, table);
    }
  }

  private static String primaryKeyColumns(Connection connection, String table) throws SQLException {
    List<String> columns = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select a.attname from pg_index i"
                + " join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)"
                + " where i.indrelid = to_regclass(?::text) and i.indisprimary"
                + " order by array_position(i.indkey, a.attnum)")) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          columns.add(rows.getString(1));
        }
      }
    }
    if (columns.isEmpty()) {
      throw new IllegalStateException("the shared table has no primary key: " + table);
    }
    return String.join(", ", columns);
  }

  private static List<List<String>> readTextRows(DataSource dataSource, String sql)
      throws SQLException {
    List<List<String>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("set time zone 'UTC'");
      try (ResultSet result = statement.executeQuery(sql)) {
        int columns = result.getMetaData().getColumnCount();
        while (result.next()) {
          List<String> row = new ArrayList<>(columns);
          for (int index = 1; index <= columns; index++) {
            row.add(result.getString(index));
          }
          rows.add(row);
        }
      }
    }
    return rows;
  }

  private static void setNullable(PreparedStatement statement, int index, String value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.OTHER);
    } else {
      statement.setString(index, value);
    }
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }
}
