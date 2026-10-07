package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 在 Skill Package 的写事务中发送 {@code skill_package_changed} 失效信号。
 *
 * <p>payload 是 package 名，只用于唤醒 {@code EnvironmentSkillSyncOrchestrator} 做权威回读，不承载可信状态。PostgreSQL
 * 只在提交时投递 {@code pg_notify}：未提交不可见、回滚静默。发布必须与成功写共用同一事务 Connection（Spring/MyBatis
 * 写入口的真实事务），不得改到提交后再开连接，否则会 产生「事实已提交、通知尚未发送」的额外失败窗口。
 */
@Component
public class PostgresqlSkillPackageChangeNotifier {

  private final JdbcTemplate jdbc;

  public PostgresqlSkillPackageChangeNotifier(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** 以 package 名发布一次失效提示；调用方必须已处于真实事务中。 */
  public void packageChanged(String packageName) {
    requireTransaction();
    jdbc.queryForObject("select pg_notify('skill_package_changed', ?)", String.class, packageName);
  }

  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Skill package change notification requires an active transaction");
    }
  }
}
