package fun.fengwk.kkstudio.platform.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 在 system settings 的 CAS 写事务中发送 {@code system_settings_changed} 失效信号。
 *
 * <p>payload 是写后权威 {@code version} 的十进制字符串，只用于唤醒其它节点回读权威记录，不承载可信状态。必须与成功 CAS 共用同一事务
 * Connection：PostgreSQL 只在提交时投递，未提交不可见、回滚静默。
 */
@Component
public class PostgresqlSystemSettingsChangeNotifier {

  private final JdbcTemplate jdbc;

  public PostgresqlSystemSettingsChangeNotifier(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** 以写后 version 的十进制字符串发布一次失效提示；调用方必须已处于真实事务中。 */
  public void versionChanged(long version) {
    requireTransaction();
    jdbc.queryForObject(
        "select pg_notify('system_settings_changed', ?)", String.class, Long.toString(version));
  }

  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "System settings change notification requires an active transaction");
    }
  }
}
