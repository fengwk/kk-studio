package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;

/**
 * J4 转换内核在真实 PostgreSQL 17 上的守卫与凭据失败关闭边界：源端排空判据、源端目录与 settings 读取的真实 SQL 故障、目标迁移历史里多出的 repeatable
 * 记录，以及旧凭据重封装与静态密钥写入的凭据边界，都必须以固定阶段拒绝，并且目标真正 pristine、连接与语句全部释放、源端零写入。
 *
 * <p>除「删掉源端唯一 canonical settings 行」「删掉旧凭据行」「往目标历史里插一条 repeatable 记录」这三种真实数据变异外，其余用例只用一个窄故障探针把指定的源端
 * SQL 替换成真实 {@link SQLException}（其余调用仍是真实连接、真实事务与真实 SQL），因此覆盖的是内核真实 JDBC 故障分支而不是伪造的 SQLState。
 *
 * <p>包内可见（package-private）：它只是升级测试的失败关闭事实，不是生产代码。
 */
class LegacyUpgradeBoundaryPostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x77);

  private static final SecretKey TARGET_KEY = key(0x88);

  private static final String SQL_MARKER = "injected-boundary-sql-marker";

  /** 目标历史里多出的成功 repeatable 记录（version 为 null 的 SQL）；只改测试数据，不 repair 也不重写任何 DDL。 */
  private static final String REPEATABLE_TARGET_HISTORY_ROW =
      "insert into flyway_schema_history (installed_rank, version, description, type, script,"
          + " checksum, installed_by, installed_on, execution_time, success)"
          + " select 2, null, 'repeatable target seed', 'SQL', 'R__target_seed.sql', null,"
          + " installed_by, installed_on, 0, true from flyway_schema_history"
          + " where installed_rank = 1";

  /** 源端连唯一 canonical settings 行都没有：必须在任何目标写事务之前以排空阶段拒绝。 */
  @Test
  void aSourceWithoutTheCanonicalSystemSettingRowIsRejected() throws SQLException {
    Fixture fixture = Fixture.open();
    execute(fixture.source(), "delete from system_setting where id = 1");
    try {
      Fixture.SourceBaseline before = fixture.sourceBaseline();
      LegacyDatabaseUpgradeException error =
          fixture.convertExpectingSafeFailure(
              TARGET_KEY, LegacyUpgradeSourceFixture.staticSecrets());
      assertEquals(LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN, error.stage());
      assertFailClosed(fixture, error, before);
    } finally {
      fixture.release();
    }
  }

  /** 源 settings 读取本身的 SQL 故障必须收敛为固定排空阶段，原始 SQLState 与 message 绝不外泄。 */
  @Test
  void aSourceSettingsReadFailureIsRejectedAsASafeSourceDrainStage() throws SQLException {
    Fixture fixture = Fixture.open();
    fixture.sourceProbe().failOnSql("from system_setting", sqlFailure("settings-read"));
    try {
      Fixture.SourceBaseline before = fixture.sourceBaseline();
      LegacyDatabaseUpgradeException error =
          fixture.convertExpectingSafeFailure(
              TARGET_KEY, LegacyUpgradeSourceFixture.staticSecrets());
      assertEquals(LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN, error.stage());
      assertFailClosed(fixture, error, before);
    } finally {
      fixture.release();
    }
  }

  /** 源端排空判据本身无法求值（第一条排空 count 查询失败）也必须固定为排空阶段拒绝，而不是把内核判定成「已排空」。 */
  @Test
  void anUnevaluableSourceDrainCountIsRejectedAsASafeSourceDrainStage() throws SQLException {
    Fixture fixture = Fixture.open();
    fixture
        .sourceProbe()
        .failOnSql(
            "select count(*) from harness_thread where execution_control",
            sqlFailure("drain-count"));
    try {
      Fixture.SourceBaseline before = fixture.sourceBaseline();
      LegacyDatabaseUpgradeException error =
          fixture.convertExpectingSafeFailure(
              TARGET_KEY, LegacyUpgradeSourceFixture.staticSecrets());
      assertEquals(LegacyDatabaseUpgradeException.Stage.SOURCE_DRAIN, error.stage());
      assertFailClosed(fixture, error, before);
    } finally {
      fixture.release();
    }
  }

  /** 源端应用 schema 目录读取失败不能退化成「未知 profile」以外的任何结论：固定为源档案阶段。 */
  @Test
  void aSourceCatalogReadFailureIsRejectedAsASafeSourceProfileStage() throws SQLException {
    Fixture fixture = Fixture.open();
    fixture.sourceProbe().failOnSql("pg_class", sqlFailure("catalog-read"));
    try {
      Fixture.SourceBaseline before = fixture.sourceBaseline();
      LegacyDatabaseUpgradeException error =
          fixture.convertExpectingSafeFailure(
              TARGET_KEY, LegacyUpgradeSourceFixture.staticSecrets());
      assertEquals(LegacyDatabaseUpgradeException.Stage.SOURCE_PROFILE, error.stage());
      assertFailClosed(fixture, error, before);
    } finally {
      fixture.release();
    }
  }

  /** 目标历史里多出的成功 repeatable 记录必须是目标 schema 拒绝，并且那条记录绝不被内核修复或改写。 */
  @Test
  void anUnexpectedRepeatableTargetHistoryRowIsRejectedWithoutRepair() throws SQLException {
    Fixture fixture = Fixture.open();
    execute(fixture.target(), REPEATABLE_TARGET_HISTORY_ROW);
    try {
      Fixture.SourceBaseline before = fixture.sourceBaseline();
      LegacyDatabaseUpgradeException error =
          fixture.convertExpectingSafeFailure(
              TARGET_KEY, LegacyUpgradeSourceFixture.staticSecrets());
      assertEquals(LegacyDatabaseUpgradeException.Stage.TARGET_SCHEMA, error.stage());
      assertFailClosed(fixture, error, before);
      assertEquals(
          2L,
          UpgradePostgresTestSupport.count(fixture.target(), "flyway_schema_history"),
          "the unexpected repeatable history row must not be repaired or deleted");
    } finally {
      fixture.release();
    }
  }

  /** 有静态密钥材料却没有目标主密钥（且源端无 Skill 令牌行）：真实候选 CAS 之后再拒绝，并且整笔写入必须全部回滚。 */
  @Test
  void aStaticCredentialWithoutATargetKeyIsRejectedAfterTheCatalogCasAndRolledBack()
      throws SQLException {
    Fixture fixture = Fixture.open();
    execute(fixture.source(), "delete from plugin_credential");
    try {
      assertEquals(
          0L,
          UpgradePostgresTestSupport.count(fixture.source(), "skill_package"),
          "this boundary must reach the static credential check instead of the skill token keys");
      Fixture.SourceBaseline before = fixture.sourceBaseline();
      LegacyDatabaseUpgradeException error =
          fixture.convertExpectingSafeFailure(null, LegacyUpgradeSourceFixture.staticSecrets());
      assertEquals(LegacyDatabaseUpgradeException.Stage.CREDENTIAL, error.stage());
      assertFailClosed(fixture, error, before);
      assertEquals(
          0L,
          UpgradePostgresTestSupport.longValue(
              fixture.target(), "select epoch from plugin_catalog_state where id = 1"),
          "the committed-by-CAS epoch must be rolled back with the rest of the target writes");
    } finally {
      fixture.release();
    }
  }

  /** 旧凭据行的读取 SQL 故障必须在目标事务内以凭据阶段拒绝并整体回滚，绝不留下部分凭据或半个目录。 */
  @Test
  void aLegacyCredentialReadFailureIsRejectedAsASafeCredentialStageWithinTheTargetTransaction()
      throws SQLException {
    Fixture fixture = Fixture.open();
    fixture.sourceProbe().failOnSql("order by plugin_id", sqlFailure("legacy-credential-read"));
    try {
      Fixture.SourceBaseline before = fixture.sourceBaseline();
      LegacyDatabaseUpgradeException error =
          fixture.convertExpectingSafeFailure(
              TARGET_KEY, LegacyUpgradeSourceFixture.staticSecrets());
      assertEquals(LegacyDatabaseUpgradeException.Stage.CREDENTIAL, error.stage());
      assertFailClosed(fixture, error, before);
      assertEquals(
          0L,
          UpgradePostgresTestSupport.longValue(
              fixture.target(), "select epoch from plugin_catalog_state where id = 1"),
          "a credential refusal after the catalog CAS must roll the target back entirely");
    } finally {
      fixture.release();
    }
  }

  /** 每条失败路径的公共事实：固定结果不是未知、不可见任何原始标记、目标真正 pristine、两个连接与全部语句都已释放、源端零写入且源端事实未变。 */
  private static void assertFailClosed(
      Fixture fixture, LegacyDatabaseUpgradeException error, Fixture.SourceBaseline before)
      throws SQLException {
    assertFalse(
        error.outcomeUncertain(),
        "a refusal without a lost commit acknowledgement must be a known rollback");
    assertNull(error.getCause(), "the safe failure must not carry a raw cause");
    assertNoLeak(error, SQL_MARKER);
    UpgradePostgresTestSupport.assertPristine(fixture.target());
    assertEquals(1, fixture.sourceProbe().connectionsOpened(), "extra source connection");
    assertEquals(1, fixture.targetProbe().connectionsOpened(), "extra target connection");
    assertTrue(fixture.sourceProbe().realConnectionClosed(), "leaked source connection");
    assertTrue(fixture.targetProbe().realConnectionClosed(), "leaked target connection");
    assertEquals(0, fixture.sourceProbe().openStatements(), "leaked source statement");
    assertEquals(0, fixture.targetProbe().openStatements(), "leaked target statement");
    assertNoSourceWrites(fixture);
    assertEquals(before, fixture.sourceBaseline(), "the legacy source was modified");
  }

  /** 源端必须只读：探针记录的每一条语句都不是 insert/update/delete。 */
  private static void assertNoSourceWrites(Fixture fixture) {
    for (String event : fixture.sourceProbe().events()) {
      int separator = event.indexOf(':');
      if (separator < 0) {
        continue;
      }
      String kind = event.substring(0, separator);
      if (!"prepare".equals(kind) && !"execute".equals(kind)) {
        continue;
      }
      assertFalse(
          isWriteStatement(event.substring(separator + 1)),
          "the legacy source must never see a write statement: " + event);
    }
  }

  private static boolean isWriteStatement(String sql) {
    String normalized = sql.stripLeading();
    return startsWithIgnoreCase(normalized, "insert")
        || startsWithIgnoreCase(normalized, "update")
        || startsWithIgnoreCase(normalized, "delete");
  }

  private static boolean startsWithIgnoreCase(String value, String prefix) {
    return value.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  /** 安全异常契约：递归的 suppressed/cause 绝不携带注入的原始标记。 */
  private static void assertNoLeak(Throwable thrown, String marker) {
    Throwable current = thrown;
    while (current != null) {
      String message = current.getMessage();
      assertFalse(
          message != null && message.contains(marker),
          "the visible safe exception must not carry the raw failure: " + message);
      for (Throwable suppressed : current.getSuppressed()) {
        assertNoLeak(suppressed, marker);
      }
      current = current.getCause();
    }
  }

  private static Supplier<Throwable> sqlFailure(String kind) {
    return () -> new SQLException(SQL_MARKER + "-" + kind);
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

  /** 一次真实转换的完整现场：真实源/目标库、两个透明窄探针与源端可观察基线。 */
  private static final class Fixture {

    private final DataSource source;

    private final DataSource target;

    private final LegacyDatabaseUpgradeLifecycleProbe sourceProbe;

    private final LegacyDatabaseUpgradeLifecycleProbe targetProbe;

    private Fixture(
        DataSource source,
        DataSource target,
        LegacyDatabaseUpgradeLifecycleProbe sourceProbe,
        LegacyDatabaseUpgradeLifecycleProbe targetProbe) {
      this.source = source;
      this.target = target;
      this.sourceProbe = sourceProbe;
      this.targetProbe = targetProbe;
    }

    /** 打开一对真实库并载入已排空的旧 dev 部署数据；数据装载走原始句柄，探针只记录内核自己的调用。 */
    static Fixture open() throws SQLException {
      DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
      DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
      LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
      LegacyHarnessHistoryFixture.insert(source);
      return new Fixture(
          source,
          target,
          LegacyDatabaseUpgradeLifecycleProbe.of("source", source),
          LegacyDatabaseUpgradeLifecycleProbe.of("target", target));
    }

    DataSource source() {
      return source;
    }

    DataSource target() {
      return target;
    }

    LegacyDatabaseUpgradeLifecycleProbe sourceProbe() {
      return sourceProbe;
    }

    LegacyDatabaseUpgradeLifecycleProbe targetProbe() {
      return targetProbe;
    }

    LegacyDatabaseUpgradeException convertExpectingSafeFailure(
        SecretKey targetKey, Map<String, Map<String, String>> staticSecretsByPlugin) {
      try {
        LegacyDatabaseUpgrade.convert(
            sourceProbe.dataSource(),
            targetProbe.dataSource(),
            SOURCE_KEY,
            targetKey,
            staticSecretsByPlugin,
            false,
            LegacyUpgradeSourceFixture.CLOCK);
      } catch (Throwable thrown) {
        assertInstanceOf(
            LegacyDatabaseUpgradeException.class,
            thrown,
            "every non-Error failure must surface as the fixed safe exception but was " + thrown);
        return (LegacyDatabaseUpgradeException) thrown;
      }
      return fail("the conversion was expected to fail closed");
    }

    /** 源端可观察事实：关键表行数与 canonical settings 行的数量/版本；settings 行缺失时版本读到 -1。 */
    SourceBaseline sourceBaseline() throws SQLException {
      return new SourceBaseline(
          UpgradePostgresTestSupport.count(source, "agent_provider"),
          UpgradePostgresTestSupport.count(source, "harness_entry"),
          UpgradePostgresTestSupport.count(source, "plugin_credential"),
          UpgradePostgresTestSupport.count(source, "skill_package"),
          UpgradePostgresTestSupport.count(source, "system_setting"),
          UpgradePostgresTestSupport.longValue(
              source, "select coalesce((select version from system_setting where id = 1), -1)"));
    }

    void release() {
      sourceProbe.releaseRemaining();
      targetProbe.releaseRemaining();
    }

    record SourceBaseline(
        long agentProvider,
        long harnessEntry,
        long pluginCredential,
        long skillPackage,
        long systemSettingRows,
        long systemSettingVersion) {}
  }
}
