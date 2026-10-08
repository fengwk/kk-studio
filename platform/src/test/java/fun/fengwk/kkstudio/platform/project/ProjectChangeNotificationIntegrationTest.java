package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.project.notification.ProjectChangeNotifier;
import fun.fengwk.kkstudio.project.notification.ProjectNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import javax.sql.DataSource;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 通过真实 {@link NotificationBus} 订阅验证 Project/Issue 仓库写入通知与事务提交边界。 */
class ProjectChangeNotificationIntegrationTest extends ProjectTestSupport {

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private NotificationBus notificationBus;

  /** Issue 及其活动写入可合并为一次提交后的定向提示；未提交和回滚均不可见。 */
  @Test
  void committedChangesNotifyOnceAndRollbackDoesNotNotify() throws Exception {
    BlockingQueue<UUID> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(
            ProjectNotifications.ISSUE_CHANGED, notifications::add, () -> {})) {
      UUID projectId = createProject();
      assertNotification(notifications, projectId);
      var issue = createIssue(projectId);
      assertNotification(notifications, projectId);

      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      tx.executeWithoutResult(
          status -> {
            issueService.appendComment(
                issue.getId(), issue.getVersion(), "notify-commit", "comment");
            assertNoNotification(notifications);
          });
      assertNotification(notifications, projectId);
      assertNoNotification(notifications);

      tx.executeWithoutResult(
          status -> {
            issueService.appendComment(
                issue.getId(),
                issueService.getIssue(issue.getId()).getVersion(),
                "notify-rollback",
                "comment");
            status.setRollbackOnly();
          });
      assertNoNotification(notifications);
      assertEquals(1, issueService.listActivities(issue.getId(), 0, 50).size());

      issueService.deleteIssue(issue.getId(), issueService.getIssue(issue.getId()).getVersion());
      assertNotification(notifications, projectId);
    }
  }

  /** 不在事务内使用 notifier 必须拒绝，避免 autocommit 提前发信号。 */
  @Test
  void notificationRequiresTransaction() {
    ProjectChangeNotifier notifier =
        new ProjectChangeNotifier(new JdbcTemplate(dataSource), notificationBus);
    assertThrows(IllegalStateException.class, () -> notifier.projectChanged(UUID.randomUUID()));
    assertThrows(IllegalStateException.class, () -> notifier.issueChanged(UUID.randomUUID()));
  }

  private static void assertNotification(BlockingQueue<UUID> notifications, UUID projectId)
      throws InterruptedException {
    assertEquals(projectId, notifications.poll(2, TimeUnit.SECONDS));
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
