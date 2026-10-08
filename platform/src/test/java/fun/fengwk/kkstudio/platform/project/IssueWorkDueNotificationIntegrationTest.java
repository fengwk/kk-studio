package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.model.IssueWork;
import fun.fengwk.kkstudio.project.notification.IssueWorkNotifier;
import fun.fengwk.kkstudio.project.notification.ProjectNotifications;
import fun.fengwk.kkstudio.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import javax.sql.DataSource;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 通过真实 {@link NotificationBus} 订阅验证 {@link ProjectNotifications#WORK_DUE} 由 Java 生产写入口在事务内发布。
 *
 * <p>断言覆盖提交/未提交/回滚，以及「写后行已到期且无有效租约」这一合成判据：立即与未来 due、活跃与过期租约、reschedule 的最终 due，以及公共 {@code
 * completeWork} 返回 false 时 released 真实写入与围栏未写的区分。
 */
class IssueWorkDueNotificationIntegrationTest extends ProjectTestSupport {

  private static final Duration LEASE = Duration.ofMinutes(1);

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private IssueWorkStore issueWorkStore;
  @Autowired private IssueWorkRepository issueWorkRepository;
  @Autowired private NotificationBus notificationBus;

  /** 已提交的真实写入提交后投递一次；同一事务未提交不可见，回滚静默。 */
  @Test
  void committedWriteNotifiesButUncommittedAndRollbackDoNot() throws Exception {
    UUID issueId = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);
      assertNoNotification(notifications);

      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      tx.executeWithoutResult(
          status -> {
            issueWorkStore.requestWork(issueId, Duration.ZERO);
            assertNoNotification(notifications);
          });
      assertNotification(notifications, issueId);
      assertNoNotification(notifications);

      tx.executeWithoutResult(
          status -> {
            issueWorkStore.requestWork(issueId, Duration.ZERO);
            status.setRollbackOnly();
          });
      assertNoNotification(notifications);
    }
  }

  /** 立即到期请求发布；未来 due 请求保持静默。 */
  @Test
  void immediateRequestNotifiesWhileFutureRequestStaysSilent() throws Exception {
    UUID immediateIssue = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(immediateIssue, Duration.ZERO);
      assertNotification(notifications, immediateIssue);

      UUID futureIssue = newIssueId();
      issueWorkStore.requestWork(futureIssue, Duration.ofMinutes(10));
      assertNoNotification(notifications);
      IssueWork future = issueWorkStore.getWork(futureIssue);
      assertEquals(future.getUpdatedAt().plus(Duration.ofMinutes(10)), future.getDueAt());
    }
  }

  /** claim/renew 的最终事实是活跃租约；requestWork 合并活跃租约也不能发出立即可领取提示。 */
  @Test
  void claimRenewAndRequestUnderActiveLeaseStaySilent() throws Exception {
    UUID issueId = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);

      IssueWork claimed = claim(issueId);
      assertNoNotification(notifications);

      issueWorkStore.renewLease(issueId, claimed.getLeaseToken(), LEASE);
      assertNoNotification(notifications);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNoNotification(notifications);
    }
  }

  /** released（公共 false）是真实写入必须通知；围栏未写的 false 与删除都静默。 */
  @Test
  void releasedCompletionNotifiesWhileFenceFailureAndDeletionStaySilent() throws Exception {
    UUID issueId = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);

      IssueWork claimed = claim(issueId);
      // 新 wake 保留活跃租约：行已到期但租约有效，不能误发提示。
      issueWorkStore.requestWork(issueId, Duration.ofMinutes(5));
      assertNoNotification(notifications);

      // 旧 claim 版本已被新 wake 推进：释放租约并立即到期，返回 false 但必须通知。
      assertFalse(
          issueWorkStore.completeWork(issueId, claimed.getLeaseToken(), claimed.getWakeVersion()));
      assertNotification(notifications, issueId);
      IssueWork released = issueWorkStore.getWork(issueId);
      assertNull(released.getLeaseToken());
      assertNull(released.getLeaseUntil());

      IssueWork reclaimed = claim(issueId);
      assertEquals(2L, reclaimed.getWakeVersion());
      assertNoNotification(notifications);

      // 非本人 token：围栏未匹配、完全未写，静默。
      assertFalse(issueWorkStore.completeWork(issueId, "intruder", reclaimed.getWakeVersion()));
      assertNoNotification(notifications);

      // 匹配 token/version 删除行，返回 true；删除不产生到期提示。
      assertTrue(
          issueWorkStore.completeWork(
              issueId, reclaimed.getLeaseToken(), reclaimed.getWakeVersion()));
      assertNoNotification(notifications);
      assertThrows(ProjectNotFoundException.class, () -> issueWorkStore.getWork(issueId));
    }
  }

  /** reschedule 的零延迟使最终 due 等于数据库当前时刻，必须通知。 */
  @Test
  void rescheduleZeroDelayNotifiesImmediately() throws Exception {
    UUID issueId = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(notifications);

      assertTrue(
          issueWorkStore.rescheduleWork(
              issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ZERO));
      assertNotification(notifications, issueId);
    }
  }

  /** reschedule 的正延迟把最终 due 推到未来，即使写成功也静默。 */
  @Test
  void reschedulePositiveDelayStaysSilent() throws Exception {
    UUID issueId = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(notifications);

      assertTrue(
          issueWorkStore.rescheduleWork(
              issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ofSeconds(30)));
      assertNoNotification(notifications);
    }
  }

  /** reschedule 的围栏未匹配（token 错误或租约已过期）不写也不通知，公共 false 语义不变。 */
  @Test
  void rescheduleFenceFailureStaysSilent() throws Exception {
    UUID issueId = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(notifications);

      // 活跃租约被其他 token 持有：不写。
      assertFalse(
          issueWorkStore.rescheduleWork(
              issueId, "intruder", claimed.getWakeVersion(), Duration.ZERO));
      assertNoNotification(notifications);

      // 本人 token 但租约已过期：不写，即使行此刻已到期也静默。
      expire(issueId);
      assertFalse(
          issueWorkStore.rescheduleWork(
              issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ZERO));
      assertNoNotification(notifications);
    }
  }

  /** 版本不匹配时 least(wake) 使最终 due 立即到期：正延迟也必须通知。 */
  @Test
  void rescheduleWithNewWakeNotifiesDespitePositiveDelay() throws Exception {
    UUID issueId = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(notifications);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNoNotification(notifications);

      assertTrue(
          issueWorkStore.rescheduleWork(
              issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ofMinutes(10)));
      assertNotification(notifications, issueId);
    }
  }

  /** 过期租约视为无有效租约：真实写入后行到期即通知；过期围栏的 complete 未写则静默，且仍可被重新 claim。 */
  @Test
  void expiredLeaseIsTreatedAsUnleased() throws Exception {
    UUID issueId = newIssueId();
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);
      IssueWork claimed = claim(issueId);

      expire(issueId);
      assertNoNotification(notifications);

      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);

      IssueWork current = issueWorkStore.getWork(issueId);
      assertFalse(
          issueWorkStore.completeWork(issueId, claimed.getLeaseToken(), current.getWakeVersion()));
      assertNoNotification(notifications);

      assertEquals(
          issueId, issueWorkStore.claimNext("reclaimer", LEASE).orElseThrow().getIssueId());
      assertNoNotification(notifications);
    }
  }

  /** Issue 删除经 repository 清理 work 行不在 due 契约内，不产生到期提示。 */
  @Test
  void issueDeletionDoesNotEmitDueNotification() throws Exception {
    var issue = createIssue(createProject());
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      issueWorkStore.requestWork(issue.getId(), Duration.ZERO);
      assertNotification(notifications, issue.getId());

      issueService.deleteIssue(issue.getId(), issueService.getIssue(issue.getId()).getVersion());
      assertNoNotification(notifications);
    }
  }

  /** 通知相关写入口在无事务时先于任何写入拒绝：行、wakeVersion 与租约不变，通知静默，公共语义不变。 */
  @Test
  void writeEntriesWithoutTransactionAreRejectedBeforeAnyWrite() throws Exception {
    UUID issueId = newIssueId();
    IssueWorkNotifier notifier =
        new IssueWorkNotifier(new JdbcTemplate(dataSource), notificationBus);
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(ProjectNotifications.WORK_DUE, notifications::add, () -> {})) {
      // 未来 due 播种，播种本身不通知。
      issueWorkStore.requestWork(issueId, Duration.ofMinutes(5));
      assertNoNotification(notifications);
      IssueWork beforeRequest = issueWorkStore.getWork(issueId);

      assertThrows(IllegalStateException.class, () -> notifier.notifyIfDue(issueId));
      assertThrows(
          IllegalStateException.class,
          () -> issueWorkRepository.requestWork(issueId, Duration.ZERO));
      assertEquals(beforeRequest, issueWorkStore.getWork(issueId));
      assertNoNotification(notifications);

      // 持有活跃租约，覆盖 complete 的删除路径与 reschedule 的释放路径。
      issueWorkStore.requestWork(issueId, Duration.ZERO);
      assertNotification(notifications, issueId);
      IssueWork claimed = claim(issueId);
      assertNoNotification(notifications);
      IssueWork beforeFence = issueWorkStore.getWork(issueId);

      assertThrows(
          IllegalStateException.class,
          () ->
              issueWorkRepository.completeWork(
                  issueId, claimed.getLeaseToken(), claimed.getWakeVersion()));
      assertEquals(beforeFence, issueWorkStore.getWork(issueId));
      assertNoNotification(notifications);

      assertThrows(
          IllegalStateException.class,
          () ->
              issueWorkRepository.rescheduleWork(
                  issueId, claimed.getLeaseToken(), claimed.getWakeVersion(), Duration.ZERO));
      assertEquals(beforeFence, issueWorkStore.getWork(issueId));
      assertNoNotification(notifications);

      // 事务内公共语义不变：仍可完成删除。
      assertTrue(
          issueWorkStore.completeWork(issueId, claimed.getLeaseToken(), claimed.getWakeVersion()));
      assertNoNotification(notifications);
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

  private static void assertNotification(BlockingQueue<UUID> notifications, UUID issueId)
      throws InterruptedException {
    assertEquals(issueId, notifications.poll(2, TimeUnit.SECONDS));
    assertTrue(notifications.isEmpty());
  }

  private static void assertNoNotification(BlockingQueue<UUID> notifications) {
    try {
      assertNull(notifications.poll(50, TimeUnit.MILLISECONDS));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}
