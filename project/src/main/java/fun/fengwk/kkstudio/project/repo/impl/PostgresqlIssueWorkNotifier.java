package fun.fengwk.kkstudio.project.repo.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.project.controller.IssueControllerDispatcher;

import java.util.Objects;
import java.util.UUID;

/**
 * 在持久化事务中发送 Issue Work 到期提示；PostgreSQL 仅在提交时投递通知。
 *
 * <p>判据完全由数据库决定：写后同一事务内回读该行，只有 {@code due_at <= clock_timestamp()} 且没有有效租约（{@code lease_until}
 * 为空或已过期）时才发送 {@code project_issue_work_due}。这样 claim/renew 的活跃租约、未来 due 与围栏失效的未写都不会误发。
 */
@Component
public class PostgresqlIssueWorkNotifier {

  private static final String NOTIFY_IF_DUE_SQL =
      "select pg_notify(?, ?) from project_issue_work"
          + " where issue_id = ? and due_at <= clock_timestamp()"
          + " and (lease_until is null or lease_until <= clock_timestamp())";

  private final JdbcTemplate jdbc;

  public PostgresqlIssueWorkNotifier(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** 调用方事务内发生真实写入后调用：仅当权威行此刻已到期且无有效租约时发送提示。 */
  public void notifyIfDue(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    requireTransaction();
    jdbc.queryForList(
        NOTIFY_IF_DUE_SQL, IssueControllerDispatcher.CHANNEL, issueId.toString(), issueId);
  }

  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Issue work due notification requires an active transaction");
    }
  }
}
