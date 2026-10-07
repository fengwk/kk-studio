package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.model.IssueWork;
import fun.fengwk.kkstudio.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

/**
 * 通过独立 PostgreSQL LISTEN 连接验证 {@code project_issue_work_due} 由 Java 生产写入口在事务内发布。
 *
 * <p>夹具先删除同名 Schema 触发器与函数，使观察到的通知只能来自写路径本身。断言覆盖提交/未提交/回滚，以及「写后行已到期且无有效租约」这一合成判据：立即与未来
 * due、活跃与过期租约、reschedule 的最终 due，以及公共 {@code completeWork} 返回 false 时 released 真实写入与围栏未写的区分。
 */
class IssueWorkDueNotificationIntegrationTest extends ProjectTestSupport {

  private static final Duration LEASE = Duration.ofMinutes(1);

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private IssueWorkStore issueWorkStore;
  @Autowired private IssueWorkRepository issueWorkRepository;

  /** 删除 Schema 触发器，使后续断言只能由 Java 写入口产生；确认确实移除，避免测试因触发器残留而假绿。 */
  @BeforeEach
  void dropSchemaTriggerToProveJavaPath() {
    jdbc.execute("drop trigger if exists trg_project_issue_work_due on project_issue_work");
    jdbc.execute("drop function if exists notify_project_issue_work_due()");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from pg_trigger where tgname = 'trg_project_issue_work_due'",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from pg_proc where proname = 'notify_project_issue_work_due'",
            Integer.class));
  }

  /** 已提交的真实写入提交后投递一次；同一事务未提交不可见，回滚静默。 */
  @Test
  void committedWriteNotifiesButUncommittedAndRollbackDoNot() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);
      assertNoNotification(pg);

      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      tx.executeWithoutResult(
          status -> {
            issueWorkStore.requestWork(issueId, Duration.ZERO);
            assertNoNotificationUnchecked(pg);
          });
      assertNotification(pg, issueId);
      assertNoNotification(pg);

      tx.executeWithoutResult(
          status -> {
            issueWorkStore.requestWork(issueId, Duration.ZERO);
            status.setRollbackOnly();
          });
      assertNoNotification(pg);
    }
  }

  /** 立即到期请求发布；未来 due 请求保持静默。 */
  @Test
  void immediateRequestNotifiesWhileFutureRequestStaysSilent() throws Exception {
    UUID immediateIssue = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(immediateIssue, Duration.ZERO);
      assertNotification(pg, immediateIssue);
    }

    UUID futureIssue = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(futureIssue, Duration.ofMinutes(10));
      assertNoNotification(pg);
      IssueWork future = issueWorkStore.getWork(futureIssue);
      assertEquals(future.getUpdatedAt().plus(Duration.ofMinutes(10)), future.getDueAt());
    }
  }

  /** claim/renew 的最终事实是活跃租约；requestWork 合并活跃租约也不能发出立即可领取提示。 */
  @Test
  void claimRenewAndRequestUnderActiveLeaseStaySilent() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);

      IssueWork claimed = claim(issueId);
      assertNoNotification(pg);

      issueWorkStore.renewLease(issueId, claimed.getLeaseToken(), LEASE);
      assertNoNotification(pg);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNoNotification(pg);
    }
  }

  /** released（公共 false）是真实写入必须通知；围栏未写的 false 与删除都静默。 */
  @Test
  void releasedCompletionNotifiesWhileFenceFailureAndDeletionStaySilent() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);

      IssueWork claimed = claim(issueId);
      // 新 wake 保留活跃租约：行已到期但租约有效，不能误发提示。
      issueWorkStore.requestWork(issueId, Duration.ofMinutes(5));
      assertNoNotification(pg);

      // 旧 claim 版本已被新 wake 推进：释放租约并立即到期，返回 false 但必须通知。
      assertFalse(
          issueWorkStore.completeWork(issueId, claimed.getLeaseToken(), claimed.getWakeVersion()));
      assertNotification(pg, issueId);
      IssueWork released = issueWorkStore.getWork(issueId);
      assertNull(released.getLeaseToken());
      assertNull(released.getLeaseUntil());

      IssueWork reclaimed = claim(issueId);
      assertEquals(2L, reclaimed.getWakeVersion());
      assertNoNotification(pg);

      // 非本人 token：围栏未匹配、完全未写，静默。
      assertFalse(issueWorkStore.completeWork(issueId, "intruder", reclaimed.getWakeVersion()));
      assertNoNotification(pg);

      // 匹配 token/version 删除行，返回 true；删除不产生到期提示。
      assertTrue(
          issueWorkStore.completeWork(
              issueId, reclaimed.getLeaseToken(), reclaimed.getWakeVersion()));
      assertNoNotification(pg);
      assertThrows(ProjectNotFoundException.class, () -> issueWorkStore.getWork(issueId));
    }
  }

  /** reschedule 的零延迟使最终 due 等于数据库当前时刻，必须通知。 */
  @Test
  void rescheduleZeroDelayNotifiesImmediately() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(pg);

      assertTrue(
          issueWorkStore.rescheduleWork(
              issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ZERO));
      assertNotification(pg, issueId);
    }
  }

  /** reschedule 的正延迟把最终 due 推到未来，即使写成功也静默。 */
  @Test
  void reschedulePositiveDelayStaysSilent() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(pg);

      assertTrue(
          issueWorkStore.rescheduleWork(
              issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ofSeconds(30)));
      assertNoNotification(pg);
    }
  }

  /** reschedule 的围栏未匹配（token 错误或租约已过期）不写也不通知，公共 false 语义不变。 */
  @Test
  void rescheduleFenceFailureStaysSilent() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(pg);

      // 活跃租约被其他 token 持有：不写。
      assertFalse(
          issueWorkStore.rescheduleWork(
              issueId, "intruder", claimed.getWakeVersion(), Duration.ZERO));
      assertNoNotification(pg);

      // 本人 token 但租约已过期：不写，即使行此刻已到期也静默。
      expire(issueId);
      assertFalse(
          issueWorkStore.rescheduleWork(
              issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ZERO));
      assertNoNotification(pg);
    }
  }

  /** 版本不匹配时 least(wake) 使最终 due 立即到期：正延迟也必须通知。 */
  @Test
  void rescheduleWithNewWakeNotifiesDespitePositiveDelay() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(pg);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNoNotification(pg);

      assertTrue(
          issueWorkStore.rescheduleWork(
              issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ofMinutes(10)));
      assertNotification(pg, issueId);
    }
  }

  /** 过期租约视为无有效租约：真实写入后行到期即通知；过期围栏的 complete 未写则静默，且仍可被重新 claim。 */
  @Test
  void expiredLeaseIsTreatedAsUnleased() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);
      IssueWork claimed = claim(issueId);

      expire(issueId);
      assertNoNotification(pg);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);

      IssueWork current = issueWorkStore.getWork(issueId);
      assertFalse(
          issueWorkStore.completeWork(issueId, claimed.getLeaseToken(), current.getWakeVersion()));
      assertNoNotification(pg);

      assertEquals(
          issueId, issueWorkStore.claimNext("reclaimer", LEASE).orElseThrow().getIssueId());
      assertNoNotification(pg);
    }
  }

  /** Issue 删除经 repository 清理 work 行不在 due 契约内，不产生到期提示。 */
  @Test
  void issueDeletionDoesNotEmitDueNotification() throws Exception {
    var issue = createIssue(createProject());
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      issueWorkStore.requestWork(issue.getId(), Duration.ZERO);
      assertNotification(pg, issue.getId());

      issueService.deleteIssue(issue.getId(), issueService.getIssue(issue.getId()).getVersion());
      assertNoNotification(pg);
    }
  }

  /** 通知相关写入口在无事务时先于任何写入拒绝：行、wakeVersion 与租约不变，通知静默，公共语义不变。 */
  @Test
  void writeEntriesWithoutTransactionAreRejectedBeforeAnyWrite() throws Exception {
    UUID issueId = newIssueId();
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_work_due");
      PGConnection pg = listener.unwrap(PGConnection.class);

      // 未来 due 播种，播种本身不通知。
      issueWorkStore.requestWork(issueId, Duration.ofMinutes(5));
      assertNoNotification(pg);
      IssueWork beforeRequest = issueWorkStore.getWork(issueId);

      assertThrows(
          IllegalStateException.class,
          () -> issueWorkRepository.requestWork(issueId, Duration.ZERO));
      assertEquals(beforeRequest, issueWorkStore.getWork(issueId));
      assertNoNotification(pg);

      // 持有活跃租约，覆盖 complete 的删除路径与 reschedule 的释放路径。
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(pg, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(pg);
      IssueWork beforeFence = issueWorkStore.getWork(issueId);

      assertThrows(
          IllegalStateException.class,
          () ->
              issueWorkRepository.completeWork(
                  issueId, claimed.getLeaseToken(), claimed.getWakeVersion()));
      assertEquals(beforeFence, issueWorkStore.getWork(issueId));
      assertNoNotification(pg);

      assertThrows(
          IllegalStateException.class,
          () ->
              issueWorkRepository.rescheduleWork(
                  issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ZERO));
      assertEquals(beforeFence, issueWorkStore.getWork(issueId));
      assertNoNotification(pg);

      // 事务内公共语义不变：仍可完成删除。
      assertTrue(
          issueWorkStore.completeWork(issueId, claimed.getLeaseToken(), claimed.getWakeVersion()));
      assertNoNotification(pg);
    }
  }

  private IssueWork claim(UUID issueId) {
    IssueWork claimed = issueWorkStore.claimNext("lease-1", LEASE).orElseThrow();
    assertEquals(issueId, claimed.getIssueId());
    return claimed;
  }

  private void expire(UUID issueId) {
    jdbc.update(
        "update project_issue_work set lease_until = statement_timestamp() - interval '1 second'"
            + " where issue_id = ?",
        issueId);
  }

  private UUID newIssueId() {
    return createIssue(createProject()).getId();
  }

  private static void assertNotification(PGConnection pg, UUID issueId) throws SQLException {
    PGNotification[] notifications = pg.getNotifications(2000);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("project_issue_work_due", notifications[0].getName());
    assertEquals(issueId.toString(), notifications[0].getParameter());
  }

  private static void assertNoNotification(PGConnection pg) throws SQLException {
    PGNotification[] notifications = pg.getNotifications(200);
    assertTrue(notifications == null || notifications.length == 0);
  }

  private static void assertNoNotificationUnchecked(PGConnection pg) {
    try {
      assertNoNotification(pg);
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }
}
