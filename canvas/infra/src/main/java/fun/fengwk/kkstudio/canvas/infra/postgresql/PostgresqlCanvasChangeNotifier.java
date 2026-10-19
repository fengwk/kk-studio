package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * 在持久化事务内发送 Canvas revision 失效信号。
 *
 * <p>信号必须在持有事实的同一事务内发送：PostgreSQL 只在提交时投递 {@code pg_notify}，回滚不投递、未提交不可见。通知只是失效提示，权威事实仍由 {@code
 * canvas_document.revision} 回读。Function Run 的唤醒由共享的 durable work 通道负责。
 */
@Component
public class PostgresqlCanvasChangeNotifier {

  static final String REVISION_CHANNEL = "canvas_revision";

  private final JdbcClient jdbc;

  public PostgresqlCanvasChangeNotifier(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** 发布 revision 失效提示；调用方必须已确认 document 被插入或 revision 真实前进。 */
  public void revisionChanged(UUID canvasId, long revision) {
    Objects.requireNonNull(canvasId, "canvasId");
    requireTransaction();
    jdbc.sql("select pg_notify(:channel, :payload)")
        .param("channel", REVISION_CHANNEL)
        .param("payload", canvasId + ":" + revision)
        .query((resultSet, rowNumber) -> null)
        .list();
  }

  static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Canvas change notification requires an active transaction");
    }
  }
}
