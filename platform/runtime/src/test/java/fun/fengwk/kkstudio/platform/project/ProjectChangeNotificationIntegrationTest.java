package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.project.repo.impl.PostgresqlProjectChangeNotifier;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/** 通过独立 PostgreSQL LISTEN 连接验证仓库写入通知与事务提交边界。 */
class ProjectChangeNotificationIntegrationTest extends ProjectTestSupport {

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private PostgresqlProjectChangeNotifier notifier;

  /** Issue 及其活动写入可合并为一次提交后的定向提示；未提交和回滚均不可见。 */
  @Test
  void committedChangesNotifyOnceAndRollbackDoesNotNotify() throws Exception {
    try (Connection listener = dataSource.getConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen project_issue_changed");
      PGConnection pg = listener.unwrap(PGConnection.class);

      UUID projectId = createProject();
      assertNotification(pg, projectId);
      var issue = createIssue(projectId);
      assertNotification(pg, projectId);

      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      tx.executeWithoutResult(
          status -> {
            issueService.appendComment(
                issue.getId(), issue.getVersion(), "notify-commit", "comment");
            try {
              assertNoNotification(pg);
            } catch (Exception error) {
              throw new IllegalStateException(error);
            }
          });
      assertNotification(pg, projectId);
      assertNoNotification(pg);

      tx.executeWithoutResult(
          status -> {
            issueService.appendComment(
                issue.getId(),
                issueService.getIssue(issue.getId()).getVersion(),
                "notify-rollback",
                "comment");
            status.setRollbackOnly();
          });
      assertNoNotification(pg);
      assertEquals(1, issueService.listActivities(issue.getId(), 0, 50).size());

      issueService.deleteIssue(issue.getId(), issueService.getIssue(issue.getId()).getVersion());
      assertNotification(pg, projectId);
    }
  }

  /** 不在事务内使用 notifier 必须拒绝，避免 autocommit 提前发信号。 */
  @Test
  void notificationRequiresTransaction() {
    assertThrows(IllegalStateException.class, () -> notifier.projectChanged(UUID.randomUUID()));
    assertThrows(IllegalStateException.class, () -> notifier.issueChanged(UUID.randomUUID()));
  }

  private static void assertNotification(PGConnection pg, UUID projectId) throws Exception {
    PGNotification[] notifications = pg.getNotifications(2000);
    assertTrue(notifications != null);
    assertEquals(1, notifications.length);
    assertEquals("project_issue_changed", notifications[0].getName());
    assertEquals(projectId.toString(), notifications[0].getParameter());
  }

  private static void assertNoNotification(PGConnection pg) throws Exception {
    PGNotification[] notifications = pg.getNotifications(200);
    assertTrue(notifications == null || notifications.length == 0);
  }
}
