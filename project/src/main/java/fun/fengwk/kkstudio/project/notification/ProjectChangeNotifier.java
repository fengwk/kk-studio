package fun.fengwk.kkstudio.project.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;

import java.util.Objects;
import java.util.UUID;

/** 在持久化事务中发送 Project 失效提示；由 NotificationBus 在事务提交后广播。 */
@Component
public class ProjectChangeNotifier {

  private final JdbcTemplate jdbc;
  private final NotificationBus bus;

  public ProjectChangeNotifier(JdbcTemplate jdbc, NotificationBus bus) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.bus = Objects.requireNonNull(bus, "bus");
  }

  public void projectChanged(UUID projectId) {
    Objects.requireNonNull(projectId, "projectId");
    requireTransaction();
    bus.publish(ProjectNotifications.ISSUE_CHANGED, NotificationAddress.broadcast(), projectId);
  }

  public void issueChanged(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    requireTransaction();
    UUID projectId =
        jdbc.query(
            "select project_id from project_issue where id = ?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            issueId);
    if (projectId != null) {
      projectChanged(projectId);
    }
  }

  public static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Project change notification requires an active transaction");
    }
  }
}
