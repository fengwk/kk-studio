package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.platform.plugin.service.PluginSecretPatch;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * J4 转换内核在真实 PostgreSQL 17 上的拒绝路径：源端排空残余、目标 pristine 形态、源/目标会话形态、非升级环境事务，以及旧/新凭据的不可导入形态。
 *
 * <p>每个拒绝用例都从同一份已验证的「已排空 dev/main 源 + 真实 Harness 历史」出发，只做一处合法变异（真实 SQL 事实，绝不关闭 trigger、不切 replica
 * 角色、不绕过外键），再断言：转换以固定阶段拒绝、不带原始 cause/suppressed 秘密、目标没有任何业务或目录部分写入。
 *
 * <p>目标侧变异（额外 epoch/业务行/目录历史/凭据/settings）已不再是 pristine，无法再用 pristine 断言表达，因此改为「全部表行数逐一与变异后完全一致 +
 * 变异事实仍精确保留」。
 *
 * <p>包内可见（package-private）：它只是升级测试的拒绝事实，不是生产代码。
 */
class LegacyDatabaseUpgradeRejectionPostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x11);

  private static final SecretKey TARGET_KEY = key(0x22);

  /** 与源 envelope 不匹配的主密钥：真实 AAD/tag 校验必须失败，而不是静默降级。 */
  private static final SecretKey WRONG_SOURCE_KEY = key(0x55);

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();

  private static final HistoryEntryPayloadJsonCodec ENTRY_CODEC =
      new HistoryEntryPayloadJsonCodec();

  private static final SystemSettingsCodec SETTINGS_CODEC = new SystemSettingsCodec();

  private static final String TIMESTAMP = LegacyHarnessHistoryFixture.TIMESTAMP;

  /** 源 DDL 里唯一的 environment 主键；排空用例把它的连接/操作/上传事实挂上去。 */
  private static final String ENVIRONMENT_ID = "'0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f'";

  private static final String HASH_A =
      "'1111111111111111111111111111111111111111111111111111111111111111'";

  private static final String HASH_B =
      "'2222222222222222222222222222222222222222222222222222222222222222'";

  private static final String HASH_C =
      "'3333333333333333333333333333333333333333333333333333333333333333'";

  private static final UUID MODEL_INVOCATION_ID =
      UUID.fromString("c1100000-0000-4000-8000-000000000020");

  private static final UUID TOOL_INVOCATION_ID =
      UUID.fromString("c1100000-0000-4000-8000-000000000021");

  private static final UUID QUEUED_COMMAND_IDEMPOTENCY =
      UUID.fromString("c1100000-0000-4000-8000-000000000022");

  private static final UUID EXTRA_CANCELLED_IDEMPOTENCY =
      UUID.fromString("c1100000-0000-4000-8000-000000000023");

  private static final UUID EXTRA_STOP_REQUEST =
      UUID.fromString("c1100000-0000-4000-8000-000000000024");

  private static final UUID INCOMPLETE_JOIN_ID =
      UUID.fromString("c1100000-0000-4000-8000-000000000025");

  private static final UUID UNDELIVERED_JOIN_ID =
      UUID.fromString("c1100000-0000-4000-8000-000000000026");

  private static final UUID PROJECT_ID = UUID.fromString("c1100000-0000-4000-8000-000000000001");

  private static final UUID ISSUE_ID = UUID.fromString("c1100000-0000-4000-8000-000000000002");

  private static final UUID ISSUE_RUN_ID = UUID.fromString("c1100000-0000-4000-8000-000000000003");

  private static final UUID CHILD_THREAD_ID =
      UUID.fromString("c1100000-0000-4000-8000-000000000004");

  private static final UUID JOIN_SUPERSEDED_ID =
      UUID.fromString("c1100000-0000-4000-8000-000000000005");

  private static final UUID JOIN_COMPACTION_ID =
      UUID.fromString("c1100000-0000-4000-8000-000000000006");

  /** 单点源端变异：真实 SQL 语句列表 + 该变异必须命中哪一条排空守卫。 */
  private record SourceResidual(
      String label, String profile, String guardToken, List<String> statements) {

    @Override
    public String toString() {
      return label;
    }
  }

  /** 单点目标变异：破坏 pristine 形态的语句 + 「变异事实仍保留」的探针。 */
  private record TargetMutation(
      String label, List<String> statements, String probe, long expected) {

    @Override
    public String toString() {
      return label;
    }
  }

  /** 单点旧凭据变异：真实 SQL 语句、期望的固定拒绝阶段与可选的主密钥替换。 */
  private record CredentialMutation(
      String label,
      LegacyDatabaseUpgradeException.Stage stage,
      List<String> statements,
      SecretKey sourceKey) {

    @Override
    public String toString() {
      return label;
    }
  }

  /** 单点历史事实变异：坏 Entry payload 或坏 stop 回执，必须在提交前以固定阶段拒绝。 */
  private record HistoryCorruption(String label, String stageName, List<String> statements) {

    @Override
    public String toString() {
      return label;
    }
  }

  /** 全部排空谓词都必须在打开目标写事务之前拒绝，并且目标仍是 pristine。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceResiduals")
  void sourceResidualIsRejectedBeforeAnyTargetWrite(SourceResidual residual) throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase(residual.profile());
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(source, residual.statements());

    LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

    assertEquals(
        LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN, error.stage(), residual.label());
    assertTrue(error.getMessage().contains(residual.guardToken()), error.getMessage());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  static Stream<Arguments> sourceResiduals() {
    List<Arguments> cases = new ArrayList<>();
    cases.add(residual("runnable thread", "dev", "runnable thread", runnableThread()));
    cases.add(
        residual("terminal model invocation", "dev", "model invocation facts", modelInvocation()));
    // tool invocation 必然带有其父 model invocation 事实（NOT NULL 外键），因此这一对残留由先遇到的 model 守卫拒绝。
    cases.add(
        residual(
            "terminal tool invocation",
            "dev",
            "model invocation facts",
            concat(modelInvocation(), toolInvocation())));
    cases.add(residual("harness work", "dev", "harness work", harnessWork()));
    cases.add(
        residual(
            "project issue work",
            "dev",
            "project issue work",
            concat(issueScaffold(), issueWork())));
    cases.add(residual("queued command", "dev", "queued commands", queuedCommand()));
    cases.add(residual("unsettled join", "dev", "unsettled thread joins", incompleteJoin()));
    cases.add(residual("undelivered task", "dev", "undelivered tasks", undeliveredTask()));
    cases.add(
        residual(
            "running issue run",
            "dev",
            "active issue run",
            concat(issueScaffold(), issueRun("RUNNING"))));
    cases.add(
        residual(
            "waiting issue run",
            "dev",
            "active issue run",
            concat(issueScaffold(), issueRun("WAITING"))));
    cases.add(
        residual(
            "active environment update",
            "dev",
            "active environment update",
            List.of(
                "insert into environment_update_operation"
                    + " (operation_id, environment_id, target_version, phase) values"
                    + " ('c1100000-0000-4000-8000-00000000000a', "
                    + ENVIRONMENT_ID
                    + ", '1.2.3', 'RUNNING')")));
    cases.add(
        residual(
            "credential refresh lease",
            "dev",
            "leases a plugin credential",
            List.of(
                "update plugin_credential set refresh_lease_token = 'legacy-refresh-lease',"
                    + " refresh_lease_until = "
                    + TIMESTAMP
                    + " where plugin_id = '"
                    + LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID
                    + "'")));
    cases.add(
        residual(
            "future environment connection lease",
            "dev",
            "leases an environment connection",
            List.of(
                "insert into environment_connection (environment_id, owner_node_id, lease_token,"
                    + " status, last_seen_at, lease_until) values ("
                    + ENVIRONMENT_ID
                    + ", 'c1100000-0000-4000-8000-00000000000b',"
                    + " 'c1100000-0000-4000-8000-00000000000c', 'CONNECTING', now(),"
                    + " now() + interval '1 hour')")));
    cases.add(
        residual(
            "future upload cleanup lease",
            "dev",
            "leases an upload cleanup",
            List.of(
                "insert into storage_upload (id, candidate_blob_id, filename, declared_media_type,"
                    + " declared_size, declared_sha256, cleanup_requested_at, cleanup_token,"
                    + " cleanup_until) values ('c1100000-0000-4000-8000-00000000000d',"
                    + " 'c1100000-0000-4000-8000-00000000000e', 'legacy.png', 'image/png', 10,"
                    + " '"
                    + HASH_A.replace("'", "")
                    + "', now(), 'legacy-cleanup-token', now() + interval '1 hour')")));
    cases.add(
        residual(
            "future MAIN cluster node lease",
            "main",
            "leases a cluster node",
            List.of(
                "insert into cluster_node (instance_id, internal_url, lease_until, last_seen) values"
                    + " ('c1100000-0000-4000-8000-00000000000f',"
                    + " 'http://legacy-node.invalid:8080', now() + interval '1 hour', now())")));
    return cases.stream();
  }

  /** 已应用与已取消的命令是历史事实，不得被误判为排队；dry run 必须真的走完并回滚。 */
  @Test
  void appliedAndCancelledCommandsAreNotQueued() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(source, concat(extraCancelledCommand(), threadSequenceWatermark()));

    LegacyDatabaseUpgradeResult result = convert(source, target, true);

    assertFalse(result.applied());
    assertEquals("dev", result.sourceProfile());
    assertEquals(3L, result.rowCounts().get("harness_thread_command"));
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 已被取代的 task join 与 terminal 的 compaction join（本就正常没有 delivery）都不是未决事实。 */
  @Test
  void supersededAndCompactionJoinsAreSettled() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(source, concat(childThreadAndCommand(), settledJoins()));

    LegacyDatabaseUpgradeResult result = convert(source, target, true);

    assertFalse(result.applied());
    assertEquals(2L, result.rowCounts().get("harness_thread"));
    assertEquals(2L, result.rowCounts().get("harness_thread_join"));
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 目标不是唯一 pristine 形态时先拒绝：额外 epoch 历史、业务行、目录历史、认证尝试与已有凭据逐项验证。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("targetMutations")
  void nonPristineTargetIsRejectedWithoutFurtherWrites(TargetMutation mutation)
      throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(target, mutation.statements());

    Map<String, Long> before = tableCounts(target);
    LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

    assertEquals(
        LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE, error.stage(), mutation.label());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    assertCounts(target, before);
    assertEquals(mutation.expected(), longValue(target, mutation.probe()), mutation.label());
  }

  static Stream<Arguments> targetMutations() {
    List<Arguments> cases = new ArrayList<>();
    cases.add(
        mutation(
            "extra epoch 42 catalog history",
            List.of(
                "insert into plugin_catalog_revision (epoch, manifest_json) values"
                    + " (42, cast('{}' as jsonb))"),
            "select count(*) from plugin_catalog_revision where epoch = 42",
            1L));
    cases.add(
        mutation(
            "existing business row",
            List.of(
                "insert into agent_provider (name, provider_type, config,"
                    + " connection_generation_id, created_at, updated_at) values ('target-business',"
                    + " 'openai_responses', cast('{}' as jsonb),"
                    + " 'c1100000-0000-4000-8000-000000000010', "
                    + TIMESTAMP
                    + ", "
                    + TIMESTAMP
                    + ")"),
            "select count(*) from agent_provider",
            1L));
    cases.add(
        mutation(
            "existing configuration history",
            List.of(
                "insert into plugin_configuration_revision (plugin_id, revision, code_version,"
                    + " schema_fingerprint, capability_fingerprint, auth_generation,"
                    + " context_fingerprint, values_json) values ('comfyui', 7, '1.0.0', "
                    + HASH_A
                    + ", "
                    + HASH_B
                    + ", 'c1100000-0000-4000-8000-000000000011', "
                    + HASH_C
                    + ", '{}')"),
            "select count(*) from plugin_configuration_revision",
            1L));
    cases.add(
        mutation(
            "existing auth attempt",
            List.of(
                "insert into plugin_auth_attempt (plugin_id, nonce_hash, auth_generation,"
                    + " context_fingerprint, config_revision, region, expires_at) values"
                    + " ('minimax-mavis', "
                    + HASH_A
                    + ", 'c1100000-0000-4000-8000-000000000012', "
                    + HASH_B
                    + ", 3, 'CN', "
                    + TIMESTAMP
                    + ")"),
            "select count(*) from plugin_auth_attempt",
            1L));
    cases.add(
        mutation(
            "existing credential",
            List.of(
                "insert into plugin_credential (plugin_id, auth_generation, context_fingerprint,"
                    + " encrypted_payload, status) values ('comfyui',"
                    + " 'c1100000-0000-4000-8000-000000000013', "
                    + HASH_A
                    + ", decode('00', 'hex'), 'CONNECTED')"),
            "select count(*) from plugin_credential",
            1L));
    return cases.stream();
  }

  /** 目标 settings 是合法但非默认的当前配置时同样不是 pristine。 */
  @Test
  void nonDefaultTargetSettingsIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);

    SystemSettings current =
        SETTINGS_CODEC.decode(
            UpgradePostgresTestSupport.text(target, "select config::text from system_setting"));
    SystemSettings.Tool tool = current.tool();
    SystemSettings mutated =
        new SystemSettings(
            new SystemSettings.Tool(
                tool.permission(),
                tool.modelGatewayBusyRetryMillis() + 1,
                tool.toolGatewayBusyRetryMillis(),
                tool.toolGatewayOverloadRetryMillis()),
            current.aiRuntime(),
            current.environment(),
            current.network(),
            current.storageMedia(),
            current.advanced());
    String mutatedJson = SETTINGS_CODEC.encode(mutated);
    update(target, "update system_setting set config = cast(? as jsonb)", mutatedJson);

    Map<String, Long> before = tableCounts(target);
    LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

    assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE, error.stage());
    assertCounts(target, before);
    assertEquals(
        1L,
        longValueWith(
            target,
            "select count(*) from system_setting where config = cast(? as jsonb)",
            mutatedJson));
    assertEquals(0L, longValue(target, "select version from system_setting"));
  }

  /** 目标 settings 版本不是 pristine 的 0 时同样拒绝，且版本与业务行都不会被改写。 */
  @Test
  void nonZeroTargetSettingsVersionIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(target, List.of("update system_setting set version = 5"));

    Map<String, Long> before = tableCounts(target);
    LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

    assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE, error.stage());
    assertCounts(target, before);
    assertEquals(5L, longValue(target, "select version from system_setting"));
  }

  /** 目标会话形态必须与源同样被预检：replica 复制角色会让真实外键失效，绝不能只看源。 */
  @Test
  void replicaReplicationRoleIsRejectedOnEitherSide() throws SQLException {
    DataSource validSource = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource validTarget = UpgradePostgresTestSupport.pristineTargetDatabase();

    LegacyDatabaseUpgradeException sourceError =
        reject(
            connectionWithOptions(validSource, "-c session_replication_role=replica"),
            validTarget,
            SOURCE_KEY);
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, sourceError.stage());
    assertNull(sourceError.getCause());

    LegacyDatabaseUpgradeException targetError =
        reject(
            validSource,
            connectionWithOptions(validTarget, "-c session_replication_role=replica"),
            SOURCE_KEY);
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, targetError.stage());
    assertNull(targetError.getCause());
    UpgradePostgresTestSupport.assertPristine(validTarget);
  }

  /** 只读目标不是可写的升级目标，必须在任何目录装配之前拒绝。 */
  @Test
  void readOnlyTargetSessionIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target =
        connectionWithOptions(
            UpgradePostgresTestSupport.pristineTargetDatabase(),
            "-c default_transaction_read_only=on");

    LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

    assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, error.stage());
    assertNull(error.getCause());
  }

  /** 非 UTF-8 的源与目标都拒绝：字节保真的 COPY 以 UTF-8 数据库为前提。 */
  @Test
  void nonUtf8DatabaseIsRejectedOnEitherSide() throws SQLException {
    DataSource validSource = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource validTarget = UpgradePostgresTestSupport.pristineTargetDatabase();

    LegacyDatabaseUpgradeException sourceError =
        reject(latin1Database(validSource), validTarget, SOURCE_KEY);
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, sourceError.stage());
    assertNull(sourceError.getCause());

    LegacyDatabaseUpgradeException targetError =
        reject(validSource, latin1Database(validTarget), SOURCE_KEY);
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, targetError.stage());
    assertNull(targetError.getCause());
    UpgradePostgresTestSupport.assertPristine(validTarget);
  }

  /** 内核不接受调用方环境里已经打开的写事务，因此不会接管别人的连接。 */
  @Test
  void ambientSpringTransactionIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    TransactionTemplate template =
        new TransactionTemplate(new DataSourceTransactionManager(target));

    LegacyDatabaseUpgradeException error =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () -> template.execute(status -> convert(source, target, false)));

    assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_TRANSACTION, error.stage());
    assertNull(error.getCause());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 旧凭据只接受「已安装 + 真实 DeepLink 兼容」的行；未知插件、非 DeepLink 插件、未声明 region 与不可读密文逐项拒绝。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("credentialMutations")
  void legacyCredentialRejectionLeavesNoPartialImport(CredentialMutation mutation)
      throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(source, mutation.statements());

    LegacyDatabaseUpgradeException error =
        reject(source, target, mutation.sourceKey() == null ? SOURCE_KEY : mutation.sourceKey());

    assertEquals(mutation.stage(), error.stage(), mutation.label());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
    assertFalse(
        error.getMessage().contains(LegacyUpgradeSourceFixture.LEGACY_CREDENTIAL_PLAINTEXT));
    UpgradePostgresTestSupport.assertPristine(target);
  }

  static Stream<Arguments> credentialMutations() {
    String legacyRow =
        "insert into plugin_credential (plugin_id, encrypted_payload, region, expires_at,"
            + " next_refresh_at, status, version, create_time, update_time) values (";
    String rowSuffix =
        ", 'CN', "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ", 'CONNECTED', 0, "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")";
    List<Arguments> cases = new ArrayList<>();
    cases.add(
        arguments(
            new CredentialMutation(
                "credential of an uninstalled plugin",
                LegacyDatabaseUpgradeException.Stage.CREDENTIAL,
                List.of(legacyRow + "'ghost-plugin', decode('00', 'hex')" + rowSuffix),
                null)));
    cases.add(
        arguments(
            new CredentialMutation(
                "credential of a plugin without deep-link authentication",
                LegacyDatabaseUpgradeException.Stage.CREDENTIAL,
                List.of(legacyRow + "'comfyui', decode('00', 'hex')" + rowSuffix),
                null)));
    cases.add(
        arguments(
            new CredentialMutation(
                "credential region that the plugin does not declare",
                LegacyDatabaseUpgradeException.Stage.CREDENTIAL,
                List.of(
                    "update plugin_credential set region = 'ZZ' where plugin_id = '"
                        + LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID
                        + "'"),
                null)));
    cases.add(
        arguments(
            new CredentialMutation(
                "credential region that no longer matches its AAD",
                LegacyDatabaseUpgradeException.Stage.CONVERT,
                List.of(
                    "update plugin_credential set region = 'EN' where plugin_id = '"
                        + LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID
                        + "'"),
                null)));
    cases.add(
        arguments(
            new CredentialMutation(
                "corrupted credential tag",
                LegacyDatabaseUpgradeException.Stage.CONVERT,
                List.of(
                    "update plugin_credential set encrypted_payload = set_byte(encrypted_payload,"
                        + " 40, (get_byte(encrypted_payload, 40) + 1) % 256) where plugin_id = '"
                        + LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID
                        + "'"),
                null)));
    cases.add(
        arguments(
            new CredentialMutation(
                "unknown credential envelope format",
                LegacyDatabaseUpgradeException.Stage.CONVERT,
                List.of(
                    "update plugin_credential set encrypted_payload = set_byte(encrypted_payload,"
                        + " 0, 2) where plugin_id = '"
                        + LegacyUpgradeSourceFixture.LEGACY_PLUGIN_ID
                        + "'"),
                null)));
    cases.add(
        arguments(
            new CredentialMutation(
                "credential read with the wrong source key",
                LegacyDatabaseUpgradeException.Stage.CONVERT,
                List.of(),
                WRONG_SOURCE_KEY)));
    return cases.stream();
  }

  /** 旧凭据存在时必须显式给出源主密钥，缺失即拒绝且不留下部分导入。 */
  @Test
  void missingSourceKeyForLegacyCredentialIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);

    LegacyDatabaseUpgradeException error =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () ->
                LegacyDatabaseUpgrade.convert(
                    source,
                    target,
                    null,
                    TARGET_KEY,
                    LegacyUpgradeSourceFixture.staticSecrets(),
                    false,
                    LegacyUpgradeSourceFixture.CLOCK));

    assertEquals(LegacyDatabaseUpgradeException.Stage.CREDENTIAL, error.stage());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 显式静态材料必须命中已安装 StaticKey 插件的真实声明字段与有界值，任何不符都在写目标之前拒绝。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("staticMaterialRejections")
  void staticMaterialRejectionLeavesNoPartialImport(
      String label, Map<String, Map<String, String>> secrets) throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);

    LegacyDatabaseUpgradeException error =
        assertThrows(
            LegacyDatabaseUpgradeException.class, () -> convert(source, target, false, secrets));

    assertEquals(LegacyDatabaseUpgradeException.Stage.CREDENTIAL, error.stage(), label);
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
    UpgradePostgresTestSupport.assertPristine(target);
  }

  static Stream<Arguments> staticMaterialRejections() {
    return Stream.of(
        Arguments.of(
            "static material for an uninstalled plugin",
            secrets("ghost-plugin", Map.of("apiKey", "synthetic-static-value"))),
        Arguments.of(
            "static material naming a field that is not a declared secret",
            secrets("comfyui", Map.of("apiKey", "synthetic-static-value", "baseUrl", "x"))),
        Arguments.of(
            "static material over the declared secret size budget",
            secrets(
                "comfyui", Map.of("apiKey", "a".repeat(PluginSecretPatch.MAX_SECRET_CHARS + 1)))),
        Arguments.of("empty static material", secrets("comfyui", Map.of())),
        Arguments.of(
            "static material for a plugin without static key authentication",
            secrets("media", Map.of("apiKey", "synthetic-static-value"))));
  }

  /** 未知 CUSTOM producer 组合与坏 stop 回执（嵌套 state、键集、sequence 范围）都必须在提交前拒绝。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("corruptedHistoryFacts")
  void corruptedHistoryFactIsRejectedBeforeCommit(HistoryCorruption corruption)
      throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(source, corruption.statements());

    LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

    assertEquals(
        LegacyDatabaseUpgradeException.Stage.valueOf(corruption.stageName()),
        error.stage(),
        corruption.label());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  static Stream<Arguments> corruptedHistoryFacts() {
    return Stream.of(
        historyCorruption(
            "unknown CUSTOM contributor",
            "CONVERT",
            List.of(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values ('c1100000-0000-4000-8000-000000000030', '"
                    + LegacyHarnessHistoryFixture.SESSION_ID
                    + "', '"
                    + LegacyHarnessHistoryFixture.TURN_END_ENTRY_ID
                    + "', 'CUSTOM', cast('{\"contributorId\":\"unknown\",\"customType\":\"thing\","
                    + "\"schemaVersion\":1,\"data\":{}}' as jsonb), "
                    + TIMESTAMP
                    + ")")),
        historyCorruption(
            "stop receipt with a nested unknown contributor state",
            "CONVERT",
            List.of(
                replaceCancelledInputs(
                    "[{\"sequence\": 2, \"idempotencyKey\": \""
                        + LegacyHarnessHistoryFixture.CANCELLED_COMMAND_IDEMPOTENCY
                        + "\", \"type\": \"SET_CONTRIBUTOR_STATE\", \"payload\": {\"state\":"
                        + " {\"contributorId\": \"unknown\", \"customType\": \"state\","
                        + " \"schemaVersion\": 1, \"data\": {}}}}]"))),
        historyCorruption(
            "stop receipt item with an unexpected key set",
            "CONVERT",
            List.of(
                replaceCancelledInputs(
                    "[{\"sequence\": 2, \"idempotencyKey\": \""
                        + LegacyHarnessHistoryFixture.CANCELLED_COMMAND_IDEMPOTENCY
                        + "\", \"type\": \"USER_MESSAGE\", \"payload\": {}, \"extra\": true}]"))),
        historyCorruption(
            "stop receipt item with an out-of-range sequence",
            "CONVERT",
            List.of(
                replaceCancelledInputs(
                    "[{\"sequence\": 9223372036854775808, \"idempotencyKey\": \""
                        + LegacyHarnessHistoryFixture.CANCELLED_COMMAND_IDEMPOTENCY
                        + "\", \"type\": \"USER_MESSAGE\", \"payload\": {}}]"))));
  }

  /** 真实 Entry 祖先链出现环时必须在提交前拒绝，且目标回滚为 pristine。 */
  @Test
  void entryParentCycleIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(source, entryParentCycle());

    LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

    assertEquals(LegacyDatabaseUpgradeException.Stage.CONVERT, error.stage());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 目标 head 仍指向未关闭的 TURN_START 时必须在提交前按 VERIFY 拒绝。 */
  @Test
  void openTurnIsRejected() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);
    execute(
        source,
        List.of(
            "update harness_thread set head_entry_id = '"
                + LegacyHarnessHistoryFixture.TURN_START_ENTRY_ID
                + "' where id = '"
                + LegacyHarnessHistoryFixture.THREAD_ID
                + "'"));

    LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

    assertEquals(LegacyDatabaseUpgradeException.Stage.VERIFY, error.stage());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  /** 另一个写者已持有 canonical 表的 ACCESS EXCLUSIVE 锁时立即拒绝：不等待、不重试，也不删除或改写目标已有数据。 */
  @Test
  void concurrentAccessExclusiveLockIsRejectedWithoutDeletingTargetData() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);

    try (Connection holder = target.getConnection()) {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute("lock table system_setting in access exclusive mode");
      }

      LegacyDatabaseUpgradeException error = reject(source, target, SOURCE_KEY);

      assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_PRISTINE, error.stage());
      assertFalse(error.outcomeUncertain());
      assertNull(error.getCause());
      // 锁持有者自身仍能看到 pristine settings 行：竞争拒绝没有删除或改写目标已有数据。
      assertEquals(1L, longValue(holder, "select count(*) from system_setting"));
      assertEquals(0L, longValue(holder, "select version from system_setting"));
      holder.rollback();
    }
    UpgradePostgresTestSupport.assertPristine(target);
  }

  private static LegacyDatabaseUpgradeResult convert(
      DataSource source, DataSource target, boolean dryRun) {
    return convert(source, target, dryRun, LegacyUpgradeSourceFixture.staticSecrets());
  }

  private static LegacyDatabaseUpgradeResult convert(
      DataSource source,
      DataSource target,
      boolean dryRun,
      Map<String, Map<String, String>> secrets) {
    return LegacyDatabaseUpgrade.convert(
        source, target, SOURCE_KEY, TARGET_KEY, secrets, dryRun, LegacyUpgradeSourceFixture.CLOCK);
  }

  /** 真实连接上的单次转换必须拒绝；返回固定阶段与安全原因的分类异常。 */
  private static LegacyDatabaseUpgradeException reject(
      DataSource source, DataSource target, SecretKey sourceKey) {
    return assertThrows(
        LegacyDatabaseUpgradeException.class,
        () ->
            LegacyDatabaseUpgrade.convert(
                source,
                target,
                sourceKey,
                TARGET_KEY,
                LegacyUpgradeSourceFixture.staticSecrets(),
                false,
                LegacyUpgradeSourceFixture.CLOCK));
  }

  private static Arguments residual(
      String label, String profile, String guardToken, List<String> statements) {
    return Arguments.of(new SourceResidual(label, profile, guardToken, statements));
  }

  private static Arguments mutation(
      String label, List<String> statements, String probe, long expected) {
    return Arguments.of(new TargetMutation(label, statements, probe, expected));
  }

  private static Arguments arguments(CredentialMutation mutation) {
    return Arguments.of(mutation);
  }

  private static Arguments historyCorruption(
      String label, String stageName, List<String> statements) {
    return Arguments.of(new HistoryCorruption(label, stageName, statements));
  }

  /** 只替换已有 stop 回执的 cancelled_inputs 原文，回执身份与 turn end 事实都不动。 */
  private static String replaceCancelledInputs(String json) {
    return "update harness_thread_stop_receipt set cancelled_inputs = cast('"
        + json
        + "' as jsonb) where thread_id = '"
        + LegacyHarnessHistoryFixture.THREAD_ID
        + "'";
  }

  /** 两条真实追加的 MESSAGE Entry 互指为父：真实 DDL 允许（同 Session、无自指），但祖先链已经是环。 */
  private static List<String> entryParentCycle() {
    UUID first = UUID.fromString("c1100000-0000-4000-8000-000000000031");
    UUID second = UUID.fromString("c1100000-0000-4000-8000-000000000032");
    return List.of(
        messageEntry(first, LegacyHarnessHistoryFixture.USER_ENTRY_ID, "cycle first"),
        messageEntry(second, first, "cycle second"),
        "update harness_entry set parent_entry_id = '" + second + "' where id = '" + first + "'",
        "update harness_thread set head_entry_id = '"
            + second
            + "' where id = '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "'");
  }

  /** 真实 codec 编码的追加 MESSAGE Entry；不猜 payload 形状。 */
  private static String messageEntry(UUID id, UUID parentId, String text) {
    String payload = ENTRY_CODEC.encode(new MessagePayload(AgentMessage.user(text), null, null));
    return "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
        + " created_at) values ('"
        + id
        + "', '"
        + LegacyHarnessHistoryFixture.SESSION_ID
        + "', '"
        + parentId
        + "', 'MESSAGE', cast('"
        + payload
        + "' as jsonb), "
        + TIMESTAMP
        + ")";
  }

  private static Map<String, Map<String, String>> secrets(
      String pluginId, Map<String, String> values) {
    Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
    secrets.put(pluginId, values);
    return Map.copyOf(secrets);
  }

  @SafeVarargs
  private static List<String> concat(List<String>... lists) {
    List<String> statements = new ArrayList<>();
    for (List<String> list : lists) {
      statements.addAll(list);
    }
    return statements;
  }

  private static List<String> runnableThread() {
    return List.of(
        "update harness_thread set execution_control = 'RUNNABLE' where id = '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "'");
  }

  /** 真实旧部署的 UNKNOWN 终态 model invocation 残留：字段形状完整，只表达「没有排空」。 */
  private static List<String> modelInvocation() {
    return List.of(
        "insert into harness_model_invocation (id, thread_id, turn_start_entry_id,"
            + " request_head_entry_id, request_spec, status, attempt, failed_attempts, created_at,"
            + " updated_at) values ('"
            + MODEL_INVOCATION_ID
            + "', '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', '"
            + LegacyHarnessHistoryFixture.TURN_START_ENTRY_ID
            + "', '"
            + LegacyHarnessHistoryFixture.USER_ENTRY_ID
            + "', cast('{}' as jsonb), 'UNKNOWN', 0, cast('[]' as jsonb), "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")");
  }

  private static List<String> toolInvocation() {
    return List.of(
        "insert into harness_tool_invocation (id, model_invocation_id, assistant_entry_id,"
            + " call_index, call, status, attempt, effects, created_at, updated_at) values ('"
            + TOOL_INVOCATION_ID
            + "', '"
            + MODEL_INVOCATION_ID
            + "', '"
            + LegacyHarnessHistoryFixture.ASSISTANT_ENTRY_ID
            + "', 0, cast('{}' as jsonb), 'UNKNOWN', 0,"
            + " cast('{\"version\": 1, \"customEntries\": []}' as jsonb), "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")");
  }

  private static List<String> harnessWork() {
    return List.of(
        "insert into harness_work (target_type, target_id, available_at, wake_version) values"
            + " ('THREAD', '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', "
            + TIMESTAMP
            + ", 1)");
  }

  /** 真实 project issue 接线：project → issue → stage budget / agent thread，work 与 run 都必须挂在这一链上。 */
  private static List<String> issueScaffold() {
    return List.of(
        "insert into project (id, title, workflow) values ('"
            + PROJECT_ID
            + "', 'legacy project', cast('{\"states\":[\"WORK\"]}' as jsonb))",
        "insert into project_issue (id, project_id, number, title, state) values ('"
            + ISSUE_ID
            + "', '"
            + PROJECT_ID
            + "', 1, 'legacy issue', 'WORK')",
        "insert into project_issue_stage_budget (issue_id, state, max_runs) values ('"
            + ISSUE_ID
            + "', 'WORK', 3)",
        "insert into project_issue_agent_thread (issue_id, agent_name, thread_id) values ('"
            + ISSUE_ID
            + "', '"
            + LegacyUpgradeSourceFixture.AGENT
            + "', '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "')");
  }

  private static List<String> issueWork() {
    return List.of(
        "insert into project_issue_work (issue_id, wake_version, due_at) values ('"
            + ISSUE_ID
            + "', 1, "
            + TIMESTAMP
            + ")");
  }

  private static List<String> issueRun(String status) {
    String activeSince = "RUNNING".equals(status) ? TIMESTAMP : "null";
    return List.of(
        "insert into project_issue_run (id, issue_id, ordinal, state, session_id, thread_id,"
            + " status, start_entry_id, remaining_execution_ms, active_since) values ('"
            + ISSUE_RUN_ID
            + "', '"
            + ISSUE_ID
            + "', 1, 'WORK', '"
            + LegacyHarnessHistoryFixture.SESSION_ID
            + "', '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', '"
            + status
            + "', '"
            + LegacyHarnessHistoryFixture.USER_ENTRY_ID
            + "', 60000, "
            + activeSince
            + ")");
  }

  /** 真正 QUEUED：applied_entry_id 与 cancelled_at 都为空。 */
  private static List<String> queuedCommand() {
    return List.of(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload,"
            + " idempotency_key, request_hash, created_at) values ('"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', 3, 'USER_MESSAGE', cast('"
            + userCommandPayload()
            + "' as jsonb), '"
            + QUEUED_COMMAND_IDEMPOTENCY
            + "', "
            + HASH_A
            + ", "
            + TIMESTAMP
            + ")");
  }

  /** 第二条真实历史命令：已经被 Stop 取消，绝不能算排队。 */
  private static List<String> extraCancelledCommand() {
    return List.of(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload,"
            + " idempotency_key, request_hash, applied_entry_id, stop_request_id, cancelled_at,"
            + " created_at) values ('"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', 3, 'USER_MESSAGE', cast('"
            + userCommandPayload()
            + "' as jsonb), '"
            + EXTRA_CANCELLED_IDEMPOTENCY
            + "', "
            + HASH_C
            + ", null, '"
            + EXTRA_STOP_REQUEST
            + "', "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")");
  }

  private static List<String> threadSequenceWatermark() {
    return List.of(
        "update harness_thread set next_command_sequence = 4 where id = '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "'");
  }

  /** 未结算 Join：terminal_entry_id 与 superseded_by_invocation_id 都为空。 */
  private static List<String> incompleteJoin() {
    return List.of(
        "insert into harness_thread_join (invocation_id, request_hash, child_thread_id,"
            + " source_command_sequence, agent, purpose, created_at, updated_at) values ('"
            + INCOMPLETE_JOIN_ID
            + "', "
            + HASH_A
            + ", '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', 1, '"
            + LegacyUpgradeSourceFixture.AGENT
            + "', 'task', "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")");
  }

  /** purpose=task 且已 terminal 但没有 delivery command：任务未交付。 */
  private static List<String> undeliveredTask() {
    return List.of(
        "insert into harness_thread_join (invocation_id, request_hash, child_thread_id,"
            + " source_command_sequence, agent, purpose, terminal_entry_id, created_at,"
            + " updated_at) values ('"
            + UNDELIVERED_JOIN_ID
            + "', "
            + HASH_B
            + ", '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', 1, '"
            + LegacyUpgradeSourceFixture.AGENT
            + "', 'task', '"
            + LegacyHarnessHistoryFixture.TURN_END_ENTRY_ID
            + "', "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")");
  }

  /** 真实子代理 Thread（FOLLOW + 执行根）与它自己的已应用命令，供 settled join 边界使用。 */
  private static List<String> childThreadAndCommand() {
    return List.of(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id,"
            + " creation_request_hash, name, yolo_mode, yolo_root_thread_id, execution_control,"
            + " input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values ('"
            + CHILD_THREAD_ID
            + "', '"
            + LegacyHarnessHistoryFixture.SESSION_ID
            + "', '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', '"
            + LegacyHarnessHistoryFixture.TURN_END_ENTRY_ID
            + "', '"
            + HASH_A.replace("'", "")
            + "', 'legacy child thread', 'FOLLOW', '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', 'STOPPED', 0, 2, 0, "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")",
        "insert into harness_thread_command (thread_id, sequence, command_type, payload,"
            + " idempotency_key, request_hash, applied_entry_id, created_at) values ('"
            + CHILD_THREAD_ID
            + "', 1, 'USER_MESSAGE', cast('"
            + userCommandPayload()
            + "' as jsonb), 'c1100000-0000-4000-8000-000000000027', "
            + HASH_B
            + ", '"
            + LegacyHarnessHistoryFixture.USER_ENTRY_ID
            + "', "
            + TIMESTAMP
            + ")");
  }

  /** 两个都不算未决的 join：被取代的 task join 与 terminal 的 compaction join（正常没有 delivery）。 */
  private static List<String> settledJoins() {
    return List.of(
        "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id,"
            + " child_thread_id, source_command_sequence, agent, purpose, terminal_entry_id,"
            + " created_at, updated_at) values ('"
            + JOIN_COMPACTION_ID
            + "', "
            + HASH_A
            + ", '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', '"
            + CHILD_THREAD_ID
            + "', 1, '"
            + LegacyUpgradeSourceFixture.AGENT
            + "', 'compaction', '"
            + LegacyHarnessHistoryFixture.TURN_END_ENTRY_ID
            + "', "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")",
        "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id,"
            + " child_thread_id, source_command_sequence, agent, purpose,"
            + " superseded_by_invocation_id, created_at, updated_at) values ('"
            + JOIN_SUPERSEDED_ID
            + "', "
            + HASH_B
            + ", '"
            + LegacyHarnessHistoryFixture.THREAD_ID
            + "', '"
            + CHILD_THREAD_ID
            + "', 1, '"
            + LegacyUpgradeSourceFixture.AGENT
            + "', 'task', '"
            + JOIN_COMPACTION_ID
            + "', "
            + TIMESTAMP
            + ", "
            + TIMESTAMP
            + ")");
  }

  private static String userCommandPayload() {
    return "{\"message\":" + MESSAGE_CODEC.encode(AgentMessage.user("legacy follow-up")) + "}";
  }

  private static void execute(DataSource dataSource, List<String> statements) throws SQLException {
    if (statements.isEmpty()) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private static void update(DataSource dataSource, String sql, String value) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, value);
      statement.executeUpdate();
    }
  }

  private static long longValue(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** 持锁连接自读的单列整数：锁持有者之外的事务会被 ACCESS EXCLUSIVE 阻塞，必须在同一连接上断言。 */
  private static long longValue(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static long longValueWith(DataSource dataSource, String sql, String value)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, value);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  /** 目标全部共有与目录表的行数快照；拒绝路径不得让其中任何一项变化。 */
  private static Map<String, Long> tableCounts(DataSource dataSource) throws SQLException {
    List<String> tables = new ArrayList<>(LegacySchemaProfile.SHARED_TABLES);
    tables.addAll(LegacySchemaProfile.TARGET_ONLY_TABLES);
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : tables) {
      counts.put(table, UpgradePostgresTestSupport.count(dataSource, table));
    }
    return counts;
  }

  private static void assertCounts(DataSource dataSource, Map<String, Long> expected)
      throws SQLException {
    for (Map.Entry<String, Long> entry : expected.entrySet()) {
      assertEquals(
          entry.getValue(),
          UpgradePostgresTestSupport.count(dataSource, entry.getKey()),
          entry.getKey());
    }
  }

  /** 只用同一批真实 JDBC 连接属性构造变体句柄，不改动进程级容器或其它库。 */
  private static DataSource connectionWithOptions(DataSource origin, String... options) {
    SimpleDriverDataSource original = (SimpleDriverDataSource) origin;
    Properties properties = new Properties();
    properties.setProperty("options", String.join(" ", options));
    SimpleDriverDataSource variant =
        new SimpleDriverDataSource(
            new Driver(), original.getUrl(), original.getUsername(), original.getPassword());
    variant.setConnectionProperties(properties);
    return variant;
  }

  /** 在同一个真实容器里显式建一个 LATIN1 库；只有主键以外的连接形态变化，DDL 与库权限都不动。 */
  private static DataSource latin1Database(DataSource origin) throws SQLException {
    SimpleDriverDataSource original = (SimpleDriverDataSource) origin;
    String database = "kk_studio_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = original.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "create database "
              + database
              + " with template template0 encoding 'LATIN1' lc_collate 'C' lc_ctype 'C'");
    }
    return new SimpleDriverDataSource(
        new Driver(),
        withDatabase(original.getUrl(), database),
        original.getUsername(),
        original.getPassword());
  }

  private static String withDatabase(String jdbcUrl, String database) {
    String prefix = "jdbc:postgresql://";
    int pathStart = jdbcUrl.indexOf('/', prefix.length());
    int queryStart = jdbcUrl.indexOf('?', pathStart);
    String suffix = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
    return jdbcUrl.substring(0, pathStart + 1) + database + suffix;
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }
}
