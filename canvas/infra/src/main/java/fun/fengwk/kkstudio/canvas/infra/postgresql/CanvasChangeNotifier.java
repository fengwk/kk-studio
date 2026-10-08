package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.canvas.notification.CanvasNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSignal;
import fun.fengwk.kkstudio.share.notification.VersionHint;

import java.util.Objects;
import java.util.UUID;

/**
 * 在持久化事务内发送 Canvas revision 与 Function work 的失效/唤醒信号。
 *
 * <p>两类信号都必须在持有事实的同一事务内发送，由 NotificationBus 在物理事务提交时广播。通知只是唤醒/失效提示，权威事实仍由 {@code
 * canvas_document.revision} 与 {@code canvas_function_run} 回读。
 */
@Component
public class CanvasChangeNotifier {

  private static final String EXISTS_READY_WORK_SQL =
      "select exists("
          + "select 1 from canvas_function_run"
          + " where node_id = ?"
          + " and status = 'READY'"
          + " and lease_token is null"
          + " and available_at <= current_timestamp"
          + ")";

  private final JdbcTemplate jdbc;
  private final NotificationBus bus;

  public CanvasChangeNotifier(JdbcTemplate jdbc, NotificationBus bus) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.bus = Objects.requireNonNull(bus, "bus");
  }

  /** 发布 revision 失效提示；调用方必须已确认 document 被插入或 revision 真实前进。 */
  public void revisionChanged(UUID canvasId, long revision) {
    Objects.requireNonNull(canvasId, "canvasId");
    requireTransaction();
    bus.publish(
        CanvasNotifications.REVISION,
        NotificationAddress.broadcast(),
        new VersionHint(canvasId, revision));
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
    Boolean exists = jdbc.queryForObject(EXISTS_READY_WORK_SQL, Boolean.class, nodeId);
    if (Boolean.TRUE.equals(exists)) {
      bus.publish(
          CanvasNotifications.FUNCTION_WORK,
          NotificationAddress.broadcast(),
          NotificationSignal.CHANGED);
    }
  }

  public static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Canvas change notification requires an active transaction");
    }
  }
}
