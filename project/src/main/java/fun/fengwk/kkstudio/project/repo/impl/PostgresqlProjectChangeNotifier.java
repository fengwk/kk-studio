package fun.fengwk.kkstudio.project.repo.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/** 在持久化事务中发送 Project 快照失效信号；PostgreSQL 仅在提交时投递通知。 */
@Component
public class PostgresqlProjectChangeNotifier {

  private final JdbcTemplate jdbc;

  public PostgresqlProjectChangeNotifier(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void projectChanged(UUID projectId) {
    requireTransaction();
    jdbc.queryForObject(
        "select pg_notify('project_issue_changed', ?)", String.class, projectId.toString());
  }

  public void issueChanged(UUID issueId) {
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

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Project change notification requires an active transaction");
    }
  }
}
