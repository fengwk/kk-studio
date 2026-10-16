package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 真实 PostgreSQL 17 上的生命周期与结果语义：窄故障探针只替换被注入的调用，其余仍是真实连接、真实事务与真实 SQL。
 *
 * <p>候选在目标事务外构造、提交前释放；事务失败保留真实回滚结果；提交回执丢失不能暗示零写入；首 {@code Error} 保持身份，安全异常不携带原始 SQL/秘密标记。
 */
class LegacyDatabaseUpgradeLifecyclePostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x55);

  private static final SecretKey TARGET_KEY = key(0x66);

  private static final String SQL_MARKER = "injected-sql-marker";

  private static final String ROLLBACK_MARKER = "injected-rollback-marker";

  private static final String SNAPSHOT_MARKER = "injected-snapshot-release-marker";

  private static final String CONTEXT_MARKER = "injected-context-release-marker";

  private static final String CLOSE_MARKER = "injected-close-marker";

  private static final String COMMIT_MARKER = "injected-commit-ack-marker";

  /** 候选构造必须发生在任何目标写事务之外，且候选释放必须发生在提交之前。 */
  @Test
  void candidateConstructionAndReleaseStayOutsideTheTargetWriteTransaction() throws SQLException {
    Fixture fixture = Fixture.open();
    try (CatalogSpy spy = CatalogSpy.install(fixture.targetProbe())) {
      LegacyDatabaseUpgradeResult result = fixture.convert(false);
      assertTrue(result.applied());
      assertTrue(spy.prepareObserved(), "the catalog candidate must be prepared exactly once");
      assertFalse(
          spy.transactionActiveAtPrepare(),
          "prepareInitial must run before the target write transaction is opened");
      assertFalse(
          spy.committedWhenSnapshotReleased(),
          "the prepared candidate must be released before the target transaction commits");
      assertEquals(1, spy.snapshotCloseAttempts());
      assertEquals(1, spy.catalogCloseAttempts());
      assertEquals(1, fixture.targetProbe().connectionsOpened());
      assertEquals(1, fixture.sourceProbe().connectionsOpened());
    } finally {
      fixture.release();
    }
  }

  /** COPY 之后、目录 CAS 之间、严格 restore 附近与提交前的失败都必须是已知回滚，并且不留任何连接与语句。 */
  @Test
  void everyMidTransactionFailureIsAKnownRollbackThatReleasesEverything() throws SQLException {
    assertKnownRollback(
        "copy", probe -> probe.failCopyApi(() -> new SQLException(SQL_MARKER + "-copy")));
    assertKnownRollback(
        "catalog-cas",
        probe -> probe.failOnSql("update plugin_catalog_state", sqlFailure("catalog")));
    assertKnownRollback(
        "catalog-revision",
        probe -> probe.failOnSql("insert into plugin_catalog_revision", sqlFailure("revision")));
    assertKnownRollback(
        "strict-restore",
        probe ->
            probe.failOnSql(
                "from plugin_configuration_revision where plugin_id", sqlFailure("restore")));
    assertKnownRollback(
        "before-commit",
        probe -> probe.failOnSql("from harness_thread order by id", sqlFailure("verify")));
  }

  /** 候选释放失败必须真实回滚，并且仍然释放另一个资源。 */
  @Test
  void aReleaseFailureRollsBackForRealAndStillReleasesTheOtherResource() throws SQLException {
    RuntimeException snapshotFailure = new RuntimeException(SNAPSHOT_MARKER);
    Fixture fixture = Fixture.open();
    try (CatalogSpy spy = CatalogSpy.install(fixture.targetProbe())) {
      spy.failSnapshotRelease(() -> snapshotFailure);
      LegacyDatabaseUpgradeException thrown = fixture.convertExpectingSafeFailure(false);
      assertFalse(thrown.outcomeUncertain());
      assertNotEquals(LegacyDatabaseUpgradeException.Stage.COMMIT, thrown.stage());
      assertEquals(1, spy.snapshotCloseAttempts());
      assertEquals(1, spy.catalogCloseAttempts());
      assertNoLeak(thrown, SNAPSHOT_MARKER);
      UpgradePostgresTestSupport.assertPristine(fixture.target());
    } finally {
      fixture.release();
    }
  }

  /** 先 Runtime 后 Error 的释放顺序不能丢掉 Error：首 Error 必须提升为可见失败并保身份。 */
  @Test
  void aCleanupErrorIsPromotedWhenAnEarlierReleaseFailureWasARuntimeFailure() throws SQLException {
    Error contextFailure = new AssertionError(CONTEXT_MARKER);
    Fixture fixture = Fixture.open();
    try (CatalogSpy spy = CatalogSpy.install(fixture.targetProbe())) {
      spy.failSnapshotRelease(() -> new RuntimeException(SNAPSHOT_MARKER));
      spy.failContextRelease(() -> contextFailure);
      Throwable thrown = fixture.convertExpectingThrowable(false);
      assertSame(
          contextFailure,
          thrown,
          "the first Error must be promoted as the primary failure instead of being suppressed");
      assertEquals(1, spy.snapshotCloseAttempts());
      assertEquals(1, spy.catalogCloseAttempts());
      UpgradePostgresTestSupport.assertPristine(fixture.target());
    } finally {
      fixture.release();
    }
  }

  /** 原发 Error 保身份，同时每一次清理都必须被尝试（回滚、两个释放点与两个连接）。 */
  @Test
  void aPrimaryErrorKeepsIdentityWhileEveryCleanupIsAttempted() throws SQLException {
    Error primary = new AssertionError(SQL_MARKER + "-primary-error");
    Fixture fixture = Fixture.open();
    fixture.targetProbe().failOnSql("update plugin_catalog_state", () -> primary);
    try (CatalogSpy spy = CatalogSpy.install(fixture.targetProbe())) {
      spy.failSnapshotRelease(() -> new RuntimeException(SNAPSHOT_MARKER));
      spy.failContextRelease(() -> new RuntimeException(CONTEXT_MARKER));
      fixture.targetProbe().failRollback(() -> new RuntimeException(ROLLBACK_MARKER));
      Throwable thrown = fixture.convertExpectingThrowable(false);
      assertSame(primary, thrown, "the primary Error must keep its identity");
      assertEquals(1, spy.snapshotCloseAttempts());
      assertEquals(1, spy.catalogCloseAttempts());
      assertEquals(1, fixture.targetProbe().countEvents("rollback"));
      assertTrue(fixture.targetProbe().sawEvent("close-connection"));
      assertTrue(fixture.sourceProbe().sawEvent("close-connection"));
      assertFalse(fixture.targetProbe().sawEvent("setAutoCommit:true"));
      UpgradePostgresTestSupport.assertPristine(fixture.target());
    } finally {
      fixture.release();
    }
  }

  /** 失败路径上先有原发 Runtime、随后上下文释放抛 Error：Error 同样必须提升为首失败并保身份。 */
  @Test
  void aCleanupErrorAfterAPrimaryFailureIsPromotedWithIdentity() throws SQLException {
    Error contextError = new AssertionError(CONTEXT_MARKER);
    Fixture fixture = Fixture.open();
    fixture.targetProbe().failOnSql("update plugin_catalog_state", sqlFailure("primary"));
    try (CatalogSpy spy = CatalogSpy.install(fixture.targetProbe())) {
      spy.failContextRelease(() -> contextError);
      Throwable thrown = fixture.convertExpectingThrowable(false);
      assertSame(contextError, thrown, "a cleanup Error must be promoted over the primary Runtime");
      assertTrue(fixture.targetProbe().sawEvent("close-connection"));
      assertTrue(fixture.sourceProbe().sawEvent("close-connection"));
      UpgradePostgresTestSupport.assertPristine(fixture.target());
    } finally {
      fixture.release();
    }
  }

  /** 回滚也失败时结果必须标为未知，且不得把原始失败伪装成「确定零写入」。 */
  @Test
  void aFailedRollbackAfterAPrimaryFailureIsReportedAsUncertain() throws SQLException {
    Fixture fixture = Fixture.open();
    fixture.targetProbe().failOnSql("update plugin_catalog_state", sqlFailure("primary"));
    fixture.targetProbe().failRollback(() -> new SQLException(ROLLBACK_MARKER));
    try {
      LegacyDatabaseUpgradeException thrown = fixture.convertExpectingSafeFailure(false);
      assertTrue(
          thrown.outcomeUncertain(),
          "a failed rollback without commit acknowledgement must be reported as an uncertain result");
      assertNoLeak(thrown, ROLLBACK_MARKER);
      assertNoLeak(thrown, SQL_MARKER);
      assertEquals(1, fixture.targetProbe().countEvents("rollback"));
      assertEquals(0, fixture.targetProbe().countEvents("commit"));
      assertFalse(fixture.targetProbe().sawEvent("setAutoCommit:true"));
      assertTrue(fixture.targetProbe().realConnectionClosed());
      UpgradePostgresTestSupport.assertPristine(fixture.target());
    } finally {
      fixture.release();
    }
  }

  /** dry-run 的回滚失败也不能在框架清理时隐式提交完整转换，关闭物理连接后仍须 pristine。 */
  @Test
  void aFailedDryRunRollbackNeverRestoresAutoCommitOrRetries() throws SQLException {
    Fixture fixture = Fixture.open();
    fixture.targetProbe().failRollback(() -> new SQLException(ROLLBACK_MARKER));
    try {
      LegacyDatabaseUpgradeException thrown = fixture.convertExpectingSafeFailure(true);
      assertTrue(thrown.outcomeUncertain());
      assertEquals(LegacyDatabaseUpgradeException.Stage.COMMIT, thrown.stage());
      assertEquals(1, fixture.targetProbe().countEvents("rollback"));
      assertEquals(0, fixture.targetProbe().countEvents("commit"));
      assertFalse(fixture.targetProbe().sawEvent("setAutoCommit:true"));
      assertTrue(fixture.targetProbe().realConnectionClosed());
      UpgradePostgresTestSupport.assertPristine(fixture.target());
      assertNoLeak(thrown, ROLLBACK_MARKER);
    } finally {
      fixture.release();
    }
  }

  /** 已提交后的致命错误保持 identity，并携带固定结果未知提示，不再尝试提交或回滚。 */
  @Test
  void aFatalCommitAcknowledgementKeepsItsIdentityAndUncertainOutcome() throws SQLException {
    Fixture fixture = Fixture.open();
    Error fatal = new AssertionError(COMMIT_MARKER);
    fixture.targetProbe().failCommitAcknowledgement(() -> fatal);
    fixture.targetProbe().failRollback(() -> new SQLException(ROLLBACK_MARKER));
    try {
      assertSame(fatal, fixture.convertExpectingThrowable(false));
      assertTrue(
          Arrays.stream(fatal.getSuppressed())
              .filter(LegacyDatabaseUpgradeException.class::isInstance)
              .map(LegacyDatabaseUpgradeException.class::cast)
              .anyMatch(LegacyDatabaseUpgradeException::outcomeUncertain));
      assertEquals(1L, UpgradePostgresTestSupport.longValue(fixture.target(), epochSql()));
      assertEquals(1, fixture.targetProbe().countEvents("commit"));
      assertEquals(0, fixture.targetProbe().countEvents("rollback"));
      assertFalse(fixture.targetProbe().sawEvent("setAutoCommit:true"));
      assertTrue(fixture.targetProbe().realConnectionClosed());
    } finally {
      fixture.release();
    }
  }

  /** Spring 的 begin 包装不能吞掉 JDBC Error，尚未开始写入时仍须释放两个连接与已构造候选。 */
  @Test
  void aFatalTransactionStartKeepsIdentityAndReleasesTheIdleTarget() throws SQLException {
    Fixture fixture = Fixture.open();
    Error fatal = new AssertionError(SQL_MARKER);
    fixture.targetProbe().failTransactionStart(() -> fatal);
    try (CatalogSpy spy = CatalogSpy.install(fixture.targetProbe())) {
      assertSame(fatal, fixture.convertExpectingThrowable(false));
      assertEquals(1, spy.snapshotCloseAttempts());
      assertEquals(1, spy.catalogCloseAttempts());
      assertEquals(0, fixture.targetProbe().countEvents("commit"));
      assertEquals(0, fixture.targetProbe().countEvents("rollback"));
      assertTrue(fixture.targetProbe().realConnectionClosed());
      assertTrue(fixture.sourceProbe().realConnectionClosed());
      UpgradePostgresTestSupport.assertPristine(fixture.target());
    } finally {
      fixture.release();
    }
  }

  /** 真实提交已落盘、客户端只丢了回执：结果必须标为未知，目标确实已推进到 epoch 1，且绝不自动重试。 */
  @Test
  void aLostCommitAcknowledgementIsUncertainAndKeepsTheSingleCommittedEpoch() throws SQLException {
    Fixture fixture = Fixture.open();
    fixture.targetProbe().failCommitAcknowledgement(() -> new SQLException(COMMIT_MARKER));
    try {
      LegacyDatabaseUpgradeException thrown = fixture.convertExpectingSafeFailure(false);
      assertTrue(
          thrown.outcomeUncertain(), "a lost commit acknowledgement must be reported as uncertain");
      assertEquals(LegacyDatabaseUpgradeException.Stage.COMMIT, thrown.stage());
      assertEquals(1L, UpgradePostgresTestSupport.longValue(fixture.target(), epochSql()));
      assertEquals(
          1L,
          UpgradePostgresTestSupport.longValue(
              fixture.target(), "select count(*) from plugin_catalog_revision where epoch = 1"));
      assertEquals(
          1,
          fixture.sourceProbe().connectionsOpened(),
          "no automatic retry may open a second source");
      assertEquals(
          1,
          fixture.sourceProbe().countEvents("getConnection"),
          "no automatic retry may open a second source connection");
      assertNoLeak(thrown, COMMIT_MARKER);
      assertTrue(fixture.targetProbe().realConnectionClosed());
    } finally {
      fixture.release();
    }
  }

  /** 真实提交之后源连接释放失败不能报告「未落盘」：结果可能已提交，异常必须可见。 */
  @Test
  void aSourceReleaseFailureAfterARealCommitIsNotReportedAsUnpersisted() throws SQLException {
    Fixture fixture = Fixture.open(() -> new SQLException(CLOSE_MARKER), null);
    try {
      LegacyDatabaseUpgradeException thrown = fixture.convertExpectingSafeFailure(false);
      assertEquals(
          1L,
          UpgradePostgresTestSupport.longValue(fixture.target(), epochSql()),
          "the committed target must stay at epoch 1");
      assertTrue(
          thrown.outcomeUncertain(),
          "a failure after a confirmed commit must still be reported as a possibly committed result");
      assertNoLeak(thrown, CLOSE_MARKER);
    } finally {
      fixture.release();
    }
  }

  /** 真实提交之后目标连接释放失败同样不得暗示未落盘，并且源连接仍会被释放。 */
  @Test
  void aTargetReleaseFailureAfterARealCommitIsUncertainAndKeepsTheCommit() throws SQLException {
    Fixture fixture = Fixture.open(null, () -> new SQLException(CLOSE_MARKER));
    try {
      LegacyDatabaseUpgradeException thrown = fixture.convertExpectingSafeFailure(false);
      assertTrue(thrown.outcomeUncertain());
      assertEquals(LegacyDatabaseUpgradeException.Stage.COMMIT, thrown.stage());
      assertEquals(1L, UpgradePostgresTestSupport.longValue(fixture.target(), epochSql()));
      assertTrue(fixture.sourceProbe().sawEvent("close-connection"));
      assertNoLeak(thrown, CLOSE_MARKER);
    } finally {
      fixture.release();
    }
  }

  /** dry run 正常回滚后目标必须保持完整 pristine，两个连接的释放互不阻断。 */
  @Test
  void aDryRunRollsBackToPristineEvenWhenSourceReleaseFails() throws SQLException {
    Fixture fixture = Fixture.open(SQLException::new, null);
    try {
      LegacyDatabaseUpgradeException thrown = fixture.convertExpectingSafeFailure(true);
      assertFalse(thrown.outcomeUncertain());
      assertNotEquals(LegacyDatabaseUpgradeException.Stage.COMMIT, thrown.stage());
      UpgradePostgresTestSupport.assertPristine(fixture.target());
      assertTrue(fixture.targetProbe().sawEvent("close-connection"));
      assertTrue(fixture.sourceProbe().sawEvent("close-connection"));
    } finally {
      fixture.release();
    }
  }

  /** 源只读一致快照的固定项与「源库零写入」事实。 */
  @Test
  void theSourceSessionStaysAReadOnlyRepeatableReadSnapshotWithoutWrites() throws SQLException {
    Fixture fixture = Fixture.open();
    fixture.targetProbe().failCopyApi(() -> new SQLException(SQL_MARKER + "-copy"));
    try {
      Fixture.SourceBaseline baseline = fixture.sourceBaseline();
      fixture.convertExpectingSafeFailure(false);
      assertTrue(fixture.sourceProbe().sawEvent("set transaction isolation level repeatable read"));
      assertTrue(fixture.sourceProbe().sawEvent("set transaction read only"));
      assertFalse(
          fixture.sourceProbe().events().stream()
              .anyMatch(
                  event ->
                      event.startsWith("prepare:insert")
                          || event.startsWith("prepare:update")
                          || event.startsWith("prepare:delete")),
          "the legacy source must never see a write statement");
      assertEquals(1, fixture.sourceProbe().connectionsOpened());
      assertEquals(baseline, fixture.sourceBaseline());
    } finally {
      fixture.release();
    }
  }

  private static String epochSql() {
    return "select epoch from plugin_catalog_state where id = 1";
  }

  private static Supplier<Throwable> sqlFailure(String kind) {
    return () -> new SQLException(SQL_MARKER + "-" + kind);
  }

  /** 逐个注入点验证「已知回滚 + 全部资源释放 + 源库未变」。 */
  private static void assertKnownRollback(String scenario, Injector injector) throws SQLException {
    Fixture fixture = Fixture.open();
    injector.inject(fixture.targetProbe());
    try {
      Fixture.SourceBaseline baseline = fixture.sourceBaseline();
      LegacyDatabaseUpgradeException thrown = fixture.convertExpectingSafeFailure(false);
      assertFalse(
          thrown.outcomeUncertain(),
          scenario
              + ": a mid transaction failure without a lost acknowledgement is a known rollback");
      assertNotEquals(
          LegacyDatabaseUpgradeException.Stage.COMMIT,
          thrown.stage(),
          scenario + ": the stage must not claim an unconfirmed commit");
      assertNoLeak(thrown, SQL_MARKER);
      UpgradePostgresTestSupport.assertPristine(fixture.target());
      assertEquals(
          1, fixture.sourceProbe().connectionsOpened(), scenario + ": extra source connection");
      assertEquals(
          1, fixture.targetProbe().connectionsOpened(), scenario + ": extra target connection");
      assertTrue(
          fixture.sourceProbe().realConnectionClosed(), scenario + ": leaked source connection");
      assertTrue(
          fixture.targetProbe().realConnectionClosed(), scenario + ": leaked target connection");
      assertEquals(
          0, fixture.targetProbe().openStatements(), scenario + ": leaked target statement");
      assertEquals(
          0, fixture.sourceProbe().openStatements(), scenario + ": leaked source statement");
      assertEquals(
          baseline, fixture.sourceBaseline(), scenario + ": the legacy source was modified");
    } finally {
      fixture.release();
    }
  }

  /** 安全异常契约：类型与 stage 固定，递归的 suppressed/cause 绝不携带注入的原始标记。 */
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

  @FunctionalInterface
  private interface Injector {
    void inject(LegacyDatabaseUpgradeLifecycleProbe probe);
  }

  /** 一次真实转换的完整现场：真实源/目标库、窄探针与源端基线。 */
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

    /** 打开一对真实库并载入已排空的旧 dev 部署数据；两个探针各自可选地注入「连接释放失败」。 */
    static Fixture open(
        Supplier<SQLException> sourceCloseFailure, Supplier<SQLException> targetCloseFailure)
        throws SQLException {
      DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
      DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
      LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
      LegacyHarnessHistoryFixture.insert(source);
      LegacyDatabaseUpgradeLifecycleProbe sourceProbe =
          LegacyDatabaseUpgradeLifecycleProbe.of("source", source);
      LegacyDatabaseUpgradeLifecycleProbe targetProbe =
          LegacyDatabaseUpgradeLifecycleProbe.of("target", target);
      if (sourceCloseFailure != null) {
        sourceProbe.failClose(() -> sourceCloseFailure.get());
      }
      if (targetCloseFailure != null) {
        targetProbe.failClose(() -> targetCloseFailure.get());
      }
      return new Fixture(source, target, sourceProbe, targetProbe);
    }

    static Fixture open() throws SQLException {
      return open(null, null);
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

    LegacyDatabaseUpgradeResult convert(boolean dryRun) {
      return upgrade(dryRun);
    }

    LegacyDatabaseUpgradeException convertExpectingSafeFailure(boolean dryRun) {
      Throwable thrown = convertExpectingThrowable(dryRun);
      assertInstanceOf(
          LegacyDatabaseUpgradeException.class,
          thrown,
          "every non-Error failure must surface as the fixed safe exception but was " + thrown);
      return (LegacyDatabaseUpgradeException) thrown;
    }

    Throwable convertExpectingThrowable(boolean dryRun) {
      try {
        upgrade(dryRun);
      } catch (Throwable thrown) {
        return thrown;
      }
      return fail("the conversion was expected to fail");
    }

    private LegacyDatabaseUpgradeResult upgrade(boolean dryRun) {
      return LegacyDatabaseUpgrade.convert(
          sourceProbe.dataSource(),
          targetProbe.dataSource(),
          SOURCE_KEY,
          TARGET_KEY,
          LegacyUpgradeSourceFixture.staticSecrets(),
          dryRun,
          LegacyUpgradeSourceFixture.CLOCK);
    }

    SourceBaseline sourceBaseline() throws SQLException {
      return new SourceBaseline(
          UpgradePostgresTestSupport.count(source, "agent_provider"),
          UpgradePostgresTestSupport.count(source, "harness_entry"),
          UpgradePostgresTestSupport.count(source, "plugin_credential"),
          UpgradePostgresTestSupport.longValue(source, "select version from system_setting"),
          UpgradePostgresTestSupport.text(source, "select config::text from system_setting"));
    }

    void release() {
      sourceProbe.releaseRemaining();
      targetProbe.releaseRemaining();
    }

    /** 源端可观察事实：几个关键表的行数、settings 版本与整份 settings 原文。 */
    record SourceBaseline(
        long agentProvider,
        long harnessEntry,
        long pluginCredential,
        long systemSettingVersion,
        String systemSettingConfig) {}
  }

  /**
   * 用 Mockito 只拦截「真实 {@code OfflinePluginCatalog.open} 的返回值」与其两个释放点：真实装配仍然由真实 {@code
   * callRealMethod} 构造，释放点可以分别注入快照 Runtime 与上下文 Error，并记录候选构造时是否已在目标写事务内。
   */
  private static final class CatalogSpy implements AutoCloseable {

    private final MockedStatic<OfflinePluginCatalog> mocked;

    private final AtomicInteger snapshotCloseAttempts = new AtomicInteger();

    private final AtomicInteger catalogCloseAttempts = new AtomicInteger();

    private volatile boolean prepareObserved;

    private volatile boolean transactionActiveAtPrepare;

    private volatile boolean committedWhenSnapshotReleased;

    private Supplier<Throwable> snapshotFailure;

    private Supplier<Throwable> catalogFailure;

    private CatalogSpy(MockedStatic<OfflinePluginCatalog> mocked) {
      this.mocked = mocked;
    }

    static CatalogSpy install(LegacyDatabaseUpgradeLifecycleProbe targetProbe) {
      CatalogSpy[] holder = new CatalogSpy[1];
      MockedStatic<OfflinePluginCatalog> mocked =
          Mockito.mockStatic(
              OfflinePluginCatalog.class,
              invocation -> {
                if (!"open".equals(invocation.getMethod().getName())) {
                  return invocation.callRealMethod();
                }
                OfflinePluginCatalog real = (OfflinePluginCatalog) invocation.callRealMethod();
                OfflinePluginCatalog spied = Mockito.spy(real);
                CatalogSpy spy = holder[0];
                Mockito.doAnswer(
                        prepare -> {
                          spy.prepareObserved = true;
                          spy.transactionActiveAtPrepare =
                              TransactionSynchronizationManager.isActualTransactionActive();
                          PluginCatalogBootstrap.PreparedInitial initial =
                              (PluginCatalogBootstrap.PreparedInitial) prepare.callRealMethod();
                          PluginCatalogSnapshot snapshot = Mockito.spy(initial.snapshot());
                          Mockito.doAnswer(
                                  close -> {
                                    Object released = close.callRealMethod();
                                    spy.committedWhenSnapshotReleased =
                                        targetProbe.sawEvent("commit");
                                    spy.snapshotCloseAttempts.incrementAndGet();
                                    Supplier<Throwable> failure = spy.snapshotFailure;
                                    if (failure != null) {
                                      throw failure.get();
                                    }
                                    return released;
                                  })
                              .when(snapshot)
                              .close();
                          return new PluginCatalogBootstrap.PreparedInitial(
                              snapshot, initial.manifest(), initial.configurationRows());
                        })
                    .when(spied)
                    .prepareInitial(Mockito.any(), Mockito.any());
                Mockito.doAnswer(
                        close -> {
                          Object released = close.callRealMethod();
                          spy.catalogCloseAttempts.incrementAndGet();
                          Supplier<Throwable> failure = spy.catalogFailure;
                          if (failure != null) {
                            throw failure.get();
                          }
                          return released;
                        })
                    .when(spied)
                    .close();
                return spied;
              });
      CatalogSpy spy = new CatalogSpy(mocked);
      holder[0] = spy;
      return spy;
    }

    void failSnapshotRelease(Supplier<Throwable> failure) {
      snapshotFailure = failure;
    }

    void failContextRelease(Supplier<Throwable> failure) {
      catalogFailure = failure;
    }

    boolean prepareObserved() {
      return prepareObserved;
    }

    boolean transactionActiveAtPrepare() {
      return transactionActiveAtPrepare;
    }

    boolean committedWhenSnapshotReleased() {
      return committedWhenSnapshotReleased;
    }

    int snapshotCloseAttempts() {
      return snapshotCloseAttempts.get();
    }

    int catalogCloseAttempts() {
      return catalogCloseAttempts.get();
    }

    @Override
    public void close() {
      mocked.close();
    }
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }
}
