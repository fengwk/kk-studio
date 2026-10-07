package fun.fengwk.kkstudio.platform.environment.repo.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * 在 Environment 持久化事务内发送 {@code environment_changed} 失效信号。
 *
 * <p>environment 注册行与 environment_connection 连接行的写入共享同一 channel 与 payload（environmentId）： 观察者只需按
 * environment 重新读取权威卡片。
 *
 * <p>PostgreSQL 只在事务提交时投递 NOTIFY，所以发布必须与对应的写发生在同一条真实事务连接上：回滚不投递，未提交不提前可见。 调用方必须处于写事务中，否则拒绝发布，避免
 * autocommit 提前发出信号。
 */
@Component
public class PostgresqlEnvironmentChangeNotifier {

  private final JdbcTemplate jdbcTemplate;

  public PostgresqlEnvironmentChangeNotifier(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /** 发布指定 environment 的失效提示；调用方必须处于写事务中。 */
  public void environmentChanged(UUID environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    requireTransaction();
    jdbcTemplate.queryForObject(
        "select pg_notify('environment_changed', ?)", String.class, environmentId.toString());
  }

  /** 校验当前线程处于真实写事务中；调用方必须在执行 SQL 之前检查，autocommit 下的写会被先行提交。 */
  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Environment change notification requires an active transaction");
    }
  }
}
