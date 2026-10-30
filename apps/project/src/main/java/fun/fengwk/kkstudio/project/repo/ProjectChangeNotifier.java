package fun.fengwk.kkstudio.project.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/** 在持久化事务中发送 Project 快照失效信号；PostgreSQL 仅在提交时投递通知。 */
@Component
public class ProjectChangeNotifier {

  private final JdbcClient jdbc;

  public ProjectChangeNotifier(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void projectChanged(UUID projectId) {
    requireTransaction();
    jdbc.sql("select pg_notify('project_issue_changed', :projectId)")
        .param("projectId", projectId.toString())
        .query(String.class)
        .single();
  }

  public void issueChanged(UUID issueId) {
    requireTransaction();
    UUID projectId =
        jdbc.sql("select project_id from project_issue where id = :issueId")
            .param("issueId", issueId)
            .query((resultSet, rowNumber) -> resultSet.getObject(1, UUID.class))
            .optional()
            .orElse(null);
    if (projectId != null) {
      projectChanged(projectId);
    }
  }

  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Project change notification requires an active transaction");
    }
  }
}
