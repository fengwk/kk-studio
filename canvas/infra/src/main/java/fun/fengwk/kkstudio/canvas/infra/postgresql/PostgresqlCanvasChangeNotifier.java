package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * 在持久化事务中发送 Canvas 失效信号，替代数据库侧的 revision/work 通知触发器。
 *
 * <p>两类信号都必须在持有事实的同一事务内发送：PostgreSQL 只在提交时投递 {@code pg_notify}，回滚不投递、未提交不可见。通知只是唤醒/失效提示， 权威事实仍由
 * {@code canvas_document.revision} 与 {@code canvas_function_run} 回读。
 */
@Component
public class PostgresqlCanvasChangeNotifier {

  static final String REVISION_CHANNEL = "canvas_revision";
  static final String FUNCTION_WORK_CHANNEL = "canvas_function_work";

  private final JdbcTemplate jdbc;

  public PostgresqlCanvasChangeNotifier(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** 发布 revision 失效提示；调用方必须已确认 document 被插入或 revision 真实前进。 */
  public void revisionChanged(UUID canvasId, long revision) {
    Objects.requireNonNull(canvasId, "canvasId");
    requireTransaction();
    jdbc.query(
        "select pg_notify(?, ?)",
        (resultSet, rowNumber) -> null,
        REVISION_CHANNEL,
        canvasId + ":" + revision);
  }

  /**
   * 成功写入后若最终持久事实是可立即领取的 READY work，则发布空 payload 唤醒提示。
   *
   * <p>是否可领取以数据库 {@code current_timestamp} 判定，不使用应用机器时钟；未来 {@code available_at} 与过期 RUNNING 由既有
   * poll 恢复，因此条件不满足时保持静默。
   */
  public void functionWorkChanged(UUID nodeId) {
    Objects.requireNonNull(nodeId, "nodeId");
    requireTransaction();
    jdbc.query(
        "select pg_notify(?, '') from canvas_function_run"
            + " where node_id = ?"
            + " and status = 'READY'"
            + " and lease_token is null"
            + " and available_at <= current_timestamp",
        (resultSet, rowNumber) -> null,
        FUNCTION_WORK_CHANNEL,
        nodeId);
  }

  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Canvas change notification requires an active transaction");
    }
  }
}
